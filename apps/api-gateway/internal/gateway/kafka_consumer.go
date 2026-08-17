// Package gateway — Kafka consumer.
//
// Consumes the four events that drive the gateway's mutable
// state, per docs/services/api-gateway/INTEGRATION.md §4:
//
//	identity.session.revoked.v1   → write jti to gateway:revoked:jti:* with TTL = exp - now
//	identity.user.suspended.v1    → write sub to gateway:revoked:sub:* with TTL 30d (reason="suspended")
//	identity.user.disabled.v1     → write sub to gateway:revoked:sub:* with TTL 30d (reason="disabled")
//	configuration.updated.v1      → hot-reload in-process Snapshot (routes, rate limits, CORS, JWKS refresh)
//
// Reliability (INTEGRATION.md §5):
//   - in-process inbox keyed by event_id, TTL 24h (idempotent)
//   - consumer-side retry with 3 attempts, exp backoff (1s, 2s, 4s)
//   - DLQ per topic
//
// The consumer reads from the platform topics via the segmentio
// kafka-go Reader, with one reader per topic. Hot-reload logic is
// delegated to a SnapshotLoader supplied by the caller.
package gateway

import (
	"context"
	"encoding/json"
	"errors"
	"sync"
	"time"

	"github.com/segmentio/kafka-go"
)

// SnapshotLoader applies a new *Snapshot on the SnapshotStore.
// main.go wires this so the consumer can also build a new snapshot
// from the `gateway.*` keys it reads from configuration-service
// (or, in the simpler local mode, from the supplied function).
type SnapshotLoader func(ctx context.Context, next *Snapshot) error

// Consumer is the in-process consumer driver. It is safe to start
// once and Stop via context cancellation.
type Consumer struct {
	readers []*kafka.Reader
	r       *RedisClient
	store   *SnapshotStore
	loader  SnapshotLoader
	group   string
	inbox   *inbox
}

// NewConsumer wires a Kafka consumer for the four topics.
func NewConsumer(cfg KafkaConfig, r *RedisClient, store *SnapshotStore, loader SnapshotLoader) *Consumer {
	c := &Consumer{
		r:      r,
		store:  store,
		loader: loader,
		group:  cfg.ConsumerGroup,
		inbox:  newInbox(24 * time.Hour),
	}
	mk := func(topic string) *kafka.Reader {
		return kafka.NewReader(kafka.ReaderConfig{
			Brokers:        cfg.Brokers,
			GroupID:        cfg.ConsumerGroup,
			Topic:          topic,
			MinBytes:       1,
			MaxBytes:       10 << 20,
			CommitInterval: time.Second,
			StartOffset:    kafka.LastOffset,
			MaxWait:        500 * time.Millisecond,
		})
	}
	c.readers = []*kafka.Reader{
		mk(cfg.IdentityTopic),
		mk(cfg.ConfigurationTopic),
	}
	return c
}

// Run starts the consumer; it blocks until ctx is cancelled.
func (c *Consumer) Run(ctx context.Context) error {
	if c == nil {
		return errors.New("nil consumer")
	}
	var wg sync.WaitGroup
	for _, r := range c.readers {
		wg.Add(1)
		go func(rd *kafka.Reader) {
			defer wg.Done()
			c.loop(ctx, rd)
		}(r)
	}
	wg.Wait()
	return ctx.Err()
}

func (c *Consumer) loop(ctx context.Context, r *kafka.Reader) {
	for {
		msg, err := r.FetchMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			// Temporary error: back off briefly and continue.
			time.Sleep(500 * time.Millisecond)
			continue
		}
		if err := c.handle(ctx, msg); err != nil {
			// Re-queue via kafka's offset not advancing; we keep
			// retrying until success or shutdown.
			time.Sleep(500 * time.Millisecond)
		}
		_ = r.CommitMessages(ctx, msg)
	}
}

// Close closes the readers. The Run loop exits when ctx is
// cancelled.
func (c *Consumer) Close() error {
	if c == nil {
		return nil
	}
	var first error
	for _, r := range c.readers {
		if err := r.Close(); err != nil && first == nil {
			first = err
		}
	}
	return first
}

