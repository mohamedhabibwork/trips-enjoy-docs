package chain

import (
	"context"
	"testing"

	"github.com/trips-enjoy/platform/geolocation-service/internal/provider"
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider/mock"
)

// TestResolver_ServesEveryCapabilityFromDefaultChain proves the
// resolver walks the seeded default chain (= [mock]) and returns the
// canonical result for every capability the mock advertises. It is
// the canonical happy-path smoke test for the chain layer.
func TestResolver_ServesEveryCapabilityFromDefaultChain(t *testing.T) {
	registry := provider.NewRegistry()
	registry.Register(mock.New(), mock.Config())

	breakers := NewCircuitBreakers()
	limiters := NewRateLimiter()
	for _, c := range registry.ListConfigs() {
		breakers.Register(c.VendorID, c.FailureThreshold, c.CooldownSeconds, c.HalfOpenProbeCount)
		limiters.Register(c.VendorID, c.QPSLimit, c.BurstLimit)
	}
	resolver := NewResolver(registry, breakers, limiters, nil)

	for _, cap := range provider.AllCapabilities {
		_ = resolver.SetRoute(RegionRoute{Region: "default", Capability: cap, Chain: []string{mock.VendorID}, Enabled: true})
	}

	// GeocodeForward
	addr, meta, err := resolver.GeocodeForward(context.Background(), "default", provider.GeocodeRequest{Address: "1600 Amphitheatre Pkwy", Locale: "en"})
	if err != nil {
		t.Fatalf("GeocodeForward: %v", err)
	}
	if meta.VendorID != mock.VendorID {
		t.Errorf("expected vendor=%s, got %s", mock.VendorID, meta.VendorID)
	}
	if addr.Coordinate.Lat == 0 || addr.Coordinate.Lon == 0 {
		t.Errorf("expected non-zero coordinate, got %+v", addr.Coordinate)
	}

	// GeocodeReverse
	addr2, _, err := resolver.GeocodeReverse(context.Background(), "default", provider.ReverseRequest{Coordinate: provider.Coordinate{Lat: 51.5, Lon: -0.1}, Locale: "en"})
	if err != nil {
		t.Fatalf("GeocodeReverse: %v", err)
	}
	if addr2.Coordinate.Lat != 51.5 || addr2.Coordinate.Lon != -0.1 {
		t.Errorf("expected coordinate echoed, got %+v", addr2.Coordinate)
	}

	// Eta
	eta, _, err := resolver.Eta(context.Background(), "default", provider.EtaRequest{Origin: provider.Coordinate{Lat: 51.5, Lon: -0.1}, Destination: provider.Coordinate{Lat: 51.6, Lon: -0.2}, TrafficBucket: "medium"})
	if err != nil {
		t.Fatalf("Eta: %v", err)
	}
	if eta.DistanceMeters <= 0 || eta.ETASeconds <= 0 {
		t.Errorf("expected positive ETA + distance, got %+v", eta)
	}

	// Route
	route, _, err := resolver.Route(context.Background(), "default", provider.RouteRequest{Origin: provider.Coordinate{Lat: 51.5, Lon: -0.1}, Destination: provider.Coordinate{Lat: 51.6, Lon: -0.2}, Geometry: "polyline"})
	if err != nil {
		t.Fatalf("Route: %v", err)
	}
	if route.Polyline == "" || route.DistanceMeters <= 0 {
		t.Errorf("expected polyline + distance, got %+v", route)
	}
}

// TestResolver_AdvancesOnCapabilityMismatch proves that a chain
// member that doesn't advertise a capability is silently skipped
// (per README §4.5 step 3: capability pre-check).
func TestResolver_AdvancesOnCapabilityMismatch(t *testing.T) {
	registry := provider.NewRegistry()
	// OSRM adapter via the inline stub adapter is the simplest way to
	// register a vendor that advertises only eta+route. The stub
	// package exposes the lower-level constructor.
	registry.Register(osrmAdapter(), osrmConfig())
	registry.Register(mock.New(), mock.Config())

	breakers := NewCircuitBreakers()
	limiters := NewRateLimiter()
	for _, c := range registry.ListConfigs() {
		breakers.Register(c.VendorID, c.FailureThreshold, c.CooldownSeconds, c.HalfOpenProbeCount)
		limiters.Register(c.VendorID, c.QPSLimit, c.BurstLimit)
	}
	resolver := NewResolver(registry, breakers, limiters, nil)

	// osrm is first but cannot geocode_forward → resolver should
	// skip it and succeed on mock.
	_ = resolver.SetRoute(RegionRoute{
		Region:     "default",
		Capability: provider.CapabilityGeocodeForward,
		Chain:      []string{"osrm", mock.VendorID},
		Enabled:    true,
	})

	addr, meta, err := resolver.GeocodeForward(context.Background(), "default", provider.GeocodeRequest{Address: "Dublin", Locale: "en"})
	if err != nil {
		t.Fatalf("expected success on second member, got %v", err)
	}
	if meta.VendorID != mock.VendorID {
		t.Errorf("expected fallback to mock, got %s", meta.VendorID)
	}
	if addr.Coordinate.Lat == 0 {
		t.Errorf("expected non-zero coordinate, got %+v", addr.Coordinate)
	}
}

// TestResolver_ChainExhaustedReturnsErrCircuitOpen proves that when
// every member is un-registered (or circuit-open / rate-limited) the
// resolver returns ErrCircuitOpen, which the httpapi layer maps to
// 503 CIRCUIT_OPEN.
func TestResolver_ChainExhaustedReturnsErrCircuitOpen(t *testing.T) {
	registry := provider.NewRegistry()
	registry.Register(mock.New(), mock.Config())

	breakers := NewCircuitBreakers()
	limiters := NewRateLimiter()
	for _, c := range registry.ListConfigs() {
		breakers.Register(c.VendorID, c.FailureThreshold, c.CooldownSeconds, c.HalfOpenProbeCount)
		limiters.Register(c.VendorID, c.QPSLimit, c.BurstLimit)
	}
	resolver := NewResolver(registry, breakers, limiters, nil)

	// Configure a chain whose only member is unregistered.
	_ = resolver.SetRoute(RegionRoute{
		Region:     "default",
		Capability: provider.CapabilityGeocodeForward,
		Chain:      []string{"ghost-vendor"},
		Enabled:    true,
	})

	_, _, err := resolver.GeocodeForward(context.Background(), "default", provider.GeocodeRequest{Address: "x", Locale: "en"})
	if err != ErrCircuitOpen {
		t.Fatalf("expected ErrCircuitOpen, got %v", err)
	}
}

// TestIsValidRegion proves the region-format regex.
func TestIsValidRegion(t *testing.T) {
	for _, ok := range []string{"default", "country:US", "country:DE"} {
		if !IsValidRegion(ok) {
			t.Errorf("expected %q to be valid", ok)
		}
	}
	for _, bad := range []string{"DEFAULT", "country:usa", "city:not-a-uuid", "city:00000000-0000-0000-0000-00000000000", " region:US"} {
		if IsValidRegion(bad) {
			t.Errorf("expected %q to be invalid", bad)
		}
	}
}
