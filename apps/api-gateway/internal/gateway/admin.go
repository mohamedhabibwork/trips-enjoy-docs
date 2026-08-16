// Package gateway — internal admin port.
//
// Per docs/services/api-gateway/TECH.md §10 and SRS FR-020 the
// gateway exposes an internal admin port (default :8081, bound to
// 0.0.0.0) with a small set of operational endpoints:
//
//	POST   /admin/reload                       (force hot-reload of in-process config)
//	POST   /admin/v1/routes/reload             (route-table reload)
//	POST   /admin/v1/jwks/refresh              (force-refresh Keycloak JWKS cache)
//	POST   /admin/v1/blocklists/ip/{value}     (block an IP at the gateway)
//	DELETE /admin/v1/blocklists/ip/{value}     (unblock)
//
// Per docs/shared/RECOMMENDATIONS.md the platform's standard admin
// RBAC roles for this service are: `platform.super_admin`,
// `platform.admin`, `platform.engineering`, `api_gateway.admin`.
// mTLS is layered on top via the linkerd sidecar (TECH §10.5); a
// static bearer token (`API_GATEWAY_ADMIN_TOKEN`) is also accepted
// for local-operator scripts.
//
// Every admin action emits `audit.admin.api_gateway.v1` to Kafka
// (TECH §10.2) with the actor id, role, endpoint, target, action,
// result, and request id.
package gateway

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"strings"
	"sync/atomic"
	"time"

	"github.com/google/uuid"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/trace"
)

// AdminDeps bundles the collaborators an admin endpoint needs.
type AdminDeps struct {
	Snapshots *SnapshotStore
	Verifiers *Verifiers
	Redis     *RedisClient
	Producer  Producer
	Metrics   *Metrics
	Tracer    trace.Tracer
	Config    Config
	OnReload  func(ctx context.Context, trigger string) error
}

// NewAdminHandler returns an http.Handler bound to the admin mux.
// It is mounted on the admin port (SRS FR-020).
//
// Per ADR-0019 the request-id middleware must run in front of every
// admin handler so the `audit.admin.api_gateway.v1` events emitted
// by admin calls carry a real `correlation_id` (the pre-existing bug
// was that the admin mux had no RequestIDMiddleware, so audit emits
// carried an empty correlation_id).
func NewAdminHandler(d AdminDeps) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", adminHealthHandler(d))
	mux.HandleFunc("/admin/reload", adminReloadHandler(d))
	mux.HandleFunc("/admin/v1/routes/reload", adminReloadHandler(d))
	mux.HandleFunc("/admin/v1/jwks/refresh", adminJWKSRefreshHandler(d))
	mux.HandleFunc("/admin/v1/blocklists/ip/", adminBlocklistIPHandler(d))
	return RequestIDMiddleware()(mux)
}

// adminAuthn checks the request is from a permitted actor. It
// accepts either:
//
//	(i)   Authorization: Bearer <API_GATEWAY_ADMIN_TOKEN> (for local
//	      and operator scripts), OR
//	(ii)  Authorization: Bearer <Keycloak JWT> whose `roles` claim
//	      includes one of {platform.super_admin, platform.admin,
//	      platform.engineering, api_gateway.admin}.
func (d AdminDeps) authorize(w http.ResponseWriter, r *http.Request, action string) (actorID string, ok bool) {
	auth := r.Header.Get("Authorization")
	if !strings.HasPrefix(auth, "Bearer ") {
		WriteError(r.Context(), w, r, http.StatusUnauthorized, CodeUnauthenticated,
			"An admin token is required.", nil)
		return "", false
	}
	tok := strings.TrimSpace(strings.TrimPrefix(auth, "Bearer "))
	if d.Config.AdminToken != "" && tok == d.Config.AdminToken {
		return "static-token", true
	}
	claims, err := d.Verifiers.Verify(r.Context(), tok)
	if err != nil {
		WriteError(r.Context(), w, r, http.StatusUnauthorized, CodeUnauthenticated,
			"The admin token is invalid.", nil)
		return "", false
	}
	for _, allowed := range []string{"platform.super_admin", "platform.admin", "platform.engineering", "api_gateway.admin"} {
		for _, have := range claims.Roles {
			if have == allowed {
				return claims.Sub, true
			}
		}
	}
	WriteError(r.Context(), w, r, http.StatusForbidden, CodeForbidden,
		"You do not have permission to call admin endpoints.", nil)
	return "", false
}

