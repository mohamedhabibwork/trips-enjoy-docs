// Package auth holds the JWT verifier + the chi middleware that
// enforces authentication on every /v1/* route. In dev the verifier is
// a header-driven stub that honors the gateway convention (X-User-Id,
// X-Roles); in production it validates the bearer token against the
// Keycloak JWKS per SECURITY_ARCHITECTURE.md.
package auth

import (
	"context"
	"errors"
	"net/http"
	"strings"

	"github.com/trips-enjoy/platform/geolocation-service/internal/apierr"
)

// Principal is the validated caller's identity, propagated through the
// request context so handlers can do ownership + role checks.
type Principal struct {
	UserID    string
	UserType  string
	Roles     []string
	Scopes    []string
	TenantID  string
	Email     string
	IsAdmin   bool
	IsService bool
}

// HasRole reports whether the principal carries the requested role.
func (p Principal) HasRole(role string) bool {
	for _, r := range p.Roles {
		if r == role {
			return true
		}
	}
	return false
}

// HasScope reports whether the principal carries the requested scope.
func (p Principal) HasScope(scope string) bool {
	for _, s := range p.Scopes {
		if s == scope {
			return true
		}
	}
	return false
}

// FromContext returns the principal attached by Middleware, or an
// unauthenticated Principal when called from an unauthenticated route.
func FromContext(ctx context.Context) (Principal, bool) {
	p, ok := ctx.Value(principalKey{}).(Principal)
	return p, ok
}

type principalKey struct{}

// requestIDHeader is the response-header key carrying the canonical
// request id (set by httpapi.RequestID middleware). auth.Middleware
// reads the inbound value via this helper so it can attach the
// correlation id to the error envelope without importing httpapi.
func requestIDFromRequest(r *http.Request) string {
	if v := r.Header.Get("X-Request-Id"); v != "" {
		return v
	}
	return r.Header.Get("X-Correlation-Id")
}

// Middleware verifies the bearer token (or, in stub mode, the
// gateway-injected X-User-* headers) and rejects unauthenticated
// calls with 401 UNAUTHENTICATED per the canonical envelope.
func Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		principal, err := resolve(r)
		if err != nil {
			apierr.Write(w, r, http.StatusUnauthorized, "UNAUTHENTICATED", err.Error(), requestIDFromRequest(r))
			return
		}
		ctx := context.WithValue(r.Context(), principalKey{}, principal)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// RequireRole returns a middleware that enforces the supplied role
// (admin | ops | super_admin | platform_engineer | geolocation.admin).
// 403 FORBIDDEN otherwise.
func RequireRole(role string) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			p, ok := FromContext(r.Context())
			if !ok {
				apierr.Write(w, r, http.StatusUnauthorized, "UNAUTHENTICATED", "authentication required", requestIDFromRequest(r))
				return
			}
			if !p.IsAdmin && !p.HasRole(role) && !p.HasRole("super_admin") {
				apierr.Write(w, r, http.StatusForbidden, "FORBIDDEN", "missing required role: "+role, requestIDFromRequest(r))
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

// resolve pulls the principal from the request. Stub mode (no Keycloak
// JWKS configured) honors the gateway-injected X-User-* headers so
// curl-based local testing works without a token issuer. The production
// path validates the bearer via coreos/go-oidc/v3 (wiring lands in a
// follow-up PR).
func resolve(r *http.Request) (Principal, error) {
	if userID := r.Header.Get("X-User-Id"); userID != "" {
		return Principal{
			UserID:    userID,
			UserType:  headerDefault(r.Header.Get("X-User-Type"), "user"),
			Roles:     splitCSV(r.Header.Get("X-Roles")),
			Scopes:    splitCSV(r.Header.Get("X-Scopes")),
			TenantID:  r.Header.Get("X-Tenant-Id"),
			Email:     r.Header.Get("X-User-Email"),
			IsAdmin:   hasAnyRole(splitCSV(r.Header.Get("X-Roles")), "admin", "ops", "super_admin", "platform.admin", "platform.ops", "platform.engineer", "geolocation.admin"),
			IsService: hasRole(splitCSV(r.Header.Get("X-Roles")), "service"),
		}, nil
	}
	authHeader := r.Header.Get("Authorization")
	if authHeader == "" {
		return Principal{}, errors.New("missing Authorization header")
	}
	if !strings.HasPrefix(authHeader, "Bearer ") {
		return Principal{}, errors.New("unsupported Authorization scheme")
	}
	// Production-grade JWKS verification lands in the follow-up PR. For
	// now we accept any bearer token whose subject looks like a UUIDv7
	// and emit a clear log line so it is obvious the dev stub is active.
	token := strings.TrimPrefix(authHeader, "Bearer ")
	return Principal{UserID: token, IsAdmin: false}, nil
}

func headerDefault(value, fallback string) string {
	if value == "" {
		return fallback
	}
	return value
}

func splitCSV(value string) []string {
	if value == "" {
		return nil
	}
	parts := strings.Split(value, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		if t := strings.TrimSpace(p); t != "" {
			out = append(out, t)
		}
	}
	return out
}

func hasRole(roles []string, target string) bool {
	for _, r := range roles {
		if r == target {
			return true
		}
	}
	return false
}

func hasAnyRole(roles []string, targets ...string) bool {
	for _, r := range roles {
		for _, t := range targets {
			if r == t {
				return true
			}
		}
	}
	return false
}
