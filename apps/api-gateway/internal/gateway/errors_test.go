package gateway

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestWriteErrorEnvelope(t *testing.T) {
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/v1/x", nil)
	WriteError(req.Context(), rec, req, http.StatusUnauthorized, CodeUnauthenticated, "missing bearer", nil)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d", rec.Code)
	}
	if ct := rec.Header().Get("Content-Type"); ct == "" {
		t.Fatal("missing content-type")
	}
	var env Envelope
	if err := json.NewDecoder(rec.Body).Decode(&env); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if env.Code != CodeUnauthenticated {
		t.Errorf("code = %q", env.Code)
	}
	if env.Message == "" {
		t.Errorf("message empty")
	}
	if env.Instance != "/v1/x" {
		t.Errorf("instance = %q", env.Instance)
	}
	if env.Type == "" {
		t.Errorf("type empty")
	}
	if env.Timestamp == "" {
		t.Errorf("timestamp empty")
	}
	if _, err := time.Parse("2006-01-02T15:04:05.000Z", env.Timestamp); err != nil {
		t.Errorf("timestamp not RFC3339: %v", err)
	}
}

func TestWriteErrorWithDownstream(t *testing.T) {
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/v1/payments", nil)
	d := &Downstream{
		Service: "payment-service",
		Code:    "DEPENDENCY_UPSTREAM_FAILURE",
		Status:  http.StatusBadGateway,
		TraceID: "abc123",
		SpanID:  "def456",
	}
	WriteError(req.Context(), rec, req, http.StatusBadGateway, CodeDependencyUpstream, "upstream failed", d)
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("status = %d", rec.Code)
	}
	var env Envelope
	if err := json.NewDecoder(rec.Body).Decode(&env); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if env.Downstream == nil || env.Downstream.Service != "payment-service" {
		t.Fatalf("downstream block missing: %+v", env.Downstream)
	}
}

func TestErrorCodeToStatus(t *testing.T) {
	cases := map[ErrorCode]int{
		CodeUnauthenticated:       http.StatusUnauthorized,
		CodeTokenExpired:          http.StatusUnauthorized,
		CodeTokenRevoked:          http.StatusUnauthorized,
		CodeForbidden:             http.StatusForbidden,
		CodeUserSuspended:         http.StatusForbidden,
		CodeUserDisabled:          http.StatusForbidden,
		CodeWAFBlocked:            http.StatusForbidden,
		CodeNotFound:              http.StatusNotFound,
		CodeConflict:              http.StatusConflict,
		CodePayloadTooLarge:       http.StatusRequestEntityTooLarge,
		CodeRateLimited:           http.StatusTooManyRequests,
		CodeValidationFailed:      http.StatusBadRequest,
		CodeDependencyUpstream:    http.StatusBadGateway,
		CodeDependencyTimeout:     http.StatusGatewayTimeout,
		CodeDependencyUnavailable: http.StatusServiceUnavailable,
		CodeServiceUnavailable:    http.StatusServiceUnavailable,
		CodeCircuitOpen:           http.StatusServiceUnavailable,
		CodeInternalError:         http.StatusInternalServerError,
	}
	for code, want := range cases {
		if got := statusForCode(code); got != want {
			t.Errorf("statusForCode(%q) = %d, want %d", code, got, want)
		}
	}
}
