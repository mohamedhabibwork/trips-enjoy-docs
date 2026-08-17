// Package mock is the deterministic in-process map provider used in
// dev, test, and CI per docs/services/geolocation-service/README.md §4.4.
// The mock advertises all 7 capabilities; responses are derived from a
// small in-memory fixtures table so the same query always returns the
// same answer. CI runs use the mock instead of live vendors.
//
// The mock also acts as the **primary** member of the seeded default
// chain (`provider_region_route` row for region=`default`) per
// README.md §4.4.
package mock

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"math"
	"strings"

	"github.com/trips-enjoy/platform/geolocation-service/internal/provider"
)

// VendorID per docs/services/geolocation-service/ERD.md §3.4.
const VendorID = "mock"

// Adapter is the mock provider.
type Adapter struct {
	fixtures *Fixtures
}

// New returns a mock adapter populated with the canonical dev fixtures
// (1000 mock geocodes around EU-WEST and US-EAST test cities, per
// README.md §17).
func New() *Adapter {
	return &Adapter{fixtures: DefaultFixtures()}
}

// VendorID implements provider.MapProvider.
func (a *Adapter) VendorID() string { return VendorID }

// DisplayName implements provider.MapProvider.
func (a *Adapter) DisplayName() string { return "Mock (dev / test / CI)" }

// Capabilities implements provider.MapProvider.
func (a *Adapter) Capabilities() []provider.Capability { return provider.AllCapabilities }

// AdapterType implements provider.MapProvider.
func (a *Adapter) AdapterType() provider.AdapterType { return provider.AdapterInProcess }

// IsSelfHost implements provider.MapProvider.
func (a *Adapter) IsSelfHost() bool { return true }

// IsStaticOnly implements provider.MapProvider.
func (a *Adapter) IsStaticOnly() bool { return false }

// Jurisdictions implements provider.MapProvider.
func (a *Adapter) Jurisdictions() []string { return []string{"global"} }

// HealthCheck implements provider.MapProvider. The mock never fails.
func (a *Adapter) HealthCheck(_ context.Context) error { return nil }

// Close implements provider.MapProvider.
func (a *Adapter) Close() error { return nil }

// GeocodeForward implements provider.MapProvider. The mock derives a
// stable coordinate from the SHA-256 of the normalized address so the
// same query always produces the same result.
func (a *Adapter) GeocodeForward(_ context.Context, req provider.GeocodeRequest) (provider.GeoAddress, error) {
	coord := deterministicCoord(req.Address)
	city, country := a.fixtures.ReverseCity(coord)
	formatted := strings.TrimSpace(req.Address)
	if city != "" {
		formatted = formatted + ", " + city
	}
	if country != "" {
		formatted = formatted + ", " + country
	}
	return provider.GeoAddress{
		Coordinate:       coord,
		FormattedAddress: formatted,
		Components: []provider.AddressComponent{
			{Key: "city", Value: city},
			{Key: "country", Value: country},
		},
		Confidence: 0.95,
		BBox:       bboxFromCoord(coord),
	}, nil
}

// GeocodeReverse implements provider.MapProvider.
func (a *Adapter) GeocodeReverse(_ context.Context, req provider.ReverseRequest) (provider.GeoAddress, error) {
	city, country := a.fixtures.ReverseCity(req.Coordinate)
	return provider.GeoAddress{
		Coordinate:       req.Coordinate,
		FormattedAddress: fmt.Sprintf("near %s, %s", city, country),
		Components: []provider.AddressComponent{
			{Key: "city", Value: city},
			{Key: "country", Value: country},
		},
		Confidence:  0.85,
		BBox:        bboxFromCoord(req.Coordinate),
		Approximate: req.Approximate,
	}, nil
}

// Eta implements provider.MapProvider.
func (a *Adapter) Eta(_ context.Context, req provider.EtaRequest) (provider.EtaEstimate, error) {
	meters := haversineMeters(req.Origin, req.Destination)
	etaSeconds := int(math.Round(meters / 13.4)) // ~30 mph in m/s
	if etaSeconds < 30 {
		etaSeconds = 30
	}
	traffic := req.TrafficBucket
	if traffic == "" {
		traffic = "unknown"
	}
	return provider.EtaEstimate{
		ETASeconds:     etaSeconds,
		DistanceMeters: int(math.Round(meters)),
		TrafficBucket:  traffic,
	}, nil
}

// Route implements provider.MapProvider.
func (a *Adapter) Route(_ context.Context, req provider.RouteRequest) (provider.Route, error) {
	meters := haversineMeters(req.Origin, req.Destination)
	etaSeconds := int(math.Round(meters / 13.4))
	if etaSeconds < 30 {
		etaSeconds = 30
	}
	steps := []provider.RouteStep{
		{Instruction: fmt.Sprintf("Depart from (%.4f, %.4f)", req.Origin.Lat, req.Origin.Lon), DistanceMeters: 0, DurationSec: 0},
		{Instruction: fmt.Sprintf("Head toward (%.4f, %.4f)", req.Destination.Lat, req.Destination.Lon), DistanceMeters: int(math.Round(meters * 0.6)), DurationSec: int(math.Round(float64(etaSeconds) * 0.6))},
		{Instruction: "Arrive at destination", DistanceMeters: int(math.Round(meters * 0.4)), DurationSec: etaSeconds - int(math.Round(float64(etaSeconds)*0.6))},
	}
	return provider.Route{
		Polyline:       encodeMockPolyline(req.Origin, req.Destination),
		DistanceMeters: int(math.Round(meters)),
		ETASeconds:     etaSeconds,
		Steps:          steps,
	}, nil
}

