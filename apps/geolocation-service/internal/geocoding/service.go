package geocoding

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"github.com/trips-enjoy/platform/geolocation-service/internal/chain"
	"github.com/trips-enjoy/platform/geolocation-service/internal/db"
	"github.com/trips-enjoy/platform/geolocation-service/internal/events"
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider"
)

// Service is the geocoding aggregate. It validates the input, computes
// the cache key per BR--020..BR--023, looks up the cache, and on miss
// delegates to the chain resolver. It writes the canonical response to
// the cache + emits the analytics event + returns the public response.
type Service struct {
	resolver   *chain.Resolver
	cache      *Cache
	publisher  events.Publisher
	logger     *slog.Logger
	geocodeTTL time.Duration
	etaTTL     time.Duration
	routeTTL   time.Duration
	now        func() time.Time
}

// NewService wires the dependencies. TTLs come from the configuration
// service (cfg.GeocodeTTL / EtaTTL / RouteTTL); main passes them in.
func NewService(
	resolver *chain.Resolver,
	cache *Cache,
	publisher events.Publisher,
	logger *slog.Logger,
	geocodeTTL, etaTTL, routeTTL time.Duration,
) *Service {
	if logger == nil {
		logger = slog.Default()
	}
	return &Service{
		resolver:   resolver,
		cache:      cache,
		publisher:  publisher,
		logger:     logger,
		geocodeTTL: geocodeTTL,
		etaTTL:     etaTTL,
		routeTTL:   routeTTL,
		now:        time.Now,
	}
}

// GeocodeForward implements POST /v1/geocodes per INTEGRATION.md §1.1.
func (s *Service) GeocodeForward(ctx context.Context, region chain.Region, req provider.GeocodeRequest) (*GeocodeResponse, error) {
	if details := ValidateGeocodeForward(req); len(details) > 0 {
		return nil, ErrValidation{Details: details}
	}
	key := GeocodeCacheKey(req.Address, req.Locale, req.RegionCityID)
	if entry, ok := s.cache.LookupGeocode(key); ok {
		s.emitGeocoded(ctx, region, provider.CapabilityGeocodeForward, true, entry.VendorID, 0, entry.Address.Coordinate, "")
		return &GeocodeResponse{
			ID:               db.NewUUIDv7(),
			Kind:             "forward",
			Locale:           req.Locale,
			Coordinate:       entry.Address.Coordinate,
			FormattedAddress: entry.Address.FormattedAddress,
			Components:       componentsToMap(entry.Address.Components),
			Confidence:       entry.Address.Confidence,
			BBox:             entry.Address.BBox,
			CacheHit:         true,
			Provider: ProviderInfo{
				VendorID: entry.VendorID, Role: provider.RolePrimary,
				Capability: provider.CapabilityGeocodeForward, Region: string(region),
			},
			OccurredAt: s.now().UTC(),
		}, nil
	}

	addr, meta, err := s.resolver.GeocodeForward(ctx, region, req)
	if err != nil {
		return nil, err
	}
	s.cache.PutGeocode(&CacheEntry{
		CacheKey:   key,
		Kind:       CacheGeocode,
		VendorID:   meta.VendorID,
		Coordinate: addr.Coordinate,
		Address:    addr,
	}, s.geocodeTTL)
	s.emitGeocoded(ctx, region, provider.CapabilityGeocodeForward, false, meta.VendorID, meta.ChainPosition, addr.Coordinate, "")
	return &GeocodeResponse{
		ID:               db.NewUUIDv7(),
		Kind:             "forward",
		Locale:           req.Locale,
		Coordinate:       addr.Coordinate,
		FormattedAddress: addr.FormattedAddress,
		Components:       componentsToMap(addr.Components),
		Confidence:       addr.Confidence,
		BBox:             addr.BBox,
		CacheHit:         false,
		Provider: ProviderInfo{
			VendorID: meta.VendorID, ChainPosition: meta.ChainPosition, Role: meta.Role,
			Capability: meta.Capability, Region: string(meta.Region), IsSelfHost: meta.IsSelfHost,
		},
		OccurredAt: s.now().UTC(),
	}, nil
}

