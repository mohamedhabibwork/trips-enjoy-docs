// Package provider defines the MapProvider interface that every map
// adapter (Google, Mapbox, HERE, OSRM, Valhalla, Nominatim, Pelias,
// Photon, mock) must implement. The interface is the **single
// integration point** between geocoding logic and the network (per
// docs/services/geolocation-service/INTEGRATION.md §4.1).
//
// Adapters MUST translate vendor responses into the canonical shapes
// declared here (GeoAddress, EtaEstimate, Route, PlaceCandidate,
// PlaceDetails). Adapters NEVER return vendor-specific shapes from
// capability calls.
package provider

import (
	"context"
	"errors"
	"time"
)

// Capability enumerates the seven documented capabilities. The set is
// fixed; an adapter advertises a subset via Capabilities().
type Capability string

const (
	CapabilityGeocodeForward Capability = "geocode_forward"
	CapabilityGeocodeReverse Capability = "geocode_reverse"
	CapabilityEta            Capability = "eta"
	CapabilityRoute          Capability = "route"
	CapabilityAutocomplete   Capability = "autocomplete"
	CapabilityPlaceDetails   Capability = "place_details"
	CapabilityStaticMap      Capability = "static_map"
)

// AllCapabilities is the canonical 7-capability list per
// docs/services/geolocation-service/README.md §4.1.
var AllCapabilities = []Capability{
	CapabilityGeocodeForward,
	CapabilityGeocodeReverse,
	CapabilityEta,
	CapabilityRoute,
	CapabilityAutocomplete,
	CapabilityPlaceDetails,
	CapabilityStaticMap,
}

// AdapterType is the broad classification used to seed provider_config.
// The actual auth type is recorded separately on the config row.
type AdapterType string

const (
	AdapterCommercialREST AdapterType = "commercial_rest"
	AdapterSelfHostREST   AdapterType = "self_host_rest"
	AdapterInProcess      AdapterType = "in_process"
)

// AuthType is the authentication strategy used by an adapter.
type AuthType string

const (
	AuthAPIKey            AuthType = "api_key"
	AuthOAuth2ClientCreds AuthType = "oauth2_client_credentials"
	AuthMTLS              AuthType = "mtls"
	AuthNone              AuthType = "none"
)

// Role is the position a chain member occupies within a per-region
// route (per README.md §4.2).
type Role string

const (
	RolePrimary   Role = "primary"
	RoleSecondary Role = "secondary"
	RoleFallback  Role = "fallback"
	RoleStatic    Role = "static"
	RoleDirect    Role = "direct" // single-vendor test call (bypasses chain)
)

// Coordinate is a WGS84 (SRID 4326) point with sub-meter precision.
type Coordinate struct {
	Lat float64 `json:"lat"`
	Lon float64 `json:"lon"`
}

// AddressComponent mirrors the structured breakdown of a formatted
// address (street, city, country, etc.).
type AddressComponent struct {
	Key       string `json:"key"`
	Value     string `json:"value"`
	Short     string `json:"short,omitempty"`
	IsPrimary bool   `json:"is_primary,omitempty"`
}

// GeoAddress is the canonical result of a forward or reverse geocode.
// Adapted from docs/services/geolocation-service/INTEGRATION.md §1.1.
type GeoAddress struct {
	Coordinate       Coordinate         `json:"coordinate"`
	FormattedAddress string             `json:"formatted_address"`
	Components       []AddressComponent `json:"components,omitempty"`
	Confidence       float64            `json:"confidence,omitempty"`
	BBox             *BBox              `json:"bbox,omitempty"`
	Approximate      bool               `json:"approximate,omitempty"`
}

// BBox is the bounding box of an address (used for reverse geocodes).
type BBox struct {
	MinLat float64 `json:"min_lat"`
	MinLon float64 `json:"min_lon"`
	MaxLat float64 `json:"max_lat"`
	MaxLon float64 `json:"max_lon"`
}

