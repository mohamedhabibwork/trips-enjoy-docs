package geocoding

import (
	"testing"
	"time"

	"github.com/trips-enjoy/platform/geolocation-service/internal/provider"
)

func TestGeocodeCacheKey_StableForSameInput(t *testing.T) {
	a := GeocodeCacheKey("1600 Amphitheatre Pkwy", "en", "")
	b := GeocodeCacheKey("1600 Amphitheatre Pkwy", "en", "")
	if a != b {
		t.Fatalf("expected stable key, got %q vs %q", a, b)
	}
	c := GeocodeCacheKey("1600 Amphitheatre Pkwy", "ar", "")
	if a == c {
		t.Fatalf("expected different key for different locale")
	}
}

func TestReverseCacheKey_RoundsToSixDecimals(t *testing.T) {
	a := ReverseCacheKey(provider.Coordinate{Lat: 51.5074001, Lon: -0.1278001}, "en")
	b := ReverseCacheKey(provider.Coordinate{Lat: 51.5074009, Lon: -0.1278009}, "en")
	if a != b {
		t.Fatalf("expected rounded-to-6dp keys to match, got %q vs %q", a, b)
	}
}

func TestEtaCacheKey_VariesByTrafficBucket(t *testing.T) {
	low, _ := time.Parse(time.RFC3339, "2026-08-14T08:00:00Z")
	req := func(bucket string) provider.EtaRequest {
		return provider.EtaRequest{Origin: provider.Coordinate{Lat: 0, Lon: 0}, Destination: provider.Coordinate{Lat: 1, Lon: 1}, DepartureTime: &low, TrafficBucket: bucket}
	}
	if EtaCacheKey(req("low")) == EtaCacheKey(req("high")) {
		t.Fatalf("expected traffic_bucket to affect cache key")
	}
}

func TestRouteCacheKey_StablePerGrid(t *testing.T) {
	req := provider.RouteRequest{Origin: provider.Coordinate{Lat: 51.5, Lon: -0.1}, Destination: provider.Coordinate{Lat: 51.6, Lon: -0.2}}
	a := RouteCacheKey(req)
	b := RouteCacheKey(req)
	if a != b {
		t.Fatalf("expected stable route cache key, got %q vs %q", a, b)
	}
}

func TestCache_PutAndLookup(t *testing.T) {
	c := NewCache(func() time.Time {
		// Frozen clock for deterministic TTL.
		return time.Date(2026, 8, 14, 12, 0, 0, 0, time.UTC)
	})
	entry := &CacheEntry{
		CacheKey:   "key-1",
		VendorID:   "mock",
		Coordinate: provider.Coordinate{Lat: 51.5, Lon: -0.1},
		Address:    provider.GeoAddress{Coordinate: provider.Coordinate{Lat: 51.5, Lon: -0.1}, FormattedAddress: "London, GB"},
	}
	c.PutGeocode(entry, 30*time.Second)

	got, ok := c.LookupGeocode("key-1")
	if !ok {
		t.Fatalf("expected cache hit")
	}
	if got.Address.FormattedAddress != "London, GB" {
		t.Fatalf("unexpected formatted address: %s", got.Address.FormattedAddress)
	}
}

func TestValidation_RejectsBadCoordinate(t *testing.T) {
	details := ValidateGeocodeReverse(provider.ReverseRequest{Coordinate: provider.Coordinate{Lat: 91, Lon: 0}, Locale: "en"})
	if len(details) == 0 {
		t.Fatalf("expected validation failure for lat=91")
	}
}
