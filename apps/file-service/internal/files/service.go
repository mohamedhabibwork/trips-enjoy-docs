package files

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"strings"
	"time"

	"github.com/trips-enjoy/platform/file-service/internal/db"
	"github.com/trips-enjoy/platform/file-service/internal/events"
	"github.com/trips-enjoy/platform/file-service/internal/httperr"
	"github.com/trips-enjoy/platform/file-service/internal/storage"
)

// metricsSink is the slice of httpapi.Metrics the aggregate calls.
// Declared locally so files never imports httpapi (the dependency
// direction is reversed: httpapi's FilesService interface is
// satisfied by *Service implicitly).
type metricsSink interface {
	IncFilesUploaded(ownerType, mimeClass, uploadMethod, driverID string)
	IncFilesScanned(result string)
}

// Service is the file-service aggregate. It owns the metadata lifecycle
// and routes every byte through storage.Registry. The aggregate does NOT
// implement a database repo directly — that lands in a follow-up PR;
// today it persists to InMemoryRepo.
type Service struct {
	Repo             *InMemoryRepo
	Drivers          *storage.Registry
	Publisher        events.Publisher
	AllowedMIMETypes []string
	MaxUploadSize    int64
	SyncScanMaxSize  int64
	DefaultSignedTTL time.Duration
	HMACSecret       []byte
	Outbox           *events.Outbox
	Metrics          metricsSink // optional; nil-safe — setters skip when nil
}

// NewService builds a Service with sensible defaults.
func NewService(repo *InMemoryRepo, drivers *storage.Registry, publisher events.Publisher, allowed []string, maxUpload, syncScan int64, defaultTTL time.Duration) *Service {
	return &Service{
		Repo:             repo,
		Drivers:          drivers,
		Publisher:        publisher,
		AllowedMIMETypes: allowed,
		MaxUploadSize:    maxUpload,
		SyncScanMaxSize:  syncScan,
		DefaultSignedTTL: defaultTTL,
		Outbox:           events.NewOutbox(),
	}
}

// InitiateUpload validates the request, resolves a driver via the
// registry, persists the metadata in the repo, and writes a
// file.uploaded.v1 envelope to the outbox. Returns the file_id + either
// the proxy body or a direct upload URL per INTEGRATION.md §1.1.
//
// Driver selection precedence (per SRS FR-032):
//  1. req.DriverID (explicit per-call override)
//  2. file_pin (out of scope; the registry walks pins at resolve time)
//  3. tenant / owner_type / retention_class override
//  4. env default (Drivers.DefaultID())
func (s *Service) InitiateUpload(ctx context.Context, req InitiateUploadRequest) (*InitiateUploadResponse, *File, error) {
	if req.SizeBytes > s.MaxUploadSize {
		return nil, nil, ErrFileTooLarge
	}
	if !mimeAllowed(req.MimeType, s.AllowedMIMETypes) {
		return nil, nil, ErrMimeTypeNotAllowed
	}
	if !IsValidRetentionClass(req.RetentionClass) {
		return nil, nil, fmt.Errorf("invalid retention_class %q", req.RetentionClass)
	}

	driverID, spec, err := s.resolveDriver(req.DriverID)
	if err != nil {
		return nil, nil, err
	}
	driver, err := s.Drivers.Resolve(driverID)
	if err != nil {
		return nil, nil, fmt.Errorf("resolve driver %q: %w", driverID, err)
	}

	fileID := db.NewUUIDv7()
	retentionUntil := time.Now().Add(30 * 24 * time.Hour).UTC()

	// Initiate driver-side handle.
	init, err := driver.InitiateUpload(ctx, storage.InitiateUploadRequest{
		FileID:    fileID,
		MimeType:  req.MimeType,
		SizeBytes: req.SizeBytes,
	})
	if err != nil {
		return nil, nil, fmt.Errorf("driver initiate: %w", err)
	}

	driverKind := spec.Kind
	if driverKind == "" {
		driverKind = "unknown"
	}
	file := NewFile(
		fileID,
		req.Name,
		req.MimeType,
		req.SizeBytes,
		req.SHA256,
		req.OwnerID,
		req.OwnerType,
		req.RetentionClass,
		driverID,
		driverKind,
		init.DriverLocator,
		retentionUntil,
	)
	if err := s.Repo.Save(file); err != nil {
		return nil, nil, fmt.Errorf("save file: %w", err)
	}

	// Build response per size threshold.
	resp := &InitiateUploadResponse{
		FileID:         fileID,
		DriverID:       driverID,
		RetentionUntil: retentionUntil,
	}
	if req.SizeBytes <= s.SyncScanMaxSize {
		file.Status = StatusScanning
		_ = s.Repo.Save(file)
		resp.UploadMethod = "proxy"
		resp.Status = StatusScanning
	} else {
		file.Status = StatusPending
		_ = s.Repo.Save(file)
		resp.UploadMethod = "direct"
		resp.Status = StatusPending
		url := init.UploadURL
		resp.UploadURL = &url
		resp.ExpiresAt = &init.ExpiresAt
	}

	// Best-effort event emission via the outbox + publisher.
	s.emitUploaded(ctx, file)
	return resp, file, nil
}

