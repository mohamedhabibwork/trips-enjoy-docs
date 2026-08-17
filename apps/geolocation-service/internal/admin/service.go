package admin

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/trips-enjoy/platform/geolocation-service/internal/chain"
	"github.com/trips-enjoy/platform/geolocation-service/internal/db"
	"github.com/trips-enjoy/platform/geolocation-service/internal/events"
	"github.com/trips-enjoy/platform/geolocation-service/internal/geocoding"
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider"
)

// PurgeFilter mirrors INTEGRATION.md §1.6 — at least one of city_id /
// bbox / query_fingerprint must be set.
type PurgeFilter struct {
	CityID           string `json:"city_id,omitempty"`
	BBox             *BBox  `json:"bbox,omitempty"`
	QueryFingerprint string `json:"query_fingerprint,omitempty"`
}

// BBox is the min/max bounding-box the admin can pass to scope a purge.
type BBox struct {
	MinLat float64 `json:"min_lat"`
	MinLon float64 `json:"min_lon"`
	MaxLat float64 `json:"max_lat"`
	MaxLon float64 `json:"max_lon"`
}

// PurgeRequest is the body of POST /v1/admin/cache/purge.
type PurgeRequest struct {
	Filter    PurgeFilter `json:"filter"`
	Resources []string    `json:"resources"`
	Reason    string      `json:"reason"`
}

// PurgeResponse is the 202 body of POST /v1/admin/cache/purge.
type PurgeResponse struct {
	PurgeID             string    `json:"purge_id"`
	AffectedGeocodeRows int       `json:"affected_geocode_rows"`
	AffectedEtaRows     int       `json:"affected_eta_rows"`
	AffectedRouteRows   int       `json:"affected_route_rows"`
	OccurredAt          time.Time `json:"occurred_at"`
}

// ProviderSummary is the per-provider row of GET /v1/admin/providers.
type ProviderSummary struct {
	provider.ProviderConfig
	CircuitState        string `json:"circuit_state"`
	ConsecutiveFailures int    `json:"consecutive_failures"`
}

// ProbeRecord is the trimmed view of one provider_health row.
type ProbeRecord struct {
	ProbedAt   time.Time `json:"probed_at"`
	Result     string    `json:"result"`
	LatencyMS  int       `json:"latency_ms,omitempty"`
	Capability string    `json:"capability"`
	Endpoint   string    `json:"endpoint"`
}

// RegionChainEdit is the body of PUT /v1/admin/region-chains/{region}/{capability}.
type RegionChainEdit struct {
	Chain   []string `json:"chain"`
	Enabled bool     `json:"enabled"`
	Notes   string   `json:"notes,omitempty"`
	Reason  string   `json:"reason"`
}

// ProviderPatch is the body of PATCH /v1/admin/providers/{vendor_id}.
type ProviderPatch struct {
	Enabled       *bool          `json:"enabled,omitempty"`
	QPSLimit      *int           `json:"qps_limit,omitempty"`
	BurstLimit    *int           `json:"burst_limit,omitempty"`
	TimeoutMS     *int           `json:"timeout_ms,omitempty"`
	CostPer1kUSD  *float64       `json:"cost_per_1k_usd,omitempty"`
	Jurisdictions []string       `json:"jurisdictions,omitempty"`
	Metadata      map[string]any `json:"metadata,omitempty"`
}

// Service is the admin surface. It depends on the chain resolver +
// provider registry + geocoding cache + event publisher so it can
// implement every admin endpoint documented in INTEGRATION.md §1.6 +
// §5. The dev scaffold returns synthesized zero-counts for purge
// (the production PG DELETE lands in a follow-up PR).
type Service struct {
	resolver  *chain.Resolver
	registry  *provider.Registry
	cache     *geocoding.Cache
	publisher events.Publisher
	logger    *slog.Logger

	mu             sync.Mutex
	adminAudit     []AuditRow
	providerProbes map[string][]ProbeRecord // ring buffer per vendor
	idempotency    map[string]IdempotencyRecord
}

// AuditRow mirrors one row in geolocation.admin_audit (ERD.md §3.6).
type AuditRow struct {
	ID             string    `json:"id"`
	OccurredAt     time.Time `json:"occurred_at"`
	Action         string    `json:"action"`
	ActorSub       string    `json:"actor_sub"`
	ActorRole      string    `json:"actor_role"`
	TenantID       string    `json:"tenant_id,omitempty"`
	RequestBody    string    `json:"request_body"`
	IdempotencyKey string    `json:"idempotency_key,omitempty"`
	Result         string    `json:"result"`
	ErrorCode      string    `json:"error_code,omitempty"`
	CorrelationID  string    `json:"correlation_id"`
}

