// Package valhalla is the Valhalla routing-engine adapter placeholder.
// Valhalla is self-host and supports only `eta` and `route` per
// docs/services/geolocation-service/README.md §4.4.
package valhalla

import (
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider"
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider/stub"
)

// VendorID per docs/services/geolocation-service/ERD.md §3.4.
const VendorID = "valhalla"

// Adapter is the Valhalla adapter.
type Adapter struct {
	stub.Adapter
}

// New returns a Valhalla adapter.
func New(_ string, _ []byte) *Adapter {
	return &Adapter{
		Adapter: *stub.New(VendorID, "Valhalla (self-host)", provider.AdapterSelfHostREST, true, false, []string{"global"},
			provider.CapabilityEta, provider.CapabilityRoute),
	}
}

// Config returns the canonical provider_config row for Valhalla.
func Config() provider.ProviderConfig {
	return provider.ProviderConfig{
		VendorID:           VendorID,
		DisplayName:        "Valhalla (self-host)",
		AdapterType:        provider.AdapterSelfHostREST,
		Capabilities:       []provider.Capability{provider.CapabilityEta, provider.CapabilityRoute},
		IsSelfHost:         true,
		IsStaticOnly:       false,
		Enabled:            true,
		Priority:           310,
		AuthType:           provider.AuthNone,
		VaultSecretPath:    "",
		QPSLimit:           500,
		BurstLimit:         500,
		TimeoutMS:          1500,
		FailureThreshold:   5,
		CooldownSeconds:    30,
		HalfOpenProbeCount: 3,
		CostPer1kUSD:       0,
		Jurisdictions:      []string{"global"},
	}
}
