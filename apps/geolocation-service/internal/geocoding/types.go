// Package geocoding is the geolocation-service domain aggregate. It
// owns the four public capabilities (forward / reverse / ETA / route)
// and the persistent cache that backs them. The package depends only
// on internal/chain (chain resolver + circuits + rate limit) and
// internal/events (publisher), not on any transport.
package geocoding

import (
	"time"

	"github.com/trips-enjoy/platform/geolocation-service/internal/provider"
)

// Coordinate mirrors provider.Coordinate for response shapes.
type Coordinate = provider.Coordinate

// ProviderInfo is the metadata block embedded in every public response
// per docs/services/geolocation-service/INTEGRATION.md §1.1.
type ProviderInfo struct {
	VendorID      string              `json:"vendor_id"`
	ChainPosition int                 `json:"chain_position"`
	Role          provider.Role       `json:"role"`
	Capability    provider.Capability `json:"capability"`
	Region        string              `json:"region"`
	IsSelfHost    bool                `json:"is_self_host"`
}

// GeocodeResponse is the canonical body for POST /v1/geocodes and
// GET /v1/geocodes/reverse per INTEGRATION.md §1.1 + §1.2.
type GeocodeResponse struct {
	ID               string            `json:"id"`
	Kind             string            `json:"kind"` // forward | reverse
	Locale           string            `json:"locale"`
	Coordinate       Coordinate        `json:"coordinate"`
	FormattedAddress string            `json:"formatted_address,omitempty"`
	Components       map[string]string `json:"address_components,omitempty"`
	Confidence       float64           `json:"confidence,omitempty"`
	BBox             *provider.BBox    `json:"bbox,omitempty"`
	CacheHit         bool              `json:"cache_hit"`
	Approximate      bool              `json:"approximate,omitempty"`
	Provider         ProviderInfo      `json:"provider"`
	OccurredAt       time.Time         `json:"occurred_at"`
}

// EtaResponse is the canonical body for POST /v1/etas per INTEGRATION.md §1.3.
type EtaResponse struct {
	ID             string       `json:"id"`
	ETASeconds     int          `json:"eta_seconds"`
	DistanceMeters int          `json:"distance_meters"`
	TrafficBucket  string       `json:"traffic_bucket"`
	CacheHit       bool         `json:"cache_hit"`
	Provider       ProviderInfo `json:"provider"`
	OccurredAt     time.Time    `json:"occurred_at"`
}

// RouteResponse is the canonical body for POST /v1/routes per INTEGRATION.md §1.4.
type RouteResponse struct {
	ID             string               `json:"id"`
	Polyline       string               `json:"polyline"`
	DistanceMeters int                  `json:"distance_meters"`
	ETASeconds     int                  `json:"eta_seconds"`
	Steps          []provider.RouteStep `json:"steps,omitempty"`
	Alternatives   []*provider.Route    `json:"alternatives,omitempty"`
	CacheHit       bool                 `json:"cache_hit"`
	Provider       ProviderInfo         `json:"provider"`
	OccurredAt     time.Time            `json:"occurred_at"`
}

// CityLookupResponse is the canonical body for GET /v1/cities/lookup
// per INTEGRATION.md §1.5.
type CityLookupResponse struct {
	CityID      string    `json:"city_id"`
	Name        string    `json:"name"`
	CountryCode string    `json:"country_code"`
	Timezone    string    `json:"timezone"`
	OccurredAt  time.Time `json:"occurred_at"`
}

// AnalyticsEvent is the data payload of every geolocation.* analytics
// event per INTEGRATION.md §3.1..3.2.
type AnalyticsEvent struct {
	Kind          string              `json:"kind"`
	Locale        string              `json:"locale,omitempty"`
	CacheHit      bool                `json:"cache_hit"`
	VendorID      string              `json:"vendor_id"`
	ChainPosition int                 `json:"chain_position"`
	Role          provider.Role       `json:"role"`
	Region        string              `json:"region"`
	Capability    provider.Capability `json:"capability"`
	IsSelfHost    bool                `json:"is_self_host"`
	Coordinate    *Coordinate         `json:"coordinate,omitempty"`
	CityID        string              `json:"city_id,omitempty"`
	Confidence    float64             `json:"confidence,omitempty"`
	LatencyMS     int                 `json:"latency_ms"`
}

// EtaAnalyticsEvent is the data payload for geolocation.eta.computed.v1
// per INTEGRATION.md §3.2.
type EtaAnalyticsEvent struct {
	ETASeconds        int                 `json:"eta_seconds"`
	DistanceMeters    int                 `json:"distance_meters"`
	TrafficBucket     string              `json:"traffic_bucket"`
	CacheHit          bool                `json:"cache_hit"`
	VendorID          string              `json:"vendor_id"`
	ChainPosition     int                 `json:"chain_position"`
	Role              provider.Role       `json:"role"`
	Region            string              `json:"region"`
	Capability        provider.Capability `json:"capability"`
	CityIDOrigin      string              `json:"city_id_origin,omitempty"`
	CityIDDestination string              `json:"city_id_destination,omitempty"`
	IsSelfHost        bool                `json:"is_self_host"`
	LatencyMS         int                 `json:"latency_ms"`
}
