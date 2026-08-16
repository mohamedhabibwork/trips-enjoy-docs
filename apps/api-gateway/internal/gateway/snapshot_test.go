package gateway

import "testing"

func TestSnapshotStoreAtomicSwap(t *testing.T) {
	s := NewSnapshotStore(&Snapshot{Version: 1, Routes: []Route{{Prefix: "/v1/a"}}})
	if got := s.Load(); got == nil || got.Version != 1 {
		t.Fatalf("initial: %+v", got)
	}
	s.Store(&Snapshot{Version: 2, Routes: []Route{{Prefix: "/v1/b"}}})
	if got := s.Load(); got == nil || got.Version != 2 || got.Routes[0].Prefix != "/v1/b" {
		t.Fatalf("after swap: %+v", got)
	}
}

func TestSnapshotStoreNilSafe(t *testing.T) {
	var s *SnapshotStore
	if got := s.Load(); got != nil {
		t.Fatalf("nil store: %+v", got)
	}
	s.Store(&Snapshot{Version: 1}) // must not panic
}

func TestDefaultSnapshot(t *testing.T) {
	cfg := Config{
		Routes:             []Route{{Prefix: "/v1/x"}},
		AllowedCORSOrigins: []string{"https://app.example.com"},
		Keycloak:           KeycloakConfig{JWKSRefresh: 5 * 60 * 1e9},
	}
	snap := DefaultSnapshot(cfg, 1)
	if snap == nil || snap.Version != 1 {
		t.Fatalf("snap = %+v", snap)
	}
	if snap.JWKSRefresh != 300 {
		t.Errorf("JWKS = %d", snap.JWKSRefresh)
	}
}
