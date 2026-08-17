package chain

import (
	"sync"

	"golang.org/x/time/rate"
)

// RateLimiter holds one token-bucket per vendor_id per
// docs/services/geolocation-service/README.md §3 / ERD.md §3.4. The
// refill rate is provider_config.qps_limit and the bucket size is
// provider_config.burst_limit. Allow() returns false when the bucket is
// empty (the chain resolver skips the vendor and advances).
type RateLimiter struct {
	mu       sync.Mutex
	limiters map[string]*rate.Limiter
}

// NewRateLimiter returns an empty registry.
func NewRateLimiter() *RateLimiter {
	return &RateLimiter{limiters: map[string]*rate.Limiter{}}
}

// Register adds a limiter for vendorID with qps + burst.
func (r *RateLimiter) Register(vendorID string, qps, burst int) {
	if qps <= 0 {
		qps = 1
	}
	if burst <= 0 {
		burst = qps
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	r.limiters[vendorID] = rate.NewLimiter(rate.Limit(qps), burst)
}

// Allow reports whether the vendor has at least one token available.
// An un-registered vendor is always allowed (lazy register with a
// permissive bucket).
func (r *RateLimiter) Allow(vendorID string) bool {
	r.mu.Lock()
	lim, ok := r.limiters[vendorID]
	r.mu.Unlock()
	if !ok {
		r.Register(vendorID, 1000, 1000)
		r.mu.Lock()
		lim = r.limiters[vendorID]
		r.mu.Unlock()
	}
	return lim.Allow()
}

// Remaining returns the vendor's remaining-token estimate. Used by the
// vendor_rate_limit_remaining Prometheus metric (SRS.md §22).
func (r *RateLimiter) Remaining(vendorID string) int {
	r.mu.Lock()
	lim, ok := r.limiters[vendorID]
	r.mu.Unlock()
	if !ok {
		return 0
	}
	tokens := lim.Tokens()
	if tokens < 0 {
		return 0
	}
	return int(tokens)
}