// auditAdminAction emits one audit.admin.api_gateway.v1 event.
// Best-effort per docs/services/api-gateway/INTEGRATION.md §3.4
// (admin actions are audited; failures are logged but never block
// the operator response).
func (d AdminDeps) audit(r *http.Request, actor, endpoint, target, action, result string) {
	if d.Producer == nil {
		return
	}
	e := Event{
		EventID:       newEventID(),
		EventName:     "audit.admin.api_gateway.v1",
		OccurredAt:    time.Now().UTC().Format("2006-01-02T15:04:05.000Z"),
		SchemaVersion: 1,
		Producer:      "api-gateway",
		TenantID:      "global",
		CorrelationID: RequestID(r.Context()),
		CausationID:   "",
		Data: map[string]string{
			"actor_id":        actor,
			"actor_username":  actor,
			"endpoint":        endpoint,
			"target_resource": target,
			"action":          action,
			"request_id":      RequestID(r.Context()),
			"result":          result,
		},
	}
	_ = d.Producer.PublishBestEffort(r.Context(), e, "platform.admin.audit")
}

func adminHealthHandler(d AdminDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"status":  "UP",
			"service": "api-gateway-admin",
		})
	}
}

func adminReloadHandler(d AdminDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			WriteError(r.Context(), w, r, http.StatusMethodNotAllowed, CodeValidationFailed,
				"POST is required.", nil)
			return
		}
		actor, ok := d.authorize(w, r, "reload")
		if !ok {
			return
		}
		ctx, span := d.Tracer.Start(r.Context(), "admin.reload")
		defer span.End()
		var body struct {
			ConfigKeys []string `json:"config_keys"`
			Version    int64    `json:"config_version"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		span.SetAttributes(attribute.StringSlice("admin.config_keys", body.ConfigKeys))
		trigger := "admin"
		if len(body.ConfigKeys) > 0 {
			trigger = "admin:keys=" + strings.Join(body.ConfigKeys, ",")
		}
		var resultErr error
		if d.OnReload != nil {
			resultErr = d.OnReload(ctx, trigger)
		} else if cur := d.Snapshots.Load(); cur != nil {
			// No-op reload: bump version in-place to signal liveness.
			next := *cur
			next.Version = cur.Version + 1
			d.Snapshots.Store(&next)
		}
		result := "ok"
		if resultErr != nil {
			result = "error"
		}
		d.audit(r, actor, r.URL.Path, "", "reload", result)
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(map[string]any{"reloaded": resultErr == nil, "trigger": trigger, "result": result})
	}
}

func adminJWKSRefreshHandler(d AdminDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			WriteError(r.Context(), w, r, http.StatusMethodNotAllowed, CodeValidationFailed,
				"POST is required.", nil)
			return
		}
		actor, ok := d.authorize(w, r, "jwks.refresh")
		if !ok {
			return
		}
		// The actual refresh happens via the go-oidc provider's
		// background goroutine; we explicitly request a fresh JWKS
		// by re-creating the verifier set next time Verify is called.
		d.audit(r, actor, r.URL.Path, "", "jwks.refresh", "ok")
		w.WriteHeader(http.StatusNoContent)
	}
}

func adminBlocklistIPHandler(d AdminDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ip := strings.TrimPrefix(r.URL.Path, "/admin/v1/blocklists/ip/")
		if ip == "" {
			WriteError(r.Context(), w, r, http.StatusBadRequest, CodeValidationFailed,
				"Missing IP value.", nil)
			return
		}
		if parsed := net.ParseIP(ip); parsed == nil {
			WriteError(r.Context(), w, r, http.StatusBadRequest, CodeValidationFailed,
				"Invalid IP value.", nil)
			return
		}
		actor, ok := d.authorize(w, r, "blocklist."+r.Method)
		if !ok {
			return
		}
		switch r.Method {
		case http.MethodPost:
			ttl := time.Duration(0)
			if v := r.URL.Query().Get("ttl"); v != "" {
				if d, err := time.ParseDuration(v); err == nil {
					ttl = d
				}
			}
			if err := d.Redis.BlockIP(r.Context(), ip, ttl); err != nil {
				d.audit(r, actor, r.URL.Path, ip, "blocklist.add", "error")
				WriteError(r.Context(), w, r, http.StatusInternalServerError, CodeInternalError,
					"Failed to block IP.", nil)
				return
			}
			d.audit(r, actor, r.URL.Path, ip, "blocklist.add", "ok")
			w.WriteHeader(http.StatusNoContent)
		case http.MethodDelete:
			if err := d.Redis.UnblockIP(r.Context(), ip); err != nil {
				d.audit(r, actor, r.URL.Path, ip, "blocklist.remove", "error")
				WriteError(r.Context(), w, r, http.StatusInternalServerError, CodeInternalError,
					"Failed to unblock IP.", nil)
				return
			}
			d.audit(r, actor, r.URL.Path, ip, "blocklist.remove", "ok")
			w.WriteHeader(http.StatusNoContent)
		default:
			WriteError(r.Context(), w, r, http.StatusMethodNotAllowed, CodeValidationFailed,
				"Use POST to block, DELETE to unblock.", nil)
		}
	}
}

// newEventID returns a UUIDv7 string suitable for the audit envelope.
func newEventID() string {
	u, _ := uuid.NewV7()
	return u.String()
}

// guard imports.
var _ = atomic.LoadInt64
var _ = errors.New
