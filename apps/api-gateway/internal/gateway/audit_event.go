// Package gateway — audit event builders.
//
// Per docs/services/api-gateway/INTEGRATION.md §3 the gateway
// emits four events on Kafka:
//
//	audit.api.request.v1                  (every authenticated request)
//	gateway.config.reloaded.v1            (on successful hot-reload)
//	gateway.rate_limit.exceeded.v1        (on 429 rejection)
//	gateway.circuit_breaker.opened.v1     (on breaker open transition)
//
// This file implements the canonical envelope and the per-event
// payload builders. Field names follow the platform's
// docs/architecture/EVENT_ARCHITECTURE.md conventions (event_name,
// schema_version, producer, tenant_id, correlation_id, ...).
package gateway

import (
	"strconv"
	"time"

	"github.com/google/uuid"
)

// Event is the platform-wide envelope common to every emitted
// event. The Data field is encoded per-event to keep the schema
// versioning clean; it is the responsibility of the producer to
// populate it with the per-event payload.
type Event struct {
	EventID       string `json:"event_id"`
	EventName     string `json:"event_name"`
	OccurredAt    string `json:"occurred_at"`
	SchemaVersion int    `json:"schema_version"`
	Producer      string `json:"producer"`
	TenantID      string `json:"tenant_id"`
	CorrelationID string `json:"correlation_id"`
	CausationID   string `json:"causation_id,omitempty"`
	AggregateType string `json:"aggregate_type,omitempty"`
	AggregateID   string `json:"aggregate_id,omitempty"`
	Data          any    `json:"data"`
}

// NewEvent creates an envelope with the platform-standard fields
// populated. correlationID is the request id (per ADR-0019). The
// caller fills Data.
func NewEvent(name, producer, tenantID, correlationID, causationID, aggType, aggID string, schemaVersion int, data any) Event {
	if tenantID == "" {
		tenantID = "global"
	}
	if aggType == "" {
		aggType = "ApiRequest"
	}
	if aggID == "" {
		aggID = correlationID
	}
	u, _ := uuid.NewV7()
	return Event{
		EventID:       u.String(),
		EventName:     name,
		OccurredAt:    time.Now().UTC().Format("2006-01-02T15:04:05.000Z"),
		SchemaVersion: schemaVersion,
		Producer:      producer,
		TenantID:      tenantID,
		CorrelationID: correlationID,
		CausationID:   causationID,
		AggregateType: aggType,
		AggregateID:   aggID,
		Data:          data,
	}
}

// setEventID is a tiny helper that overwrites the placeholder ID
// with a real UUIDv7 value.
func (e *Event) setEventID() {
	u, _ := uuid.NewV7()
	e.EventID = u.String()
}

// AuditRequestData is the `data` block of audit.api.request.v1 per
// INTEGRATION.md §3.1.
type AuditRequestData struct {
	UserID         string `json:"user_id"`
	UserType       string `json:"user_type"`
	Method         string `json:"method"`
	Route          string `json:"route"`
	MatchedRouteID string `json:"matched_route_id,omitempty"`
	Upstream       string `json:"upstream,omitempty"`
	UpstreamStatus int    `json:"upstream_status,omitempty"`
	Status         int    `json:"status"`
	LatencyMs      int64  `json:"latency_ms"`
	ClientIP       string `json:"client_ip,omitempty"`
	UserAgentHash  string `json:"user_agent_hash,omitempty"`
	BodySHA256     string `json:"body_sha256,omitempty"`
	RateLimited    bool   `json:"rate_limited"`
	CircuitOpen    bool   `json:"circuit_open"`
}

// BuildAuditRequest builds the canonical audit envelope.
func BuildAuditRequest(correlationID string, data AuditRequestData) Event {
	e := NewEvent("audit.api.request.v1", "api-gateway", "global", correlationID, "", "ApiRequest", correlationID, 1, data)
	e.setEventID()
	return e
}

// RateLimitData is the `data` block of gateway.rate_limit.exceeded.v1
// per INTEGRATION.md §3.3.
type RateLimitData struct {
	Route             string `json:"route"`
	PrincipalType     string `json:"principal_type"` // "token" | "ip"
	PrincipalID       string `json:"principal_id"`
	Limit             int    `json:"limit"`
	WindowSeconds     int    `json:"window_seconds"`
	RetryAfterSeconds int    `json:"retry_after_seconds"`
	ClientIP          string `json:"client_ip,omitempty"`
	UserAgentHash     string `json:"user_agent_hash,omitempty"`
}

// BuildRateLimitExceeded builds the rate-limit event.
func BuildRateLimitExceeded(correlationID string, data RateLimitData) Event {
	e := NewEvent("gateway.rate_limit.exceeded.v1", "api-gateway", "global", correlationID, "", "RateLimit", "route:"+data.Route, 1, data)
	e.setEventID()
	return e
}

// CircuitBreakerData is the `data` block of
// gateway.circuit_breaker.opened.v1 per INTEGRATION.md §3.4.
type CircuitBreakerData struct {
	Upstream          string `json:"upstream"`
	PreviousState     string `json:"previous_state"`
	NewState          string `json:"new_state"`
	FailureCount      int    `json:"failure_count"`
	WindowSeconds     int    `json:"window_seconds"`
	ResetAfterSeconds int    `json:"reset_after_seconds"`
}

// BuildCircuitBreakerOpened builds the breaker event.
func BuildCircuitBreakerOpened(correlationID, upstream, prev, next string, failureCount int) Event {
	e := NewEvent("gateway.circuit_breaker.opened.v1", "api-gateway", "global", correlationID, "", "CircuitBreaker", "upstream:"+upstream, 1, CircuitBreakerData{
		Upstream:          upstream,
		PreviousState:     prev,
		NewState:          next,
		FailureCount:      failureCount,
		WindowSeconds:     10,
		ResetAfterSeconds: 30,
	})
	e.setEventID()
	return e
}

// ConfigReloadedData is the `data` block of
// gateway.config.reloaded.v1 per INTEGRATION.md §3.2.
type ConfigReloadedData struct {
	ConfigKeys    []string `json:"config_keys"`
	ConfigVersion int64    `json:"config_version"`
	Trigger       string   `json:"trigger"` // "event" | "admin"
	Result        string   `json:"result"`  // "ok" | "error"
}

// BuildConfigReloaded builds the config-reloaded event.
func BuildConfigReloaded(correlationID string, data ConfigReloadedData) Event {
	e := NewEvent("gateway.config.reloaded.v1", "api-gateway", "global", correlationID, "", "GatewayConfig", "config:"+strconv.FormatInt(data.ConfigVersion, 10), 1, data)
	e.setEventID()
	return e
}
