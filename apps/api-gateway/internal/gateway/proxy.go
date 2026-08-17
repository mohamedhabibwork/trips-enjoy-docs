// Package gateway — reverse proxy.
//
// This file is the heart of the gateway. The proxy middleware is
// the orchestrator that walks the request through the canonical
// edge pipeline (per docs/services/api-gateway/WORKFLOWS.md §1):
//
//  1. Match the request path to a route (Route table from
//     Snapshot; falls back to NotFound 404).
//  2. WAF pre-check on URL+query+body (defense in depth; emits
//     403 WAF_BLOCKED).
//  3. Body-size guard (413 PAYLOAD_TOO_LARGE).
//  4. Bearer extraction → JWT verify against Keycloak JWKS
//     → revocation set lookup → suspended/disabled set lookup.
//     Unauthenticated / revoked / suspended → 401/403.
//  5. Coarse role check (route.RequiredRoles → FORBIDDEN 403).
//  6. Per-route rate-limit decision (Redis-backed; emits
//     RateLimit-* headers on every response, Retry-After + 429 on
//     rejection).
//  7. Translate claims to X-User-* headers; delete any
//     client-supplied copies.
//  8. Forward via httputil.ReverseProxy through the per-upstream
//     circuit breaker + bulkhead; propagate X-Request-Id,
//     X-Correlation-Id, W3C traceparent.
//  9. Emit audit.api.request.v1 (sync ack) with the per-request
//     fields bound.
//  10. Observe the request in Prometheus; emit one JSON log line.
//
// The proxy fails closed per WORKFLOWS.md §1.8: when JWKS is
// unreachable the gateway 503s; when the revocation set can't be
// consulted (Redis down) the gateway 503s with REVOCATION_UNAVAILABLE
// (security-sensitive); when the rate-limit Redis path errors the
// gateway degrades to in-process bucket for ≤ 5 s (best-effort,
// still bounded).
package gateway

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/sony/gobreaker"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/trace"
)

// ProxyDeps holds the collaborators the proxy needs. Built once in
// main.go and reused by tests.
type ProxyDeps struct {
	Config      Config
	Snapshots   *SnapshotStore
	Verifiers   *Verifiers
	Redis       *RedisClient
	RateLimiter *RateLimiter
	Circuits    *CircuitRegistry
	Producer    Producer
	Metrics     *Metrics
	Tracer      trace.Tracer
	Logger      func(ctx context.Context) interface { /* *slog.Logger */
	}
	LoggerFn func(ctx context.Context) any
}

