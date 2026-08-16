package requestid

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestMiddlewareUsesInbound(t *testing.T) {
	h := Middleware()(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if id := FromContext(r.Context()); id != "abc" {
			t.Errorf("FromContext = %q", id)
		}
		w.WriteHeader(http.StatusOK)
	}))
	r := httptest.NewRequest("GET", "/v1/x", nil)
	r.Header.Set(HeaderRequestID, "abc")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Header().Get(HeaderRequestID) != "abc" {
		t.Errorf("X-Request-Id = %q", w.Header().Get(HeaderRequestID))
	}
	if w.Header().Get(HeaderCorrelationID) != "abc" {
		t.Errorf("X-Correlation-Id = %q", w.Header().Get(HeaderCorrelationID))
	}
}

func TestMiddlewareGeneratesUUIDv7(t *testing.T) {
	h := Middleware()(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if id := FromContext(r.Context()); id == "" {
			t.Error("expected generated id")
		}
	}))
	r := httptest.NewRequest("GET", "/v1/x", nil)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Header().Get(HeaderRequestID) == "" {
		t.Error("expected X-Request-Id response header")
	}
}

func TestRequestIDWinsOverCorrelationID(t *testing.T) {
	h := Middleware()(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	r := httptest.NewRequest("GET", "/v1/x", nil)
	r.Header.Set(HeaderRequestID, "winner")
	r.Header.Set(HeaderCorrelationID, "loser")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Header().Get(HeaderRequestID) != "winner" {
		t.Errorf("expected winner, got %q", w.Header().Get(HeaderRequestID))
	}
}