// GeocodeReverse implements GET /v1/geocodes/reverse per INTEGRATION.md §1.2.
func (s *Service) GeocodeReverse(ctx context.Context, region chain.Region, req provider.ReverseRequest) (*GeocodeResponse, error) {
	if details := ValidateGeocodeReverse(req); len(details) > 0 {
		return nil, ErrValidation{Details: details}
	}
	key := ReverseCacheKey(req.Coordinate, req.Locale)
	if entry, ok := s.cache.LookupGeocode(key); ok {
		s.emitGeocoded(ctx, region, provider.CapabilityGeocodeReverse, true, entry.VendorID, 0, entry.Address.Coordinate, "")
		return &GeocodeResponse{
			ID:               db.NewUUIDv7(),
			Kind:             "reverse",
			Locale:           req.Locale,
			Coordinate:       entry.Address.Coordinate,
			FormattedAddress: entry.Address.FormattedAddress,
			Components:       componentsToMap(entry.Address.Components),
			Confidence:       entry.Address.Confidence,
			BBox:             entry.Address.BBox,
			CacheHit:         true,
			Approximate:      entry.Address.Approximate || req.Approximate,
			Provider: ProviderInfo{
				VendorID: entry.VendorID, Role: provider.RolePrimary,
				Capability: provider.CapabilityGeocodeReverse, Region: string(region),
			},
			OccurredAt: s.now().UTC(),
		}, nil
	}
	addr, meta, err := s.resolver.GeocodeReverse(ctx, region, req)
	if err != nil {
		return nil, err
	}
	if req.Approximate {
		addr.Approximate = true
	}
	s.cache.PutGeocode(&CacheEntry{
		CacheKey:   key,
		Kind:       CacheGeocode,
		VendorID:   meta.VendorID,
		Coordinate: addr.Coordinate,
		Address:    addr,
	}, s.geocodeTTL)
	s.emitGeocoded(ctx, region, provider.CapabilityGeocodeReverse, false, meta.VendorID, meta.ChainPosition, addr.Coordinate, "")
	return &GeocodeResponse{
		ID:               db.NewUUIDv7(),
		Kind:             "reverse",
		Locale:           req.Locale,
		Coordinate:       addr.Coordinate,
		FormattedAddress: addr.FormattedAddress,
		Components:       componentsToMap(addr.Components),
		Confidence:       addr.Confidence,
		BBox:             addr.BBox,
		CacheHit:         false,
		Approximate:      addr.Approximate,
		Provider: ProviderInfo{
			VendorID: meta.VendorID, ChainPosition: meta.ChainPosition, Role: meta.Role,
			Capability: meta.Capability, Region: string(meta.Region), IsSelfHost: meta.IsSelfHost,
		},
		OccurredAt: s.now().UTC(),
	}, nil
}

// Eta implements POST /v1/etas per INTEGRATION.md §1.3.
func (s *Service) Eta(ctx context.Context, region chain.Region, req provider.EtaRequest) (*EtaResponse, error) {
	if details := ValidateEta(req); len(details) > 0 {
		return nil, ErrValidation{Details: details}
	}
	key := EtaCacheKey(req)
	if entry, ok := s.cache.LookupEta(key); ok {
		s.emitEta(ctx, region, true, entry.VendorID, 0, entry.Eta, req)
		return &EtaResponse{
			ID:             db.NewUUIDv7(),
			ETASeconds:     entry.Eta.ETASeconds,
			DistanceMeters: entry.Eta.DistanceMeters,
			TrafficBucket:  entry.Eta.TrafficBucket,
			CacheHit:       true,
			Provider: ProviderInfo{
				VendorID: entry.VendorID, Role: provider.RolePrimary,
				Capability: provider.CapabilityEta, Region: string(region),
			},
			OccurredAt: s.now().UTC(),
		}, nil
	}
	eta, meta, err := s.resolver.Eta(ctx, region, req)
	if err != nil {
		return nil, err
	}
	s.cache.PutEta(&CacheEntry{
		CacheKey:   key,
		Kind:       CacheEta,
		VendorID:   meta.VendorID,
		Coordinate: req.Origin,
		Eta:        eta,
	}, s.etaTTL)
	s.emitEta(ctx, region, false, meta.VendorID, meta.ChainPosition, eta, req)
	return &EtaResponse{
		ID:             db.NewUUIDv7(),
		ETASeconds:     eta.ETASeconds,
		DistanceMeters: eta.DistanceMeters,
		TrafficBucket:  eta.TrafficBucket,
		CacheHit:       false,
		Provider: ProviderInfo{
			VendorID: meta.VendorID, ChainPosition: meta.ChainPosition, Role: meta.Role,
			Capability: meta.Capability, Region: string(meta.Region), IsSelfHost: meta.IsSelfHost,
		},
		OccurredAt: s.now().UTC(),
	}, nil
}

// Route implements POST /v1/routes per INTEGRATION.md §1.4.
func (s *Service) Route(ctx context.Context, region chain.Region, req provider.RouteRequest) (*RouteResponse, error) {
	if details := ValidateRoute(req); len(details) > 0 {
		return nil, ErrValidation{Details: details}
	}
	key := RouteCacheKey(req)
	if entry, ok := s.cache.LookupRoute(key); ok {
		s.emitRoute(ctx, region, true, entry.VendorID, 0, entry.Route)
		return &RouteResponse{
			ID:             db.NewUUIDv7(),
			Polyline:       entry.Route.Polyline,
			DistanceMeters: entry.Route.DistanceMeters,
			ETASeconds:     entry.Route.ETASeconds,
			Steps:          entry.Route.Steps,
			Alternatives:   entry.Route.Alternatives,
			CacheHit:       true,
			Provider: ProviderInfo{
				VendorID: entry.VendorID, Role: provider.RolePrimary,
				Capability: provider.CapabilityRoute, Region: string(region),
			},
			OccurredAt: s.now().UTC(),
		}, nil
	}
	route, meta, err := s.resolver.Route(ctx, region, req)
	if err != nil {
		return nil, err
	}
	s.cache.PutRoute(&CacheEntry{
		CacheKey:   key,
		Kind:       CacheRoute,
		VendorID:   meta.VendorID,
		Coordinate: req.Origin,
		Route:      route,
	}, s.routeTTL)
	s.emitRoute(ctx, region, false, meta.VendorID, meta.ChainPosition, route)
	return &RouteResponse{
		ID:             db.NewUUIDv7(),
		Polyline:       route.Polyline,
		DistanceMeters: route.DistanceMeters,
		ETASeconds:     route.ETASeconds,
		Steps:          route.Steps,
		Alternatives:   route.Alternatives,
		CacheHit:       false,
		Provider: ProviderInfo{
			VendorID: meta.VendorID, ChainPosition: meta.ChainPosition, Role: meta.Role,
			Capability: meta.Capability, Region: string(meta.Region), IsSelfHost: meta.IsSelfHost,
		},
		OccurredAt: s.now().UTC(),
	}, nil
}

