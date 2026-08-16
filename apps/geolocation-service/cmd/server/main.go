// geolocation-service entrypoint.
//
// Wires the dependency graph documented in the implementation plan:
//   - config.Load()                    fail-fast on missing required env
//   - observability.NewLogger() + Init slog + best-effort OTel
//   - db.Connect()                     pgxpool from GEOLOCATION_SERVICE_DB_URL
//   - provider.Registry                mock + 8 stubs
//   - chain.CircuitBreakers + RateLimit seeded from provider_config
//   - chain.Resolver                   per-region chain lookup
//   - events.NewStdoutPublisher        JSON-to-stdout event surface
//   - geocoding.NewService()           the read-path aggregate
//   - zones.NewLookup + Invalidator    last-known city + zone-update handler
//   - admin.NewService()               /v1/admin/* handlers
//   - httpapi.NewRouter()              public chi mux
//   - httpapi.NewAdminRouter()         /admin/v1/* admin mux
//   - two http.Server{}                public :PORT + admin :ADMIN_PORT
//
// Per INTEGRATION.md the /v1/admin/* routes live on the public mux; per
// TECH.md §10.4 the additional /admin/v1/* admin mux is exposed on a
// separate port for ops tooling. Both start here and both shut down on
// SIGTERM/SIGINT.
//
// Subcommand dispatch: when invoked with `migrate` as the first arg,
// the binary applies pending migrations via the standalone
// golang-migrate CLI (installed at /usr/local/bin/migrate by the
// Dockerfile) and exits. This is the contract used by the k8s
// pre-upgrade Job (see k8s/geolocation-service.yaml).
package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/trips-enjoy/platform/geolocation-service/internal/admin"
	"github.com/trips-enjoy/platform/geolocation-service/internal/chain"
	"github.com/trips-enjoy/platform/geolocation-service/internal/config"
	dbpkg "github.com/trips-enjoy/platform/geolocation-service/internal/db"
	"github.com/trips-enjoy/platform/geolocation-service/internal/events"
	"github.com/trips-enjoy/platform/geolocation-service/internal/geocoding"
	"github.com/trips-enjoy/platform/geolocation-service/internal/httpapi"
	"github.com/trips-enjoy/platform/geolocation-service/internal/observability"
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider"
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider/google"
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider/here"
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider/mapbox"
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider/mock"
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider/nominatim"
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider/osrm"
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider/pelias"
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider/photon"
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider/valhalla"
	"github.com/trips-enjoy/platform/geolocation-service/internal/zones"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "migrate" {
		runMigrate()
		return
	}
	runServer()
}

// loadDotEnv reads KEY=VALUE pairs from .env next to the working
// directory and seeds os.Environ for any key not already set. Stdlib
// only — no third-party dependency. Values are unquoted POSIX-style.
// Mirrors the api-gateway convention so `./bin/server` works without
// `make run` or an explicit env file export. Path collision is fine:
// the file is opt-in via .gitignore.
func loadDotEnv(path string) {
	data, err := os.ReadFile(path)
	if err != nil {
		return
	}
	for _, line := range strings.Split(string(data), "\n") {
		s := strings.TrimSpace(line)
		if s == "" || strings.HasPrefix(s, "#") {
			continue
		}
		eq := strings.IndexByte(s, '=')
		if eq <= 0 {
			continue
		}
		key := strings.TrimSpace(s[:eq])
		val := strings.TrimSpace(s[eq+1:])
		val = strings.Trim(val, `"'`)
		if _, exists := os.LookupEnv(key); !exists {
			_ = os.Setenv(key, val)
		}
	}
}