// resolveDriver picks the driver for an upload request. explicit
// non-empty reqOverride wins; otherwise the registry default. Validates
// the driver exists in the catalog and is in `enabled` state. The
// `draining` state is rejected (per SRS FR-038) — uploads to a
// draining driver fail with ErrDriverDrained.
func (s *Service) resolveDriver(reqOverride string) (string, storage.DriverSpec, error) {
	driverID := reqOverride
	if driverID == "" {
		driverID = s.Drivers.DefaultID()
	}
	if driverID == "" {
		return "", storage.DriverSpec{}, fmt.Errorf("no driver configured (default is empty)")
	}
	spec, ok := s.Drivers.Spec(driverID)
	if !ok {
		return "", storage.DriverSpec{}, fmt.Errorf("driver %q not in catalog", driverID)
	}
	if spec.State == "disabled" {
		return "", storage.DriverSpec{}, fmt.Errorf("driver %q is disabled", driverID)
	}
	if spec.State == "draining" {
		return "", storage.DriverSpec{}, fmt.Errorf("driver %q is draining", driverID)
	}
	return driverID, spec, nil
}

// InitiateUploadBatch is the bulk counterpart of InitiateUpload. It
// runs every item independently — a single bad item lands in
// results[i].error and the rest of the batch continues. Returns the
// 207 response with per-item outcomes.
func (s *Service) InitiateUploadBatch(ctx context.Context, req BulkUploadRequest) (*BulkUploadResponse, error) {
	now := time.Now().UTC()
	results := make([]BulkUploadItemResult, len(req.Items))
	succeeded := 0
	failed := 0
	for i := range req.Items {
		item := req.Items[i] // copy so we can stamp the per-item override without mutating the caller's slice
		// Batch-level DriverID is the default; per-item wins.
		if item.DriverID == "" {
			item.DriverID = req.DriverID
		}
		resp, file, err := s.InitiateUpload(ctx, item)
		entry := BulkUploadItemResult{Index: i}
		if err != nil {
			entry.Error = &BulkUploadItemError{
				Code:    classifyBatchError(err),
				Message: err.Error(),
			}
			failed++
		} else {
			entry.Response = resp
			entry.File = file
			succeeded++
		}
		results[i] = entry
	}
	return &BulkUploadResponse{
		Total:      len(req.Items),
		Succeeded:  succeeded,
		Failed:     failed,
		Results:    results,
		OccurredAt: now,
	}, nil
}

// classifyBatchError maps sentinel + ad-hoc errors from InitiateUpload
// into the canonical error codes callers see on the single-item path.
func classifyBatchError(err error) string {
	switch err {
	case ErrFileTooLarge:
		return "FILE_TOO_LARGE"
	case ErrMimeTypeNotAllowed:
		return "MIME_TYPE_NOT_ALLOWED"
	}
	if msg := err.Error(); msg != "" {
		if containsAny(msg, "not in catalog") {
			return "DRIVER_NOT_CONFIGURED"
		}
		if containsAny(msg, "is draining") {
			return "DRIVER_DRAINED"
		}
		if containsAny(msg, "is disabled") {
			return "DRIVER_UNAVAILABLE"
		}
		if containsAny(msg, "invalid retention_class") {
			return "VALIDATION_FAILED"
		}
	}
	return "INTERNAL_ERROR"
}

