package chain

import (
	"context"
	"errors"
	"log/slog"
	"sort"
	"sync"
	"time"

	"github.com/trips-enjoy/platform/geolocation-service/internal/provider"
)

// Region is the key under which provider_region_route stores a chain
// per docs/services/geolocation-service/ERD.md §3.5. The literal values
// are `default`, `country:<ISO2>`, or `city:<uuid>`.
type Region string

// IsValidRegion reports whether value conforms to the documented format.
func IsValidRegion(value string) bool {
	if value == "default" {
		return true
	}
	if len(value) > len("country:") && value[:len("country:")] == "country:" && len(value) == len("country:")+2 {
		return true
	}
	if len(value) > len("city:") && value[:len("city:")] == "city:" && len(value) == len("city:")+36 {
		return true
	}
	return false
}

// RegionRoute is one row of provider_region_route: an ordered chain of
// vendor_ids for a (region, capability) pair.
type RegionRoute struct {
	Region     Region
	Capability provider.Capability
	Chain      []string
	Enabled    bool
}

// Resolver picks the most-specific chain for a request, walks it in
// order, skips members that are circuit-open / capability-mismatched /
// rate-limited, and returns the first success. It implements README.md
// §4.5 and SRS.md §5 (FR--021..FR--030) end-to-end.
type Resolver struct {
	registry *provider.Registry
	breakers *CircuitBreakers
	limiters *RateLimiter
	routes   *routeTable
	logger   *slog.Logger
}

// NewResolver wires the dependencies and returns a ready resolver.
func NewResolver(reg *provider.Registry, br *CircuitBreakers, rl *RateLimiter, logger *slog.Logger) *Resolver {
	if logger == nil {
		logger = slog.Default()
	}
	// Wire the package-level shim so the admin service can read breaker
	// states without holding a reference to the resolver.
	SetBreakerStatesSource(br)
	return &Resolver{
		registry: reg,
		breakers: br,
		limiters: rl,
		routes:   newRouteTable(),
		logger:   logger,
	}
}

// SetRoute upserts one provider_region_route row.
func (r *Resolver) SetRoute(route RegionRoute) error {
	if !IsValidRegion(string(route.Region)) {
		return errors.New("invalid region format")
	}
	if route.Chain == nil || len(route.Chain) == 0 {
		return errors.New("chain must be non-empty")
	}
	r.routes.set(route)
	return nil
}

// LookupRoute returns the chain for (region, capability).
func (r *Resolver) LookupRoute(region Region, cap provider.Capability) (RegionRoute, bool) {
	return r.routes.get(region, cap)
}

// HasMember reports whether vendorID is registered (and therefore a
// candidate chain member).
func (r *Resolver) HasMember(vendorID string) bool {
	_, ok := r.registry.Get(vendorID)
	return ok
}

// CallResult is what the resolver returns to the geocoding service.
type CallResult struct {
	VendorID      string
	ChainPosition int
	Role          provider.Role
	Region        Region
	Capability    provider.Capability
	IsSelfHost    bool
	LatencyMS     int
}

// GeocodeForward dispatches to the first viable chain member.
func (r *Resolver) GeocodeForward(ctx context.Context, region Region, req provider.GeocodeRequest) (provider.GeoAddress, CallResult, error) {
	addr, meta, err := r.call(ctx, region, provider.CapabilityGeocodeForward, func(p provider.MapProvider) (any, error) {
		return p.GeocodeForward(ctx, req)
	})
	if err != nil {
		return provider.GeoAddress{}, meta, err
	}
	return addr.(provider.GeoAddress), meta, nil
}

// GeocodeReverse dispatches to the first viable chain member.
func (r *Resolver) GeocodeReverse(ctx context.Context, region Region, req provider.ReverseRequest) (provider.GeoAddress, CallResult, error) {
	addr, meta, err := r.call(ctx, region, provider.CapabilityGeocodeReverse, func(p provider.MapProvider) (any, error) {
		return p.GeocodeReverse(ctx, req)
	})
	if err != nil {
		return provider.GeoAddress{}, meta, err
	}
	return addr.(provider.GeoAddress), meta, nil
}

// Eta dispatches to the first viable chain member for ETA.
func (r *Resolver) Eta(ctx context.Context, region Region, req provider.EtaRequest) (provider.EtaEstimate, CallResult, error) {
	eta, meta, err := r.call(ctx, region, provider.CapabilityEta, func(p provider.MapProvider) (any, error) {
		return p.Eta(ctx, req)
	})
	if err != nil {
		return provider.EtaEstimate{}, meta, err
	}
	return eta.(provider.EtaEstimate), meta, nil
}

