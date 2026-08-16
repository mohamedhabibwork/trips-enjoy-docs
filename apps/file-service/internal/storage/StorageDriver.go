// Package storage defines the StorageDriver port plus the in-memory
// driver registry. Adding a new backend is a matter of dropping a package
// under internal/storage/drivers/<id>/ and wiring it in cmd/server/main.go
// — that is the contract that satisfies FR-031 (a new driver must require
// only a new package under internal/storage/drivers/<id>/).
package storage

import (
	"context"
	"errors"
	"io"
	"time"
)

// DriverLocator is the per-driver opaque object handle. The service stores
// it as JSONB on files.driver_locator and treats it as a black box
// (per ERD.md DATA-009 — driver-opaque shape, varies per driver kind).
type DriverLocator = map[string]any

// InitiateUploadRequest is the input to StorageDriver.InitiateUpload. The
// service supplies the proposed key/bucket + mime/size so each driver can
// mint the opaque upload handle (presigned URL, SAS, PAR, signed-redirect,
// etc.) using its native SDK.
type InitiateUploadRequest struct {
	FileID    string
	MimeType  string
	SizeBytes int64
}

// InitiateUploadResponse carries whatever opaque handle the driver returns.
// For S3 / Azure / OCI / GCS this is a presigned URL; for local_fs it is a
// short-lived signed-redirect ticket. The service never inspects the value.
type InitiateUploadResponse struct {
	UploadURL     string
	DriverLocator DriverLocator
	ExpiresAt     time.Time
}

// CompleteUploadRequest is the input to StorageDriver.CompleteUpload —
// called after the bytes have landed on the driver (proxy or direct).
// DriverLocator carries whatever the driver needs to stat the object.
type CompleteUploadRequest struct {
	DriverLocator DriverLocator
	SHA256        string
}

// ObjectMetadata is the result of HeadObject (size + content hash + etag).
// SHA256 is populated for drivers that compute it (local_fs does on the fly;
// cloud drivers store it via server-side encryption metadata).
type ObjectMetadata struct {
	SizeBytes int64
	SHA256    string
	ETag      string
	Exists    bool
}

// ProbeResult is what the synthetic health probe returns per driver.
// Healthy = pass; Degraded = warn; Unreachable = fail. The driver manager
// opens a circuit breaker after 3 consecutive Unreachable results.
type ProbeResult struct {
	Healthy   bool
	LatencyMS int64
	Error     error
}

// StorageDriver is the platform's storage port. Every byte read or
// written by file-service passes through one of these. The interface is
// intentionally minimal so each driver SDK stays isolated (TECH.md §5.1).
type StorageDriver interface {
	// InitiateUpload returns an opaque handle the caller uses to land bytes
	// on the driver (presigned URL, SAS, PAR, ticket). MUST be idempotent
	// on (driver_id, key) so a retry from the caller lands on the same
	// object (INTEGRATION.md §2.3).
	InitiateUpload(ctx context.Context, req InitiateUploadRequest) (InitiateUploadResponse, error)

	// CompleteUpload is called after InitiateUpload + bytes land; it
	// verifies the object exists, returns the resulting metadata, and is
	// the hook for any driver-side post-upload tasks (e.g. OCI commit).
	CompleteUpload(ctx context.Context, req CompleteUploadRequest) (ObjectMetadata, error)

	// GetObject streams the object bytes. Caller MUST close the reader.
	GetObject(ctx context.Context, locator DriverLocator) (io.ReadCloser, error)

	// PutObject streams bytes from src into the object identified by
	// locator. encryptionKeyID is per-object KMS for KYC on cloud drivers.
	PutObject(ctx context.Context, locator DriverLocator, src io.Reader, encryptionKeyID string) error

	// DeleteObject removes the object. Idempotent.
	DeleteObject(ctx context.Context, locator DriverLocator) error

	// HeadObject returns metadata without reading the body.
	HeadObject(ctx context.Context, locator DriverLocator) (ObjectMetadata, error)

	// CreateSignedURL returns a time-bound URL scoped to verb (e.g. GET vs
	// PUT) and bound to file_id + purpose by the driver implementation.
	CreateSignedURL(ctx context.Context, locator DriverLocator, ttl time.Duration, scope string) (string, error)

	// Probe is the synthetic health check used by the readiness probe +
	// the per-driver circuit breaker.
	Probe(ctx context.Context) ProbeResult

	// Shutdown drains the driver's connection pool.
	Shutdown(ctx context.Context) error
}

// ErrDriverNotFound is returned by Registry.Resolve when no driver is
// registered under the requested id.
var ErrDriverNotFound = errors.New("storage driver not registered")

// ErrDriverDrained is returned when the resolved driver is in state
// "draining" and the caller is trying to upload (per FR-038).
var ErrDriverDrained = errors.New("storage driver is draining")

// ErrDriverNotImplemented is returned by stub drivers that compile-clean
// but have no SDK wired yet (s3, azure_blob, oracle_object_storage, gcs).
var ErrDriverNotImplemented = errors.New("storage driver not yet implemented")
