// Package osrm is the OpenStreetMap Routing Machine adapter placeholder.
// OSRM is self-host and supports only `eta` and `route` per
// docs/services/geolocation-service/README.md §4.4. Dev scaffold
// returns ErrNotConfigured.
package osrm

import (
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider"
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider/stub"
)

// VendorID per docs/services/geolocation-service/ERD.md §3.4.
const VendorID = "osrm"

// Adapter is the OSRM adapter.
type Adapter struct {
	stub.Adapter
}

// New returns an OSRM adapter. baseURL is the LAN endpoint of the
// OSRM container (e.g. http://osrm:5000).
func New(_ string, _ []byte) *Adapter {
	return &Adapter{
		Adapter: *stub.New(VendorID, "OSRM (self-host)", provider.AdapterSelfHostREST, true, false, []string{"global"},
			provider.CapabilityEta, provider.CapabilityRoute),
	}
}

// Config returns the canonical provider_config row for OSRM.
func Config() provider.ProviderConfig {
	return provider.ProviderConfig{
		VendorID:           VendorID,
		DisplayName:        "OSRM (self-host)",
		AdapterType:        provider.AdapterSelfHostREST,
		Capabilities:       []provider.Capability{provider.CapabilityEta, provider.CapabilityRoute},
		IsSelfHost:         true,
		IsStaticOnly:       false,
		Enabled:            true,
		Priority:           300,
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
