// Package gateway — Redis client wrapper.
//
// The gateway uses Redis for three things per
// docs/services/api-gateway/ERD.md §5:
//
//  1. Revocation set
//     gateway:revoked:jti:<jti>   string  TTL = remaining token lifetime
//     gateway:revoked:sub:<kc_sub> string  TTL 30 days
//  2. Rate-limit counters
//     gateway:rl:<route_id>:<principal>:<window_floor>  counter (INCR + EXPIRE)
//  3. JWKS cache (mirroring the in-process one for cross-replica
//     hit rate)
//     gateway:jwks:<realm>  string
//
// All keys are prefixed with `gateway:` per the platform namespace
// convention (PLATFORM_BASELINE.md §"Redis"). The client is
// fail-closed: if the connection is unavailable the proxy surfaces
// `503 REVOCATION_UNAVAILABLE` for security-sensitive checks
// (the JWT revocation and the suspended/disabled sub check) and
// degrades the rate limiter (in-process token bucket for ≤ 5 s
// per WORKFLOWS.md §1.8).
package gateway

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
)

// RedisClient is a thin wrapper around redis.UniversalClient that
// exposes only the gateway's needed operations and abstracts over
// fail-closed / fail-open semantics.
type RedisClient struct {
	c       redis.UniversalClient
	timeout time.Duration
}

// NewRedisClient opens a redis client, pings to verify
// reachability, and returns the wrapper. The connection is broken
// if the initial ping fails; the gateway treats this as a fatal
// startup error (per WORKFLOWS.md §1.8 and §24).
func NewRedisClient(ctx context.Context, cfg RedisConfig) (*RedisClient, error) {
	c := redis.NewClient(&redis.Options{
		Addr:         cfg.Addr,
		Password:     cfg.Password,
		DB:           cfg.DB,
		DialTimeout:  cfg.Timeout,
		ReadTimeout:  cfg.Timeout,
		WriteTimeout: cfg.Timeout,
		PoolSize:     64,
	})
	if err := c.Ping(ctx).Err(); err != nil {
		_ = c.Close()
		return nil, fmt.Errorf("redis ping: %w", err)
	}
	return &RedisClient{c: c, timeout: cfg.Timeout}, nil
}

// NewRedisClientFrom is the constructor for tests that want to
// inject an arbitrary redis client (e.g. miniredis).
func NewRedisClientFrom(c redis.UniversalClient, timeout time.Duration) *RedisClient {
	if timeout <= 0 {
		timeout = defaultRedisTimeout
	}
	return &RedisClient{c: c, timeout: timeout}
}

// Close releases all connections.
func (r *RedisClient) Close() error {
	if r == nil || r.c == nil {
		return nil
	}
	return r.c.Close()
}

// IsJTIRevoked returns true iff the JTI is in the revoked-jti set.
// Returns an error only on transport failure; the caller decides
// whether to fail closed (security) or fail open.
func (r *RedisClient) IsJTIRevoked(ctx context.Context, jti string) (bool, error) {
	if r == nil || r.c == nil || jti == "" {
		return false, errNoRedis
	}
	cctx, cancel := r.ctx(ctx)
	defer cancel()
	v, err := r.c.Exists(cctx, fmt.Sprintf(defaultRevokedJTIKeyTpl, jti)).Result()
	return v > 0, err
}

// RevokeJTI writes jti with the given TTL. Idempotent.
func (r *RedisClient) RevokeJTI(ctx context.Context, jti string, ttl time.Duration) error {
	if r == nil || r.c == nil {
		return errNoRedis
	}
	if jti == "" {
		return errors.New("empty jti")
	}
	if ttl <= 0 {
		return nil
	}
	cctx, cancel := r.ctx(ctx)
	defer cancel()
	return r.c.Set(cctx, fmt.Sprintf(defaultRevokedJTIKeyTpl, jti), "1", ttl).Err()
}

