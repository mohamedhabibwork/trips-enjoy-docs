// Package httpapi hosts the geolocation-service HTTP plumbing:
// middleware, error envelope, per-route metrics, and the chi router.
// It mirrors apps/file-service/internal/httpapi/ verbatim so every Go
// service on the platform produces the same X-Request-Id /
// X-Correlation-Id behavior (per ADR-0019).
package httpapi

import (
	"context"

	"github.com/trips-enjoy/platform/geolocation-service/internal/admin"
	"github.com/trips-enjoy/platform/geolocation-service/internal/chain"
	"github.com/trips-enjoy/platform/geolocation-service/internal/geocoding"
	"github.com/trips-enjoy/platform/geolocation-service/internal/observability"
	"github.com/trips-enjoy/platform/geolocation-service/internal/zones"
)

// Deps is the single dependency bundle passed to NewRouter +
// NewAdminRouter. Every field is populated by main.go so the routers
// can be wired without panicking at construction time.
//
// Defined ONCE here (in deps.go) to avoid the file-service import-cycle
// trap where Deps was duplicated across router.go and health.go.
type Deps struct {
	ServiceName string
	DBPinger    DBPinger
	RedisPinger RedisPinger
	Geocoding   *geocoding.Service
	Zones       *zones.Lookup
	Admin       *admin.Service
	Resolver    *chain.Resolver
	Logger      *observability.Logger
	HMACSecret  []byte
}

// DBPinger is satisfied by *pgxpool.Pool. It returns nil when the
// database is reachable, a non-nil error otherwise. The interface
// mirrors pgx's own signature (Ping takes a context) so production
// wiring works without an adapter. The interface keeps this package
// independent of pgx (so it can be stubbed in tests).
type DBPinger interface {
	Ping(ctx context.Context) error
}

// RedisPinger mirrors DBPinger for go-redis. Same rationale.
type RedisPinger interface {
	Ping(ctx context.Context) error
}
