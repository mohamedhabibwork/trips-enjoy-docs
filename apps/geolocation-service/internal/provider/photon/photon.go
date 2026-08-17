// Package photon is the Photon OSM-based geocoder adapter placeholder.
// Photon supports forward and reverse geocoding per
// docs/services/geolocation-service/README.md §4.4. It is lighter than
// Pelias and is suitable as a fallback.
package photon

import (
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider"
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider/stub"
)

// VendorID per docs/services/geolocation-service/ERD.md §3.4.
const VendorID = "photon"

// Adapter is the Photon adapter.
type Adapter struct {
	stub.Adapter
}

// New returns a Photon adapter.
func New(_ string) *Adapter {
	return &Adapter{
		Adapter: *stub.New(VendorID, "Photon (self-host)", provider.AdapterSelfHostREST, true, false, []string{"global"},
			provider.CapabilityGeocodeForward, provider.CapabilityGeocodeReverse),
	}
}

// Config returns the canonical provider_config row for Photon.
func Config() provider.ProviderConfig {
	return provider.ProviderConfig{
		VendorID:           VendorID,
		DisplayName:        "Photon (self-host)",
		AdapterType:        provider.AdapterSelfHostREST,
		Capabilities:       []provider.Capability{provider.CapabilityGeocodeForward, provider.CapabilityGeocodeReverse},
		IsSelfHost:         true,
		IsStaticOnly:       false,
		Enabled:            false,
		Priority:           410,
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