// IdempotencyRecord is the dev-scaffold in-memory mirror of the
// (admin_id, idempotency_key) → response map per SRS.md §15.
type IdempotencyRecord struct {
	Key         string
	RequestHash string
	Status      int
	Body        []byte
	ExpiresAt   time.Time
}

// NewService wires the dependencies.
func NewService(
	resolver *chain.Resolver,
	registry *provider.Registry,
	cache *geocoding.Cache,
	publisher events.Publisher,
	logger *slog.Logger,
) *Service {
	if logger == nil {
		logger = slog.Default()
	}
	return &Service{
		resolver:       resolver,
		registry:       registry,
		cache:          cache,
		publisher:      publisher,
		logger:         logger,
		adminAudit:     []AuditRow{},
		providerProbes: map[string][]ProbeRecord{},
		idempotency:    map[string]IdempotencyRecord{},
	}
}

// PurgeCache implements POST /v1/admin/cache/purge. The dev scaffold
// returns zero row counts (the SQL DELETE is a follow-up); the real
// integration is documented in INTEGRATION.md §1.6.
func (s *Service) PurgeCache(_ context.Context, req PurgeRequest, actorSub, actorRole, correlationID string) (*PurgeResponse, error) {
	if req.Filter.CityID == "" && req.Filter.BBox == nil && req.Filter.QueryFingerprint == "" {
		return nil, ErrValidation{Field: "filter", Issue: "REQUIRED"}
	}
	if len(req.Resources) == 0 {
		return nil, ErrValidation{Field: "resources", Issue: "REQUIRED"}
	}
	if req.Reason == "" || len(req.Reason) > 256 {
		return nil, ErrValidation{Field: "reason", Issue: "OUT_OF_RANGE"}
	}
	for _, r := range req.Resources {
		if r != "geocode" && r != "eta" && r != "route" {
			return nil, ErrValidation{Field: "resources", Issue: "OUT_OF_RANGE"}
		}
	}
	purgeID := db.NewUUIDv7()
	// Best-effort in-memory eviction (no SQL DELETE yet).
	affectedGeocode := 0
	if req.Filter.QueryFingerprint != "" {
		affectedGeocode = s.cache.PurgeByQueryFingerprint(req.Filter.QueryFingerprint)
	} else {
		affectedGeocode = s.cache.PurgeAll()
	}
	s.recordAudit(AuditRow{
		ID:            db.NewUUIDv7(),
		OccurredAt:    time.Now().UTC(),
		Action:        "cache_purge",
		ActorSub:      actorSub,
		ActorRole:     actorRole,
		RequestBody:   marshalOrEmpty(req),
		Result:        "success",
		CorrelationID: correlationID,
	})
	s.emitEvent(events.EventCacheInvalidatedV1, "GeocodeCachePurge", purgeID, map[string]any{
		"purge_id":       purgeID,
		"trigger":        "admin",
		"filter":         req.Filter,
		"resources":      req.Resources,
		"actor_sub":      actorSub,
		"reason":         req.Reason,
		"correlation_id": correlationID,
	})
	return &PurgeResponse{
		PurgeID:             purgeID,
		AffectedGeocodeRows: affectedGeocode,
		AffectedEtaRows:     0,
		AffectedRouteRows:   0,
		OccurredAt:          time.Now().UTC(),
	}, nil
}

// ListProviders implements GET /v1/admin/providers.
func (s *Service) ListProviders() []ProviderSummary {
	configs := s.registry.ListConfigs()
	states := s.resolverAllStates()
	out := make([]ProviderSummary, 0, len(configs))
	for _, c := range configs {
		out = append(out, ProviderSummary{
			ProviderConfig: c,
			CircuitState:   string(states[c.VendorID]),
		})
	}
	return out
}

func (s *Service) resolverAllStates() map[string]chain.CircuitState {
	// Access via the public breaker state map. The Resolver exposes
	// the breakers indirectly via LookupRoute; we keep a sibling
	// accessor on the admin service via the registry's
	// "circuit_states" snapshot. The simplest path: the registry holds
	// per-vendor circuit state in its own map (mirrored from gobreaker).
	// For now we return a default closed map; the production wiring
	// surfaces the live breaker states from chain.BreakerStates().
	states := chain.BreakerStates()
	if states == nil {
		return map[string]chain.CircuitState{}
	}
	return states
}

// GetProvider implements GET /v1/admin/providers/{vendor_id}.
func (s *Service) GetProvider(vendorID string) (ProviderSummary, []ProbeRecord, bool) {
	cfg, ok := s.registry.Config(vendorID)
	if !ok {
		return ProviderSummary{}, nil, false
	}
	summary := ProviderSummary{ProviderConfig: cfg, CircuitState: string(s.resolverAllStates()[vendorID])}
	s.mu.Lock()
	probes := append([]ProbeRecord(nil), s.providerProbes[vendorID]...)
	s.mu.Unlock()
	return summary, probes, true
}

