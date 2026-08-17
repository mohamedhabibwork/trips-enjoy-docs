// Package chain hosts the multi-provider chain resolver, the per-vendor
// circuit breaker (sony/gobreaker), and the per-vendor token-bucket
// rate limiter (golang.org/x/time/rate). It implements the chain
// semantics from docs/services/geolocation-service/README.md §4.5 and
// SRS.md §5 (FR--021..FR--030).
package chain

import (
	"errors"
	"sync"
	"time"

	"github.com/sony/gobreaker"
)

// CircuitState is the canonical state name emitted to
// geolocation.provider_chain.changed.v1 and surfaced via
// vendor_circuit_state metric per SRS.md §22.
type CircuitState string

const (
	CircuitStateClosed   CircuitState = "closed"
	CircuitStateOpen     CircuitState = "open"
	CircuitStateHalfOpen CircuitState = "half_open"
)

// CircuitBreakers wraps sony/gobreaker with one breaker per vendor_id
// + an in-memory mirror of the last-known state for the
// provider_circuit_state read path (the PG mirror lands in a follow-up).
type CircuitBreakers struct {
	mu       sync.Mutex
	breakers map[string]*gobreaker.CircuitBreaker
	states   map[string]CircuitState
}

// NewCircuitBreakers returns an empty registry.
func NewCircuitBreakers() *CircuitBreakers {
	return &CircuitBreakers{
		breakers: map[string]*gobreaker.CircuitBreaker{},
		states:   map[string]CircuitState{},
	}
}

// Register adds a breaker for vendorID with the supplied threshold
// and cooldown. It is safe to call repeatedly with the same vendorID
// (subsequent calls reset the threshold/cooldown).
func (c *CircuitBreakers) Register(vendorID string, failureThreshold, cooldownSeconds, halfOpenProbes int) {
	c.mu.Lock()
	defer c.mu.Unlock()
	settings := gobreaker.Settings{
		Name:        vendorID,
		MaxRequests: uint32(halfOpenProbes),
		Interval:    time.Duration(cooldownSeconds) * time.Second,
		Timeout:     time.Duration(cooldownSeconds) * time.Second,
		ReadyToTrip: func(counts gobreaker.Counts) bool {
			return counts.ConsecutiveFailures >= uint32(failureThreshold)
		},
		OnStateChange: func(name string, from, to gobreaker.State) {
			c.mu.Lock()
			defer c.mu.Unlock()
			c.states[name] = mapState(to)
		},
	}
	c.breakers[vendorID] = gobreaker.NewCircuitBreaker(settings)
	c.states[vendorID] = CircuitStateClosed
}

// Execute runs fn through the breaker for vendorID. A closed breaker
// calls fn directly; an open breaker returns gobreaker.ErrOpenState.
// The fn shape matches gobreaker's contract (func() (interface{}, error))
// so the resolver can hand the breaker the typed capability call
// directly.
func (c *CircuitBreakers) Execute(vendorID string, fn func() (interface{}, error)) (interface{}, error) {
	c.mu.Lock()
	cb, ok := c.breakers[vendorID]
	c.mu.Unlock()
	if !ok {
		// Lazy register with conservative defaults so an un-seeded
		// vendor still benefits from circuit protection.
		c.Register(vendorID, 5, 30, 3)
		c.mu.Lock()
		cb = c.breakers[vendorID]
		c.mu.Unlock()
	}
	return cb.Execute(fn)
}

// State returns the last-known state for vendorID.
func (c *CircuitBreakers) State(vendorID string) CircuitState {
	c.mu.Lock()
	defer c.mu.Unlock()
	if s, ok := c.states[vendorID]; ok {
		return s
	}
	return CircuitStateClosed
}

// AllStates returns a snapshot of every breaker's state. Used by the
// admin GET /v1/admin/providers handler.
func (c *CircuitBreakers) AllStates() map[string]CircuitState {
	c.mu.Lock()
	defer c.mu.Unlock()
	out := make(map[string]CircuitState, len(c.states))
	for k, v := range c.states {
		out[k] = v
	}
	return out
}

// BreakerStates returns the package-level snapshot of all breaker
// states. The admin service uses this via a small shim because the
// resolver owns the breakers — the shim forwards to the resolver's
// breaker set. Returns nil if no resolver has registered breakers.
var BreakerStates = func() map[string]CircuitState { return nil }

// SetBreakerStatesSource wires the resolver's breaker set so the
// package-level BreakerStates() shim returns live data.
func SetBreakerStatesSource(c *CircuitBreakers) {
	BreakerStates = func() map[string]CircuitState { return c.AllStates() }
}

func mapState(s gobreaker.State) CircuitState {
	switch s {
	case gobreaker.StateOpen:
		return CircuitStateOpen
	case gobreaker.StateHalfOpen:
		return CircuitStateHalfOpen
	default:
		return CircuitStateClosed
	}
}

// ErrCircuitOpen is the canonical "all providers exhausted" error. It
// is surfaced as 503 CIRCUIT_OPEN per SRS.md §13.
var ErrCircuitOpen = errors.New("chain exhausted: every provider circuit open or rate-limited")
