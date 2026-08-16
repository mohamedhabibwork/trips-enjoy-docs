package gateway

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/trips-enjoy/platform-go/errormodel"
	"github.com/trips-enjoy/platform-go/httperr"
	"github.com/trips-enjoy/platform-go/money"
	"github.com/trips-enjoy/platform-go/requestid"
)

// TestPlatformGoLink verifies that the api-gateway can compile against
// the shared Go library at packages/platform-go/. This test fails to
// compile if the workspace substitution breaks.
func TestPlatformGoLink(t *testing.T) {
	// requestid
	h := requestid.Middleware()(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id := requestid.FromContext(r.Context())
		if id == "" {
			t.Error("expected request id")
		}
	}))
	r := httptest.NewRequest("GET", "/x", nil)
	r.Header.Set(requestid.HeaderRequestID, "abc-123")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Header().Get(requestid.HeaderRequestID) != "abc-123" {
		t.Errorf("got %q", w.Header().Get(requestid.HeaderRequestID))
	}

	// errormodel
	e := errormodel.New(errormodel.CodeNotFound, "missing", "/x", "t", "s")
	if e.Status != 404 {
		t.Errorf("status = %d", e.Status)
	}

	// money
	m := money.OfMinor(1999, "USD")
	if m.Major() != "19.99" {
		t.Errorf("major = %q", m.Major())
	}

	// httperr
	w2 := httptest.NewRecorder()
	httperr.Write(w2, e)
	if w2.Code != 404 {
		t.Errorf("http status = %d", w2.Code)
	}

	// context
	_ = context.Background()
}
