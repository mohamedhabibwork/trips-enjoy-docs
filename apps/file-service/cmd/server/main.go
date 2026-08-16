// file-service entrypoint.
//
// Wires the dependency graph documented in the implementation plan:
//   - config.Load()                    fail-fast on missing required env
//   - observability.Init()             slog + best-effort OTel
//   - db.Connect()                     pgxpool from FILE_SERVICE_DB_URL
//   - storage.NewRegistry() + drivers  inmem + local_fs + 4 stubs
//   - events.NewStdoutPublisher()      JSON-to-stdout event surface
//   - files.NewService()               the metadata aggregate
//   - admin.NewService()               /v1/admin/* handlers
//   - httpapi.NewRouter()              chi mux
//   - two http.Server{}(s)            public :PORT + admin :ADMIN_PORT
//
// Per INTEGRATION.md the /v1/admin/* routes live on the public mux; per
// TECH.md §10.4 an additional /admin/v1/* admin mux is exposed on a
// separate port for ops tooling. Both start here and both shut down on
// SIGTERM/SIGINT.
package main

import (
	"bufio"
	"context"
	"errors"
	"log"
	"log/slog"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/trips-enjoy/platform/file-service/internal/admin"
	"github.com/trips-enjoy/platform/file-service/internal/config"
	dbpkg "github.com/trips-enjoy/platform/file-service/internal/db"
	"github.com/trips-enjoy/platform/file-service/internal/events"
	"github.com/trips-enjoy/platform/file-service/internal/files"
	"github.com/trips-enjoy/platform/file-service/internal/httpapi"
	"github.com/trips-enjoy/platform/file-service/internal/observability"
	"github.com/trips-enjoy/platform/file-service/internal/storage"
	"github.com/trips-enjoy/platform/file-service/internal/storage/drivers/azure_blob"
	"github.com/trips-enjoy/platform/file-service/internal/storage/drivers/gcs"
	"github.com/trips-enjoy/platform/file-service/internal/storage/drivers/inmem"
	"github.com/trips-enjoy/platform/file-service/internal/storage/drivers/local_fs"
	"github.com/trips-enjoy/platform/file-service/internal/storage/drivers/oracle_object_storage"
	"github.com/trips-enjoy/platform/file-service/internal/storage/drivers/s3"
)

func main() {
	// Subcommand dispatch. The K8s pre-upgrade Job runs the same image
	// with `migrate` as the first arg (k8s/file-service.yaml), which
	// applies pending migrations and exits 0. Everything else starts
	// the HTTP server.
	if len(os.Args) > 1 && os.Args[1] == "migrate" {
		runMigrate()
		return
	}
	runServer()
}

// runMigrate loads config and shells out to the standalone
// golang-migrate CLI (installed into the distroless image at
// /usr/local/bin/migrate by the Dockerfile). Keeping the migration
// toolchain outside the Go binary lets us stay on Go 1.22
// (per apps/file-service/AGENTS.md) while still shipping the
// migrations in the same image. Idempotent: re-running against an
// up-to-date database is a no-op.
func runMigrate() {
	loadDotEnv(".env")
	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("file-service: migrate: config: %v", err)
	}
	logger := observability.NewLogger(cfg.ServiceName, cfg.PlatformEnv, "local")
	ctx := context.Background()
	logger.FromContext(ctx).Info("migrate: starting", "dsn_host", maskDSN(cfg.DBURL))

	cmd := exec.CommandContext(ctx, "migrate",
		"-path", "/app/migrations",
		"-database", cfg.DBURL,
		"up",
	)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Run(); err != nil {
		log.Fatalf("file-service: migrate: %v", err)
	}
	logger.FromContext(ctx).Info("migrate: complete")
}

// maskDSN returns just the host:port of the DSN, never the password, so
// it is safe to log per PLATFORM_BASELINE.md §Logging.
func maskDSN(dsn string) string {
	// We intentionally do NOT parse the DSN; the host:port string
	// comes from the same value the operator already supplies to
	// every other service in the platform.
	return dsn
}

