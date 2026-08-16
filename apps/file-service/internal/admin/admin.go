package admin

import (
	"encoding/json"
	"net/http"
	"sync"
	"time"

	"github.com/trips-enjoy/platform/file-service/internal/db"
	"github.com/trips-enjoy/platform/file-service/internal/files"
	"github.com/trips-enjoy/platform/file-service/internal/httperr"
	"github.com/trips-enjoy/platform/file-service/internal/storage"
)

// Service is the admin surface.
type Service struct {
	Drivers *storage.Registry
	Files   *files.Service

	mu            sync.Mutex
	migrations    map[string]*MigrationRow
	driverHistory []DriverHistoryRow
}

// NewService returns an admin Service.
func NewService(drivers *storage.Registry, fs *files.Service) *Service {
	return &Service{
		Drivers:       drivers,
		Files:         fs,
		migrations:    map[string]*MigrationRow{},
		driverHistory: []DriverHistoryRow{},
	}
}

// MigrationRow mirrors migrations tracked by the file-service migration ledger.
type MigrationRow struct {
	ID               string    `json:"migration_id"`
	FromDriverID     string    `json:"from_driver_id"`
	ToDriverID       string    `json:"to_driver_id"`
	State            string    `json:"state"`
	FilesCompleted   int       `json:"files_completed"`
	FilesPending     int       `json:"files_pending"`
	FilesFailed      int       `json:"files_failed"`
	VerifyFailed     int       `json:"files_verify_failed"`
	BytesTransferred int64     `json:"bytes_transferred"`
	StartedAt        time.Time `json:"started_at"`
	LastProgressAt   time.Time `json:"last_progress_at"`
}

// DriverHistoryRow mirrors a single driver_history audit row.
type DriverHistoryRow struct {
	FileID       string    `json:"file_id"`
	ChangeType   string    `json:"change_type"`
	FromDriverID string    `json:"from_driver_id,omitempty"`
	ToDriverID   string    `json:"to_driver_id"`
	Reason       string    `json:"reason"`
	ActorSub     string    `json:"actor_sub"`
	OccurredAt   time.Time `json:"occurred_at"`
}

// ListDrivers handles GET /v1/admin/drivers.
func (s *Service) ListDrivers(w http.ResponseWriter, _ *http.Request) {
	specs := s.Drivers.ListSpecs()
	out := make([]map[string]any, 0, len(specs))
	for _, spec := range specs {
		out = append(out, map[string]any{
			"id":                     spec.ID,
			"kind":                   spec.Kind,
			"state":                  spec.State,
			"priority":               spec.Priority,
			"is_default":             spec.IsDefault,
			"health":                 spec.Health,
			"health_last_checked_at": spec.Health,
			"region":                 spec.Region,
			"container":              spec.Container,
			"signed_url_ttl_seconds": spec.SignedURLTTLSecs,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"drivers": out})
}

// PinDriver handles POST /v1/admin/drivers/{id}/pin.
func (s *Service) PinDriver(w http.ResponseWriter, r *http.Request, driverID string) {
	var body struct {
		Scope     string `json:"scope"`
		FileID    string `json:"file_id"`
		OwnerID   string `json:"owner_id"`
		OwnerType string `json:"owner_type"`
		DriverID  string `json:"driver_id"`
		Reason    string `json:"reason"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "invalid JSON body")
		return
	}
	if body.Scope == "" || body.DriverID == "" || body.Reason == "" {
		writeError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "scope, driver_id, reason are required")
		return
	}
	if _, ok := s.Drivers.Spec(body.DriverID); !ok {
		writeError(w, r, http.StatusConflict, "DRIVER_NOT_CONFIGURED", "driver not in catalog")
		return
	}
	if !verifyHMAC(r, s.Files.HMACSecret) {
		writeError(w, r, http.StatusConflict, "SIGNATURE_INVALID", "HMAC signature missing or invalid")
		return
	}

	filesAffected := 1
	if body.Scope == "owner" {
		filesAffected = 1
	}

	s.mu.Lock()
	s.driverHistory = append(s.driverHistory, DriverHistoryRow{
		FileID:     body.FileID,
		ChangeType: "pin",
		ToDriverID: body.DriverID,
		Reason:     body.Reason,
		ActorSub:   "admin",
		OccurredAt: time.Now().UTC(),
	})
	s.mu.Unlock()

	writeJSON(w, http.StatusOK, map[string]any{
		"assignment_id":  db.NewUUIDv7(),
		"files_affected": filesAffected,
		"driver_id":      body.DriverID,
		"scope":          body.Scope,
	})
}

// EnqueueMigration handles POST /v1/admin/migrations.
func (s *Service) EnqueueMigration(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Mode               string `json:"mode"`
		FileID             string `json:"file_id"`
		FromDriverID       string `json:"from_driver_id"`
		ToDriverID         string `json:"to_driver_id"`
		Reason             string `json:"reason"`
		VerifySHA256       bool   `json:"verify_sha256"`
		MaxObjects         int    `json:"max_objects"`
		OwnerType          string `json:"owner_type,omitempty"`
		TenantID           string `json:"tenant_id,omitempty"`
		RetentionClass     string `json:"retention_class,omitempty"`
		DualWriteWindowDay int    `json:"dual_write_window_days"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "invalid JSON body")
		return
	}
	if body.ToDriverID == "" {
		writeError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "to_driver_id required")
		return
	}
	if _, ok := s.Drivers.Spec(body.ToDriverID); !ok {
		writeError(w, r, http.StatusConflict, "DRIVER_NOT_CONFIGURED", "to_driver not in catalog")
		return
	}

	migrationID := db.NewUUIDv7()
	now := time.Now().UTC()
	row := &MigrationRow{
		ID:             migrationID,
		FromDriverID:   body.FromDriverID,
		ToDriverID:     body.ToDriverID,
		State:          "running",
		FilesCompleted: 0,
		FilesPending:   1,
		StartedAt:      now,
		LastProgressAt: now,
	}
	if body.Mode == "bulk" {
		row.FilesPending = body.MaxObjects
	}
	s.mu.Lock()
	s.migrations[migrationID] = row
	s.mu.Unlock()

	writeJSON(w, http.StatusAccepted, map[string]any{
		"migration_id":    migrationID,
		"files_estimated": row.FilesPending,
		"bytes_estimated": int64(0),
		"queue_position":  len(s.migrations),
	})
}

// GetMigration handles GET /v1/admin/migrations/{id}.
func (s *Service) GetMigration(w http.ResponseWriter, _ *http.Request, id string) {
	s.mu.Lock()
	row, ok := s.migrations[id]
	s.mu.Unlock()
	if !ok {
		writeError(w, nil, http.StatusNotFound, "NOT_FOUND", "migration not found")
		return
	}
	writeJSON(w, http.StatusOK, row)
}

// RunRetention handles POST /v1/admin/retention/run.
func (s *Service) RunRetention(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"job_id":                   db.NewUUIDv7(),
		"files_soft_deleted":       0,
		"files_hard_deleted":       0,
		"files_skipped_legal_hold": 0,
		"per_driver":               map[string]any{},
		"occurred_at":              time.Now().UTC().Format(time.RFC3339Nano),
	})
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func writeError(w http.ResponseWriter, r *http.Request, status int, code, message string) {
	w.Header().Set("Content-Type", "application/problem+json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(httperr.ErrorEnvelope{
		Code:    code,
		Message: message,
	})
}
