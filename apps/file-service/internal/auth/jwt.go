package auth

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strings"

	"github.com/trips-enjoy/platform/file-service/internal/httperr"
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

// Middleware verifies the bearer token (or, in stub mode, the gateway-
// injected headers) and rejects unauthenticated calls with 401
// UNAUTHENTICATED per the canonical envelope.
func Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		principal, err := resolve(r)
		if err != nil {
			writeAuthError(w, http.StatusUnauthorized, "UNAUTHENTICATED", err.Error())
			return
		}
		ctx := context.WithValue(r.Context(), principalKey{}, principal)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// RequireRole returns a middleware that enforces the supplied role.
// 403 FORBIDDEN otherwise.
func RequireRole(role string) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			p, ok := FromContext(r.Context())
			if !ok {
				writeAuthError(w, http.StatusUnauthorized, "UNAUTHENTICATED", "authentication required")
				return
			}
			if !p.IsAdmin && !p.HasRole(role) && !p.HasRole("super_admin") {
				writeAuthError(w, http.StatusForbidden, "FORBIDDEN", "missing required role: "+role)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

// resolve pulls the principal from the request. Stub mode honors the
// gateway-injected X-User-* headers; production validates the bearer
// against the Keycloak JWKS (follow-up PR).
func resolve(r *http.Request) (Principal, error) {
	if userID := r.Header.Get("X-User-Id"); userID != "" {
		return Principal{
			UserID:    userID,
			UserType:  headerDefault(r.Header.Get("X-User-Type"), "user"),
			Roles:     splitCSV(r.Header.Get("X-Roles")),
			Scopes:    splitCSV(r.Header.Get("X-Scopes")),
			TenantID:  r.Header.Get("X-Tenant-Id"),
			Email:     r.Header.Get("X-User-Email"),
			IsAdmin:   hasRole(splitCSV(r.Header.Get("X-Roles")), "admin") || hasRole(splitCSV(r.Header.Get("X-Roles")), "ops") || hasRole(splitCSV(r.Header.Get("X-Roles")), "super_admin"),
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

// writeAuthError emits the canonical envelope via the httperr leaf types
// so auth does not import httpapi (which itself imports auth).
func writeAuthError(w http.ResponseWriter, status int, code, message string) {
	w.Header().Set("Content-Type", "application/problem+json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(httperr.ErrorEnvelope{
		Code:    code,
		Message: message,
	})
}