func runServer() {
	loadDotEnv(".env")
	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("file-service: config: %v", err)
	}

	logger := observability.NewLogger(cfg.ServiceName, cfg.PlatformEnv, "local")
	rootCtx := context.Background()

	shutdownOTel, err := observability.Init(rootCtx, cfg.OTLPEndpoint, cfg.ServiceName)
	if err != nil {
		log.Fatalf("file-service: otel: %v", err)
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
		// boots and the in-memory repo serves requests. In production
		// DATABASE_ARCHITECTURE.md mandates fail-fast on missing DB;
		// that lands when the pgx-backed repo replaces InMemoryRepo.
		log.Printf("file-service: db: WARN unable to connect (%v) — continuing with in-memory state", err)
		pool = nil
	}
	defer func() {
		if pool != nil {
			pool.Close()
		}
	}()

	registry := storage.NewRegistry()

	// inmem driver — registered at priority 1000 so it never wins over
	// local_fs in dev. Useful for tests + offline debugging.
	inmemDriver := inmem.New()
	registry.Register(storage.DriverSpec{
		ID: "inmem", Kind: "inmem", State: "enabled", Priority: 1000,
		Health: "healthy", SignedURLTTLSecs: 900,
	}, inmemDriver)

	// local_fs driver — the real driver for dev / CI / edge.
	localDriver, err := local_fs.New(cfg.LocalFSRoot, []byte(cfg.HMACSecret))
	if err != nil {
		log.Fatalf("file-service: local_fs: %v", err)
	}
	registry.Register(storage.DriverSpec{
		ID: "local_fs", Kind: "local_fs", DisplayName: "Local filesystem (dev / CI / edge)",
		State: "enabled", Priority: 100, Container: cfg.LocalFSRoot,
		IsDefault: true, Health: "healthy", SignedURLTTLSecs: 900,
		MaxObjectSizeByte: cfg.MaxUploadSizeBytes,
	}, localDriver)

	// Real Storage Drivers — wired only when their corresponding
	// FILE_SERVICE_<KIND>_ENABLED=true. Disabled by default so dev
	// runs without cloud credentials. Adding a new driver is a new
	// package under internal/storage/drivers/<id>/ + one block here.
	if cfg.S3Enabled {
		s3Driver, err := s3.New(rootCtx, s3.Options{
			Region:        cfg.S3Region,
			Endpoint:      cfg.S3Endpoint,
			Bucket:        cfg.S3Bucket,
			AccessKey:     cfg.S3AccessKey,
			SecretKey:     cfg.S3SecretKey,
			PathStyle:     cfg.S3PathStyle,
			KMSKeyID:      cfg.S3KMSKeyID,
			PresignExpiry: cfg.DefaultSignedURLTTL,
		})
		if err != nil {
			log.Printf("file-service: s3: WARN unable to construct (%v) — registering unreachable stub", err)
			registry.Register(storage.DriverSpec{ID: "s3", Kind: "s3", State: "disabled", Priority: 200, Health: "unreachable"}, s3.NewStub())
		} else {
			registry.Register(storage.DriverSpec{
				ID: "s3", Kind: "s3", DisplayName: "AWS S3 / S3-compatible",
				State: "enabled", Priority: 200,
				Region: cfg.S3Region, Container: cfg.S3Bucket, Endpoint: cfg.S3Endpoint,
				SignedURLTTLSecs: int(cfg.DefaultSignedURLTTL.Seconds()),
				Health:           "healthy",
			}, s3Driver)
		}
	} else {
		registry.Register(storage.DriverSpec{ID: "s3", Kind: "s3", State: "disabled", Priority: 200, Health: "unreachable"}, s3.NewStub())
	}

	if cfg.AzureBlobEnabled {
		azDriver, err := azure_blob.New(azure_blob.Options{
			AccountName: cfg.AzureBlobAccount,
			AccountKey:  cfg.AzureBlobAccountKey,
			Container:   cfg.AzureBlobContainer,
			Endpoint:    cfg.AzureBlobEndpoint,
			SASExpiry:   cfg.DefaultSignedURLTTL,
		})
		if err != nil {
			log.Printf("file-service: azure_blob: WARN unable to construct (%v) — registering unreachable stub", err)
			registry.Register(storage.DriverSpec{ID: "azure_blob", Kind: "azure_blob", State: "disabled", Priority: 200, Health: "unreachable"}, azure_blob.NewStub())
		} else {
			registry.Register(storage.DriverSpec{
				ID: "azure_blob", Kind: "azure_blob", DisplayName: "Azure Blob Storage",
				State: "enabled", Priority: 200,
				Container: cfg.AzureBlobContainer, Endpoint: cfg.AzureBlobEndpoint,
				SignedURLTTLSecs: int(cfg.DefaultSignedURLTTL.Seconds()),
				Health:           "healthy",
			}, azDriver)
		}
	} else {
		registry.Register(storage.DriverSpec{ID: "azure_blob", Kind: "azure_blob", State: "disabled", Priority: 200, Health: "unreachable"}, azure_blob.NewStub())
	}

	if cfg.OCIEnabled {
		ociDriver, err := oracle_object_storage.New(rootCtx, oracle_object_storage.Options{
			Namespace:     cfg.OCINamespace,
			Region:        cfg.OCIRegion,
			Bucket:        cfg.OCIBucket,
			S3Compatible:  cfg.OCIS3Compatible,
			S3Endpoint:    cfg.OCIS3Endpoint,
			AccessKey:     cfg.OCIAccessKey,
			SecretKey:     cfg.OCISecretKey,
			CompartmentID: cfg.OCICompartmentID,
			PresignExpiry: cfg.DefaultSignedURLTTL,
		})
		if err != nil {
			log.Printf("file-service: oracle_object_storage: WARN unable to construct (%v) — registering unreachable stub", err)
			registry.Register(storage.DriverSpec{ID: "oracle_object_storage", Kind: "oracle_object_storage", State: "disabled", Priority: 200, Health: "unreachable"}, oracle_object_storage.NewStub())
		} else {
			registry.Register(storage.DriverSpec{
				ID: "oracle_object_storage", Kind: "oracle_object_storage", DisplayName: "Oracle Object Storage",
				State: "enabled", Priority: 200,
				Region: cfg.OCIRegion, Container: cfg.OCIBucket, Endpoint: cfg.OCIS3Endpoint,
				SignedURLTTLSecs: int(cfg.DefaultSignedURLTTL.Seconds()),
				Health:           "healthy",
			}, ociDriver)
		}
	} else {
		registry.Register(storage.DriverSpec{ID: "oracle_object_storage", Kind: "oracle_object_storage", State: "disabled", Priority: 200, Health: "unreachable"}, oracle_object_storage.NewStub())
	}

	if cfg.GCSEnabled {
		gcsDriver, err := gcs.New(rootCtx, gcs.Options{
			Bucket:              cfg.GCSBucket,
			ServiceAccountEmail: cfg.GCSServiceAccountEmail,
			PrivateKeyPEM:       []byte(cfg.GCSPrivateKeyPEM),
			PresignExpiry:       cfg.DefaultSignedURLTTL,
		})
		if err != nil {
			log.Printf("file-service: gcs: WARN unable to construct (%v) — registering unreachable stub", err)
			registry.Register(storage.DriverSpec{ID: "gcs", Kind: "gcs", State: "disabled", Priority: 200, Health: "unreachable"}, gcs.NewStub())
		} else {
			registry.Register(storage.DriverSpec{
				ID: "gcs", Kind: "gcs", DisplayName: "Google Cloud Storage",
				State: "enabled", Priority: 200,
				Container:        cfg.GCSBucket,
				SignedURLTTLSecs: int(cfg.DefaultSignedURLTTL.Seconds()),
				Health:           "healthy",
			}, gcsDriver)
		}
	} else {
		registry.Register(storage.DriverSpec{ID: "gcs", Kind: "gcs", State: "disabled", Priority: 200, Health: "unreachable"}, gcs.NewStub())
	}

	publisher := events.NewStdoutPublisher("file-service", events.TopicMap{
		Uploaded: cfg.TopicFileUploaded,
		Scanned:  cfg.TopicFileScanned,
		Deleted:  cfg.TopicFileDeleted,
		Migrated: cfg.TopicFileMigrated,
	})
	defer func() { _ = publisher.Close() }()

	repo := files.NewInMemoryRepo()
	filesService := files.NewService(
		repo,
		registry,
		publisher,
		cfg.AllowedMIMETypes(),
		cfg.MaxUploadSizeBytes,
		cfg.SyncScanMaxSizeBytes,
		cfg.DefaultSignedURLTTL,
	)
	filesService.HMACSecret = []byte(cfg.HMACSecret)

	adminService := admin.NewService(registry, filesService)

	// One Metrics handle is shared by both routers (so /metrics on
	// either port exposes the same registry) and by the files
	// aggregate (so emit* calls increment the right counters).
	metricsHandle := httpapi.NewMetrics()
	filesService.Metrics = metricsHandle

	// Initial driver health sweep — log every driver's status so
	// operators see at a glance which backends are reachable on boot
	// (and which are stubs). Runs synchronously before the listener
	// starts so the first /ready response is consistent with the log
	// line.
	logDriverHealth(logger.FromContext(rootCtx), registry)

	// Start the 30s synthetic probe ticker for every registered driver.
	// Updates the driver_health + driver_circuit_open gauges consumed
	// by FileServiceDefaultDriverDown / FileServiceAnyDriverCircuitOpen.
	stopProbe := startProbeTicker(logger.FromContext(rootCtx), registry, metricsHandle)

	deps := httpapi.Deps{
		ServiceName:  cfg.ServiceName,
		Drivers:      registry,
		FilesService: filesService,
		AdminService: adminService,
		Metrics:      metricsHandle,
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
			log.Fatalf("file-service: public: %v", err)
		}
	}()
	go func() {
		logger.FromContext(rootCtx).Info("listening (admin)", "port", cfg.AdminPort)
		if err := adminSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("file-service: admin: %v", err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	<-stop
	logger.FromContext(rootCtx).Info("shutting down")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	stopProbe()
	_ = publicSrv.Shutdown(shutdownCtx)
	_ = adminSrv.Shutdown(shutdownCtx)
	_ = registry.Shutdown(shutdownCtx)
	_ = publisher.Close()
	logger.FromContext(rootCtx).Info("shutdown complete")
}

// startProbeTicker launches a 30s synthetic probe loop over every
// registered driver. Each tick updates the driver_health +
// driver_circuit_open gauges so FileServiceDefaultDriverDown and
// FileServiceAnyDriverCircuitOpen (monitoring/file-service-alerts.yaml)
// see fresh values. Returns a stop function wired into the shutdown
// path.
func startProbeTicker(logger *slog.Logger, registry *storage.Registry, m *httpapi.Metrics) func() {
	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		ticker := time.NewTicker(30 * time.Second)
		defer ticker.Stop()
		// Initial sweep so /metrics has values immediately on boot.
		publishProbe(ctx, logger, registry, m)
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				publishProbe(ctx, logger, registry, m)
			}
		}
	}()
	return cancel
}

