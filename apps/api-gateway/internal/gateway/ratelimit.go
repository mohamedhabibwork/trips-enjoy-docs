// Package gateway — rate limiter.
//
// Per docs/services/api-gateway/SRS.md FR-007 the gateway applies
// per-token, per-IP, per-route limits and surfaces them on the
// response with `RateLimit-Limit`, `RateLimit-Remaining`, and
// `RateLimit-Reset`. A 429 response MUST also set `Retry-After`.
//
// The limiter is implemented as a Redis token-bucket (INCR +
// EXPIRE). When Redis is unreachable the limiter degrades to an
// in-process token bucket for ≤ 5 s (per WORKFLOWS.md §1.8) and
// then fail-closes on the revocation check (which is a separate
// concern and remains strict).
package gateway

import (
	"context"
	"errors"
	"math"
	"net/http"
	"strconv"
	"sync"
	"time"
)

// RateLimiter wraps a Redis-backed limiter with an in-process
// fallback bucket. Safe for concurrent use.
type RateLimiter struct {
	r            *RedisClient
	defaultLimit int
	windowSec    int
	degrade      *inProcBucket
}

// NewRateLimiter wires the limiter to a Redis client.
func NewRateLimiter(r *RedisClient, limit int, windowSec int) *RateLimiter {
	return &RateLimiter{
		r:            r,
		defaultLimit: limit,
		windowSec:    windowSec,
		degrade:      newInProcBucket(),
	}
}

// Decision is the rate-limit verdict for one request. Allowed is
// false when over the budget; Limit / Remaining / Reset are the
// response-header values.
type Decision struct {
	Allowed    bool
	Limit      int
	Remaining  int
	Reset      int
	RetryAfter int
}

// Check enforces the limit for (route, principal) using Redis when
// available and the in-process bucket as fallback. principal is a
// stable identifier (sub when authenticated, client IP otherwise).
func (rl *RateLimiter) Check(ctx context.Context, routeID, principal string) (Decision, error) {
	if rl == nil {
		return Decision{Allowed: true}, nil
	}
	limit := rl.defaultLimit
	window := rl.windowSec
	if limit <= 0 || window <= 0 {
		return Decision{Allowed: true}, nil
	}
	if rl.r != nil {
		count, epoch, err := rl.r.IncrRateLimit(ctx, routeID, principal, window)
		if err == nil {
			now := time.Now().Unix()
			reset := epoch + int64(window) - now
			if reset < 0 {
				reset = 0
			}
			remaining := limit - int(count)
			if remaining < 0 {
				remaining = 0
			}
			allowed := count <= int64(limit)
			d := Decision{
				Allowed:    allowed,
				Limit:      limit,
				Remaining:  remaining,
				Reset:      int(reset),
				RetryAfter: int(reset),
			}
			if !allowed && d.RetryAfter < 1 {
				d.RetryAfter = 1
			}
			return d, nil
		}
		// Redis error: fall through to in-process bucket.
	}
	allowed, used := rl.degrade.Take(routeID, principal, limit, time.Duration(window)*time.Second)
	remaining := limit - used
	if remaining < 0 {
		remaining = 0
	}
	d := Decision{
		Allowed:    allowed,
		Limit:      limit,
		Remaining:  remaining,
		Reset:      int(window),
		RetryAfter: int(window),
	}
	if !allowed && d.RetryAfter < 1 {
		d.RetryAfter = 1
	}
	return d, nil
}

// WriteHeaders copies the decision's rate-limit metadata onto w.
func (d Decision) WriteHeaders(w http.ResponseWriter) {
	w.Header().Set("RateLimit-Limit", strconv.Itoa(d.Limit))
	w.Header().Set("RateLimit-Remaining", strconv.Itoa(d.Remaining))
	w.Header().Set("RateLimit-Reset", strconv.Itoa(d.Reset))
	if !d.Allowed {
		w.Header().Set("Retry-After", strconv.Itoa(d.RetryAfter))
	}
}

// inProcBucket is a small token-bucket fallback used while Redis is
// unreachable. Each (route, principal) bucket holds a count that
// resets every window.
type inProcBucket struct {
	mu      sync.Mutex
	buckets map[string]inProcKey
}

type inProcKey struct {
	count   int
	resetAt time.Time
}

func newInProcBucket() *inProcBucket {
	return &inProcBucket{buckets: make(map[string]inProcKey)}
}

// Take returns (allowed, used). The bucket refills to 0 on reset.
func (b *inProcBucket) Take(routeID, principal string, limit int, window time.Duration) (bool, int) {
	now := time.Now()
	key := routeID + "|" + principal
	b.mu.Lock()
	defer b.mu.Unlock()
	k, ok := b.buckets[key]
	if !ok || now.After(k.resetAt) {
		k = inProcKey{count: 1, resetAt: now.Add(window)}
		b.buckets[key] = k
		return 1 <= limit, 1
	}
	k.count++
	b.buckets[key] = k
	if k.count > limit {
		return false, k.count
	}
	return true, k.count
}

// intFromEnvironment is a tiny helper used by configuration code
// that's not worth a full ConfigRoundtrip dependency.
func intFromEnvironment(value string, fallback int) int {
	if value == "" {
		return fallback
	}
	if v, err := strconv.Atoi(value); err == nil {
		return v
	}
	return fallback
}

// mathFloor is for tests that need a deterministic window reset.
func mathFloor(now, window int64) int64 {
	if window <= 0 {
		return now
	}
	return now - (now % window)
}

// guard unused-import for errors/math in this file.
var (
	_ = errors.New
	_ = math.Floor
)
