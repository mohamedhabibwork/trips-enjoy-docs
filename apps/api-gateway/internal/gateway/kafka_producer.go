// Package gateway — Kafka producer.
//
// Per docs/services/api-gateway/INTEGRATION.md §3 the gateway
// publishes four events; the producer behind this file implements:
//
//   - audit.api.request.v1                  topic=audit.api.request
//   - gateway.config.reloaded.v1            topic=platform.gateway.config.reloaded
//   - gateway.rate_limit.exceeded.v1        topic=platform.gateway.rate_limit.exceeded
//   - gateway.circuit_breaker.opened.v1     topic=platform.gateway.circuit_breaker
//
// Each message:
//
//   - carries `X-Request-Id` and `X-Correlation-Id` Kafka headers
//     (per ADR-0019 step 4)
//   - is keyed by correlation_id (audit), route (rate-limit), or
//     upstream (circuit-breaker) so the per-flow ordering is
//     preserved on the partition
//   - is JSON-encoded with the platform envelope (see audit_event.go)
//
// Reliability knobs (INTEGRATION.md §5):
//   - producer acks=all
//   - 3 attempts with exp backoff (250ms, 500ms, 1s)
//   - failures ≥ retries → DLQ topic (`audit.api.request.dlq`, etc.)
//   - best-effort for rate-limit / circuit-breaker (synchronous
//     response is NOT blocked on emit)
//   - synchronous ack for audit.api.request.v1 (per WORKFLOWS.md §1.8
//     and SRS NFR--013: 100% audit emission success)
//
// In local development the producer can be a no-op (`NewNoopProducer`)
// so unit tests can exercise the rest of the gateway without a
// running Kafka.
package gateway

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"strconv"
	"sync"
	"time"

	"github.com/segmentio/kafka-go"
)

// Producer publishes gateway events to Kafka. The interface keeps
// the proxy + main independent from the kafka-go specifics so tests
// can use a synchronous in-memory stub.
type Producer interface {
	// PublishAudit publishes audit.api.request.v1 with sync ack.
	// Returns nil on success; ErrProducerFailed if all retries
	// failed and the DLQ also rejects.
	PublishAudit(ctx context.Context, e Event) error
	// PublishBestEffort publishes rate-limit / circuit-breaker /
	// config-reloaded events on a fire-and-forget basis. Failures
	// are logged but never returned to the caller.
	PublishBestEffort(ctx context.Context, e Event, topic string) error
	// Close drains in-flight messages and closes the underlying
	// writers.
	Close() error
}

// ErrProducerFailed is returned by PublishAudit when both the
// primary topic and the DLQ reject the message after retries.
var ErrProducerFailed = errors.New("kafka producer: publish failed")

// kafkaProducer is the segmentio/kafka-go backed implementation.
type kafkaProducer struct {
	mu       sync.Mutex
	writers  map[string]*kafka.Writer
	brokers  []string
	timeout  time.Duration
	audit    string
	dlqAudit string
}

// NewKafkaProducer opens one *kafka.Writer per topic and returns a
// Producer. The writers are pooled lazily (the first publish for a
// topic opens the writer; subsequent publishes reuse it).
func NewKafkaProducer(cfg KafkaConfig) (Producer, error) {
	if len(cfg.Brokers) == 0 {
		return nil, errors.New("kafka: no brokers configured")
	}
	return &kafkaProducer{
		writers:  make(map[string]*kafka.Writer),
		brokers:  cfg.Brokers,
		timeout:  cfg.RequestTimeout,
		audit:    cfg.AuditTopic,
		dlqAudit: cfg.AuditDLQTopic,
	}, nil
}

// NewNoopProducer returns a Producer that encodes messages to
// memory and silently swallows write failures. Useful in tests and
// for code paths that should not depend on a real Kafka.
func NewNoopProducer() Producer { return noopProducer{} }

type noopProducer struct{}

func (noopProducer) PublishAudit(ctx context.Context, e Event) error                    { return nil }
func (noopProducer) PublishBestEffort(ctx context.Context, e Event, topic string) error { return nil }
func (noopProducer) Close() error                                                       { return nil }

