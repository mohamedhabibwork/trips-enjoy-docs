// Package errormodel defines the platform-wide RFC 7807 error envelope
// shared by every Go service (api-gateway, chat-service, file-service,
// geolocation-service). The JSON shapes are normative — matching the
// Spring Boot `platform-spring-boot-error` module and the FastAPI
// `platform-python` package one-for-one.
//
// Source of truth: docs/shared/CONVENTIONS.md §1.
//
// The envelope is:
//
//	{
//	  "type":       "https://platform.trips-enjoy.com/errors/<code-kebab>",
//	  "title":      "<Human title>",
//	  "status":     503,
//	  "detail":     "<service-specific detail>",
//	  "instance":   "<request path>",
//	  "code":       "DEPENDENCY_UNAVAILABLE",
//	  "traceId":    "<otel trace_id>",
//	  "spanId":     "<otel span_id>",
//	  "timestamp":  "RFC3339",
//	  "errors":     [{"field": "...", "message": "...", "code": "MIN_VALUE"}],
//	  "downstream": {"service": "...", "code": "...", "status": 503,
//	                "traceId": "...", "latency_ms": 17, "attempt": 1}
//	}
package errormodel

import (
	"strconv"
	"strings"
	"time"
)

// Code is the SCREAMING_SNAKE_CASE machine identifier per the platform
// catalog. The list mirrors `ErrorCode` in
// `platform-spring-boot-error/src/main/kotlin/.../ErrorCode.kt`.
type Code string

const (
	CodeValidationFailed      Code = "VALIDATION_FAILED"
	CodeUnauthenticated       Code = "UNAUTHENTICATED"
	CodeForbidden             Code = "FORBIDDEN"
	CodeNotFound              Code = "NOT_FOUND"
	CodeConflict              Code = "CONFLICT"
	CodeIdempotencyKeyReused  Code = "IDEMPOTENCY_KEY_REUSED"
	CodeRateLimited           Code = "RATE_LIMITED"
	CodeBusinessRuleViolation Code = "BUSINESS_RULE_VIOLATION"
	CodeStateInvalid          Code = "STATE_INVALID"
	CodeInternalError         Code = "INTERNAL_ERROR"
	CodeDependencyUnavailable Code = "DEPENDENCY_UNAVAILABLE"
	CodeDependencyTimeout     Code = "DEPENDENCY_TIMEOUT"
	CodeBadGateway            Code = "BAD_GATEWAY"
	CodeCircuitOpen           Code = "CIRCUIT_OPEN"
	CodeBulkheadFull          Code = "BULKHEAD_FULL"
)

// HTTPStatus is the canonical HTTP status for each platform code.
func (c Code) HTTPStatus() int {
	switch c {
	case CodeValidationFailed:
		return 400
	case CodeUnauthenticated:
		return 401
	case CodeForbidden:
		return 403
	case CodeNotFound:
		return 404
	case CodeConflict:
		return 409
	case CodeIdempotencyKeyReused, CodeBusinessRuleViolation:
		return 422
	case CodeRateLimited:
		return 429
	case CodeBadGateway:
		return 502
	case CodeCircuitOpen, CodeDependencyUnavailable, CodeBulkheadFull:
		return 503
	case CodeDependencyTimeout:
		return 504
	case CodeStateInvalid:
		return 409
	default:
		return 500
	}
}

// FieldError is one row in the `errors[]` array (validation failures).
type FieldError struct {
	Field   string `json:"field"`
	Message string `json:"message"`
	Code    string `json:"code,omitempty"`
}

// Downstream identifies the originating service for an error that
// crossed a service boundary.
type Downstream struct {
	Service   string `json:"service"`
	Code      string `json:"code,omitempty"`
	Status    int    `json:"status"`
	TraceID   string `json:"traceId,omitempty"`
	LatencyMs int64  `json:"latency_ms,omitempty"`
	Attempt   int    `json:"attempt,omitempty"`
}

// Envelope is the RFC 7807 + platform-extension response body.
type Envelope struct {
	Type      string       `json:"type"`
	Title     string       `json:"title"`
	Status    int          `json:"status"`
	Detail    string       `json:"detail"`
	Instance  string       `json:"instance"`
	Code      Code         `json:"code"`
	TraceID   string       `json:"traceId,omitempty"`
	SpanID    string       `json:"spanId,omitempty"`
	Timestamp string       `json:"timestamp"`
	Errors    []FieldError `json:"errors,omitempty"`
	Downstream *Downstream `json:"downstream,omitempty"`
}

// now is override-able in tests.
var now = func() time.Time { return time.Now().UTC() }

// New constructs an Envelope for a business error.
//
//   - code:        the platform ErrorCode
//   - detail:      service-specific human message
//   - instance:    the request path (e.g. r.URL.Path)
//   - traceID:     OTel trace_id (may be empty)
//   - spanID:      OTel span_id (may be empty)
func New(code Code, detail, instance, traceID, spanID string) Envelope {
	return Envelope{
		Type:      "https://platform.trips-enjoy.com/errors/" + kebab(code),
		Title:     titleize(code),
		Status:    code.HTTPStatus(),
		Detail:    detail,
		Instance:  instance,
		Code:      code,
		TraceID:   traceID,
		SpanID:    spanID,
		Timestamp: now().Format(time.RFC3339Nano),
	}
}

// NewWithDownstream constructs an Envelope attached to a downstream
// originator block.
func NewWithDownstream(code Code, detail, instance, traceID, spanID string, ds *Downstream) Envelope {
	e := New(code, detail, instance, traceID, spanID)
	e.Downstream = ds
	return e
}

// NewValidation constructs an Envelope with one or more field errors
// (status 400).
func NewValidation(detail, instance, traceID, spanID string, fieldErrors []FieldError) Envelope {
	e := New(CodeValidationFailed, detail, instance, traceID, spanID)
	e.Errors = fieldErrors
	return e
}

func kebab(c Code) string {
	return strings.ReplaceAll(strings.ToLower(string(c)), "_", "-")
}

func titleize(c Code) string {
	parts := strings.Split(strings.ToLower(string(c)), "_")
	for i, p := range parts {
		parts[i] = strings.ToUpper(p[:1]) + p[1:]
	}
	return strings.Join(parts, " ")
}

// IntFromContext reads an integer from a context field (helpers for
// downstream error payloads).
func IntFromContext(s string, fallback int) int {
	if s == "" {
		return fallback
	}
	n, err := strconv.Atoi(s)
	if err != nil {
		return fallback
	}
	return n
}
