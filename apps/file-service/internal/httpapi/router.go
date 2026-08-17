package httpapi

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/cors"

	"github.com/trips-enjoy/platform/file-service/internal/auth"
	"github.com/trips-enjoy/platform/file-service/internal/filedto"
	"github.com/trips-enjoy/platform/file-service/internal/httperr"
	"github.com/trips-enjoy/platform/file-service/internal/storage"
)

// NewRouter assembles the public chi mux: request-id + CORS + RED metrics
// + health/ready/started + OpenAPI + the file + admin routes per
// INTEGRATION.md.
func NewRouter(deps Deps) http.Handler {
	m := metricsFromDeps(deps)
	r := chi.NewRouter()
	r.Use(RequestID)
	r.Use(cors.Handler(cors.Options{
		AllowedOrigins: []string{"*"},
		AllowedMethods: []string{http.MethodGet, http.MethodPost, http.MethodPut, http.MethodPatch, http.MethodDelete, http.MethodOptions},
		AllowedHeaders: []string{
			"Accept", "Authorization", "Content-Type", "Idempotency-Key",
			"X-Correlation-Id", "X-Request-Id", "X-User-Id", "X-User-Type",
			"X-Roles", "X-Scopes", "X-Tenant-Id", "X-Signature",
		},
		ExposedHeaders:   []string{"RateLimit-Limit", "RateLimit-Remaining", "X-Correlation-Id", "X-Request-Id"},
		AllowCredentials: true,
		MaxAge:           300,
	}))
	r.Use(m.observe())

	r.Get("/health", healthHandler(deps.ServiceName))
	r.Get("/ready", readyHandler(deps))
	r.Get("/started", startedHandler(deps.ServiceName))
	r.Get("/openapi.json", openAPIHandler())
	r.Get("/docs", docsHandler)
	r.Handle("/metrics", m.handler())

	r.Route("/v1/files", func(r chi.Router) {
		r.Use(auth.Middleware)
		r.Post("/", initiateUploadHandler(deps))
		r.Post("/batch", bulkUploadHandler(deps))
		r.Post("/{id}/upload", proxyUploadHandler(deps))
		r.Post("/{id}/complete", completeUploadHandler(deps))
		r.Get("/{id}", getFileMetadataHandler(deps))
		r.Post("/{id}/signed-url", signedURLHandler(deps))
		r.Get("/{id}/download", downloadHandler(deps))
		r.Delete("/{id}", deleteFileHandler(deps))
		r.Get("/{id}/scan", scanHandler(deps))
		r.Get("/{id}/driver", driverAssignmentHandler(deps))
	})

	r.Route("/v1/admin", func(r chi.Router) {
		r.Use(auth.Middleware)
		r.Get("/drivers", listDriversHandler(deps))
		r.Post("/drivers/{id}/pin", pinDriverHandler(deps))
		r.Post("/migrations", enqueueMigrationHandler(deps))
		r.Get("/migrations/{id}", getMigrationHandler(deps))
		r.Post("/retention/run", retentionRunHandler(deps))
	})

	r.Get("/local-fs-proxy/stream", localFSStreamHandler(deps))
	r.Post("/local-fs-proxy/upload", localFSUploadHandler(deps))

	return r
}

func initiateUploadHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "could not read request body")
			return
		}
		var req filedto.InitiateUploadRequest
		if err := json.Unmarshal(body, &req); err != nil {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "invalid JSON body")
			return
		}
		// Allow X-Driver-Id header to set the override (admin callers
		// typically pass driver selection as a header rather than a body
		// field, so curl --proxy-friendly flows work).
		if req.DriverID == "" {
			if h := r.Header.Get("X-Driver-Id"); h != "" {
				req.DriverID = h
			}
		}
		ctx := r.Context()
		resp, _, err := deps.FilesService.InitiateUpload(ctx, req)
		if err != nil {
			mapServiceError(w, r, err)
			return
		}
		writeJSONStatus(w, http.StatusCreated, resp)
	}
}

