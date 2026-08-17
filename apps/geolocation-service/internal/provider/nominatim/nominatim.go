// Package nominatim is the Nominatim (OSM-based) geocoder adapter
// placeholder. Nominatim supports only forward and reverse geocoding
// per docs/services/geolocation-service/README.md §4.4. Fair-use is 1
// req/s so it is only suitable as a fallback.
package nominatim

import (
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider"
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider/stub"
)

// VendorID per docs/services/geolocation-service/ERD.md §3.4.
const VendorID = "nominatim"

// Adapter is the Nominatim adapter.
type Adapter struct {
	stub.Adapter
}

// New returns a Nominatim adapter.
func New(_ string) *Adapter {
	return &Adapter{
		Adapter: *stub.New(VendorID, "Nominatim (OSM, fair-use)", provider.AdapterSelfHostREST, true, false, []string{"global"},
			provider.CapabilityGeocodeForward, provider.CapabilityGeocodeReverse),
	}
}

// Config returns the canonical provider_config row for Nominatim.
func Config() provider.ProviderConfig {
	return provider.ProviderConfig{
		VendorID:           VendorID,
		DisplayName:        "Nominatim (OSM, fair-use)",
		AdapterType:        provider.AdapterSelfHostREST,
		Capabilities:       []provider.Capability{provider.CapabilityGeocodeForward, provider.CapabilityGeocodeReverse},
		IsSelfHost:         true,
		IsStaticOnly:       false,
		Enabled:            false, // fair-use; default off, opt-in
		Priority:           400,
		AuthType:           provider.AuthNone,
		VaultSecretPath:    "",
		QPSLimit:           1,
		BurstLimit:         1,
		TimeoutMS:          1500,
		FailureThreshold:   5,
		CooldownSeconds:    30,
		HalfOpenProbeCount: 3,
		CostPer1kUSD:       0,
		Jurisdictions:      []string{"global"},
	}
}
