// Package mapbox is the Mapbox adapter placeholder. Production wiring
// per docs/services/geolocation-service/TECH.md §2.1 uses mapbox/mapbox-sdk-go
// + raw resty. Dev scaffold returns ErrNotConfigured from every call.
package mapbox

import (
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider"
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider/stub"
)

// VendorID per docs/services/geolocation-service/ERD.md §3.4.
const VendorID = "mapbox"

// Adapter is the Mapbox adapter. It advertises all 7 capabilities.
type Adapter struct {
	stub.Adapter
}

// New returns a Mapbox adapter. The access token is the production
// follow-up.
func New(_ string) *Adapter {
	return &Adapter{
		Adapter: *stub.New(VendorID, "Mapbox", provider.AdapterCommercialREST, false, false, []string{"global"}, provider.AllCapabilities...),
	}
}

// Config returns the canonical provider_config row for Mapbox.
func Config() provider.ProviderConfig {
	return provider.ProviderConfig{
		VendorID:           VendorID,
		DisplayName:        "Mapbox",
		AdapterType:        provider.AdapterCommercialREST,
		Capabilities:       provider.AllCapabilities,
		IsSelfHost:         false,
		IsStaticOnly:       false,
		Enabled:            true,
		Priority:           110,
		AuthType:           provider.AuthAPIKey,
		VaultSecretPath:    "kv/<env>/geolocation/mapbox",
		QPSLimit:           200,
		BurstLimit:         200,
		TimeoutMS:          1500,
		FailureThreshold:   5,
		CooldownSeconds:    30,
		HalfOpenProbeCount: 3,
		CostPer1kUSD:       4.00,
		Jurisdictions:      []string{"global"},
		Metadata:           map[string]any{"dataset": "mapbox.places"},
	}
}
