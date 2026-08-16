package httpapi_test

import (
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"testing"

	"github.com/trips-enjoy/platform/file-service/internal/admin"
	"github.com/trips-enjoy/platform/file-service/internal/events"
	"github.com/trips-enjoy/platform/file-service/internal/files"
	"github.com/trips-enjoy/platform/file-service/internal/httpapi"
	"github.com/trips-enjoy/platform/file-service/internal/storage"
	"github.com/trips-enjoy/platform/file-service/internal/storage/drivers/inmem"
)

// newTestRouter builds a minimal router wired to the inmem driver so the
// tests can verify the request-id + CORS + metrics middleware without a
// running DB.
func newTestRouter() (http.Handler, *storage.Registry, *files.Service, *admin.Service) {
	reg := storage.NewRegistry()
	reg.Register(storage.DriverSpec{ID: "inmem", Kind: "inmem", State: "enabled", Priority: 100, IsDefault: true, Health: "healthy", SignedURLTTLSecs: 900}, inmem.New())

	publisher := events.NewStdoutPublisher("file-service-test", events.TopicMap{
		Uploaded: "file.file.uploaded", Scanned: "file.file.scanned", Deleted: "file.file.deleted", Migrated: "file.file.migrated",
	})
	repo := files.NewInMemoryRepo()
	fs := files.NewService(repo, reg, publisher, []string{"image/jpeg", "application/pdf"}, 1024*1024, 5*1024*1024, 15*60*1_000_000_000)
	as := admin.NewService(reg, fs)
	router := httpapi.NewRouter(httpapi.Deps{ServiceName: "file-service-test", Drivers: reg, FilesService: fs, AdminService: as, Metrics: httpapi.NewMetrics()})
	return router, reg, fs, as
}

func TestRequestIDGeneratesAndReturnsBothHeaders(t *testing.T) {
	router, _, _, _ := newTestRouter()
	resp := httptest.NewRecorder()
	router.ServeHTTP(resp, httptest.NewRequest(http.MethodGet, "/health", nil))

	got := resp.Header().Get("X-Request-Id")
	if !regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`).MatchString(got) {
		t.Fatalf("request id %q is not a UUIDv7", got)
	}
	if alias := resp.Header().Get("X-Correlation-Id"); alias != got {
		t.Fatalf("correlation id = %q, want %q", alias, got)
	}
}

func TestRequestIDPrefersPrimaryHeader(t *testing.T) {
	router, _, _, _ := newTestRouter()
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	req.Header.Set("X-Request-Id", "primary")
	req.Header.Set("X-Correlation-Id", "alias")
	resp := httptest.NewRecorder()

	router.ServeHTTP(resp, req)

	if got := resp.Header().Get("X-Request-Id"); got != "primary" {
		t.Fatalf("request id = %q, want primary", got)
	}
}

func TestUnknownRouteReturns404(t *testing.T) {
	router, _, _, _ := newTestRouter()
	req := httptest.NewRequest(http.MethodGet, "/v1/unknown", nil)
	req.Header.Set("X-User-Id", "u-1")
	resp := httptest.NewRecorder()

	router.ServeHTTP(resp, req)

	if resp.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", resp.Code)
	}
}

func TestMetricsReportRequestsAndErrors(t *testing.T) {
	router, _, _, _ := newTestRouter()
	router.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/health", nil))
	router.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/unknown", nil))

	resp := httptest.NewRecorder()
	router.ServeHTTP(resp, httptest.NewRequest(http.MethodGet, "/metrics", nil))
	body := resp.Body.String()
	for _, expect := range []string{
		`file_service_requests_total{method="GET",route="/health",status="200"} 1`,
		`file_service_errors_total{method="GET",route="unmatched",status="404"} 1`,
	} {
		if !strings.Contains(body, expect) {
			t.Errorf("metrics response does not contain %q:\n%s", expect, body)
		}
	}
}

func TestInitiateUploadRequiresAuth(t *testing.T) {
	router, _, _, _ := newTestRouter()
	resp := httptest.NewRecorder()
	router.ServeHTTP(resp, httptest.NewRequest(http.MethodPost, "/v1/files", nil))
	if resp.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", resp.Code)
	}
}

func TestInitiateUploadSucceedsWithStubAuth(t *testing.T) {
	router, _, _, _ := newTestRouter()
	req := httptest.NewRequest(http.MethodPost, "/v1/files",
		strings.NewReader(`{"name":"avatar.jpg","mime_type":"image/jpeg","size_bytes":1024,"sha256":"abc","owner_id":"00000000-0000-0000-0000-000000000001","owner_type":"customer","retention_class":"avatar"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-User-Id", "u-1")
	resp := httptest.NewRecorder()
	router.ServeHTTP(resp, req)
	if resp.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body=%s", resp.Code, resp.Body.String())
	}
}

func TestSignedURL404ForUnknownFile(t *testing.T) {
	router, _, _, _ := newTestRouter()
	req := httptest.NewRequest(http.MethodPost, "/v1/files/00000000-0000-0000-0000-000000000099/signed-url",
		strings.NewReader(`{"ttl_seconds":900,"purpose":"download"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-User-Id", "u-1")
	resp := httptest.NewRecorder()
	router.ServeHTTP(resp, req)
	if resp.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404; body=%s", resp.Code, resp.Body.String())
	}
}
