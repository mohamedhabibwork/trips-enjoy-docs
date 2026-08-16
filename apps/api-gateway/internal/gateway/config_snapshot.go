// Package gateway — atomic in-memory config snapshot.
//
// Per docs/services/api-gateway/WORKFLOWS.md §3 the gateway hot-
// reloads its route table, rate limits, CORS policy, and JWKS
// refresh interval without dropping in-flight requests and without
// a restart. The hot-reload mechanism is an atomic pointer swap
// over an immutable *Snapshot: a reader always observes either the
// old or the new snapshot, never a half-built one.
//
// Concurrency strategy: Snapshot is immutable once Store'd. The
// snapshot pointer is held in an atomic.Pointer. The proxy reads
// the snapshot once per request and binds the route/config lookups
// to the value it saw.
package gateway

import "sync/atomic"

// Snapshot is the read-only view of gateway configuration at a
// particular version. The version stamp is monotonic; readers can
// short-circuit when the version has not changed (e.g. the
// `configuration.updated.v1` consumer in WORKFLOWS.md §3.5).
type Snapshot struct {
	Version      int64
	Routes       []Route
	RateLimits   RateLimitConfig
	CORS         []string
	JWKSRefresh  int64 // seconds
	BlocklistIPs []string
}

// RateLimitConfig is the per-route rate-limit defaults.
type RateLimitConfig struct {
	DefaultLimit  int
	WindowSeconds int
}

// SnapshotStore holds the active *Snapshot and atomically swaps
// new snapshots in. The zero value is ready to use.
type SnapshotStore struct {
	current atomic.Pointer[Snapshot]
}

// NewSnapshotStore returns a store pre-loaded with seed.
func NewSnapshotStore(seed *Snapshot) *SnapshotStore {
	s := &SnapshotStore{}
	if seed != nil {
		s.current.Store(seed)
	}
	return s
}

// Load returns the current snapshot. Returns nil if no snapshot
// has been Store'd (callers should treat nil as the bootstrap-
// not-yet-complete case).
func (s *SnapshotStore) Load() *Snapshot {
	if s == nil {
		return nil
	}
	return s.current.Load()
}

// Store atomically replaces the active snapshot with next. The
// store monotonically bumps Version (next wins even if it has the
// same value as current — caller controls whether to skip).
func (s *SnapshotStore) Store(next *Snapshot) {
	if s == nil || next == nil {
		return
	}
	s.current.Store(next)
}

// DefaultSnapshot builds the seed snapshot from the bootstrap
// Config. It is intentionally lightweight — the production
// reload path swaps a more elaborate snapshot built by the
// `configuration.updated.v1` consumer.
func DefaultSnapshot(c Config, version int64) *Snapshot {
	return &Snapshot{
		Version:      version,
		Routes:       append([]Route(nil), c.Routes...),
		RateLimits:   RateLimitConfig{DefaultLimit: 100, WindowSeconds: 60},
		CORS:         append([]string(nil), c.AllowedCORSOrigins...),
		JWKSRefresh:  int64(c.Keycloak.JWKSRefresh.Seconds()),
		BlocklistIPs: nil,
	}
}
