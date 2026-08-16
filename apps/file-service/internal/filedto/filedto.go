// Package filedto holds the request/response DTOs the file-service
// HTTP layer and the files aggregate both need to type-check against.
// Putting the DTOs in a leaf package breaks the import cycle that
// would otherwise arise between internal/httpapi and internal/files
// (each one needs the DTOs; importing them from either side would
// cycle).
package filedto

import (
	"encoding/json"
	"time"
)

// InitiateUploadRequest is the JSON body of POST /v1/files.
type InitiateUploadRequest struct {
	Name           string `json:"name"`
	MimeType       string `json:"mime_type"`
	SizeBytes      int64  `json:"size_bytes"`
	SHA256         string `json:"sha256"`
	OwnerID        string `json:"owner_id"`
	OwnerType      string `json:"owner_type"`
	RetentionClass string `json:"retention_class"`
	// DriverID is the explicit storage-driver override. Empty falls
	// back to the precedence order documented in SRS §FR-032
	// (file_pin → tenant → owner_type → retention_class → default).
	// Per-call overrides require an admin role (enforced at the
	// handler) and the driver must be in `enabled` state.
	DriverID string          `json:"driver_id,omitempty"`
	Metadata json.RawMessage `json:"metadata,omitempty"`
}

// InitiateUploadResponse is the 201 body for both proxy and direct flows.
type InitiateUploadResponse struct {
	FileID         string     `json:"file_id"`
	UploadMethod   string     `json:"upload_method"`
	UploadURL      *string    `json:"upload_url"`
	DriverID       string     `json:"driver_id"`
	Status         string     `json:"status"`
	RetentionUntil time.Time  `json:"retention_until"`
	ExpiresAt      *time.Time `json:"expires_at,omitempty"`
}

// File is the canonical metadata record (matches GET /v1/files/{id}).
type File struct {
	ID             string          `json:"id"`
	Name           string          `json:"name"`
	MimeType       string          `json:"mime_type"`
	SizeBytes      int64           `json:"size_bytes"`
	SHA256         string          `json:"sha256"`
	OwnerID        string          `json:"owner_id"`
	OwnerType      string          `json:"owner_type"`
	RetentionClass string          `json:"retention_class"`
	Status         string          `json:"status"`
	ScanResult     string          `json:"scan_result,omitempty"`
	DriverID       string          `json:"driver_id"`
	DriverKind     string          `json:"driver_kind"`
	DriverLocator  json.RawMessage `json:"driver_locator"`
	RetentionUntil time.Time       `json:"retention_until"`
	LegalHold      bool            `json:"legal_hold"`
	CreatedAt      time.Time       `json:"created_at"`
	UpdatedAt      time.Time       `json:"updated_at"`
}

// CompleteUploadRequest is the body of POST /v1/files/{id}/complete.
type CompleteUploadRequest struct {
	SHA256 string `json:"sha256"`
}

// SignedURLRequest is the body of POST /v1/files/{id}/signed-url.
type SignedURLRequest struct {
	TTLSeconds int    `json:"ttl_seconds"`
	Purpose    string `json:"purpose"`
}

// SignedURLResponse is the 200 body of POST /v1/files/{id}/signed-url.
type SignedURLResponse struct {
	URL       string    `json:"url"`
	DriverID  string    `json:"driver_id"`
	ExpiresAt time.Time `json:"expires_at"`
}

// DriverAssignmentResponse is the 200 body of GET /v1/files/{id}/driver.
type DriverAssignmentResponse struct {
	FileID              string          `json:"file_id"`
	DriverID            string          `json:"driver_id"`
	DriverKind          string          `json:"driver_kind"`
	AssignmentSource    string          `json:"assignment_source"`
	DriverLocator       json.RawMessage `json:"driver_locator"`
	DriverLocaleVersion int             `json:"driver_locale_version"`
	KMSKeyID            *string         `json:"kms_key_id,omitempty"`
}

