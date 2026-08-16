package httpapi

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"time"

	"github.com/trips-enjoy/platform/file-service/internal/filedto"
	"github.com/trips-enjoy/platform/file-service/internal/storage"
)

// MetricsSink is the slice of httpapi.Metrics the files aggregate +
// admin aggregate + cmd/server probe ticker call. *httpapi.Metrics
// satisfies this implicitly.
type MetricsSink interface {
	IncFilesUploaded(ownerType, mimeClass, uploadMethod, driverID string)
	IncFilesScanned(result string)
	ObserveSignedURL(driverID, result string, d time.Duration)
	SetDriverHealth(driverID string, healthy bool)
	SetDriverCircuitOpen(driverID string, open bool)
	ObserveDriver(driverID, operation, outcome string, d time.Duration, errClass string)
	IncMigrationVerifyFailed(fromDriverID, toDriverID string)
	SetOutboxState(oldestSeconds float64, pending int)
}

// FilesService is the slice of files.Service the router calls.
// *files.Service satisfies this implicitly.
type FilesService interface {
	InitiateUpload(ctx context.Context, req filedto.InitiateUploadRequest) (*filedto.InitiateUploadResponse, *filedto.File, error)
	InitiateUploadBatch(ctx context.Context, req filedto.BulkUploadRequest) (*filedto.BulkUploadResponse, error)
	ProxyUpload(ctx context.Context, id string, body io.Reader) (*filedto.File, error)
	CompleteUpload(ctx context.Context, id, sha string) (*filedto.File, error)
	GetMetadata(ctx context.Context, id string) (*filedto.File, error)
	IssueSignedURL(ctx context.Context, id string, ttl int, purpose string) (*filedto.SignedURLResponse, error)
	Download(ctx context.Context, id string) (io.ReadCloser, *filedto.File, error)
	SoftDelete(ctx context.Context, id, actor string) error
	GetScan(ctx context.Context, id string) (*filedto.ScanResult, error)
	GetDriverAssignment(ctx context.Context, id string) (*filedto.DriverAssignmentResponse, error)
	ListDriverHealth(ctx context.Context) []filedto.DriverStatus
}

// AdminService is the slice of admin.Service the router calls.
type AdminService interface {
	ListDrivers(w http.ResponseWriter, r *http.Request)
	PinDriver(w http.ResponseWriter, r *http.Request, driverID string)
	EnqueueMigration(w http.ResponseWriter, r *http.Request)
	GetMigration(w http.ResponseWriter, r *http.Request, id string)
	RunRetention(w http.ResponseWriter, r *http.Request)
}

// Deps is the dependency bundle passed to NewRouter.
type Deps struct {
	ServiceName  string
	Drivers      *storage.Registry
	DBPinger     DBPinger
	RedisPinger  RedisPinger
	FilesService FilesService
	AdminService AdminService
	Metrics      *Metrics // shared registry; nil → NewMetrics() in metricsFromDeps
}

// DBPinger is satisfied by *pgxpool.Pool.
type DBPinger interface {
	Ping(ctx context.Context) error
}

// RedisPinger mirrors DBPinger for go-redis.
type RedisPinger interface {
	Ping() error
}

// healthHandler is the liveness probe.
func healthHandler(service string) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(map[string]string{"status": "UP", "service": service})
	}
}

// readyHandler is the readiness probe.
func readyHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		checks := map[string]string{"db": "UP", "drivers": "UP"}
		status := http.StatusOK
		if deps.DBPinger != nil {
			ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
			err := deps.DBPinger.Ping(ctx)
			cancel()
			if err != nil {
				checks["db"] = "DOWN"
				status = http.StatusServiceUnavailable
			}
		}
		if deps.Drivers != nil {
			if !deps.Drivers.DefaultReachable(r.Context()) {
				checks["drivers"] = "DOWN"
				status = http.StatusServiceUnavailable
			}
		}
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"status": map[bool]string{true: "UP", false: "DEGRADED"}[status == http.StatusOK],
			"checks": checks,
		})
	}
}

// startedHandler is the startup probe.
func startedHandler(service string) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(map[string]string{"status": "UP", "service": service})
	}
}