// EtaEstimate is the canonical ETA result (INTEGRATION.md §1.3).
type EtaEstimate struct {
	ETASeconds     int    `json:"eta_seconds"`
	DistanceMeters int    `json:"distance_meters"`
	TrafficBucket  string `json:"traffic_bucket"` // low | medium | high | unknown
}

// RouteStep is a single turn-by-turn instruction.
type RouteStep struct {
	Instruction    string `json:"instruction"`
	DistanceMeters int    `json:"distance_meters"`
	DurationSec    int    `json:"duration_seconds"`
}

// Route is the canonical routing result (INTEGRATION.md §1.4).
type Route struct {
	Polyline       string      `json:"polyline"`
	DistanceMeters int         `json:"distance_meters"`
	ETASeconds     int         `json:"eta_seconds"`
	Steps          []RouteStep `json:"steps,omitempty"`
	Alternatives   []*Route    `json:"alternatives,omitempty"`
}

// PlaceCandidate is one autocomplete hit (INTEGRATION.md §4.1).
type PlaceCandidate struct {
	PlaceID    string   `json:"place_id"`
	PrimaryTxt string   `json:"primary_text"`
	Secondary  string   `json:"secondary_text,omitempty"`
	Coordinate CoordF64 `json:"coordinate"`
}

// CoordF64 is a thin alias so adapters that need floating-point-only
// coordinates can avoid the named Coordinate struct in their fixtures.
type CoordF64 = Coordinate

// PlaceDetails is the canonical record returned by place_details.
type PlaceDetails struct {
	PlaceID    string     `json:"place_id"`
	Name       string     `json:"name"`
	Coordinate Coordinate `json:"coordinate"`
	Address    GeoAddress `json:"address"`
}

// Request types mirror INTEGRATION.md §4.1. They are small by design —
// the resolver enriches them with region/correlation metadata before
// calling the adapter.

type GeocodeRequest struct {
	Address      string            `json:"address"`
	Locale       string            `json:"locale"`
	RegionCityID string            `json:"region_city_id,omitempty"`
	Components   map[string]string `json:"components,omitempty"`
}

type ReverseRequest struct {
	Coordinate  Coordinate `json:"coordinate"`
	Locale      string     `json:"locale"`
	Approximate bool       `json:"approximate,omitempty"`
}

type EtaRequest struct {
	Origin        Coordinate   `json:"origin"`
	Destination   Coordinate   `json:"destination"`
	Waypoints     []Coordinate `json:"waypoints,omitempty"`
	DepartureTime *time.Time   `json:"departure_time,omitempty"`
	TrafficBucket string       `json:"traffic_bucket"`
}

type RouteRequest struct {
	Origin       Coordinate   `json:"origin"`
	Destination  Coordinate   `json:"destination"`
	Waypoints    []Coordinate `json:"waypoints,omitempty"`
	Alternatives bool         `json:"alternatives"`
	Geometry     string       `json:"geometry"` // polyline | geojson
}

type AutocompleteRequest struct {
	Prefix string `json:"prefix"`
	Locale string `json:"locale"`
	Region string `json:"region,omitempty"`
}

type PlaceDetailsRequest struct {
	PlaceID string `json:"place_id"`
	Locale  string `json:"locale"`
}

type StaticMapRequest struct {
	Coordinate Coordinate `json:"coordinate"`
	Zoom       int        `json:"zoom"`
	Width      int        `json:"width"`
	Height     int        `json:"height"`
}

