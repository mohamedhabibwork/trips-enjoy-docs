package httpapi

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

// TestRequestID_GeneratesWhenMissing proves the middleware mints a
// UUIDv7-shaped request id when the inbound headers are absent.
func TestRequestID_GeneratesWhenMissing(t *testing.T) {
	called := false
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = true
		id := RequestIDFromContext(r.Context())
		if id == "" {
			t.Fatalf("expected generated request id")
		}
		if len(id) != 36 {
			t.Fatalf("expected UUIDv7-shaped id, got %q", id)
		}
	})
	handler := RequestID(next)

	req := httptest.NewRequest("GET", "/v1/cities/lookup?lat=51.5&lon=-0.1", nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if !called {
		t.Fatalf("next handler was not called")
	}
	if got := rec.Header().Get("X-Request-Id"); got == "" {
		t.Fatalf("expected X-Request-Id header on response")
	}
	if got := rec.Header().Get("X-Correlation-Id"); got == "" {
		t.Fatalf("expected X-Correlation-Id header on response (ADR-0019 alias)")
	}
}

// TestRequestID_HonorsInboundXRequestID proves the middleware honors
// the inbound X-Request-Id header (the gateway is the canonical
// root generator per ADR-0019).
func TestRequestID_HonorsInboundXRequestID(t *testing.T) {
	const want = "01HZX9C7T0XK2P9F0V6E4B1MZA"
	captured := ""
	next := http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		captured = RequestIDFromContext(r.Context())
	})
	handler := RequestID(next)
	req := httptest.NewRequest("GET", "/", nil)
	req.Header.Set("X-Request-Id", want)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if captured != want {
		t.Fatalf("expected %q, got %q", want, captured)
	}
}

// TestRequestIDFromContext_NilContextSafe ensures the helper returns
// an empty string (not a panic) when called from a context that never
// passed through the middleware.
func TestRequestIDFromContext_NilContextSafe(t *testing.T) {
	if got := RequestIDFromContext(context.Background()); got != "" {
		t.Fatalf("expected empty string, got %q", got)
	}
}