// Route dispatches to the first viable chain member for routing.
func (r *Resolver) Route(ctx context.Context, region Region, req provider.RouteRequest) (provider.Route, CallResult, error) {
	route, meta, err := r.call(ctx, region, provider.CapabilityRoute, func(p provider.MapProvider) (any, error) {
		return p.Route(ctx, req)
	})
	if err != nil {
		return provider.Route{}, meta, err
	}
	return route.(provider.Route), meta, nil
}

// call is the shared walk-the-chain implementation. The closure
// returns the typed result as any so the resolver can stay generic.
// gobreaker expects func() (interface{}, error), which is exactly the
// shape we need.
func (r *Resolver) call(ctx context.Context, region Region, cap provider.Capability, fn func(provider.MapProvider) (any, error)) (any, CallResult, error) {
	route, ok := r.routes.get(region, cap)
	if !ok || !route.Enabled {
		return nil, CallResult{}, ErrCircuitOpen
	}

	var tried []string
	for idx, vendorID := range route.Chain {
		p, ok := r.registry.Get(vendorID)
		if !ok {
			r.logger.Warn("chain member not registered; skipping",
				"vendor_id", vendorID, "region", string(region), "capability", string(cap))
			continue
		}
		if !provider.SupportsCapability(p, cap) {
			continue
		}
		if !r.limiters.Allow(vendorID) {
			continue
		}
		if r.breakers.State(vendorID) == CircuitStateOpen {
			continue
		}

		startedAt := time.Now()
		result, err := r.breakers.Execute(vendorID, func() (any, error) {
			return fn(p)
		})
		latencyMS := int(time.Since(startedAt) / time.Millisecond)
		tried = append(tried, vendorID)

		if err == nil {
			cfg, _ := r.registry.Config(vendorID)
			meta := CallResult{
				VendorID:      vendorID,
				ChainPosition: idx,
				Role:          roleForPosition(idx, route.Chain),
				Region:        region,
				Capability:    cap,
				IsSelfHost:    cfg.IsSelfHost,
				LatencyMS:     latencyMS,
			}
			return result, meta, nil
		}

		// Non-retryable → return immediately.
		if errors.Is(err, provider.ErrRegionUnsupported) {
			return nil, CallResult{VendorID: vendorID, ChainPosition: idx, Region: region, Capability: cap}, err
		}

		r.logger.Warn("chain member failed; advancing",
			"vendor_id", vendorID, "region", string(region),
			"capability", string(cap), "error", err.Error(), "latency_ms", latencyMS)
	}

	if len(tried) == 0 {
		return nil, CallResult{}, ErrCircuitOpen
	}
	r.logger.Warn("chain exhausted", "tried", tried, "region", string(region), "capability", string(cap))
	return nil, CallResult{}, ErrCircuitOpen
}

func roleForPosition(idx int, chain []string) provider.Role {
	switch {
	case len(chain) == 0:
		return provider.RolePrimary
	case idx == 0:
		return provider.RolePrimary
	case idx == len(chain)-1:
		return provider.RoleFallback
	default:
		return provider.RoleSecondary
	}
}

// routeTable is the in-memory mirror of provider_region_route. The
// most-specific row wins: city: > country: > default.
type routeTable struct {
	mu     sync.RWMutex
	rows   []RegionRoute
	byPair map[routeKey]RegionRoute
}

type routeKey struct {
	region     Region
	capability provider.Capability
}

func newRouteTable() *routeTable {
	return &routeTable{byPair: map[routeKey]RegionRoute{}}
}

func (t *routeTable) set(row RegionRoute) {
	t.mu.Lock()
	defer t.mu.Unlock()
	key := routeKey{region: row.Region, capability: row.Capability}
	t.byPair[key] = row
	// Append if first time.
	found := false
	for _, r := range t.rows {
		if r.Region == row.Region && r.Capability == row.Capability {
			found = true
			break
		}
	}
	if !found {
		t.rows = append(t.rows, row)
	}
}

func (t *routeTable) get(region Region, cap provider.Capability) (RegionRoute, bool) {
	t.mu.RLock()
	defer t.mu.RUnlock()

	if row, ok := t.byPair[routeKey{region: region, capability: cap}]; ok {
		return row, true
	}
	if row, ok := t.byPair[routeKey{region: "default", capability: cap}]; ok {
		return row, true
	}

	candidates := make([]RegionRoute, 0, len(t.rows))
	for _, row := range t.rows {
		if row.Capability == cap && row.Enabled {
			candidates = append(candidates, row)
		}
	}
	if len(candidates) == 0 {
		return RegionRoute{}, false
	}
	sort.Slice(candidates, func(i, j int) bool {
		return specificityRank(candidates[i].Region) > specificityRank(candidates[j].Region)
	})
	return candidates[0], true
}

func specificityRank(r Region) int {
	switch {
	case len(r) > 4 && r[:4] == "city":
		return 3
	case len(r) > 8 && r[:8] == "country:":
		return 2
	case r == "default":
		return 1
	default:
		return 0
	}
}
