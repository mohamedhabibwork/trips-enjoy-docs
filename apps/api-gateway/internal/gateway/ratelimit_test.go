package gateway

import (
	"context"
	"testing"
	"time"
)

func TestInProcBucketAllowsUntilLimit(t *testing.T) {
	b := newInProcBucket()
	for i := 0; i < 5; i++ {
		ok, used := b.Take("/v1/a", "sub:u", 5, time.Hour)
		if !ok || used != i+1 {
			t.Fatalf("iter %d: allowed=%v used=%d", i, ok, used)
		}
	}
	ok, used := b.Take("/v1/a", "sub:u", 5, time.Hour)
	if ok || used != 6 {
		t.Fatalf("6th call: allowed=%v used=%d", ok, used)
	}
}

func TestRateLimiterHeaders(t *testing.T) {
	rl := NewRateLimiter(nil, 3, 60) // in-proc bucket
	d, err := rl.Check(context.Background(), "/v1/x", "sub:1")
	if err != nil {
		t.Fatal(err)
	}
	if d.Limit != 3 || d.Remaining != 2 || d.Reset != 60 {
		t.Fatalf("decision = %+v", d)
	}
	// Trigger rejection
	for i := 0; i < 2; i++ {
		_, _ = rl.Check(context.Background(), "/v1/x", "sub:1")
	}
	d, err = rl.Check(context.Background(), "/v1/x", "sub:1")
	if err != nil {
		t.Fatal(err)
	}
	if d.Allowed {
		t.Fatal("expected rejection")
	}
	if d.RetryAfter < 1 {
		t.Fatalf("RetryAfter = %d", d.RetryAfter)
	}
}
