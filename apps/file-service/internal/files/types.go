package files

import (
	"github.com/trips-enjoy/platform/file-service/internal/filedto"
)

// Re-export the DTOs from the filedto leaf package so existing files
// callers keep their short name. The HTTP layer imports filedto
// directly to avoid the import cycle.
type (
	InitiateUploadRequest    = filedto.InitiateUploadRequest
	InitiateUploadResponse   = filedto.InitiateUploadResponse
	File                     = filedto.File
	CompleteUploadRequest    = filedto.CompleteUploadRequest
	SignedURLRequest         = filedto.SignedURLRequest
	SignedURLResponse        = filedto.SignedURLResponse
	DriverAssignmentResponse = filedto.DriverAssignmentResponse
	ScanResult               = filedto.ScanResult
	BulkUploadRequest        = filedto.BulkUploadRequest
	BulkUploadResponse       = filedto.BulkUploadResponse
	BulkUploadItemResult     = filedto.BulkUploadItemResult
	BulkUploadItemError      = filedto.BulkUploadItemError
	DriverStatus             = filedto.DriverStatus
	DriverListResponse       = filedto.DriverListResponse
)

// Status / RetentionClass enums.
const (
	StatusPending     = filedto.StatusPending
	StatusScanning    = filedto.StatusScanning
	StatusAvailable   = filedto.StatusAvailable
	StatusQuarantined = filedto.StatusQuarantined
	StatusDeleted     = filedto.StatusDeleted
)

// IsValidRetentionClass re-export.
func IsValidRetentionClass(value string) bool { return filedto.IsValidRetentionClass(value) }

// WithCorrelation tags a context with the request id so outbound
// event envelopes carry it. Re-exported from filedto to keep the
// service's surface stable.