// TestProvider implements POST /v1/admin/providers/{vendor_id}/test.
// It invokes the named provider directly (bypassing the chain) and
// returns the canonical result.
func (s *Service) TestProvider(ctx context.Context, vendorID string, cap provider.Capability, query map[string]any) (any, string, error) {
	p, ok := s.registry.Get(vendorID)
	if !ok {
		return nil, "", errors.New("vendor not registered")
	}
	if !provider.SupportsCapability(p, cap) {
		return nil, "", fmt.Errorf("vendor does not advertise capability %s", cap)
	}
	// Dispatch by capability — the production wiring shapes the query
	// map per vendor; the dev scaffold maps free-form keys into the
	// canonical request shapes.
	switch cap {
	case provider.CapabilityGeocodeForward:
		req := provider.GeocodeRequest{
			Address: stringOf(query["address"]),
			Locale:  stringOf(query["locale"]),
		}
		addr, err := p.GeocodeForward(ctx, req)
		return addr, "direct", err
	case provider.CapabilityGeocodeReverse:
		// query["lat"] + query["lon"] are read by the caller — the
		// dev scaffold treats query as opaque and returns ErrNotConfigured.
		return nil, "direct", provider.ErrNotConfigured
	default:
		return nil, "direct", provider.ErrNotConfigured
	}
}

// SetRegionChain implements PUT /v1/admin/region-chains/{region}/{capability}.
func (s *Service) SetRegionChain(region chain.Region, cap provider.Capability, edit RegionChainEdit, actorSub, correlationID string) error {
	for _, v := range edit.Chain {
		if _, ok := s.registry.Get(v); !ok {
			return fmt.Errorf("vendor %q not registered", v)
		}
	}
	if err := s.resolver.SetRoute(chain.RegionRoute{
		Region:     region,
		Capability: cap,
		Chain:      edit.Chain,
		Enabled:    edit.Enabled,
	}); err != nil {
		return err
	}
	s.recordAudit(AuditRow{
		ID:            db.NewUUIDv7(),
		OccurredAt:    time.Now().UTC(),
		Action:        "region_chain_updated",
		ActorSub:      actorSub,
		ActorRole:     "platform_engineer",
		RequestBody:   marshalOrEmpty(edit),
		Result:        "success",
		CorrelationID: correlationID,
	})
	s.emitEvent(events.EventProviderChainV1, "RegionChainEdit", string(region), map[string]any{
		"change_kind":    "region_chain_updated",
		"region":         string(region),
		"capability":     string(cap),
		"new_chain":      edit.Chain,
		"actor_sub":      actorSub,
		"reason":         edit.Reason,
		"correlation_id": correlationID,
	})
	return nil
}

// PatchProvider implements PATCH /v1/admin/providers/{vendor_id}.
func (s *Service) PatchProvider(vendorID string, patch ProviderPatch, actorSub, correlationID string) (provider.ProviderConfig, error) {
	ok := s.registry.PatchConfig(vendorID, func(c *provider.ProviderConfig) {
		if patch.Enabled != nil {
			c.Enabled = *patch.Enabled
		}
		if patch.QPSLimit != nil {
			c.QPSLimit = *patch.QPSLimit
		}
		if patch.BurstLimit != nil {
			c.BurstLimit = *patch.BurstLimit
		}
		if patch.TimeoutMS != nil {
			c.TimeoutMS = *patch.TimeoutMS
		}
		if patch.CostPer1kUSD != nil {
			c.CostPer1kUSD = *patch.CostPer1kUSD
		}
		if len(patch.Jurisdictions) > 0 {
			c.Jurisdictions = patch.Jurisdictions
		}
		if len(patch.Metadata) > 0 {
			if c.Metadata == nil {
				c.Metadata = map[string]any{}
			}
			for k, v := range patch.Metadata {
				c.Metadata[k] = v
			}
		}
	})
	if !ok {
		return provider.ProviderConfig{}, errors.New("vendor not registered")
	}
	cfg, _ := s.registry.Config(vendorID)
	s.recordAudit(AuditRow{
		ID:            db.NewUUIDv7(),
		OccurredAt:    time.Now().UTC(),
		Action:        "provider_config_updated",
		ActorSub:      actorSub,
		ActorRole:     "platform_engineer",
		RequestBody:   marshalOrEmpty(patch),
		Result:        "success",
		CorrelationID: correlationID,
	})
	s.emitEvent(events.EventProviderChainV1, "ProviderConfigPatch", vendorID, map[string]any{
		"change_kind":    "provider_config_updated",
		"vendor_id":      vendorID,
		"actor_sub":      actorSub,
		"correlation_id": correlationID,
	})
	return cfg, nil
}

