// Package stub is shared scaffolding for the 8 placeholder adapters
// (Google, Mapbox, HERE, OSRM, Valhalla, Nominatim, Pelias, Photon)
// that the dev scaffold ships. Every stub advertises its capabilities
// correctly but returns provider.ErrNotConfigured from every capability
// call — the production wiring lands in follow-up PRs. The chain
// resolver treats ErrNotConfigured as non-retryable and advances to
// the next chain member (per INTEGRATION.md §4.3).
package stub

import (
	"context"

	"github.com/trips-enjoy/platform/geolocation-service/internal/provider"
)

// Adapter is the common shape every placeholder satisfies. The actual
// method receivers embed it so they can share the metadata fields.
type Adapter struct {
	VendorIDValue      string
	DisplayNameValue   string
	CapabilitiesValue  []provider.Capability
	AdapterTypeValue   provider.AdapterType
	IsSelfHostValue    bool
	IsStaticOnlyValue  bool
	JurisdictionsValue []string
}

// VendorID implements provider.MapProvider.
func (a *Adapter) VendorID() string { return a.VendorIDValue }

// DisplayName implements provider.MapProvider.
func (a *Adapter) DisplayName() string { return a.DisplayNameValue }

// Capabilities implements provider.MapProvider.
func (a *Adapter) Capabilities() []provider.Capability { return a.CapabilitiesValue }

// AdapterType implements provider.MapProvider.
func (a *Adapter) AdapterType() provider.AdapterType { return a.AdapterTypeValue }

// IsSelfHost implements provider.MapProvider.
func (a *Adapter) IsSelfHost() bool { return a.IsSelfHostValue }

// IsStaticOnly implements provider.MapProvider.
func (a *Adapter) IsStaticOnly() bool { return a.IsStaticOnlyValue }

// Jurisdictions implements provider.MapProvider.
func (a *Adapter) Jurisdictions() []string { return a.JurisdictionsValue }

// GeocodeForward implements provider.MapProvider.
func (a *Adapter) GeocodeForward(_ context.Context, _ provider.GeocodeRequest) (provider.GeoAddress, error) {
	return provider.GeoAddress{}, provider.ErrNotConfigured
}

// GeocodeReverse implements provider.MapProvider.
func (a *Adapter) GeocodeReverse(_ context.Context, _ provider.ReverseRequest) (provider.GeoAddress, error) {
	return provider.GeoAddress{}, provider.ErrNotConfigured
}

// Eta implements provider.MapProvider.
func (a *Adapter) Eta(_ context.Context, _ provider.EtaRequest) (provider.EtaEstimate, error) {
	return provider.EtaEstimate{}, provider.ErrNotConfigured
}

// Route implements provider.MapProvider.
func (a *Adapter) Route(_ context.Context, _ provider.RouteRequest) (provider.Route, error) {
	return provider.Route{}, provider.ErrNotConfigured
}

// Autocomplete implements provider.MapProvider.
func (a *Adapter) Autocomplete(_ context.Context, _ provider.AutocompleteRequest) ([]provider.PlaceCandidate, error) {
	return nil, provider.ErrNotConfigured
}

// PlaceDetails implements provider.MapProvider.
func (a *Adapter) PlaceDetails(_ context.Context, _ provider.PlaceDetailsRequest) (provider.PlaceDetails, error) {
	return provider.PlaceDetails{}, provider.ErrNotConfigured
}

// StaticMap implements provider.MapProvider.
func (a *Adapter) StaticMap(_ context.Context, _ provider.StaticMapRequest) (string, error) {
	return "", provider.ErrNotConfigured
}

// HealthCheck implements provider.MapProvider.
func (a *Adapter) HealthCheck(_ context.Context) error { return provider.ErrNotConfigured }

// Close implements provider.MapProvider.
func (a *Adapter) Close() error { return nil }

// With returns a copy of a with the supplied capability list overridden.
// Useful when the stub's declared capabilities must be a strict subset
// of the full 7 (e.g. OSRM is eta + route only).
func With(a *Adapter, caps ...provider.Capability) *Adapter {
	copy := *a
	copy.CapabilitiesValue = caps
	return &copy
}

// New builds a fully-populated Adapter in one call. The parameter
// order matches the field order of the Adapter struct, so callers
// can quickly wire a placeholder without juggling field names.
func New(
	vendorID, displayName string,
	adapterType provider.AdapterType,
	isSelfHost, isStaticOnly bool,
	jurisdictions []string,
	capabilities ...provider.Capability,
) *Adapter {
	return &Adapter{
		VendorIDValue:      vendorID,
		DisplayNameValue:   displayName,
		AdapterTypeValue:   adapterType,
		IsSelfHostValue:    isSelfHost,
		IsStaticOnlyValue:  isStaticOnly,
		JurisdictionsValue: jurisdictions,
		CapabilitiesValue:  capabilities,
	}
}
