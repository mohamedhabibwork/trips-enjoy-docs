package storage

import (
	"context"
	"sort"
	"sync"
	"time"
)

// DriverSpec is the public view of a registered driver (what GET
// /v1/admin/drivers returns). Mirrors the storage_drivers catalog row from
// ERD.md but stays in-memory so the binary boots without a database.
type DriverSpec struct {
	ID                string
	Kind              string
	DisplayName       string
	State             string // enabled | draining | disabled
	Priority          int
	IsDefault         bool
	Region            string
	Container         string
	Endpoint          string
	SignedURLTTLSecs  int
	MaxObjectSizeByte int64
	Health            string
}

// Registry holds every StorageDriver the service has loaded at startup.
// Lookups by id are O(1); the ordered list is materialized on demand and
// sorted by Priority ascending (lower wins per ERD.md).
type Registry struct {
	mu                  sync.RWMutex
	defaults            string
	specs               map[string]DriverSpec
	drivers             map[string]StorageDriver
	consecutiveFailures map[string]int // per-driver counter feeding the circuit-open gauge
	circuitOpen         map[string]bool
}

// NewRegistry returns an empty Registry. Drivers are added via Register.
func NewRegistry() *Registry {
	return &Registry{
		specs:               map[string]DriverSpec{},
		drivers:             map[string]StorageDriver{},
		consecutiveFailures: map[string]int{},
		circuitOpen:         map[string]bool{},
	}
}

// Register adds a driver to the registry. The first driver registered
// with isDefault=true becomes the default; subsequent default flags are
// ignored (only one default is allowed per ERD.md).
func (r *Registry) Register(spec DriverSpec, driver StorageDriver) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.defaults == "" && spec.IsDefault {
		r.defaults = spec.ID
	}
	r.specs[spec.ID] = spec
	r.drivers[spec.ID] = driver
}

// Resolve returns the driver registered under id, or ErrDriverNotFound.
func (r *Registry) Resolve(id string) (StorageDriver, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	d, ok := r.drivers[id]
	if !ok {
		return nil, ErrDriverNotFound
	}
	return d, nil
}

// DefaultID returns the registered default driver id (or empty).
func (r *Registry) DefaultID() string {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.defaults
}

// Default returns the resolved default driver, or ErrDriverNotFound when
// no driver is registered or the default is missing.
func (r *Registry) Default() (StorageDriver, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	if r.defaults == "" {
		return nil, ErrDriverNotFound
	}
	d, ok := r.drivers[r.defaults]
	if !ok {
		return nil, ErrDriverNotFound
	}
	return d, nil
}

// Spec returns the catalog row for a driver id (or empty + false).
func (r *Registry) Spec(id string) (DriverSpec, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	s, ok := r.specs[id]
	return s, ok
}

// ListSpecs returns every registered driver sorted by Priority then id.
// Used by GET /v1/admin/drivers and by /ready/drivers/{id}.
func (r *Registry) ListSpecs() []DriverSpec {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]DriverSpec, 0, len(r.specs))
	for _, s := range r.specs {
		out = append(out, s)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Priority != out[j].Priority {
			return out[i].Priority < out[j].Priority
		}
		return out[i].ID < out[j].ID
	})
	return out
}

// DefaultReachable runs the default driver's Probe() and returns true
// when it is Healthy. Used by /ready.
func (r *Registry) DefaultReachable(ctx context.Context) bool {
	r.mu.RLock()
	defer r.mu.RUnlock()
	if r.defaults == "" {
		return false
	}
	d, ok := r.drivers[r.defaults]
	if !ok {
		return false
	}
	probeCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	return d.Probe(probeCtx).Healthy
}

// Shutdown drains every registered driver.
func (r *Registry) Shutdown(ctx context.Context) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	var firstErr error
	for id, d := range r.drivers {
		if err := d.Shutdown(ctx); err != nil && firstErr == nil {
			firstErr = err
		}
		delete(r.drivers, id)
	}
	return firstErr
}

// ProbeAll runs every registered driver's Probe() with a 5s timeout
// and updates the consecutive-failure counter. Returns a map of
// driver_id -> ProbeResult. The Registry is the single source of
// truth for circuit state (cmd/server reads IsCircuitOpen from here
// every scrape).
func (r *Registry) ProbeAll(ctx context.Context) map[string]ProbeResult {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make(map[string]ProbeResult, len(r.drivers))
	for id, d := range r.drivers {
		probeCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
		res := d.Probe(probeCtx)
		cancel()
		if !res.Healthy {
			r.consecutiveFailures[id]++
			if r.consecutiveFailures[id] >= 3 {
				r.circuitOpen[id] = true
			}
		} else {
			r.consecutiveFailures[id] = 0
			r.circuitOpen[id] = false
		}
		out[id] = res
	}
	return out
}

// IsCircuitOpen reports whether the given driver has tripped its
// circuit breaker (3 consecutive failed probes). The metric in
// monitoring/file-service-alerts.yaml maps this gauge to FileServiceAnyDriverCircuitOpen.
func (r *Registry) IsCircuitOpen(id string) bool {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.circuitOpen[id]
}

// IsReachable runs the given driver's Probe() and returns the result.
// Used by the /ready/drivers/{id} handler.
func (r *Registry) IsReachable(ctx context.Context, id string) bool {
	r.mu.RLock()
	d, ok := r.drivers[id]
	r.mu.RUnlock()
	if !ok {
		return false
	}
	probeCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	return d.Probe(probeCtx).Healthy
}