// IsSubBlocked returns the block reason ("suspended" / "disabled")
// for kc_sub, or "" if not blocked.
func (r *RedisClient) IsSubBlocked(ctx context.Context, sub string) (string, error) {
	if r == nil || r.c == nil || sub == "" {
		return "", errNoRedis
	}
	cctx, cancel := r.ctx(ctx)
	defer cancel()
	v, err := r.c.Get(cctx, fmt.Sprintf(defaultSuspendedSubKey, sub)).Result()
	if errors.Is(err, redis.Nil) {
		return "", nil
	}
	if err != nil {
		return "", err
	}
	return v, nil
}

// BlockSub records kc_sub as blocked with the given reason and TTL.
// Idempotent.
func (r *RedisClient) BlockSub(ctx context.Context, sub, reason string, ttl time.Duration) error {
	if r == nil || r.c == nil {
		return errNoRedis
	}
	if sub == "" {
		return errors.New("empty sub")
	}
	if ttl <= 0 {
		ttl = defaultRevocationTTL
	}
	cctx, cancel := r.ctx(ctx)
	defer cancel()
	return r.c.Set(cctx, fmt.Sprintf(defaultSuspendedSubKey, sub), reason, ttl).Err()
}

// IncrRateLimit atomically increments the rate-limit counter for
// (route, principal) inside a 1-second precision window and
// returns the new counter value + the window epoch. The caller
// owns the comparison against the limit.
func (r *RedisClient) IncrRateLimit(ctx context.Context, routeID, principal string, windowSeconds int) (count int64, windowEpoch int64, err error) {
	if r == nil || r.c == nil {
		return 0, 0, errNoRedis
	}
	cctx, cancel := r.ctx(ctx)
	defer cancel()
	now := time.Now().Unix()
	windowEpoch = now - (now % int64(windowSeconds))
	key := fmt.Sprintf(defaultRateLimitKeyTpl, routeID, principal, windowEpoch)
	pipe := r.c.Pipeline()
	incrCmd := pipe.Incr(cctx, key)
	pipe.Expire(cctx, key, time.Duration(windowSeconds)*time.Second)
	if _, err := pipe.Exec(cctx); err != nil {
		return 0, 0, err
	}
	return incrCmd.Val(), windowEpoch, nil
}

// IsIPBlocked is the platform-side IP blocklist mirror (`gateway:blocks:ip:*`).
func (r *RedisClient) IsIPBlocked(ctx context.Context, ip string) (bool, error) {
	if r == nil || r.c == nil || ip == "" {
		return false, errNoRedis
	}
	cctx, cancel := r.ctx(ctx)
	defer cancel()
	v, err := r.c.Exists(cctx, fmt.Sprintf(defaultBlockedIPKeyTpl, ip)).Result()
	return v > 0, err
}

// BlockIP adds ip to the blocklist with the given TTL.
func (r *RedisClient) BlockIP(ctx context.Context, ip string, ttl time.Duration) error {
	if r == nil || r.c == nil || ip == "" {
		return errNoRedis
	}
	cctx, cancel := r.ctx(ctx)
	defer cancel()
	if ttl <= 0 {
		ttl = defaultRevocationTTL
	}
	return r.c.Set(cctx, fmt.Sprintf(defaultBlockedIPKeyTpl, ip), "1", ttl).Err()
}

// UnblockIP removes ip from the blocklist.
func (r *RedisClient) UnblockIP(ctx context.Context, ip string) error {
	if r == nil || r.c == nil {
		return errNoRedis
	}
	cctx, cancel := r.ctx(ctx)
	defer cancel()
	return r.c.Del(cctx, fmt.Sprintf(defaultBlockedIPKeyTpl, ip)).Err()
}

// Ping is exposed for /ready probes.
func (r *RedisClient) Ping(ctx context.Context) error {
	if r == nil || r.c == nil {
		return errNoRedis
	}
	cctx, cancel := r.ctx(ctx)
	defer cancel()
	return r.c.Ping(cctx).Err()
}

var errNoRedis = errors.New("redis: client not configured")

func (r *RedisClient) ctx(parent context.Context) (context.Context, context.CancelFunc) {
	if r.timeout > 0 {
		return context.WithTimeout(parent, r.timeout)
	}
	return parent, func() {}
}
