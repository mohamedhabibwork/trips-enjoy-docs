package jwtauth

import (
	"testing"
)

func TestClaimsHasRole(t *testing.T) {
	c := Claims{Roles: []string{"platform.admin", "trip.support"}}
	if !c.HasRole("platform.admin") {
		t.Error("HasRole(platform.admin) = false")
	}
	if c.HasRole("nope") {
		t.Error("HasRole(nope) = true")
	}
	if !c.HasAnyRole("nope", "trip.support") {
		t.Error("HasAnyRole(nope, trip.support) = false")
	}
}

func TestTokens(t *testing.T) {
	got := tokens("read write admin")
	want := []string{"read", "write", "admin"}
	if len(got) != len(want) {
		t.Fatalf("len = %d", len(got))
	}
	for i, w := range want {
		if got[i] != w {
			t.Errorf("[%d] = %q", i, got[i])
		}
	}
}