// ProxyHandler returns the http.Handler that orchestrates every
// proxied request. Public so router.go can mount it as the
// NotFound catch-all (preserving the chi-onwards behaviour).
func ProxyHandler(d ProxyDeps) http.Handler {
	transport := &http.Transport{
		Proxy:                 http.ProxyFromEnvironment,
		MaxIdleConns:          1024,
		MaxIdleConnsPerHost:   256,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   5 * time.Second,
		ResponseHeaderTimeout: d.Config.UpstreamTimeout,
		ExpectContinueTimeout: 1 * time.Second,
		DisableCompression:    false,
	}

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		ctx, span := d.Tracer.Start(r.Context(), r.Method+" "+routeOf(d.Snapshots, r.URL.Path))
		defer span.End()

		snap := d.Snapshots.Load()
		if snap == nil {
			WriteError(ctx, w, r, http.StatusServiceUnavailable, CodeServiceUnavailable,
				"The gateway is starting; please retry shortly.", nil)
			d.Metrics.Observe("*", r.Method, http.StatusServiceUnavailable, time.Since(start))
			return
		}

		route, ok := matchRoute(snap.Routes, r.URL.Path)
		if !ok {
			WriteError(ctx, w, r, http.StatusNotFound, CodeNotFound, "The requested resource was not found.", nil)
			d.Metrics.Observe("*", r.Method, http.StatusNotFound, time.Since(start))
			return
		}

		span.SetAttributes(attribute.String("api_gateway.route", route.Prefix), attribute.String("api_gateway.upstream", route.Service))

		// 2. WAF pre-check on URL + body (with a cap so a malicious
		//    client can't OOM us).
		bodyBuf, err := readBodyCapped(r.Body, d.Config.BodyMaxBytes+1)
		if err != nil {
			WriteError(ctx, w, r, http.StatusBadRequest, CodeValidationFailed, "Failed to read request body.", nil)
			d.Metrics.Observe(route.Prefix, r.Method, http.StatusBadRequest, time.Since(start))
			return
		}
		r.Body = io.NopCloser(bytes.NewReader(bodyBuf))
		r.ContentLength = int64(len(bodyBuf))

		if pattern := WAFMatch(r, bodyBuf); pattern != "" {
			span.SetStatus(codes.Error, "waf_block")
			span.SetAttributes(attribute.String("api_gateway.waf_pattern", pattern))
			WriteError(ctx, w, r, http.StatusForbidden, CodeWAFBlocked,
				"The request was rejected by the gateway's WAF.", nil)
			d.Metrics.Observe(route.Prefix, r.Method, http.StatusForbidden, time.Since(start))
			return
		}

		// 3. Body-size guard.
		if int64(len(bodyBuf)) > d.Config.BodyMaxBytes {
			WriteError(ctx, w, r, http.StatusRequestEntityTooLarge, CodePayloadTooLarge,
				"The request body exceeds the allowed size.", nil)
			d.Metrics.Observe(route.Prefix, r.Method, http.StatusRequestEntityTooLarge, time.Since(start))
			return
		}

		// 4. JWT verify (Keycloak).
		if !route.AllowAnonymous {
			raw, err := ExtractBearer(r)
			if err != nil {
				d.Metrics.IncJWTFailure("missing")
				WriteError(ctx, w, r, http.StatusUnauthorized, CodeUnauthenticated,
					"Authentication is required.", nil)
				d.Metrics.Observe(route.Prefix, r.Method, http.StatusUnauthorized, time.Since(start))
				return
			}
			claims, err := d.Verifiers.Verify(ctx, raw)
			if err != nil {
				reason := string(VerificationErrorCode(err))
				d.Metrics.IncJWTFailure(reason)
				WriteError(ctx, w, r, statusForCode(VerificationErrorCode(err)),
					VerificationErrorCode(err), humanMessage(err), nil)
				d.Metrics.Observe(route.Prefix, r.Method, statusForCode(VerificationErrorCode(err)), time.Since(start))
				return
			}
			if claims.JTI != "" && d.Redis != nil {
				revoked, rerr := d.Redis.IsJTIRevoked(ctx, claims.JTI)
				if rerr != nil {
					WriteError(ctx, w, r, http.StatusServiceUnavailable, CodeRevocationUnavailable,
						"The revocation store is unavailable.", nil)
					d.Metrics.Observe(route.Prefix, r.Method, http.StatusServiceUnavailable, time.Since(start))
					return
				}
				if revoked {
					WriteError(ctx, w, r, http.StatusUnauthorized, CodeTokenRevoked,
						"The token has been revoked.", nil)
					d.Metrics.Observe(route.Prefix, r.Method, http.StatusUnauthorized, time.Since(start))
					return
				}
			}
			if claims.Sub != "" && d.Redis != nil {
				reason, rerr := d.Redis.IsSubBlocked(ctx, claims.Sub)
				if rerr != nil {
					WriteError(ctx, w, r, http.StatusServiceUnavailable, CodeRevocationUnavailable,
						"The user-store is unavailable.", nil)
					d.Metrics.Observe(route.Prefix, r.Method, http.StatusServiceUnavailable, time.Since(start))
					return
				}
				switch reason {
				case "suspended":
					WriteError(ctx, w, r, http.StatusForbidden, CodeUserSuspended, "The user is suspended.", nil)
					d.Metrics.Observe(route.Prefix, r.Method, http.StatusForbidden, time.Since(start))
					return
				case "disabled":
					WriteError(ctx, w, r, http.StatusForbidden, CodeUserDisabled, "The user is disabled.", nil)
					d.Metrics.Observe(route.Prefix, r.Method, http.StatusForbidden, time.Since(start))
					return
				}
			}
			if !AssertRoles(claims, route.RequiredRoles) {
				WriteError(ctx, w, r, http.StatusForbidden, CodeForbidden,
					"You do not have permission to call this endpoint.", nil)
				d.Metrics.Observe(route.Prefix, r.Method, http.StatusForbidden, time.Since(start))
				return
			}

			// Bind claims to log MDC for the request scope.
			ctx = SetLogField(ctx, func(f *logFields) {
				f.UserID = claims.Sub
				f.UserType = claims.UserType
			})
			r = r.WithContext(ctx)

			// 7. Header translation (delete any client-supplied X-User-*).
			stripUserHeaders(r.Header)
			Translate(r.Header, claims)
		} else {
			// Public / anonymous path: the audit still carries user_id="anonymous".
			ctx = SetLogField(ctx, func(f *logFields) {
				f.UserID = "anonymous"
				f.UserType = "anonymous"
			})
			r = r.WithContext(ctx)
		}

		// 6. Per-route rate limit.
		principal := rateLimitPrincipal(r)
		decision, _ := d.RateLimiter.Check(ctx, route.Prefix, principal)
		decision.WriteHeaders(w)
		if !decision.Allowed {
			d.Metrics.IncRateLimitRejection(route.Prefix)
			event := BuildRateLimitExceeded(RequestID(r.Context()), RateLimitData{
				Route:             route.Prefix,
				PrincipalType:     principalKind(r),
				PrincipalID:       principal,
				Limit:             decision.Limit,
				WindowSeconds:     60,
				RetryAfterSeconds: decision.RetryAfter,
				ClientIP:          clientIP(r),
			})
			_ = d.Producer.PublishBestEffort(ctx, event, "platform.gateway.rate_limit.exceeded")
			WriteError(ctx, w, r, http.StatusTooManyRequests, CodeRateLimited,
				"Rate limit exceeded.", nil)
			d.Metrics.Observe(route.Prefix, r.Method, http.StatusTooManyRequests, time.Since(start))
			return
		}

		// 8. Forward via reverse proxy through the circuit breaker.
		target, err := url.Parse(route.Upstream)
		if err != nil || target.Scheme == "" || target.Host == "" {
			WriteError(ctx, w, r, http.StatusServiceUnavailable, CodeDependencyUpstream,
				"The requested service is unavailable.", nil)
			d.Metrics.Observe(route.Prefix, r.Method, http.StatusServiceUnavailable, time.Since(start))
			return
		}

		var upstreamStatus int
		var upstreamBodyHash string
		var upstreamStart = time.Now()
		var clientErr error

		breaker := d.Circuits.For(route.Service)
		err = breaker.Do(ctx, func(innerCtx context.Context) error {
			// Create a new proxy per call so we can capture status
			// + body-hash for the audit event.
			rp := &httputil.ReverseProxy{
				Transport: transport,
				Director: func(req *http.Request) {
					req.URL.Scheme = target.Scheme
					req.URL.Host = target.Host
					req.Host = target.Host
					// Outbound: per ADR-0019 add both request-id aliases.
					req.Header.Set("X-Request-Id", RequestID(r.Context()))
					req.Header.Set("X-Correlation-Id", RequestID(r.Context()))
					// Outbound: W3C traceparent propagation.
					propagation.TraceContext{}.Inject(innerCtx, propagation.HeaderCarrier(req.Header))
					req.Header.Set("X-Gateway-Service", route.Service)
					req.Header.Set("X-Forwarded-Method", r.Method)
				},
				ErrorHandler: func(rw http.ResponseWriter, req *http.Request, err error) {
					clientErr = err
					upstreamStatus = 0
					WriteError(ctx, rw, r, http.StatusBadGateway, CodeDependencyUpstream,
						"The requested service is unavailable.", nil)
				},
				ModifyResponse: func(resp *http.Response) error {
					upstreamStatus = resp.StatusCode
					// Body SHA-256 (capped) for audit.
					if resp.Body != nil {
						defer resp.Body.Close()
						limited := io.LimitReader(resp.Body, int64(MaxBodyHashBytes)+1)
						hasher := sha256.New()
						buf := &bytes.Buffer{}
						_, _ = io.Copy(io.MultiWriter(hasher, buf), limited)
						upstreamBodyHash = hex.EncodeToString(hasher.Sum(nil))
						resp.Body = io.NopCloser(buf)
					}
					return nil
				},
			}
			rp.ServeHTTP(w, r)
			return nil
		})
		upstreamLatency := time.Since(upstreamStart)
		d.Metrics.ObserveUpstream(route.Prefix, route.Service, upstreamLatency)

		if errors.Is(err, ErrCircuitOpen) {
			WriteError(ctx, w, r, http.StatusServiceUnavailable, CodeCircuitOpen,
				"The upstream circuit breaker is open.", &Downstream{
					Service:   route.Service,
					Status:    http.StatusServiceUnavailable,
					LatencyMs: upstreamLatency.Milliseconds(),
				})
			d.Metrics.Observe(route.Prefix, r.Method, http.StatusServiceUnavailable, time.Since(start))
			return
		}
		if clientErr != nil {
			// Reverse proxy already wrote the error envelope; just
			// record metrics + emit audit.
		}

		// 9. Emit audit.api.request.v1 (sync ack, best-effort dlq).
		audit := BuildAuditRequest(RequestID(r.Context()), AuditRequestData{
			Method:         r.Method,
			Route:          route.Prefix,
			MatchedRouteID: route.Prefix,
			Upstream:       route.Service,
			UpstreamStatus: upstreamStatus,
			Status:         upstreamStatusOr(w, upstreamStatus),
			LatencyMs:      time.Since(start).Milliseconds(),
			ClientIP:       clientIP(r),
			UserAgentHash:  hashUA(r.Header.Get("User-Agent")),
			BodySHA256:     upstreamBodyHash,
			UserID:         userIDOf(r),
			UserType:       userTypeOf(r),
		})
		if perr := d.Producer.PublishAudit(ctx, audit); perr != nil {
			d.Metrics.IncAuditEmitFailure("audit.api.request.v1")
		} else {
			d.Metrics.IncAuditEmitted("audit.api.request.v1", "ok")
		}

		d.Metrics.Observe(route.Prefix, r.Method, statusOrDefault(w, upstreamStatus), time.Since(start))
	})
}