func containsAny(haystack string, needles ...string) bool {
	for _, n := range needles {
		if indexOf(haystack, n) >= 0 {
			return true
		}
	}
	return false
}

// indexOf is a tiny substring helper to avoid pulling strings into
// files just for containsAny.
func indexOf(s, sub string) int {
	if len(sub) == 0 {
		return 0
	}
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return i
		}
	}
	return -1
}

// ProxyUpload lands bytes onto the resolved driver for the proxy flow.
func (s *Service) ProxyUpload(ctx context.Context, fileID string, body io.Reader) (*File, error) {
	file, ok := s.Repo.Get(fileID)
	if !ok {
		return nil, ErrFileNotFound
	}
	if file.Status != StatusPending && file.Status != StatusScanning {
		return nil, ErrFileNotAvailable
	}
	driver, err := s.Drivers.Resolve(file.DriverID)
	if err != nil {
		return nil, fmt.Errorf("resolve driver: %w", err)
	}
	var locator storage.DriverLocator
	_ = json.Unmarshal(file.DriverLocator, &locator)
	if err := driver.PutObject(ctx, locator, body, ""); err != nil {
		return nil, fmt.Errorf("driver put: %w", err)
	}
	file.Status = StatusScanning
	file.UpdatedAt = time.Now().UTC()
	_ = s.Repo.Save(file)
	s.emitScanned(ctx, file, "clean", "")
	return file, nil
}

// CompleteUpload verifies the driver's HeadObject after a direct upload,
// flips the file to StatusScanning, and writes a file.scanned.v1 event.
func (s *Service) CompleteUpload(ctx context.Context, fileID string, expectedSHA string) (*File, error) {
	file, ok := s.Repo.Get(fileID)
	if !ok {
		return nil, ErrFileNotFound
	}
	driver, err := s.Drivers.Resolve(file.DriverID)
	if err != nil {
		return nil, fmt.Errorf("resolve driver: %w", err)
	}
	var locator storage.DriverLocator
	_ = json.Unmarshal(file.DriverLocator, &locator)
	meta, err := driver.HeadObject(ctx, locator)
	if err != nil {
		return nil, fmt.Errorf("driver head: %w", err)
	}
	if expectedSHA != "" && meta.SHA256 != "" && meta.SHA256 != expectedSHA {
		// Abort the upload by deleting the partial object.
		_ = driver.DeleteObject(ctx, locator)
		return nil, ErrSignatureInvalid
	}
	file.SHA256 = expectedSHA
	file.Status = StatusScanning
	file.UpdatedAt = time.Now().UTC()
	_ = s.Repo.Save(file)
	s.emitScanned(ctx, file, "clean", "")
	return file, nil
}

// GetMetadata returns the file by id (404 NOT_FOUND when missing).
func (s *Service) GetMetadata(_ context.Context, fileID string) (*File, error) {
	file, ok := s.Repo.Get(fileID)
	if !ok {
		return nil, ErrFileNotFound
	}
	return file, nil
}

// IssueSignedURL calls the driver's CreateSignedURL and returns the
// canonical 200 body. TTL is clamped to [1s, 1h] per SRS §9.
func (s *Service) IssueSignedURL(ctx context.Context, fileID string, ttl int, purpose string) (*SignedURLResponse, error) {
	file, ok := s.Repo.Get(fileID)
	if !ok {
		return nil, ErrFileNotFound
	}
	if file.Status != StatusAvailable && file.Status != StatusScanning {
		return nil, ErrFileNotAvailable
	}
	if ttl <= 0 {
		ttl = int(s.DefaultSignedTTL.Seconds())
	}
	if ttl > 3600 {
		ttl = 3600
	}
	driver, err := s.Drivers.Resolve(file.DriverID)
	if err != nil {
		return nil, fmt.Errorf("resolve driver: %w", err)
	}
	var locator storage.DriverLocator
	_ = json.Unmarshal(file.DriverLocator, &locator)
	url, err := driver.CreateSignedURL(ctx, locator, time.Duration(ttl)*time.Second, purpose)
	if err != nil {
		return nil, fmt.Errorf("driver sign: %w", err)
	}
	exp := time.Now().Add(time.Duration(ttl) * time.Second).UTC()
	return &SignedURLResponse{URL: url, DriverID: file.DriverID, ExpiresAt: exp}, nil
}

