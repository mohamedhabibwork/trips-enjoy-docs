package gateway

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestWAFRejectsSQLi(t *testing.T) {
	r := httptestNewRequest("GET", "/v1/users?id=1%20OR%201%3D1", "")
	if pat := WAFMatch(r, nil); pat == "" {
		t.Fatal("expected WAF match on SQLi")
	}
}

func TestWAFRejectsXXE(t *testing.T) {
	body := []byte("<!DOCTYPE foo [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>")
	r := httptestNewRequest("POST", "/v1/xml", "")
	if pat := WAFMatch(r, body); pat == "" {
		t.Fatal("expected WAF match on XXE")
	}
}

func TestWAFRejectsPathTraversal(t *testing.T) {
	r := httptestNewRequest("GET", "/v1/files/../../etc/passwd", "")
	if pat := WAFMatch(r, nil); pat == "" {
		t.Fatal("expected WAF match on path traversal")
	}
}

func TestWAFRejectsShellInjection(t *testing.T) {
	r := httptestNewRequest("POST", "/v1/cmds", "")
	body := []byte("name=$(rm -rf /)")
	if pat := WAFMatch(r, body); pat == "" {
		t.Fatal("expected WAF match on shell injection")
	}
}

func TestWAFAllowsCleanTraffic(t *testing.T) {
	r := httptestNewRequest("GET", "/v1/customers/me", "")
	body := []byte(`{"name":"Mohamed"}`)
	if pat := WAFMatch(r, body); pat != "" {
		t.Fatalf("false positive: %q", pat)
	}
}

func TestWAFCheckReturns400Or403Envelope(t *testing.T) {
	r := httptestNewRequest("GET", "/v1/whatever/..%2f..%2fetc", "")
	rec := httptest.NewRecorder()
	if !WAFCheck(r, nil, rec) {
		t.Fatal("WAFCheck should short-circuit on pattern")
	}
	if rec.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want 403", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, "WAF_BLOCKED") {
		t.Fatalf("missing WAF_BLOCKED code: %s", body)
	}
	if strings.Contains(body, "path_traversal") {
		t.Fatalf("WAF pattern name leaked into response: %s", body)
	}
}