// runMigrate loads config and shells out to the standalone
// golang-migrate CLI (installed into the distroless image at
// /usr/local/bin/migrate by the Dockerfile). Keeping the migration
// toolchain outside the Go binary lets us stay on Go 1.22 while still
// shipping the migrations in the same image. Idempotent: re-running
// against an up-to-date database is a no-op.
func runMigrate() {
	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("geolocation-service: migrate: config: %v", err)
	}
	logger := observability.NewLogger(cfg.ServiceName, cfg.PlatformEnv, "local")
	ctx := context.Background()
	logger.FromContext(ctx).Info("migrate: starting", "dsn_host", cfg.DBURL)

	cmd := exec.CommandContext(ctx, "migrate",
		"-path", "/app/migrations",
		"-database", cfg.DBURL,
		"up",
	)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Run(); err != nil {
		log.Fatalf("geolocation-service: migrate: %v", err)
	}
	logger.FromContext(ctx).Info("migrate: complete")
}

func runServer() {
	loadDotEnv(".env")
	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("geolocation-service: config: %v", err)
	}

	logger := observability.NewLogger(cfg.ServiceName, cfg.PlatformEnv, "local")
	rootCtx := context.Background()

	shutdownOTel, err := observability.Init(rootCtx, cfg.OTLPEndpoint, cfg.ServiceName)
	if err != nil {
		log.Fatalf("geolocation-service: otel: %v", err)
	}
	defer func() {
		_ = shutdownOTel(rootCtx)
	}()

	pool, err := dbpkg.Connect(rootCtx, dbpkg.Config{
		URL:      cfg.DBURL,
		Username: cfg.DBUsername,
		Password: cfg.DBPassword,
	})
	if err != nil {
		// Database is best-effort in dev: log WARN so the binary still
		// boots and the in-memory cache + mock provider serve requests.
		// In production DATABASE_ARCHITECTURE.md mandates fail-fast on
		// missing DB; that lands when the pgx-backed repo replaces the
		// in-memory cache (follow-up PR).
		log.Printf("geolocation-service: db: WARN unable to connect (%v) — continuing with in-memory cache", err)
		pool = nil
	}
	defer func() {
		if pool != nil {
			pool.Close()
		}
	}()

	// --- provider registry (mock + 8 stubs per INTEGRATION.md §4.2) ---
	registry := provider.NewRegistry()

	mockAdapter := mock.New()
	registry.Register(mockAdapter, mock.Config())

	registry.Register(google.New(cfg.GoogleMapsAPIKey), google.Config())
	registry.Register(mapbox.New(""), mapbox.Config())
	registry.Register(here.New(cfg.HereAPIKey, ""), here.Config())
	registry.Register(osrm.New("", nil), osrm.Config())
	registry.Register(valhalla.New("", nil), valhalla.Config())
	registry.Register(nominatim.New(""), nominatim.Config())
	registry.Register(pelias.New("", ""), pelias.Config())
	registry.Register(photon.New(""), photon.Config())
	defer func() { _ = registry.Close() }()

	// --- chain resolver: breakers + rate limits seeded from provider_config ---
	breakers := chain.NewCircuitBreakers()
	limiters := chain.NewRateLimiter()
	for _, c := range registry.ListConfigs() {
		breakers.Register(c.VendorID, c.FailureThreshold, c.CooldownSeconds, c.HalfOpenProbeCount)
		limiters.Register(c.VendorID, c.QPSLimit, c.BurstLimit)
	}

	resolver := chain.NewResolver(registry, breakers, limiters, logger.FromContext(rootCtx))
	// Seed the default chain = [mock] for every capability (per
	// README.md §4.4 — local dev / CI runs against the mock).
	for _, cap := range provider.AllCapabilities {
		_ = resolver.SetRoute(chain.RegionRoute{
			Region:     "default",
			Capability: cap,
			Chain:      []string{mock.VendorID},
			Enabled:    true,
		})
	}

	// --- event publisher (stdout dev, kafka wired behind env) ---
	publisher := events.NewStdoutPublisher(cfg.ServiceName, events.TopicMap{
		Geocoded:         cfg.TopicGeocoded,
		EtaComputed:      cfg.TopicEtaComputed,
		CacheInvalidated: cfg.TopicCacheInvalidated,
		ProviderChain:    cfg.TopicProviderChain,
		ProviderHealth:   cfg.TopicProviderHealth,
	})
	defer func() { _ = publisher.Close() }()

	// --- geocoding aggregate ---
	cache := geocoding.NewCache(time.Now)
	geocodingSvc := geocoding.NewService(
		resolver,
		cache,
		publisher,
		logger.FromContext(rootCtx),
		cfg.GeocodeTTL,
		cfg.EtaTTL,
		cfg.RouteTTL,
	)

	// --- zones (last-known city + invalidator) ---
	zonesLookup := zones.NewLookup()
	// Seed the eight canonical cities from the mock fixtures so the
	// /v1/cities/lookup endpoint returns plausible data in dev.
	zonesLookup.Add(zones.City{Name: "London", CountryCode: "GB", Timezone: "Europe/London", Centroid: zones.Coordinate{Lat: 51.5074, Lon: -0.1278}})
	zonesLookup.Add(zones.City{Name: "Paris", CountryCode: "FR", Timezone: "Europe/Paris", Centroid: zones.Coordinate{Lat: 48.8566, Lon: 2.3522}})
	zonesLookup.Add(zones.City{Name: "Berlin", CountryCode: "DE", Timezone: "Europe/Berlin", Centroid: zones.Coordinate{Lat: 52.5200, Lon: 13.4050}})
	zonesLookup.Add(zones.City{Name: "Dublin", CountryCode: "IE", Timezone: "Europe/Dublin", Centroid: zones.Coordinate{Lat: 53.3498, Lon: -6.2603}})
	zonesLookup.Add(zones.City{Name: "New York", CountryCode: "US", Timezone: "America/New_York", Centroid: zones.Coordinate{Lat: 40.7128, Lon: -74.0060}})
	zonesLookup.Add(zones.City{Name: "San Francisco", CountryCode: "US", Timezone: "America/Los_Angeles", Centroid: zones.Coordinate{Lat: 37.7749, Lon: -122.4194}})
	zonesLookup.Add(zones.City{Name: "Austin", CountryCode: "US", Timezone: "America/Chicago", Centroid: zones.Coordinate{Lat: 30.2672, Lon: -97.7431}})
	zonesLookup.Add(zones.City{Name: "Toronto", CountryCode: "CA", Timezone: "America/Toronto", Centroid: zones.Coordinate{Lat: 43.6532, Lon: -79.3832}})

	// --- admin service ---
	adminSvc := admin.NewService(resolver, registry, cache, publisher, logger.FromContext(rootCtx))

	// --- HTTP layers ---
	deps := httpapi.Deps{
		ServiceName: cfg.ServiceName,
		Geocoding:   geocodingSvc,
		Zones:       zonesLookup,
		Admin:       adminSvc,
		Resolver:    resolver,
		Logger:      logger,
		HMACSecret:  []byte(cfg.HMACSecret),
	}
	if pool != nil {
		deps.DBPinger = pool
	}

	publicHandler := httpapi.NewRouter(deps)
	adminHandler := httpapi.NewAdminRouter(deps)

	publicSrv := &http.Server{
		Addr:              ":" + cfg.PublicPort,
		Handler:           publicHandler,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	adminSrv := &http.Server{
		Addr:              ":" + cfg.AdminPort,
		Handler:           adminHandler,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      10 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	go func() {
		logger.FromContext(rootCtx).Info("listening", "port", cfg.PublicPort)
		if err := publicSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("geolocation-service: public: %v", err)
		}
	}()
	go func() {
		logger.FromContext(rootCtx).Info("listening (admin)", "port", cfg.AdminPort)
		if err := adminSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("geolocation-service: admin: %v", err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	<-stop
	logger.FromContext(rootCtx).Info("shutting down")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = publicSrv.Shutdown(shutdownCtx)
	_ = adminSrv.Shutdown(shutdownCtx)
	_ = registry.Close()
	_ = publisher.Close()
	logger.FromContext(rootCtx).Info("shutdown complete")
}