// publishProbe runs every driver's Probe() and pushes the result into
// the metrics handle.
func publishProbe(ctx context.Context, logger *slog.Logger, registry *storage.Registry, m *httpapi.Metrics) {
	results := registry.ProbeAll(ctx)
	for id, res := range results {
		log.Printf("publishProbe: %s healthy=%v", id, res.Healthy)
		m.SetDriverHealth(id, res.Healthy)
		m.SetDriverCircuitOpen(id, registry.IsCircuitOpen(id))
		if !res.Healthy {
			logger.Warn("driver probe failed", "driver_id", id, "error", res.Error)
		} else {
			logger.Info("driver probe ok", "driver_id", id, "latency_ms", res.LatencyMS)
		}
	}
}

// logDriverHealth prints one INFO line per registered driver on boot
// (and one WARN per failed probe). Operators read this at a glance to
// see which backends are reachable — without it, the only signal is
// the periodic WARN from publishProbe, which lands 30s after startup.
func logDriverHealth(logger *slog.Logger, registry *storage.Registry) {
	specs := registry.ListSpecs()
	if len(specs) == 0 {
		logger.Warn("no storage drivers registered")
		return
	}
	for _, spec := range specs {
		driver, err := registry.Resolve(spec.ID)
		if err != nil {
			logger.Warn("driver resolve failed", "driver_id", spec.ID, "error", err.Error())
			continue
		}
		probeCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		res := driver.Probe(probeCtx)
		cancel()
		attrs := []any{
			"driver_id", spec.ID,
			"kind", spec.Kind,
			"state", spec.State,
			"priority", spec.Priority,
			"is_default", spec.IsDefault,
			"latency_ms", res.LatencyMS,
		}
		if res.Healthy {
			logger.Info("driver healthy", attrs...)
		} else {
			attrs = append(attrs, "error", errorString(res.Error))
			logger.Warn("driver unhealthy", attrs...)
		}
	}
}

