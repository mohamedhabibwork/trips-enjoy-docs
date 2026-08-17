package gateway

import (
	"net/http"
	"testing"
)

func TestMissingBearer(t *testing.T) {
	r, _ := http.NewRequest("GET", "/v1/x", nil)
	if _, err := ExtractBearer(r); err == nil {
		t.Fatal("expected missing-bearer error")
	}
	r.Header.Set("Authorization", "Bearer ")
	if _, err := ExtractBearer(r); err == nil {
		t.Fatal("expected missing-bearer error on empty token")
	}
	r.Header.Set("Authorization", "Bearer abc")
	if tok, err := ExtractBearer(r); err != nil || tok != "abc" {
		t.Fatalf("got (%q, %v)", tok, err)
	}
}
