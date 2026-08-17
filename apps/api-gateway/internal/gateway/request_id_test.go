package gateway

import (
	"context"
	"net/http"
	"net/http/httptest"
	"regexp"
	"testing"
)

var uuidV7Regex = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)

func TestRequestIDGeneratedWhenAbsent(t *testing.T) {
	rec := httptest.NewRecorder()
	r := httptest.NewRequest(http.MethodGet, "/anything", nil)
	var seenInCtx string
	mw := RequestIDMiddleware()
	mw(http.HandlerFunc(func(_ http.ResponseWriter, rr *http.Request) {
		seenInCtx = RequestID(rr.Context())
	})).ServeHTTP(rec, r)

	got := rec.Header().Get("X-Request-Id")
	if !uuidV7Regex.MatchString(got) {
		t.Fatalf("X-Request-Id %q is not a UUIDv7", got)
	}
	if rec.Header().Get("X-Correlation-Id") != got {
		t.Fatalf("X-Correlation-Id should equal X-Request-Id")
	}
	if seenInCtx != got {
		t.Fatalf("context id = %q, header id = %q", seenInCtx, got)
	}
}

func TestRequestIDPrefersXRequestId(t *testing.T) {
	rec := httptest.NewRecorder()
	r := httptest.NewRequest(http.MethodGet, "/anything", nil)
	r.Header.Set("X-Request-Id", "request-A")
	r.Header.Set("X-Correlation-Id", "correlation-B")
	var seenInCtx string
	RequestIDMiddleware()(http.HandlerFunc(func(_ http.ResponseWriter, rr *http.Request) {
		seenInCtx = RequestID(rr.Context())
	})).ServeHTTP(rec, r)

	if got := rec.Header().Get("X-Request-Id"); got != "request-A" {
		t.Fatalf("X-Request-Id = %q, want request-A", got)
	}
	if got := rec.Header().Get("X-Correlation-Id"); got != "request-A" {
		t.Fatalf("X-Correlation-Id should equal X-Request-Id (got %q)", got)
	}
	if seenInCtx != "request-A" {
		t.Fatalf("ctx id = %q", seenInCtx)
	}
}

func TestRequestIDAcceptsAliasHeader(t *testing.T) {
	rec := httptest.NewRecorder()
	r := httptest.NewRequest(http.MethodGet, "/anything", nil)
	r.Header.Set("X-Correlation-Id", "alias-value")
	RequestIDMiddleware()(http.HandlerFunc(func(_ http.ResponseWriter, _ *http.Request) {})).ServeHTTP(rec, r)
	if got := rec.Header().Get("X-Request-Id"); got != "alias-value" {
		t.Fatalf("X-Request-Id = %q, want alias-value", got)
	}
	if got := rec.Header().Get("X-Correlation-Id"); got != "alias-value" {
		t.Fatalf("X-Correlation-Id = %q", got)
	}
}

func TestRequestIDStableAcrossRetries(t *testing.T) {
	for i := 0; i < 3; i++ {
		rec := httptest.NewRecorder()
		r := httptest.NewRequest(http.MethodGet, "/anything", nil)
		r.Header.Set("X-Request-Id", "retry-token")
		RequestIDMiddleware()(http.HandlerFunc(func(_ http.ResponseWriter, _ *http.Request) {})).ServeHTTP(rec, r)
		if got := rec.Header().Get("X-Request-Id"); got != "retry-token" {
			t.Fatalf("iter %d: X-Request-Id = %q", i, got)
		}
	}
}

func TestSetLogField(t *testing.T) {
	ctx := SetLogField(context.Background(), func(f *logFields) {
		f.UserID = "u1"
		f.Route = "/v1/x"
	})
	f := LogFieldsFromContext(ctx)
	if f.UserID != "u1" || f.Route != "/v1/x" {
		t.Fatalf("LogFieldsFromContext = %+v", f)
	}
}