// Download streams the file bytes from the driver.
func (s *Service) Download(ctx context.Context, fileID string) (io.ReadCloser, *File, error) {
	file, ok := s.Repo.Get(fileID)
	if !ok {
		return nil, nil, ErrFileNotFound
	}
	if file.Status != StatusAvailable {
		return nil, nil, ErrFileNotAvailable
	}
	driver, err := s.Drivers.Resolve(file.DriverID)
	if err != nil {
		return nil, nil, fmt.Errorf("resolve driver: %w", err)
	}
	var locator storage.DriverLocator
	_ = json.Unmarshal(file.DriverLocator, &locator)
	rc, err := driver.GetObject(ctx, locator)
	if err != nil {
		return nil, nil, fmt.Errorf("driver get: %w", err)
	}
	return rc, file, nil
}

// SoftDelete marks the file as deleted and writes file.deleted.v1.
// Idempotent: re-deleting a deleted file is a no-op.
func (s *Service) SoftDelete(ctx context.Context, fileID string, actorSub string) error {
	file, ok := s.Repo.Get(fileID)
	if !ok {
		return ErrFileNotFound
	}
	if file.LegalHold {
		return ErrLegalHoldActive
	}
	if file.Status == StatusDeleted {
		return nil
	}
	file.Status = StatusDeleted
	file.UpdatedAt = time.Now().UTC()
	_ = s.Repo.Save(file)
	s.emitDeleted(ctx, file, "user", actorSub)
	return nil
}

// GetScan returns the latest scan result for a file.
func (s *Service) GetScan(_ context.Context, fileID string) (*ScanResult, error) {
	if s, ok := s.Repo.GetScan(fileID); ok {
		return s, nil
	}
	return nil, ErrFileNotFound
}

// GetDriverAssignment returns the opaque driver locator for a file.
func (s *Service) GetDriverAssignment(_ context.Context, fileID string) (*DriverAssignmentResponse, error) {
	file, ok := s.Repo.Get(fileID)
	if !ok {
		return nil, ErrFileNotFound
	}
	var locator map[string]any
	_ = json.Unmarshal(file.DriverLocator, &locator)
	return &DriverAssignmentResponse{
		FileID:              fileID,
		DriverID:            file.DriverID,
		DriverKind:          file.DriverKind,
		AssignmentSource:    "default",
		DriverLocator:       file.DriverLocator,
		DriverLocaleVersion: 1,
	}, nil
}

// ListDriverHealth returns the live probe status for every registered
// driver. The returned slice is sorted by Priority then id (matches
// registry.ListSpecs). Used by GET /v1/admin/drivers; the metric ticker
// also calls this on every probe tick to keep the gauges fresh.
func (s *Service) ListDriverHealth(ctx context.Context) []DriverStatus {
	now := time.Now().UTC()
	specs := s.Drivers.ListSpecs()
	out := make([]DriverStatus, 0, len(specs))
	for _, spec := range specs {
		entry := DriverStatus{
			ID:                 spec.ID,
			Kind:               spec.Kind,
			DisplayName:        spec.DisplayName,
			State:              spec.State,
			Priority:           spec.Priority,
			IsDefault:          spec.IsDefault,
			Region:             spec.Region,
			Container:          spec.Container,
			Endpoint:           spec.Endpoint,
			MaxObjectSizeBytes: spec.MaxObjectSizeByte,
			CheckedAt:          now.Format(time.RFC3339Nano),
		}
		// Reachable == Probe().Healthy. We also surface the circuit-
		// breaker state so the admin can tell "down" from "isolated".
		if d, err := s.Drivers.Resolve(spec.ID); err == nil {
			res := d.Probe(ctx)
			entry.Reachable = res.Healthy
			entry.Healthy = res.Healthy && !s.Drivers.IsCircuitOpen(spec.ID)
			entry.LatencyMS = res.LatencyMS
			if res.Error != nil {
				entry.ProbeError = res.Error.Error()
			}
		} else {
			entry.ProbeError = err.Error()
		}
		out = append(out, entry)
	}
	return out
}

