// Package gateway — canonical error envelope.
//
// All gateway-owned error responses use the platform error envelope
// defined in docs/shared/CONVENTIONS.md §1 and
// docs/architecture/DOWNSTREAM_ERROR_CATALOG.md §1.1. The body
// shape (RFC 7807 + downstream block + correlation ID):
//
//	{
//	 "code":            "DEPENDENCY_UPSTREAM_FAILURE",
//	 "message":         "...",
//	 "correlationId":   "<request id>",
//	 "traceId":         "<otel trace_id>",
//	 "spanId":          "<otel span_id>",
//	 "timestamp":       "RFC3339",
//	 "type":            "https://platform.../errors/<code-kebab-case>",
//	 "title":           "<title>",
//	 "status":          502,
//	 "instance":        "<request path>",
//	 "downstream":      { "service": "...", "code": "...", "status": ..., "traceId": "...", "latency_ms": ..., "attempt": ... },
//	 "errors":          [{ "field": "...", "message": "...", "code": "MIN_VALUE" }]
//	}
//
// Per SRS §19 (security), the gateway never includes JWTs, PANs,
// OTPs, or full request bodies in the envelope.
package gateway

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"go.opentelemetry.io/otel/trace"
)

// now is overridden in tests.
var now = time.Now

// ErrorCode is a SCREAMING_SNAKE_CASE machine code per the platform
// catalog. Per-service refinement lives in
// docs/services/api-gateway/SRS.md §13 and
// docs/architecture/DOWNSTREAM_ERROR_CATALOG.md.
type ErrorCode string

const (
	CodeValidationFailed      ErrorCode = "VALIDATION_FAILED"
	CodeUnauthenticated       ErrorCode = "UNAUTHENTICATED"
	CodeMissingBearer         ErrorCode = "MISSING_BEARER"
	CodeForbidden             ErrorCode = "FORBIDDEN"
	CodeUserSuspended         ErrorCode = "USER_SUSPENDED"
	CodeUserDisabled          ErrorCode = "USER_DISABLED"
	CodeTokenExpired          ErrorCode = "TOKEN_EXPIRED"
	CodeTokenInvalid          ErrorCode = "TOKEN_INVALID"
	CodeTokenRevoked          ErrorCode = "TOKEN_REVOKED"
	CodeNotFound              ErrorCode = "NOT_FOUND"
	CodeConflict              ErrorCode = "CONFLICT"
	CodePayloadTooLarge       ErrorCode = "PAYLOAD_TOO_LARGE"
	CodeRateLimited           ErrorCode = "RATE_LIMITED"
	CodeBusinessRuleViolation ErrorCode = "BUSINESS_RULE_VIOLATION"
	CodeStateInvalid          ErrorCode = "STATE_INVALID"
	CodeWAFBlocked            ErrorCode = "WAF_BLOCKED"
	CodeRevocationUnavailable ErrorCode = "REVOCATION_UNAVAILABLE"
	CodeInternalError         ErrorCode = "INTERNAL_ERROR"
	CodeDependencyUpstream    ErrorCode = "DEPENDENCY_UPSTREAM_FAILURE"
	CodeDependencyTimeout     ErrorCode = "DEPENDENCY_TIMEOUT"
	CodeDependencyUnavailable ErrorCode = "DEPENDENCY_UNAVAILABLE"
	CodeBadGateway            ErrorCode = "BAD_GATEWAY"
	CodeCircuitOpen           ErrorCode = "CIRCUIT_OPEN"
	CodeBulkheadFull          ErrorCode = "BULKHEAD_FULL"
	CodeServiceUnavailable    ErrorCode = "SERVICE_UNAVAILABLE"
	CodeAuthNotConfigured     ErrorCode = "GATEWAY_AUTH_NOT_CONFIGURED"
)

// Downstream describes an originating service for an error that
// comes from another service.
type Downstream struct {
	Service   string `json:"service"`
	Code      string `json:"code,omitempty"`
	Status    int    `json:"status"`
	TraceID   string `json:"traceId,omitempty"`
	SpanID    string `json:"spanId,omitempty"`
	LatencyMs int64  `json:"latency_ms,omitempty"`
	Attempt   int    `json:"attempt,omitempty"`
	Message   string `json:"message,omitempty"`
}

// FieldError is one element of the `errors` array used by
// VALIDATION_FAILED (RFC 7807 + Spring validation pattern).
type FieldError struct {
	Field   string `json:"field"`
	Message string `json:"message"`
	Code    string `json:"code,omitempty"`
}