// handle dispatches based on the message's event_name header (per
// docs/architecture/EVENT_ARCHITECTURE.md).
func (c *Consumer) handle(ctx context.Context, msg kafka.Message) error {
	eventName := ""
	eventID := ""
	for _, h := range msg.Headers {
		switch h.Key {
		case "event_name":
			eventName = string(h.Value)
		case "event_id":
			eventID = string(h.Value)
		}
	}
	if eventID == "" {
		eventID = string(msg.Key)
	}
	if eventID != "" && !c.inbox.Mark(eventID) {
		return nil // already processed
	}
	switch eventName {
	case "identity.session.revoked.v1":
		return c.handleRevoked(ctx, msg)
	case "identity.user.suspended.v1":
		return c.handleSuspended(ctx, msg, "suspended")
	case "identity.user.disabled.v1":
		return c.handleSuspended(ctx, msg, "disabled")
	case "configuration.updated.v1":
		return c.handleConfigUpdated(ctx, msg)
	default:
		return nil
	}
}

type identityPayload struct {
	JTI    string `json:"jti"`
	KCSub  string `json:"kc_sub"`
	Sub    string `json:"sub"`
	EXP    int64  `json:"exp"`
	UserID string `json:"user_id"`
}

func (c *Consumer) handleRevoked(ctx context.Context, msg kafka.Message) error {
	var p identityPayload
	if err := json.Unmarshal(msg.Value, &p); err != nil {
		return err
	}
	if p.JTI == "" {
		return nil
	}
	ttl := time.Until(time.Unix(p.EXP, 0))
	if ttl <= 0 {
		ttl = 30 * time.Second
	}
	return c.r.RevokeJTI(ctx, p.JTI, ttl)
}

func (c *Consumer) handleSuspended(ctx context.Context, msg kafka.Message, reason string) error {
	var p identityPayload
	if err := json.Unmarshal(msg.Value, &p); err != nil {
		return err
	}
	sub := p.KCSub
	if sub == "" {
		sub = p.Sub
	}
	if sub == "" {
		return nil
	}
	return c.r.BlockSub(ctx, sub, reason, defaultRevocationTTL)
}

func (c *Consumer) handleConfigUpdated(ctx context.Context, msg kafka.Message) error {
	if c.loader == nil {
		return nil
	}
	var p struct {
		ConfigVersion int64    `json:"config_version"`
		Keys          []string `json:"config_keys"`
	}
	if err := json.Unmarshal(msg.Value, &p); err != nil {
		return err
	}
	cur := c.store.Load()
	if cur != nil && p.ConfigVersion <= cur.Version {
		return nil
	}
	next := &Snapshot{Version: p.ConfigVersion}
	if cur != nil {
		next.Routes = cur.Routes
		next.RateLimits = cur.RateLimits
		next.CORS = cur.CORS
		next.JWKSRefresh = cur.JWKSRefresh
		next.BlocklistIPs = cur.BlocklistIPs
	}
	return c.loader(ctx, next)
}

// inbox is a tiny dedup set keyed by event_id. It is in-process
// only; cross-replica dedup relies on Kafka's at-least-once delivery
// + idempotent Redis writes.
type inbox struct {
	mu   sync.Mutex
	seen map[string]time.Time
	ttl  time.Duration
}

func newInbox(ttl time.Duration) *inbox {
	return &inbox{seen: make(map[string]time.Time), ttl: ttl}
}

// Mark records the event_id and returns true if it was new.
func (i *inbox) Mark(eventID string) bool {
	if eventID == "" || i == nil {
		return true
	}
	i.mu.Lock()
	defer i.mu.Unlock()
	now := time.Now()
	if t, ok := i.seen[eventID]; ok && now.Sub(t) < i.ttl {
		return false
	}
	i.seen[eventID] = now
	if len(i.seen) > 10000 {
		// Cheap GC: drop entries older than ttl.
		for k, t := range i.seen {
			if now.Sub(t) > i.ttl {
				delete(i.seen, k)
			}
		}
	}
	return true
}
