// Package here is the HERE Maps adapter placeholder. HERE supports
// OAuth2 client-credentials (TECH.md §2.1); the production wiring reads
// the credentials from Vault. Dev scaffold returns ErrNotConfigured.
package here

import (
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider"
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider/stub"
)

// VendorID per docs/services/geolocation-service/ERD.md §3.4.
const VendorID = "here"

// Adapter is the HERE Maps adapter. It advertises all 7 capabilities
// and supports optional mTLS (per INTEGRATION.md §4.2 row).
type Adapter struct {
	stub.Adapter
}

// New returns a HERE adapter. OAuth2 token + optional mTLS are the
// production follow-up.
func New(_, _ string) *Adapter {
	return &Adapter{
		Adapter: *stub.New(VendorID, "HERE Maps", provider.AdapterCommercialREST, false, false, []string{"global"}, provider.AllCapabilities...),
	}
}

// Config returns the canonical provider_config row for HERE.
func Config() provider.ProviderConfig {
	return provider.ProviderConfig{
		VendorID:           VendorID,
		DisplayName:        "HERE Maps",
		AdapterType:        provider.AdapterCommercialREST,
		Capabilities:       provider.AllCapabilities,
		IsSelfHost:         false,
		IsStaticOnly:       false,
		Enabled:            true,
		Priority:           90,
		AuthType:           provider.AuthOAuth2ClientCreds,
		VaultSecretPath:    "kv/<env>/geolocation/here",
		QPSLimit:           200,
		BurstLimit:         200,
		TimeoutMS:          1500,
		FailureThreshold:   5,
		CooldownSeconds:    30,
		HalfOpenProbeCount: 3,
		CostPer1kUSD:       4.50,
		Jurisdictions:      []string{"global"},
		Metadata:           map[string]any{"transport_mode": "car"},
	}
}