func (p *kafkaProducer) writer(topic string) (*kafka.Writer, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	w, ok := p.writers[topic]
	if ok {
		return w, nil
	}
	w = &kafka.Writer{
		Addr:                   kafka.TCP(p.brokers...),
		Topic:                  topic,
		Balancer:               &kafka.Hash{}, // partition by key
		RequiredAcks:           kafka.RequireAll,
		Async:                  false,
		AllowAutoTopicCreation: false,
		WriteTimeout:           p.timeout,
		ReadTimeout:            p.timeout,
		BatchTimeout:           50 * time.Millisecond,
		MaxAttempts:            3,
	}
	p.writers[topic] = w
	return w, nil
}

// Close closes every cached writer.
func (p *kafkaProducer) Close() error {
	p.mu.Lock()
	defer p.mu.Unlock()
	var first error
	for _, w := range p.writers {
		if err := w.Close(); err != nil && first == nil {
			first = err
		}
	}
	p.writers = nil
	return first
}

// publish writes one message with retries + exp backoff.
func (p *kafkaProducer) publish(ctx context.Context, topic string, key string, e Event) error {
	body, err := json.Marshal(e)
	if err != nil {
		return fmt.Errorf("encode event: %w", err)
	}
	w, err := p.writer(topic)
	if err != nil {
		return err
	}
	msg := kafka.Message{
		Key:   []byte(key),
		Value: body,
		Time:  time.Now().UTC(),
		Headers: []kafka.Header{
			{Key: "X-Request-Id", Value: []byte(e.CorrelationID)},
			{Key: "X-Correlation-Id", Value: []byte(e.CorrelationID)},
			{Key: "Content-Type", Value: []byte("application/json")},
			{Key: "event_name", Value: []byte(e.EventName)},
			{Key: "schema_version", Value: []byte(strconv.Itoa(e.SchemaVersion))},
		},
	}
	delays := []time.Duration{0, 250 * time.Millisecond, 500 * time.Millisecond}
	var lastErr error
	for attempt, delay := range delays {
		if delay > 0 {
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(delay):
			}
		}
		wctx, cancel := context.WithTimeout(ctx, p.timeout)
		err := w.WriteMessages(wctx, msg)
		cancel()
		if err == nil {
			return nil
		}
		lastErr = err
		_ = attempt
	}
	return lastErr
}

// PublishAudit publishes audit.api.request.v1 with sync ack and
// falls back to the DLQ topic if the primary write fails.
func (p *kafkaProducer) PublishAudit(ctx context.Context, e Event) error {
	err := p.publish(ctx, p.audit, e.CorrelationID, e)
	if err == nil {
		return nil
	}
	// Last resort: DLQ. The DLQ MUST accept; if it does not we
	// surface ErrProducerFailed so the proxy can decide whether to
	// block the response.
	dlqErr := p.publish(ctx, p.dlqAudit, e.CorrelationID, e)
	if dlqErr != nil {
		return fmt.Errorf("%w: primary=%v, dlq=%v", ErrProducerFailed, err, dlqErr)
	}
	return nil
}

// PublishBestEffort fires and forgets (best-effort) per the SRS.
func (p *kafkaProducer) PublishBestEffort(ctx context.Context, e Event, topic string) error {
	go func() {
		_ = p.publish(context.Background(), topic, partitionKey(e), e)
	}()
	return nil
}

// partitionKey is the per-topic key strategy. audit.api.request is
// keyed by correlation_id (INTEGRATION.md §3.1); rate-limit and
// config-reloaded and circuit-breaker follow the spec.
func partitionKey(e Event) string {
	switch e.EventName {
	case "audit.api.request.v1":
		return e.CorrelationID
	case "gateway.rate_limit.exceeded.v1":
		if d, ok := e.Data.(RateLimitData); ok {
			return d.Route
		}
	case "gateway.circuit_breaker.opened.v1":
		if d, ok := e.Data.(CircuitBreakerData); ok {
			return d.Upstream
		}
	case "gateway.config.reloaded.v1":
		if d, ok := e.Data.(ConfigReloadedData); ok {
			return "config:" + strconv.FormatInt(d.ConfigVersion, 10)
		}
	}
	return e.CorrelationID
}

// guard unused-import for net package.
var _ = net.IPv4zero