func routeOf(s *SnapshotStore, path string) string {
	if s == nil {
		return "*"
	}
	snap := s.Load()
	if snap == nil {
		return "*"
	}
	if r, ok := matchRoute(snap.Routes, path); ok {
		return r.Prefix
	}
	return "*"
}

func readBodyCapped(r io.ReadCloser, max int64) ([]byte, error) {
	if r == nil {
		return nil, nil
	}
	defer r.Close()
	return io.ReadAll(io.LimitReader(r, max))
}

func humanMessage(err error) string {
	switch {
	case errors.Is(err, ErrTokenExpired):
		return "The token has expired."
	case errors.Is(err, ErrTokenRevoked):
		return "The token has been revoked."
	case errors.Is(err, ErrUserSuspended):
		return "The user is suspended."
	case errors.Is(err, ErrUserDisabled):
		return "The user is disabled."
	case errors.Is(err, ErrMissingBearer):
		return "Authentication is required."
	case errors.Is(err, ErrTokenInvalid):
		return "The token is invalid."
	default:
		return "Authentication failed."
	}
}

func statusForCode(code ErrorCode) int {
	switch code {
	case CodeUnauthenticated, CodeTokenInvalid, CodeTokenExpired, CodeTokenRevoked, CodeMissingBearer, CodeAuthNotConfigured:
		return http.StatusUnauthorized
	case CodeForbidden, CodeUserSuspended, CodeUserDisabled, CodeWAFBlocked:
		return http.StatusForbidden
	case CodeNotFound:
		return http.StatusNotFound
	case CodeConflict, CodeStateInvalid:
		return http.StatusConflict
	case CodePayloadTooLarge:
		return http.StatusRequestEntityTooLarge
	case CodeRateLimited:
		return http.StatusTooManyRequests
	case CodeValidationFailed, CodeBusinessRuleViolation:
		return http.StatusBadRequest
	case CodeDependencyUnavailable, CodeCircuitOpen, CodeBulkheadFull, CodeServiceUnavailable, CodeRevocationUnavailable:
		return http.StatusServiceUnavailable
	case CodeDependencyTimeout:
		return http.StatusGatewayTimeout
	case CodeDependencyUpstream, CodeBadGateway:
		return http.StatusBadGateway
	default:
		return http.StatusInternalServerError
	}
}