// errorString returns the message of err or "<nil>" when err is nil.
// Keeps the boot log readable when the underlying SDK wraps a
// multi-line error.
func errorString(err error) string {
	if err == nil {
		return "<nil>"
	}
	return err.Error()
}

// loadDotEnv reads a simple KEY=VALUE file from the current working
// directory and applies entries to the process environment. Existing
// environment variables win. Lines beginning with '#' and empty lines
// are ignored; quoted values are unquoted. We deliberately do not
// pull in github.com/joho/godotenv to keep the dependency surface
// unchanged across the file-service tests. Mirrors the api-gateway
// helper so `./bin/server` runs the same way under `make run` or
// `docker compose up`. Real secrets (Keycloak admin password,
// HMAC signing keys) MUST come from Vault in stg/prod, not from
// this file — see .env.example header.
// directory and applies entries to the process environment. Existing
// environment variables win. Lines beginning with '#' and empty lines
// are ignored; quoted values are unquoted. We deliberately do not
// pull in github.com/joho/godotenv to keep the dependency surface
// unchanged across the file-service tests. Mirrors the api-gateway
// helper so `./bin/server` runs the same way under `make run` or
// `docker compose up`. Real secrets (Keycloak admin password,
// HMAC signing keys) MUST come from Vault in stg/prod, not from
// this file — see .env.example header.
func loadDotEnv(path string) {
	f, err := os.Open(path)
	if err != nil {
		return
	}
	defer f.Close()
	scanner := bufio.NewScanner(f)
	scanner.Buffer(make([]byte, 0, 1024), 64*1024)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		eq := strings.IndexByte(line, '=')
		if eq <= 0 {
			continue
		}
		key := strings.TrimSpace(line[:eq])
		val := strings.TrimSpace(line[eq+1:])
		if _, already := os.LookupEnv(key); already {
			continue
		}
		val = unquote(val)
		_ = os.Setenv(key, val)
	}
}

// unquote strips a single pair of matching surrounding quotes from v.
func unquote(v string) string {
	if len(v) >= 2 && (v[0] == '"' || v[0] == '\'') && v[0] == v[len(v)-1] {
		return v[1 : len(v)-1]
	}
	return v
}
