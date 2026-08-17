package files

import (
	"net/http"

	"github.com/trips-enjoy/platform/file-service/internal/httperr"
)

// ValidateInitiateUpload enforces the input rules from SRS.md §9.
// On failure it writes a 400 VALIDATION_FAILED response with a Details
// array and returns false; on success it returns true and writes nothing.
//
// The HTTP writer is supplied so the function owns the response shape;
// it does NOT depend on internal/httpapi (which would create an import
// cycle because httpapi imports files for the service type). The handler
// in httpapi/router.go calls this helper directly.
func ValidateInitiateUpload(w http.ResponseWriter, req *InitiateUploadRequest, maxUploadSize int64, allowedMIMEs []string) bool {
	details := validateRequest(req, maxUploadSize, allowedMIMEs)
	if len(details) == 0 {
		return true
	}
	w.Header().Set("Content-Type", "application/problem+json; charset=utf-8")
	w.WriteHeader(http.StatusBadRequest)
	_, _ = w.Write(buildValidationEnvelope(details))
	return false
}

// validateRequest is the pure (no HTTP) validation core; it returns the
// per-field failures so callers that want a different response shape
// (e.g. tests) can use it directly.
func validateRequest(req *InitiateUploadRequest, maxUploadSize int64, allowedMIMEs []string) []httperr.ValidationDetail {
	var details []httperr.ValidationDetail
	if l := len(req.Name); l < 1 || l > 255 {
		details = append(details, httperr.ValidationDetail{Field: "name", Issue: "OUT_OF_RANGE"})
	}
	if req.MimeType == "" {
		details = append(details, httperr.ValidationDetail{Field: "mime_type", Issue: "REQUIRED"})
	} else if !mimeAllowed(req.MimeType, allowedMIMEs) {
		details = append(details, httperr.ValidationDetail{Field: "mime_type", Issue: "MIME_TYPE_NOT_ALLOWED"})
	}
	if req.SizeBytes <= 0 {
		details = append(details, httperr.ValidationDetail{Field: "size_bytes", Issue: "OUT_OF_RANGE"})
	} else if req.SizeBytes > maxUploadSize {
		details = append(details, httperr.ValidationDetail{Field: "size_bytes", Issue: "FILE_TOO_LARGE"})
	}
	if !IsValidRetentionClass(req.RetentionClass) {
		details = append(details, httperr.ValidationDetail{Field: "retention_class", Issue: "OUT_OF_RANGE"})
	}
	if req.OwnerID == "" {
		details = append(details, httperr.ValidationDetail{Field: "owner_id", Issue: "REQUIRED"})
	}
	if req.OwnerType == "" {
		details = append(details, httperr.ValidationDetail{Field: "owner_type", Issue: "REQUIRED"})
	}
	return details
}

func mimeAllowed(mime string, allow []string) bool {
	for _, a := range allow {
		if a == mime {
			return true
		}
	}
	return false
}
