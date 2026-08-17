// Package pelias is the Pelias modular OSM geocoder adapter placeholder.
// Pelias supports everything except static_map per
// docs/services/geolocation-service/README.md §4.4.
package pelias

import (
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider"
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider/stub"
)

// VendorID per docs/services/geolocation-service/ERD.md §3.4.
const VendorID = "pelias"

// Adapter is the Pelias adapter.
type Adapter struct {
	stub.Adapter
}

// New returns a Pelias adapter.
func New(_, _ string) *Adapter {
	return &Adapter{
		Adapter: *stub.New(VendorID, "Pelias (self-host)", provider.AdapterSelfHostREST, true, false, []string{"global"},
			provider.CapabilityGeocodeForward,
			provider.CapabilityGeocodeReverse,
			provider.CapabilityEta,
			provider.CapabilityRoute,
			provider.CapabilityAutocomplete,
			provider.CapabilityPlaceDetails,
		),
	}
}

// Config returns the canonical provider_config row for Pelias.
func Config() provider.ProviderConfig {
	return provider.ProviderConfig{
		VendorID:    VendorID,
		DisplayName: "Pelias (self-host)",
		AdapterType: provider.AdapterSelfHostREST,
		Capabilities: []provider.Capability{
			provider.CapabilityGeocodeForward,
			provider.CapabilityGeocodeReverse,
			provider.CapabilityEta,
			provider.CapabilityRoute,
			provider.CapabilityAutocomplete,
			provider.CapabilityPlaceDetails,
		},
		IsSelfHost:         true,
		IsStaticOnly:       false,
		Enabled:            false,
		Priority:           320,
		AuthType:           provider.AuthNone,
		VaultSecretPath:    "",
		QPSLimit:           100,
		BurstLimit:         100,
		TimeoutMS:          1500,
		FailureThreshold:   5,
		CooldownSeconds:    30,
		HalfOpenProbeCount: 3,
		CostPer1kUSD:       0,
		Jurisdictions:      []string{"global"},
	}
}