// Autocomplete implements provider.MapProvider.
func (a *Adapter) Autocomplete(_ context.Context, req provider.AutocompleteRequest) ([]provider.PlaceCandidate, error) {
	coord := deterministicCoord(req.Prefix)
	city, _ := a.fixtures.ReverseCity(coord)
	return []provider.PlaceCandidate{
		{PlaceID: "mock-" + coordKey(coord), PrimaryTxt: req.Prefix, Secondary: city, Coordinate: provider.CoordF64(coord)},
	}, nil
}

// PlaceDetails implements provider.MapProvider.
func (a *Adapter) PlaceDetails(_ context.Context, req provider.PlaceDetailsRequest) (provider.PlaceDetails, error) {
	coord := deterministicCoord(req.PlaceID)
	city, country := a.fixtures.ReverseCity(coord)
	return provider.PlaceDetails{
		PlaceID:    req.PlaceID,
		Name:       city,
		Coordinate: coord,
		Address:    provider.GeoAddress{Coordinate: coord, FormattedAddress: fmt.Sprintf("%s, %s", city, country)},
	}, nil
}

// StaticMap implements provider.MapProvider. Returns a deterministic
// data-URI-shaped URL so callers can spot-check that the wiring is live
// without making a real network call.
func (a *Adapter) StaticMap(_ context.Context, req provider.StaticMapRequest) (string, error) {
	return fmt.Sprintf("data:image/png;base64,mock:%s:%d", coordKey(req.Coordinate), req.Zoom), nil
}

// Config returns the canonical provider_config row for the mock provider.
func Config() provider.ProviderConfig {
	return provider.ProviderConfig{
		VendorID:           VendorID,
		DisplayName:        "Mock (dev / test / CI)",
		AdapterType:        provider.AdapterInProcess,
		Capabilities:       provider.AllCapabilities,
		IsSelfHost:         true,
		IsStaticOnly:       false,
		Enabled:            true,
		Priority:           1,
		AuthType:           provider.AuthNone,
		VaultSecretPath:    "",
		QPSLimit:           10000,
		BurstLimit:         10000,
		TimeoutMS:          50,
		FailureThreshold:   1000,
		CooldownSeconds:    5,
		HalfOpenProbeCount: 100,
		CostPer1kUSD:       0,
		Jurisdictions:      []string{"global"},
	}
}

// deterministicCoord maps an arbitrary string to a WGS84 coordinate by
// using the first 8 bytes of SHA-256(query) as a uniform sampler.
// latitude ∈ [-85, 85], longitude ∈ [-180, 180].
func deterministicCoord(query string) provider.Coordinate {
	sum := sha256.Sum256([]byte(strings.ToLower(strings.TrimSpace(query))))
	latBits := binary.BigEndian.Uint32(sum[0:4])
	lonBits := binary.BigEndian.Uint32(sum[4:8])
	latFrac := float64(latBits) / float64(math.MaxUint32)
	lonFrac := float64(lonBits) / float64(math.MaxUint32)
	lat := -85.0 + latFrac*170.0
	lon := -180.0 + lonFrac*360.0
	return provider.Coordinate{
		Lat: math.Round(lat*1e6) / 1e6,
		Lon: math.Round(lon*1e6) / 1e6,
	}
}

// coordKey is a stable short key for use as a place_id / fixture row id.
func coordKey(c provider.Coordinate) string {
	return fmt.Sprintf("%.4f,%.4f", c.Lat, c.Lon)
}

func bboxFromCoord(c provider.Coordinate) *provider.BBox {
	const d = 0.005 // ~500m at the equator
	return &provider.BBox{
		MinLat: c.Lat - d, MaxLat: c.Lat + d,
		MinLon: c.Lon - d, MaxLon: c.Lon + d,
	}
}

func haversineMeters(a, b provider.Coordinate) float64 {
	const r = 6371000.0
	lat1 := a.Lat * math.Pi / 180
	lat2 := b.Lat * math.Pi / 180
	dLat := (b.Lat - a.Lat) * math.Pi / 180
	dLon := (b.Lon - a.Lon) * math.Pi / 180
	s := math.Sin(dLat/2)*math.Sin(dLat/2) +
		math.Cos(lat1)*math.Cos(lat2)*math.Sin(dLon/2)*math.Sin(dLon/2)
	return 2 * r * math.Asin(math.Sqrt(s))
}

// encodeMockPolyline emits a Google-encoded polyline with 1 decimal
// of precision over the two endpoints. The format is what
// GoogleMapPolylineCodec decodes; this is enough for fixture round-trip
// checks (the polyline field on RouteCache is opaque to the platform).
func encodeMockPolyline(a, b provider.Coordinate) string {
	encode := func(lat, lon float64) string {
		const precision = 1e5
		late := int(math.Round(lat * precision))
		lone := int(math.Round(lon * precision))
		var sb strings.Builder
		for _, v := range []int{late, lone} {
			v = v << 1
			if v < 0 {
				v = ^v
			}
			for v >= 0x20 {
				sb.WriteByte(byte(0x20 | (v & 0x1f)))
				v >>= 5
			}
			sb.WriteByte(byte(v))
		}
		return sb.String()
	}
	return encode(a.Lat, a.Lon) + encode(b.Lat, b.Lon)
}