// emitUploaded writes a file.uploaded.v1 envelope to the publisher and
// the in-memory outbox.
func (s *Service) emitUploaded(ctx context.Context, file *File) {
	if s.Publisher == nil {
		return
	}
	payload := map[string]any{
		"file_id":         file.ID,
		"owner_id":        file.OwnerID,
		"owner_type":      file.OwnerType,
		"name":            file.Name,
		"mime_type":       file.MimeType,
		"size_bytes":      file.SizeBytes,
		"retention_class": file.RetentionClass,
		"sha256":          file.SHA256,
		"driver_id":       file.DriverID,
		"driver_kind":     file.DriverKind,
		"uploaded_at":     file.UpdatedAt.Format(time.RFC3339Nano),
		"retention_until": file.RetentionUntil.Format(time.RFC3339Nano),
		"correlation_id":  correlationFromContext(ctx),
	}
	env := events.Envelope{
		EventID:       db.NewUUIDv7(),
		EventName:     events.EventFileUploadedV1,
		SchemaVersion: 1,
		OccurredAt:    time.Now().UTC(),
		Producer:      "file-service",
		TenantID:      "global",
		CorrelationID: correlationFromContext(ctx),
		AggregateType: "File",
		AggregateID:   file.OwnerID,
		Data:          payload,
	}
	_ = s.Publisher.Publish(env)
	s.Outbox.Append(events.OutboxRow{
		ID:            env.EventID,
		AggregateType: env.AggregateType,
		AggregateID:   env.AggregateID,
		EventName:     env.EventName,
		Payload:       marshalOrEmpty(env),
		CreatedAt:     env.OccurredAt,
	})
	if s.Metrics != nil {
		s.Metrics.IncFilesUploaded(file.OwnerType, mimeClass(file.MimeType), uploadMethodFor(file.Status), file.DriverID)
	}
}

func (s *Service) emitScanned(ctx context.Context, file *File, result, threat string) {
	if s.Publisher == nil {
		return
	}
	payload := map[string]any{
		"file_id":        file.ID,
		"owner_id":       file.OwnerID,
		"result":         result,
		"threat_name":    threat,
		"provider":       "clamav",
		"occurred_at":    time.Now().UTC().Format(time.RFC3339Nano),
		"correlation_id": correlationFromContext(ctx),
	}
	env := events.Envelope{
		EventID:       db.NewUUIDv7(),
		EventName:     events.EventFileScannedV1,
		SchemaVersion: 1,
		OccurredAt:    time.Now().UTC(),
		Producer:      "file-service",
		TenantID:      "global",
		CorrelationID: correlationFromContext(ctx),
		AggregateType: "File",
		AggregateID:   file.OwnerID,
		Data:          payload,
	}
	_ = s.Publisher.Publish(env)
	s.Outbox.Append(events.OutboxRow{
		ID:            env.EventID,
		AggregateType: env.AggregateType,
		AggregateID:   env.AggregateID,
		EventName:     env.EventName,
		Payload:       marshalOrEmpty(env),
		CreatedAt:     env.OccurredAt,
	})
	if s.Metrics != nil {
		s.Metrics.IncFilesScanned(result)
	}
}