// ProviderConfig is the in-memory mirror of provider_config (ERD §3.4).
// The production wiring hydrates this from PostgreSQL on boot and on
// configuration.updated.v1; the dev scaffold seeds it in main.go.
type ProviderConfig struct {
	VendorID           string         `json:"vendor_id"`
	DisplayName        string         `json:"display_name"`
	AdapterType        AdapterType    `json:"adapter_type"`
	Capabilities       []Capability   `json:"capabilities"`
	IsSelfHost         bool           `json:"is_self_host"`
	IsStaticOnly       bool           `json:"is_static_only"`
	Enabled            bool           `json:"enabled"`
	Priority           int            `json:"priority"`
	BaseURL            string         `json:"base_url,omitempty"`
	AuthType           AuthType       `json:"auth_type,omitempty"`
	VaultSecretPath    string         `json:"vault_secret_path"`
	QPSLimit           int            `json:"qps_limit"`
	BurstLimit         int            `json:"burst_limit"`
	TimeoutMS          int            `json:"timeout_ms"`
	FailureThreshold   int            `json:"failure_threshold"`
	CooldownSeconds    int            `json:"cooldown_seconds"`
	HalfOpenProbeCount int            `json:"half_open_probe_count"`
	CostPer1kUSD       float64        `json:"cost_per_1k_usd,omitempty"`
	Jurisdictions      []string       `json:"jurisdictions"`
	Metadata           map[string]any `json:"metadata,omitempty"`
}

// MapProvider is the canonical adapter interface. Every commercial,
// self-host, and in-process implementation satisfies it (INTEGRATION.md §4.1).
type MapProvider interface {
	// Metadata
	VendorID() string
	DisplayName() string
	Capabilities() []Capability
	AdapterType() AdapterType
	IsSelfHost() bool
	IsStaticOnly() bool
	Jurisdictions() []string

	// Capability calls — every adapter implements the full surface; for
	// unsupported capabilities it returns ErrCapabilityUnsupported.
	GeocodeForward(ctx context.Context, req GeocodeRequest) (GeoAddress, error)
	GeocodeReverse(ctx context.Context, req ReverseRequest) (GeoAddress, error)
	Eta(ctx context.Context, req EtaRequest) (EtaEstimate, error)
	Route(ctx context.Context, req RouteRequest) (Route, error)
	Autocomplete(ctx context.Context, req AutocompleteRequest) ([]PlaceCandidate, error)
	PlaceDetails(ctx context.Context, req PlaceDetailsRequest) (PlaceDetails, error)
	StaticMap(ctx context.Context, req StaticMapRequest) (string, error)

	// Lifecycle
	HealthCheck(ctx context.Context) error
	Close() error
}

// Sentinel errors. The chain resolver maps these to canonical error
// codes per docs/services/geolocation-service/SRS.md §13.
var (
	// ErrNotConfigured is returned by the placeholder stubs (Google,
	// Mapbox, HERE, OSRM, Valhalla, Nominatim, Pelias, Photon) until
	// the production wiring lands. The chain resolver treats it as a
	// non-retryable, capability-shape error and advances to the next
	// chain member.
	ErrNotConfigured = errors.New("provider not configured")

	// ErrCapabilityUnsupported is returned when an adapter does not
	// implement a capability (e.g. OSRM cannot geocode_forward). The
	// chain resolver skips the adapter — never advances for this.
	ErrCapabilityUnsupported = errors.New("capability unsupported by provider")

	// ErrRegionUnsupported is returned when the adapter refuses to
	// serve a jurisdiction (e.g. Google in mainland China). The chain
	// resolver treats it as non-retryable and surfaces the
	// ADDRESS_UNSUPPORTED_REGION error to the caller.
	ErrRegionUnsupported = errors.New("region unsupported by provider")

	// ErrTimeout is returned when the per-vendor timeout elapses. The
	// chain resolver records a failure in the circuit and advances.
	ErrTimeout = errors.New("vendor timeout")

	// ErrUpstream5xx is returned for a retryable HTTP 5xx. The chain
	// resolver records a failure in the circuit and advances.
	ErrUpstream5xx = errors.New("upstream 5xx")
)

// SupportsCapability reports whether the adapter advertises the
// requested capability. Callers use this before invoking the adapter
// (the adapter still returns ErrCapabilityUnsupported defensively, but
// the chain resolver pre-checks via this method).
func SupportsCapability(p MapProvider, cap Capability) bool {
	for _, c := range p.Capabilities() {
		if c == cap {
			return true
		}
	}
	return false
}