// Envelope is the canonical error response body. See package docs.
type Envelope struct {
	Type          string       `json:"type"`
	Title         string       `json:"title"`
	Status        int          `json:"status"`
	Detail        string       `json:"detail"`
	Instance      string       `json:"instance"`
	Code          ErrorCode    `json:"code"`
	Message       string       `json:"message"`
	CorrelationID string       `json:"correlationId"`
	TraceID       string       `json:"traceId,omitempty"`
	SpanID        string       `json:"spanId,omitempty"`
	Timestamp     string       `json:"timestamp"`
	Downstream    *Downstream  `json:"downstream,omitempty"`
	Errors        []FieldError `json:"errors,omitempty"`
}

// TitleFor returns the RFC 7807 title for the given code. Kept
// inline with the platform i18n catalog (shared/CONVENTIONS.md §1).
func TitleFor(code ErrorCode) string {
	switch code {
	case CodeValidationFailed:
		return "Validation failed"
	case CodeUnauthenticated:
		return "Unauthenticated"
	case CodeForbidden:
		return "Forbidden"
	case CodeUserSuspended:
		return "User suspended"
	case CodeUserDisabled:
		return "User disabled"
	case CodeTokenExpired:
		return "Token expired"
	case CodeTokenInvalid:
		return "Token invalid"
	case CodeTokenRevoked:
		return "Token revoked"
	case CodeNotFound:
		return "Not found"
	case CodeConflict:
		return "Conflict"
	case CodePayloadTooLarge:
		return "Payload too large"
	case CodeRateLimited:
		return "Rate limit exceeded"
	case CodeBusinessRuleViolation:
		return "Business rule violation"
	case CodeStateInvalid:
		return "State invalid"
	case CodeWAFBlocked:
		return "WAF block"
	case CodeRevocationUnavailable:
		return "Revocation store unavailable"
	case CodeDependencyUpstream:
		return "Upstream dependency failure"
	case CodeDependencyTimeout:
		return "Upstream dependency timeout"
	case CodeDependencyUnavailable:
		return "Dependency unavailable"
	case CodeBadGateway:
		return "Bad gateway"
	case CodeCircuitOpen:
		return "Upstream circuit open"
	case CodeBulkheadFull:
		return "Upstream bulkhead full"
	case CodeServiceUnavailable:
		return "Service unavailable"
	case CodeAuthNotConfigured:
		return "Gateway authentication not configured"
	default:
		return "Internal error"
	}
}

// TypeURLFor returns the platform documentation URL for the given
// error code. Per CONVENTIONS.md §1 the URL is stable per code.
func TypeURLFor(code ErrorCode) string {
	if code == "" {
		code = CodeInternalError
	}
	return "https://platform.trips-enjoy.com/errors/" + strings.ReplaceAll(strings.ToLower(string(code)), "_", "-")
}

// WriteError writes the canonical error envelope to w. r is used
// only for the request id (X-Request-Id), the instance (URL path),
// and the trace id. detail is the user-facing safe message; the
// `code` is the machine-readable identifier. downstream is optional
// (omitted for gateway-owned failures).
func WriteError(ctx context.Context, w http.ResponseWriter, r *http.Request, status int, code ErrorCode, detail string, downstream *Downstream) {
	w.Header().Set("Content-Type", "application/problem+json; charset=utf-8")
	w.WriteHeader(status)

	reqID := RequestIDFromContext(r.Context())
	span := trace.SpanFromContext(r.Context())
	traceID := span.SpanContext().TraceID().String()
	spanID := span.SpanContext().SpanID().String()

	env := Envelope{
		Type:          TypeURLFor(code),
		Title:         TitleFor(code),
		Status:        status,
		Detail:        detail,
		Instance:      r.URL.Path,
		Code:          code,
		Message:       detail,
		CorrelationID: reqID,
		TraceID:       traceID,
		SpanID:        spanID,
		Timestamp:     now().UTC().Format("2006-01-02T15:04:05.000Z"),
		Downstream:    downstream,
	}
	if env.TraceID == "00000000000000000000000000000000" {
		env.TraceID = ""
		env.SpanID = ""
	}
	// body writes are not time-stamped at runtime; JSON-encode here.
	_ = json.NewEncoder(w).Encode(env)

	_ = ctx
}