// emitGeocoded writes a geolocation.geocoded.v1 envelope to the
// publisher. The data block follows INTEGRATION.md §3.1.
func (s *Service) emitGeocoded(ctx context.Context, region chain.Region, cap provider.Capability, hit bool, vendorID string, position int, coord provider.Coordinate, cityID string) {
	if s.publisher == nil {
		return
	}
	env := events.Envelope{
		EventID:       db.NewUUIDv7(),
		EventName:     events.EventGeocodedV1,
		SchemaVersion: 1,
		OccurredAt:    s.now().UTC(),
		Producer:      "geolocation-service",
		TenantID:      "global",
		AggregateType: "Geocode",
		AggregateID:   db.NewUUIDv7(),
		Data: AnalyticsEvent{
			Kind:          "forward",
			Locale:        "",
			CacheHit:      hit,
			VendorID:      vendorID,
			ChainPosition: position,
			Role:          provider.RolePrimary,
			Region:        string(region),
			Capability:    cap,
			IsSelfHost:    false,
			Coordinate:    &coord,
			CityID:        cityID,
		},
	}
	_ = s.publisher.Publish(env)
}

// emitEta writes a geolocation.eta.computed.v1 envelope.
func (s *Service) emitEta(ctx context.Context, region chain.Region, hit bool, vendorID string, position int, eta provider.EtaEstimate, req provider.EtaRequest) {
	if s.publisher == nil {
		return
	}
	env := events.Envelope{
		EventID:       db.NewUUIDv7(),
		EventName:     events.EventEtaComputedV1,
		SchemaVersion: 1,
		OccurredAt:    s.now().UTC(),
		Producer:      "geolocation-service",
		TenantID:      "global",
		AggregateType: "Eta",
		AggregateID:   db.NewUUIDv7(),
		Data: EtaAnalyticsEvent{
			ETASeconds:     eta.ETASeconds,
			DistanceMeters: eta.DistanceMeters,
			TrafficBucket:  eta.TrafficBucket,
			CacheHit:       hit,
			VendorID:       vendorID,
			ChainPosition:  position,
			Role:           provider.RolePrimary,
			Region:         string(region),
			Capability:     provider.CapabilityEta,
			IsSelfHost:     false,
		},
	}
	_ = s.publisher.Publish(env)
}

// emitRoute is a placeholder for route analytics; the dev scaffold
// emits the event with a minimal payload (full shape lands with the
// production Kafka publisher follow-up).
func (s *Service) emitRoute(ctx context.Context, region chain.Region, hit bool, vendorID string, position int, route provider.Route) {
	if s.publisher == nil {
		return
	}
	env := events.Envelope{
		EventID:       db.NewUUIDv7(),
		EventName:     "geolocation.route.computed.v1",
		SchemaVersion: 1,
		OccurredAt:    s.now().UTC(),
		Producer:      "geolocation-service",
		TenantID:      "global",
		AggregateType: "Route",
		AggregateID:   db.NewUUIDv7(),
		Data: map[string]any{
			"cache_hit":       hit,
			"vendor_id":       vendorID,
			"chain_position":  position,
			"region":          string(region),
			"capability":      string(provider.CapabilityRoute),
			"distance_meters": route.DistanceMeters,
			"eta_seconds":     route.ETASeconds,
		},
	}
	_ = s.publisher.Publish(env)
}

// ErrValidation is the canonical 400 VALIDATION_FAILED error.
type ErrValidation struct {
	Details []ValidationDetail
}

func (e ErrValidation) Error() string { return "validation failed: " + JoinDetails(e.Details) }

// IsValidation reports whether err is a validation error.
func IsValidation(err error) bool {
	var v ErrValidation
	return errors.As(err, &v)
}

// componentsToMap flattens the structured address components into the
// map shape used by the public response (per INTEGRATION.md §1.1).
func componentsToMap(components []provider.AddressComponent) map[string]string {
	if len(components) == 0 {
		return nil
	}
	out := make(map[string]string, len(components))
	for _, c := range components {
		out[c.Key] = c.Value
	}
	return out
}