func (s *Service) emitDeleted(ctx context.Context, file *File, reason, actorSub string) {
	if s.Publisher == nil {
		return
	}
	payload := map[string]any{
		"file_id":        file.ID,
		"owner_id":       file.OwnerID,
		"reason":         reason,
		"actor_sub":      actorSub,
		"driver_id":      file.DriverID,
		"driver_kind":    file.DriverKind,
		"hard_delete_at": file.RetentionUntil.Format(time.RFC3339Nano),
		"correlation_id": correlationFromContext(ctx),
	}
	env := events.Envelope{
		EventID:       db.NewUUIDv7(),
		EventName:     events.EventFileDeletedV1,
		SchemaVersion: 1,
		OccurredAt:    time.Now().UTC(),
		Producer:      "file-service",
		TenantID:      "global",
		CorrelationID: correlationFromContext(ctx),
		AggregateType: "File",
		AggregateID:   file.OwnerID,
		Data:          payload,
	}
	_ = s.Publisher.Publish(env)
	s.Outbox.Append(events.OutboxRow{
		ID:            env.EventID,
		AggregateType: env.AggregateType,
		AggregateID:   env.AggregateID,
		EventName:     env.EventName,
		Payload:       marshalOrEmpty(env),
		CreatedAt:     env.OccurredAt,
	})
}

// correlationFromContext returns the request id set by httpapi.RequestID
// (or "" if absent). Imported here as a small wrapper so files.Service
// does not need to know about httpapi internals.
func correlationFromContext(ctx context.Context) string {
	if v := ctx.Value(correlationKey{}); v != nil {
		if s, ok := v.(string); ok {
			return s
		}
	}
	return ""
}

// WithCorrelation returns a context tagged with the correlation id so
// outbound event envelopes carry it.
func WithCorrelation(ctx context.Context, correlationID string) context.Context {
	return context.WithValue(ctx, correlationKey{}, correlationID)
}

type correlationKey struct{}

// Sentinel errors. Re-exported from internal/httperr so the
// service's public API keeps its existing name; router.go matches
// against these values via errors.Is.
var (
	ErrFileNotFound       = httperr.ErrFileNotFound
	ErrFileNotAvailable   = httperr.ErrFileNotAvailable
	ErrFileTooLarge       = httperr.ErrFileTooLarge
	ErrMimeTypeNotAllowed = httperr.ErrMimeTypeNotAllowed
	ErrSignatureInvalid   = httperr.ErrSignatureInvalid
	ErrLegalHoldActive    = httperr.ErrLegalHoldActive
)

// marshalOrEmpty JSON-encodes the envelope or returns an empty slice on
// failure. Used by the outbox so a single bad envelope does not block
// the poller from draining subsequent rows.
func marshalOrEmpty(env events.Envelope) []byte {
	buf := &bytes.Buffer{}
	_ = json.NewEncoder(buf).Encode(env)
	return buf.Bytes()
}

// ReadAll is a small helper used by handlers reading multipart bodies.
func ReadAll(r io.Reader) ([]byte, error) { return io.ReadAll(r) }

// TrimNonEmpty is used by validation helpers to normalize optional
// fields.
func TrimNonEmpty(s string) string { return strings.TrimSpace(s) }

// AsAny marshals a value to json.RawMessage so handlers can embed it in
// response bodies. It returns null on marshal failure.
func AsAny(v any) json.RawMessage {
	b, err := json.Marshal(v)
	if err != nil {
		return json.RawMessage(`null`)
	}
	return b
}

// mimeClass buckets a mime_type into one of the small set of classes
// the platform dashboard groups by. Anything not in the allowlist
// falls into "other" (the upload would have been rejected earlier).
func mimeClass(mime string) string {
	switch {
	case len(mime) >= 6 && mime[:6] == "image/":
		return "image"
	case len(mime) >= 6 && mime[:6] == "video/":
		return "video"
	case len(mime) >= 6 && mime[:6] == "audio/":
		return "audio"
	case mime == "application/pdf":
		return "document"
	default:
		return "other"
	}
}

// uploadMethodFor returns the proxy/direct label used by the
// files_uploads_total metric. After InitiateUpload the Status
// distinguishes small (proxy → scanning) from large (direct → pending).
func uploadMethodFor(status string) string {
	if status == StatusScanning {
		return "proxy"
	}
	if status == StatusPending {
		return "direct"
	}
	return "other"
}
