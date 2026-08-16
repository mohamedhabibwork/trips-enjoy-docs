package provider

import (
	"fmt"
	"sync"
)

// Registry holds the registered MapProvider adapters. Adapters are
// registered at startup (per INTEGRATION.md §4.3) and looked up by
// VendorID during chain resolution. The registry is goroutine-safe.
type Registry struct {
	mu      sync.RWMutex
	entries map[string]MapProvider
	configs map[string]ProviderConfig
}

// NewRegistry returns an empty Registry.
func NewRegistry() *Registry {
	return &Registry{
		entries: map[string]MapProvider{},
		configs: map[string]ProviderConfig{},
	}
}

// Register attaches an adapter under its VendorID. The corresponding
// ProviderConfig is stored alongside so the chain resolver can read
// qps_limit / timeout_ms / failure_threshold without re-decoding the
// adapter metadata at every call.
//
// Panics on duplicate registration per INTEGRATION.md §4.3 ("must
// register at startup"; a duplicate indicates a misconfigured boot
// sequence, not a recoverable condition).
func (r *Registry) Register(p MapProvider, cfg ProviderConfig) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, dup := r.entries[p.VendorID()]; dup {
		panic(fmt.Sprintf("provider %q registered twice", p.VendorID()))
	}
	r.entries[p.VendorID()] = p
	r.configs[p.VendorID()] = cfg
}

// Get returns the adapter registered under vendorID. The boolean is
// false when no adapter is registered (per INTEGRATION.md §4.3 the
// chain resolver logs a warning and treats this as circuit-open).
func (r *Registry) Get(vendorID string) (MapProvider, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	p, ok := r.entries[vendorID]
	return p, ok
}

// Config returns the provider_config row for vendorID.
func (r *Registry) Config(vendorID string) (ProviderConfig, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	c, ok := r.configs[vendorID]
	return c, ok
}

// List returns the registered adapter VendorIDs in insertion order.
func (r *Registry) List() []string {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]string, 0, len(r.entries))
	for id := range r.entries {
		out = append(out, id)
	}
	return out
}

// ListConfigs returns every provider_config row as a slice. Used by
// the admin GET /v1/admin/providers handler.
func (r *Registry) ListConfigs() []ProviderConfig {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]ProviderConfig, 0, len(r.configs))
	for _, c := range r.configs {
		out = append(out, c)
	}
	return out
}

// PatchConfig updates the mutable fields on a ProviderConfig row. The
// vendor-side adapter is untouched (no restart required). Used by
// PATCH /v1/admin/providers/{vendor_id}.
func (r *Registry) PatchConfig(vendorID string, fn func(*ProviderConfig)) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	c, ok := r.configs[vendorID]
	if !ok {
		return false
	}
	fn(&c)
	r.configs[vendorID] = c
	return true
}

// Close calls Close() on every registered adapter. Best-effort: errors
// from one adapter do not prevent the others from closing.
func (r *Registry) Close() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	var firstErr error
	for id, p := range r.entries {
		if err := p.Close(); err != nil && firstErr == nil {
			firstErr = fmt.Errorf("close %s: %w", id, err)
		}
	}
	return firstErr
}
