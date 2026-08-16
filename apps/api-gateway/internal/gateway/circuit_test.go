package gateway

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/sony/gobreaker"
)

func TestCircuitBreakerTripsAfterThreshold(t *testing.T) {
	cb := NewCircuitBreaker("test", 3, 50*time.Millisecond, 50*time.Millisecond, 10)
	cb.OnStateChange = func(_ string, _, _ gobreaker.State) {}
	for i := 0; i < 3; i++ {
		_ = cb.Do(context.Background(), func(_ context.Context) error {
			return errors.New("boom")
		})
	}
	if cb.State() != gobreaker.StateOpen {
		t.Fatalf("state = %v, want open", cb.State())
	}
	err := cb.Do(context.Background(), func(_ context.Context) error { return nil })
	if !errors.Is(err, ErrCircuitOpen) {
		t.Fatalf("err = %v, want ErrCircuitOpen", err)
	}
}

func TestCircuitRegistryBuildsAndReuses(t *testing.T) {
	r := NewCircuitRegistry(BulkheadConfig{Threshold: 2, Cooldown: 10 * time.Millisecond, Timeout: 50 * time.Millisecond, Size: 4})
	a := r.For("svc-A")
	b := r.For("svc-A")
	if a != b {
		t.Fatal("registry should reuse breaker for same upstream")
	}
	if r.For("svc-B") == a {
		t.Fatal("different upstream must yield different breaker")
	}
}
