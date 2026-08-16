// Package google is the Google Maps Platform adapter placeholder.
// Per docs/services/geolocation-service/TECH.md §2.1 the production
// wiring uses googlemaps/go-maps + raw resty for autocomplete / static
// map. The dev scaffold ships a stub that returns ErrNotConfigured
// from every capability call (the chain resolver advances to the next
// member).
package google

import (
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider"
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider/stub"
)

// VendorID is the canonical kebab-case vendor identifier per
// docs/services/geolocation-service/ERD.md §3.4 (provider_config).
const VendorID = "google"

// Adapter is the Google Maps Platform adapter. It advertises all 7
// capabilities and returns provider.ErrNotConfigured until production
// wiring lands.
type Adapter struct {
	stub.Adapter
}

// New returns a Google adapter. API key handling is the production
// follow-up — the dev scaffold ignores it.
func New(_ string) *Adapter {
	return &Adapter{
		Adapter: *stub.New(VendorID, "Google Maps Platform", provider.AdapterCommercialREST, false, false, []string{"global"}, provider.AllCapabilities...),
	}
}

// Config returns the canonical provider_config row for the Google
// adapter per docs/services/geolocation-service/ERD.md §3.4.
func Config() provider.ProviderConfig {
	return provider.ProviderConfig{
		VendorID:           VendorID,
		DisplayName:        "Google Maps Platform",
		AdapterType:        provider.AdapterCommercialREST,
		Capabilities:       provider.AllCapabilities,
		IsSelfHost:         false,
		IsStaticOnly:       false,
		Enabled:            true,
		Priority:           100,
		AuthType:           provider.AuthAPIKey,
		VaultSecretPath:    "kv/<env>/geolocation/google",
		QPSLimit:           200,
		BurstLimit:         200,
		TimeoutMS:          1500,
		FailureThreshold:   5,
		CooldownSeconds:    30,
		HalfOpenProbeCount: 3,
		CostPer1kUSD:       5.00,
		Jurisdictions:      []string{"global"},
		Metadata:           map[string]any{"quota_project_id": ""},
	}
}