// bulkUploadHandler implements POST /v1/files/batch. The body is a
// BulkUploadRequest with up to N items; each item is processed
// independently and lands in results[i] with either a response or a
// per-item error envelope.
//
// The HTTP status code is 207 (Multi-Status) — we deliberately do NOT
// collapse to 200 even when every item succeeded, so the wire shape is
// always consistent and clients never have to inspect `failed > 0` to
// parse the body. Pass a single-item batch to get the 207 envelope
// without changing any other field.
func bulkUploadHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "could not read request body")
			return
		}
		var req filedto.BulkUploadRequest
		if err := json.Unmarshal(body, &req); err != nil {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "invalid JSON body")
			return
		}
		if len(req.Items) == 0 {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "items array must not be empty")
			return
		}
		if len(req.Items) > 200 {
			WriteError(w, r, http.StatusRequestEntityTooLarge, "BATCH_TOO_LARGE", "max 200 items per batch")
			return
		}
		// X-Driver-Id header applies to every item that does not have its
		// own override — same precedence as the single-item endpoint.
		if req.DriverID == "" {
			if h := r.Header.Get("X-Driver-Id"); h != "" {
				req.DriverID = h
			}
		}
		resp, err := deps.FilesService.InitiateUploadBatch(r.Context(), req)
		if err != nil {
			WriteError(w, r, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
			return
		}
		writeJSONStatus(w, http.StatusMultiStatus, resp)
	}
}

func proxyUploadHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := chi.URLParam(r, "id")
		file, err := deps.FilesService.ProxyUpload(r.Context(), id, r.Body)
		if err != nil {
			mapServiceError(w, r, err)
			return
		}
		writeJSONStatus(w, http.StatusOK, file)
	}
}

func completeUploadHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := chi.URLParam(r, "id")
		var body struct {
			SHA256 string `json:"sha256"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "invalid JSON body")
			return
		}
		file, err := deps.FilesService.CompleteUpload(r.Context(), id, body.SHA256)
		if err != nil {
			mapServiceError(w, r, err)
			return
		}
		writeJSONStatus(w, http.StatusOK, file)
	}
}

func getFileMetadataHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := chi.URLParam(r, "id")
		file, err := deps.FilesService.GetMetadata(r.Context(), id)
		if err != nil {
			mapServiceError(w, r, err)
			return
		}
		writeJSONStatus(w, http.StatusOK, file)
	}
}

func signedURLHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := chi.URLParam(r, "id")
		var body filedto.SignedURLRequest
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "invalid JSON body")
			return
		}
		resp, err := deps.FilesService.IssueSignedURL(r.Context(), id, body.TTLSeconds, body.Purpose)
		if err != nil {
			mapServiceError(w, r, err)
			return
		}
		writeJSONStatus(w, http.StatusOK, resp)
	}
}

func downloadHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := chi.URLParam(r, "id")
		rc, file, err := deps.FilesService.Download(r.Context(), id)
		if err != nil {
			mapServiceError(w, r, err)
			return
		}
		defer rc.Close()
		w.Header().Set("Content-Type", file.MimeType)
		w.Header().Set("Content-Length", itoa(file.SizeBytes))
		w.WriteHeader(http.StatusOK)
		_, _ = io.Copy(w, rc)
	}
}

func deleteFileHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := chi.URLParam(r, "id")
		p, _ := auth.FromContext(r.Context())
		actor := p.UserID
		if err := deps.FilesService.SoftDelete(r.Context(), id, actor); err != nil {
			mapServiceError(w, r, err)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

func scanHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := chi.URLParam(r, "id")
		scan, err := deps.FilesService.GetScan(r.Context(), id)
		if err != nil {
			mapServiceError(w, r, err)
			return
		}
		writeJSONStatus(w, http.StatusOK, scan)
	}
}

func driverAssignmentHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := chi.URLParam(r, "id")
		assignment, err := deps.FilesService.GetDriverAssignment(r.Context(), id)
		if err != nil {
			mapServiceError(w, r, err)
			return
		}
		writeJSONStatus(w, http.StatusOK, assignment)
	}
}

func listDriversHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		// Live probe every driver. We do the work here (not in
		// admin.Service) so the response shape is owned by the
		// filedto.DriverListResponse wire type — no dependency on
		// admin's older shape.
		drivers := deps.FilesService.ListDriverHealth(r.Context())
		now := time.Now().UTC().Format(time.RFC3339Nano)
		writeJSONStatus(w, http.StatusOK, filedto.DriverListResponse{
			Drivers:   drivers,
			Total:     len(drivers),
			CheckedAt: now,
		})
	}
}

func pinDriverHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := chi.URLParam(r, "id")
		deps.AdminService.PinDriver(w, r, id)
	}
}

func enqueueMigrationHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		deps.AdminService.EnqueueMigration(w, r)
	}
}

func getMigrationHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := chi.URLParam(r, "id")
		deps.AdminService.GetMigration(w, r, id)
	}
}

func retentionRunHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		deps.AdminService.RunRetention(w, r)
	}
}

func localFSStreamHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		path := r.URL.Query().Get("path")
		exp := r.URL.Query().Get("exp")
		ticket := r.URL.Query().Get("ticket")
		driver, err := deps.Drivers.Resolve("local_fs")
		if err != nil {
			WriteError(w, r, http.StatusServiceUnavailable, "DRIVER_UNAVAILABLE", "local_fs driver not registered")
			return
		}
		if !verifyLocalFSTicket(driver, path, exp, ticket) {
			WriteError(w, r, http.StatusForbidden, "FORBIDDEN", "ticket invalid or expired")
			return
		}
		rc, err := driver.GetObject(r.Context(), storage.DriverLocator{"path": path})
		if err != nil {
			WriteError(w, r, http.StatusNotFound, "NOT_FOUND", "object not found")
			return
		}
		defer rc.Close()
		w.WriteHeader(http.StatusOK)
		_, _ = io.Copy(w, rc)
	}
}

func localFSUploadHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		path := r.URL.Query().Get("path")
		exp := r.URL.Query().Get("exp")
		ticket := r.URL.Query().Get("ticket")
		driver, err := deps.Drivers.Resolve("local_fs")
		if err != nil {
			WriteError(w, r, http.StatusServiceUnavailable, "DRIVER_UNAVAILABLE", "local_fs driver not registered")
			return
		}
		if !verifyLocalFSTicket(driver, path, exp, ticket) {
			WriteError(w, r, http.StatusForbidden, "FORBIDDEN", "ticket invalid or expired")
			return
		}
		body, err := io.ReadAll(r.Body)
		if err != nil {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "could not read body")
			return
		}
		if err := driver.PutObject(r.Context(), storage.DriverLocator{"path": path}, bytes.NewReader(body), ""); err != nil {
			WriteError(w, r, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
			return
		}
		w.WriteHeader(http.StatusOK)
	}
}

// mapServiceError translates a sentinel error from files.Service into the
// canonical platform envelope (status + code).
func mapServiceError(w http.ResponseWriter, r *http.Request, err error) {
	switch err {
	case httperr.ErrFileNotFound:
		WriteError(w, r, http.StatusNotFound, "NOT_FOUND", "file not found")
	case httperr.ErrFileNotAvailable:
		WriteError(w, r, http.StatusConflict, "FILE_NOT_AVAILABLE", "file not in an available state")
	case httperr.ErrFileTooLarge:
		WriteError(w, r, http.StatusUnprocessableEntity, "FILE_TOO_LARGE", "file exceeds maximum size")
	case httperr.ErrMimeTypeNotAllowed:
		WriteError(w, r, http.StatusUnprocessableEntity, "MIME_TYPE_NOT_ALLOWED", "mime type is not in the allowlist")
	case httperr.ErrSignatureInvalid:
		WriteError(w, r, http.StatusConflict, "SIGNATURE_INVALID", "uploaded bytes do not match the supplied sha256")
	case httperr.ErrLegalHoldActive:
		WriteError(w, r, http.StatusConflict, "LEGAL_HOLD_ACTIVE", "file is on legal hold and cannot be deleted")
	default:
		WriteError(w, r, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
	}
}

func writeJSONStatus(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func itoa(i int64) string {
	if i == 0 {
		return "0"
	}
	var buf [20]byte
	pos := len(buf)
	neg := i < 0
	if neg {
		i = -i
	}
	for i > 0 {
		pos--
		buf[pos] = byte('0' + i%10)
		i /= 10
	}
	if neg {
		pos--
		buf[pos] = '-'
	}
	return string(buf[pos:])
}

// verifyLocalFSTicket accepts any non-empty ticket in the dev scaffold.
// Real HMAC ticket verification is a follow-up PR (TECH.md §5.1); today
// we rely on the in-process reverse-proxy URL only.
func verifyLocalFSTicket(_ storage.StorageDriver, path, _ string, ticket string) bool {
	return path != "" && ticket != ""
}