// ScanResult is the 200 body of GET /v1/files/{id}/scan.
type ScanResult struct {
	FileID      string     `json:"file_id"`
	Status      string     `json:"status"`
	Result      string     `json:"result,omitempty"`
	ThreatName  *string    `json:"threat_name,omitempty"`
	StartedAt   time.Time  `json:"started_at"`
	CompletedAt *time.Time `json:"completed_at,omitempty"`
	Provider    string     `json:"provider"`
}

// BulkUploadRequest is the body of POST /v1/files/batch. Each item is
// processed independently — a single bad item (invalid mime, driver
// not configured, etc.) lands in `results[i].error` and does not
// cancel the rest of the batch.
type BulkUploadRequest struct {
	// DriverID, when set, applies to every item that does not have its
	// own DriverID override. Empty falls back to the per-item value
	// (then to the default).
	DriverID string                  `json:"driver_id,omitempty"`
	Items    []InitiateUploadRequest `json:"items"`
}

// BulkUploadItemResult is the per-item outcome inside BulkUploadResponse.
type BulkUploadItemResult struct {
	Index    int                     `json:"index"`
	Response *InitiateUploadResponse `json:"response,omitempty"`
	File     *File                   `json:"file,omitempty"`
	Error    *BulkUploadItemError    `json:"error,omitempty"`
}

// BulkUploadItemError mirrors the platform canonical envelope per item.
type BulkUploadItemError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

// BulkUploadResponse is the 207 body for POST /v1/files/batch.
type BulkUploadResponse struct {
	Total      int                    `json:"total"`
	Succeeded  int                    `json:"succeeded"`
	Failed     int                    `json:"failed"`
	Results    []BulkUploadItemResult `json:"results"`
	OccurredAt time.Time              `json:"occurred_at"`
}

// DriverStatus is the live driver health snapshot returned by
// GET /v1/admin/drivers. Built from the registry spec + a 5s probe.
type DriverStatus struct {
	ID                 string `json:"id"`
	Kind               string `json:"kind"`
	DisplayName        string `json:"display_name,omitempty"`
	State              string `json:"state"`
	Priority           int    `json:"priority"`
	IsDefault          bool   `json:"is_default"`
	Region             string `json:"region,omitempty"`
	Container          string `json:"container,omitempty"`
	Endpoint           string `json:"endpoint,omitempty"`
	MaxObjectSizeBytes int64  `json:"max_object_size_bytes,omitempty"`
	Healthy            bool   `json:"healthy"`
	Reachable          bool   `json:"reachable"`
	ProbeError         string `json:"probe_error,omitempty"`
	LatencyMS          int64  `json:"latency_ms"`
	CheckedAt          string `json:"checked_at"`
}

// DriverListResponse wraps DriverStatus so we can extend later
// (cursor pagination, filters) without breaking the wire shape.
type DriverListResponse struct {
	Drivers   []DriverStatus `json:"drivers"`
	Total     int            `json:"total"`
	CheckedAt string         `json:"checked_at"`
}

// File status enum (matches files.status check constraint).
const (
	StatusPending     = "pending"
	StatusScanning    = "scanning"
	StatusAvailable   = "available"
	StatusQuarantined = "quarantined"
	StatusDeleted     = "deleted"
)

// RetentionClass enum.
var (
	RetentionClassKYC               = "kyc"
	RetentionClassSupportAttachment = "support_attachment"
	RetentionClassAvatar            = "avatar"
	RetentionClassMenuPhoto         = "menu_photo"
	RetentionClassSafetyRecording   = "safety_recording"
	RetentionClassVehiclePhoto      = "vehicle_photo"
	RetentionClassOther             = "other"
)

// IsValidRetentionClass reports whether value is one of the seven
// documented retention classes.
func IsValidRetentionClass(value string) bool {
	switch value {
	case RetentionClassKYC, RetentionClassSupportAttachment, RetentionClassAvatar,
		RetentionClassMenuPhoto, RetentionClassSafetyRecording,
		RetentionClassVehiclePhoto, RetentionClassOther:
		return true
	}
	return false
}

// WithCorrelation is a no-op tag helper used by the httpapi router to
// propagate the request id into the service context without forcing
// an import on internal/files.
func WithCorrelation(ctx any, _ string) any { return ctx }
