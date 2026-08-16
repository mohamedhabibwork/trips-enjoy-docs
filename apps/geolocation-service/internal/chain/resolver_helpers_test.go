package chain

import (
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider"
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider/stub"
)

// osrmAdapter returns a stub.Adapter that advertises only eta + route,
// mirroring the production OSRM adapter. Defined here so the chain
// tests don't have to import internal/provider/osrm (which pulls in
// the full provider registry).
func osrmAdapter() provider.MapProvider {
	a := stub.New("osrm", "OSRM (self-host)", provider.AdapterSelfHostREST, true, false, []string{"global"},
		provider.CapabilityEta, provider.CapabilityRoute)
	return a
}

// osrmConfig mirrors the production OSRM ProviderConfig row.
func osrmConfig() provider.ProviderConfig {
	return provider.ProviderConfig{
		VendorID:           "osrm",
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