// ErrMissingBearer is declared in jwt.go.

// hashUA returns a short SHA-256 of the User-Agent header for
// audit. The full UA is never logged.
func hashUA(ua string) string {
	if ua == "" {
		return ""
	}
	h := sha256.Sum256([]byte(ua))
	return hex.EncodeToString(h[:])[:16]
}

// rateLimitPrincipal picks the rate-limit principal in priority
// order: authenticated subject > client IP. Anonymous requests
// bucket by IP only.
func rateLimitPrincipal(r *http.Request) string {
	if sub := r.Header.Get(HeaderUserID); sub != "" {
		return "sub:" + sub
	}
	return "ip:" + clientIP(r)
}

func principalKind(r *http.Request) string {
	if sub := r.Header.Get(HeaderUserID); sub != "" {
		return "token"
	}
	return "ip"
}

func clientIP(r *http.Request) string {
	if v := r.Header.Get("X-Forwarded-For"); v != "" {
		if i := strings.IndexByte(v, ','); i >= 0 {
			return strings.TrimSpace(v[:i])
		}
		return strings.TrimSpace(v)
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func userIDOf(r *http.Request) string {
	if v := r.Header.Get(HeaderUserID); v != "" {
		return v
	}
	return "anonymous"
}

func userTypeOf(r *http.Request) string {
	if v := r.Header.Get(HeaderUserType); v != "" {
		return v
	}
	return "anonymous"
}

// statusOrDefault records the status code actually written to the
// response. The ReverseProxy may have set headers but not committed
// yet; we peek the underlying recorder via a small wrapper.
func statusOrDefault(w http.ResponseWriter, fallback int) int {
	if rw, ok := w.(*statusRecorder); ok && rw.status != 0 {
		return rw.status
	}
	if fallback != 0 {
		return fallback
	}
	return http.StatusOK
}

func upstreamStatusOr(w http.ResponseWriter, fallback int) int {
	return statusOrDefault(w, fallback)
}

// statusRecorder is a thread-unsafe wrapper used by the proxy to
// capture the response status for metrics + audit. ReverseProxy
// writes the status on the wrapped ResponseWriter.
type statusRecorder struct {
	http.ResponseWriter
	status int
	wrote  bool
}

func (w *statusRecorder) WriteHeader(code int) {
	if w.wrote {
		return
	}
	w.status = code
	w.wrote = true
	w.ResponseWriter.WriteHeader(code)
}

func (w *statusRecorder) Write(b []byte) (int, error) {
	if !w.wrote {
		w.status = http.StatusOK
		w.wrote = true
	}
	return w.ResponseWriter.Write(b)
}

// wrapRecorder captures the response status; the caller wraps the
// http.ResponseWriter before invoking the proxy.
func wrapRecorder(w http.ResponseWriter) *statusRecorder {
	if rw, ok := w.(*statusRecorder); ok {
		return rw
	}
	return &statusRecorder{ResponseWriter: w}
}

// unused guard.
var _ = strconv.Itoa
var _ = gobreaker.StateClosed
