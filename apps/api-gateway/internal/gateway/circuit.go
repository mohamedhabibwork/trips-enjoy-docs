// Package gateway — circuit breaker + bulkhead.
//
// Per docs/architecture/SERVICE_ISOLATION.md the gateway applies
// the 5-layer isolation pattern (timeout → bulkhead → circuit
// breaker → retry → fallback) on every outbound call. This file
// implements:
//
//   - per-upstream circuit breaker via sony/gobreaker
//     (open after CircuitFailureThresh consecutive failures; cooldown
//     CircuitCooldown; the half-open trial window is built-in)
//   - per-upstream concurrency cap via golang.org/x/sync/semaphore
//     (BulkheadSize)
//
// When a breaker transitions to `open` the gateway emits
// `gateway.circuit_breaker.opened.v1` (INTEGRATION.md §3.4) on the
// gateway.circuit_breaker topic. The breaker also exposes a `State`
// getter so the Prometheus gauge (gateway_circuit_breaker_state) can
// be observed per upstream.
package gateway

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/sony/gobreaker"
	"golang.org/x/sync/semaphore"
)

// CircuitBreaker is the per-upstream isolation wrapper.
type CircuitBreaker struct {
	upstream  string
	br        *gobreaker.CircuitBreaker
	sem       *semaphore.Weighted
	timeout   time.Duration
	stateMu   sync.RWMutex
	lastState gobreaker.State

	// OnStateChange is invoked synchronously when the breaker
	// transitions. Wire it to the Kafka producer in main.go so the
	// `gateway.circuit_breaker.opened.v1` event is published.
	OnStateChange func(upstream string, prev, next gobreaker.State)
}

// NewCircuitBreaker creates a per-upstream breaker + bulkhead.
func NewCircuitBreaker(upstream string, threshold uint32, cooldown, timeout time.Duration, bulkheadSize int) *CircuitBreaker {
	if threshold == 0 {
		threshold = 5
	}
	if cooldown == 0 {
		cooldown = 30 * time.Second
	}
	if timeout == 0 {
		timeout = 30 * time.Second
	}
	if bulkheadSize == 0 {
		bulkheadSize = 1024
	}
	cb := &CircuitBreaker{
		upstream: upstream,
		sem:      semaphore.NewWeighted(int64(bulkheadSize)),
		timeout:  timeout,
	}
	settings := gobreaker.Settings{
		Name:        upstream,
		MaxRequests: 3, // half-open trial window
		Interval:    0, // never auto-clear counters
		Timeout:     cooldown,
		ReadyToTrip: func(counts gobreaker.Counts) bool {
			return counts.ConsecutiveFailures >= threshold
		},
		OnStateChange: func(name string, from, to gobreaker.State) {
			cb.stateMu.Lock()
			prev := cb.lastState
			cb.lastState = to
			cb.stateMu.Unlock()
			if cb.OnStateChange != nil && from != to {
				cb.OnStateChange(name, from, to)
			}
			_ = prev
		},
	}
	cb.br = gobreaker.NewCircuitBreaker(settings)
	return cb
}

// Do executes op under the circuit breaker + bulkhead. Returns
// ErrCircuitOpen when the breaker is open.
func (c *CircuitBreaker) Do(ctx context.Context, op func(context.Context) error) error {
	if c == nil {
		return op(ctx)
	}
	if err := c.sem.Acquire(ctx, 1); err != nil {
		return fmt.Errorf("bulkhead acquire: %w", err)
	}
	defer c.sem.Release(1)
	_, err := c.br.Execute(func() (interface{}, error) {
		return nil, op(ctx)
	})
	if errors.Is(err, gobreaker.ErrOpenState) || errors.Is(err, gobreaker.ErrTooManyRequests) {
		return ErrCircuitOpen
	}
	return err
}

// State returns the current breaker state (closed | half-open |
// open).
func (c *CircuitBreaker) State() gobreaker.State {
	if c == nil {
		return gobreaker.StateClosed
	}
	return c.br.State()
}

// ErrCircuitOpen is returned by Do when the breaker rejects the call.
var ErrCircuitOpen = errors.New("circuit breaker open")

// CircuitRegistry manages CircuitBreakers per upstream service.
type CircuitRegistry struct {
	mu       sync.RWMutex
	breakers map[string]*CircuitBreaker
	cfg      BulkheadConfig
}

// BulkheadConfig groups the breakers' defaults.
type BulkheadConfig struct {
	Threshold uint32
	Cooldown  time.Duration
	Timeout   time.Duration
	Size      int
}

// NewCircuitRegistry creates a registry with the given defaults.
func NewCircuitRegistry(cfg BulkheadConfig) *CircuitRegistry {
	return &CircuitRegistry{
		breakers: make(map[string]*CircuitBreaker),
		cfg:      cfg,
	}
}

// For returns the breaker for the given upstream, building one if
// it does not exist.
func (r *CircuitRegistry) For(upstream string) *CircuitBreaker {
	r.mu.RLock()
	cb, ok := r.breakers[upstream]
	r.mu.RUnlock()
	if ok {
		return cb
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if cb, ok = r.breakers[upstream]; ok {
		return cb
	}
	cb = NewCircuitBreaker(upstream, r.cfg.Threshold, r.cfg.Cooldown, r.cfg.Timeout, r.cfg.Size)
	r.breakers[upstream] = cb
	return cb
}

// SetOnStateChange sets a single callback on every breaker in the
// registry. Called once at startup; the registry does not deep-copy
// the breaker state.
func (r *CircuitRegistry) SetOnStateChange(fn func(upstream string, prev, next gobreaker.State)) {
	if r == nil || fn == nil {
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, cb := range r.breakers {
		cb.OnStateChange = fn
	}
}

// ForEach iterates breakers (used by metrics scrape). The callback
// MUST NOT mutate the registry.
func (r *CircuitRegistry) ForEach(cb func(upstream string, br *CircuitBreaker)) {
	if r == nil {
		return
	}
	r.mu.RLock()
	defer r.mu.RUnlock()
	for upstream, br := range r.breakers {
		cb(upstream, br)
	}
}