// RotateProvider implements POST /v1/admin/providers/rotate. The dev
// scaffold returns ErrEndpointRetired; the production wiring performs
// the Vault read + key swap.
func (s *Service) RotateProvider(_ context.Context, actorSub, correlationID string) error {
	s.recordAudit(AuditRow{
		ID:            db.NewUUIDv7(),
		OccurredAt:    time.Now().UTC(),
		Action:        "provider_rotate",
		ActorSub:      actorSub,
		ActorRole:     "platform_engineer",
		RequestBody:   "{}",
		Result:        "failure",
		ErrorCode:     "ENDPOINT_RETIRED",
		CorrelationID: correlationID,
	})
	return ErrEndpointRetired
}

// ForceProbe implements POST /admin/v1/providers/{vendor_id}/probe.
// It calls HealthCheck on the adapter and appends a probe record to
// the in-memory ring buffer (last 50 per vendor).
func (s *Service) ForceProbe(ctx context.Context, vendorID string) (ProbeRecord, error) {
	p, ok := s.registry.Get(vendorID)
	if !ok {
		return ProbeRecord{}, errors.New("vendor not registered")
	}
	startedAt := time.Now()
	err := p.HealthCheck(ctx)
	latency := int(time.Since(startedAt) / time.Millisecond)
	result := "ok"
	if err != nil {
		result = "failure"
	}
	probe := ProbeRecord{
		ProbedAt:   time.Now().UTC(),
		Result:     result,
		LatencyMS:  latency,
		Capability: "geocode_forward",
		Endpoint:   fmt.Sprintf("provider:%s/health", vendorID),
	}
	s.mu.Lock()
	s.providerProbes[vendorID] = append(s.providerProbes[vendorID], probe)
	if len(s.providerProbes[vendorID]) > 50 {
		s.providerProbes[vendorID] = s.providerProbes[vendorID][len(s.providerProbes[vendorID])-50:]
	}
	s.mu.Unlock()
	s.emitEvent(events.EventProviderHealthV1, "ProviderProbe", vendorID, map[string]any{
		"vendor_id":           vendorID,
		"probed_at":           probe.ProbedAt,
		"result":              probe.Result,
		"latency_ms":          probe.LatencyMS,
		"capability":          probe.Capability,
		"circuit_state_after": "closed",
	})
	return probe, err
}

// AdminAudit returns the in-memory audit log (last N). Used by the
// admin GET /v1/admin/audit surface.
func (s *Service) AdminAudit() []AuditRow {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]AuditRow, len(s.adminAudit))
	copy(out, s.adminAudit)
	return out
}

// recordAudit appends an audit row. The production implementation
// writes the row to PostgreSQL (geolocation.admin_audit, monthly RANGE
// partitioned per ERD.md §3.6).
func (s *Service) recordAudit(row AuditRow) {
	s.mu.Lock()
	s.adminAudit = append(s.adminAudit, row)
	s.mu.Unlock()
}

// emitEvent writes an envelope through the publisher (best-effort).
func (s *Service) emitEvent(name, aggType, aggID string, data any) {
	if s.publisher == nil {
		return
	}
	env := events.Envelope{
		EventID:       db.NewUUIDv7(),
		EventName:     name,
		SchemaVersion: 1,
		OccurredAt:    time.Now().UTC(),
		Producer:      "geolocation-service",
		TenantID:      "global",
		AggregateType: aggType,
		AggregateID:   aggID,
		Data:          data,
	}
	_ = s.publisher.Publish(env)
}

// ErrValidation is the canonical 400 VALIDATION_FAILED error.
type ErrValidation struct {
	Field string
	Issue string
}

func (e ErrValidation) Error() string {
	return fmt.Sprintf("validation failed: %s=%s", e.Field, e.Issue)
}

// ErrEndpointRetired is the 501 ENDPOINT_RETIRED error returned by
// follow-up stubs (provider rotation, etc.).
var ErrEndpointRetired = errors.New("endpoint retired in this scaffold; production wiring is a follow-up")

// IsValidation reports whether err is a validation error.
func IsValidation(err error) bool {
	var v ErrValidation
	return errors.As(err, &v)
}

// IsEndpointRetired reports whether err is an endpoint-retired stub.
func IsEndpointRetired(err error) bool {
	return errors.Is(err, ErrEndpointRetired)
}

// marshalOrEmpty JSON-encodes the value or returns "{}" on failure.
func marshalOrEmpty(v any) string {
	b, err := jsonMarshal(v)
	if err != nil {
		return "{}"
	}
	return string(b)
}

// stringOf returns the string value of v if it is a string.
func stringOf(v any) string {
	if s, ok := v.(string); ok {
		return s
	}
	return ""
}
