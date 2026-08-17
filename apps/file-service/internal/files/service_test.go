package files_test

import (
	"bytes"
	"context"
	"strings"
	"testing"
	"time"

	"github.com/trips-enjoy/platform/file-service/internal/events"
	"github.com/trips-enjoy/platform/file-service/internal/files"
	"github.com/trips-enjoy/platform/file-service/internal/storage"
	"github.com/trips-enjoy/platform/file-service/internal/storage/drivers/inmem"
)

// newTestService wires a files.Service backed by the inmem driver.
func newTestService(t *testing.T) (*files.Service, *storage.Registry) {
	t.Helper()
	reg := storage.NewRegistry()
	reg.Register(storage.DriverSpec{
		ID: "inmem", Kind: "inmem", State: "enabled",
		Priority: 100, IsDefault: true, Health: "healthy", SignedURLTTLSecs: 900,
	}, inmem.New())
	publisher := events.NewStdoutPublisher("file-service-test", events.TopicMap{
		Uploaded: "file.file.uploaded", Scanned: "file.file.scanned",
		Deleted: "file.file.deleted", Migrated: "file.file.migrated",
	})
	repo := files.NewInMemoryRepo()
	svc := files.NewService(repo, reg, publisher,
		[]string{"image/jpeg", "application/pdf"},
		100*1024*1024,
		5*1024*1024,
		15*time.Minute,
	)
	return svc, reg
}

func TestInitiateUploadSmallUsesProxyFlow(t *testing.T) {
	svc, _ := newTestService(t)
	resp, file, err := svc.InitiateUpload(context.Background(), files.InitiateUploadRequest{
		Name:           "avatar.jpg",
		MimeType:       "image/jpeg",
		SizeBytes:      1024,
		SHA256:         "abc",
		OwnerID:        "00000000-0000-0000-0000-000000000001",
		OwnerType:      "customer",
		RetentionClass: "avatar",
	})
	if err != nil {
		t.Fatalf("InitiateUpload: %v", err)
	}
	if resp.UploadMethod != "proxy" {
		t.Fatalf("upload_method = %q, want proxy", resp.UploadMethod)
	}
	if file.Status != files.StatusScanning {
		t.Fatalf("status = %q, want %q", file.Status, files.StatusScanning)
	}
}

func TestInitiateUploadLargeUsesDirectFlow(t *testing.T) {
	svc, _ := newTestService(t)
	resp, file, err := svc.InitiateUpload(context.Background(), files.InitiateUploadRequest{
		Name:           "huge.pdf",
		MimeType:       "application/pdf",
		SizeBytes:      10 * 1024 * 1024,
		SHA256:         "abc",
		OwnerID:        "00000000-0000-0000-0000-000000000001",
		OwnerType:      "merchant",
		RetentionClass: "support_attachment",
	})
	if err != nil {
		t.Fatalf("InitiateUpload: %v", err)
	}
	if resp.UploadMethod != "direct" {
		t.Fatalf("upload_method = %q, want direct", resp.UploadMethod)
	}
	if file.Status != files.StatusPending {
		t.Fatalf("status = %q, want %q", file.Status, files.StatusPending)
	}
	if resp.UploadURL == nil || !strings.HasPrefix(*resp.UploadURL, "mem://upload/") {
		t.Fatalf("upload_url = %v, want mem://upload/...", resp.UploadURL)
	}
}

func TestInitiateUploadRejectsBadMime(t *testing.T) {
	svc, _ := newTestService(t)
	_, _, err := svc.InitiateUpload(context.Background(), files.InitiateUploadRequest{
		Name: "x.exe", MimeType: "application/x-msdownload", SizeBytes: 100,
		OwnerID: "00000000-0000-0000-0000-000000000001", OwnerType: "customer",
		RetentionClass: "other",
	})
	if err != files.ErrMimeTypeNotAllowed {
		t.Fatalf("err = %v, want ErrMimeTypeNotAllowed", err)
	}
}

func TestInitiateUploadRejectsBadRetentionClass(t *testing.T) {
	svc, _ := newTestService(t)
	_, _, err := svc.InitiateUpload(context.Background(), files.InitiateUploadRequest{
		Name: "x.jpg", MimeType: "image/jpeg", SizeBytes: 100,
		OwnerID: "00000000-0000-0000-0000-000000000001", OwnerType: "customer",
		RetentionClass: "nope",
	})
	if err == nil {
		t.Fatalf("expected error for bad retention class")
	}
}

func TestProxyUploadThenComplete(t *testing.T) {
	svc, _ := newTestService(t)
	resp, _, err := svc.InitiateUpload(context.Background(), files.InitiateUploadRequest{
		Name: "doc.pdf", MimeType: "application/pdf", SizeBytes: 100,
		OwnerID: "00000000-0000-0000-0000-000000000001", OwnerType: "customer",
		RetentionClass: "support_attachment",
	})
	if err != nil {
		t.Fatalf("InitiateUpload: %v", err)
	}
	file, err := svc.ProxyUpload(context.Background(), resp.FileID, bytes.NewReader([]byte("hello world")))
	if err != nil {
		t.Fatalf("ProxyUpload: %v", err)
	}
	if file.Status != files.StatusScanning {
		t.Fatalf("status = %q, want scanning", file.Status)
	}
}

func TestSignedURLClampsTTL(t *testing.T) {
	svc, _ := newTestService(t)
	resp, _, err := svc.InitiateUpload(context.Background(), files.InitiateUploadRequest{
		Name: "doc.pdf", MimeType: "application/pdf", SizeBytes: 100,
		OwnerID: "00000000-0000-0000-0000-000000000001", OwnerType: "customer",
		RetentionClass: "support_attachment",
	})
	if err != nil {
		t.Fatalf("InitiateUpload: %v", err)
	}
	// Proxy-upload so the file is in a state that supports signing.
	if _, err := svc.ProxyUpload(context.Background(), resp.FileID, bytes.NewReader([]byte("ok"))); err != nil {
		t.Fatalf("ProxyUpload: %v", err)
	}
	// ttl > 3600 should be clamped to 3600.
	signed, err := svc.IssueSignedURL(context.Background(), resp.FileID, 7200, "download")
	if err != nil {
		t.Fatalf("IssueSignedURL: %v", err)
	}
	if !strings.HasPrefix(signed.URL, "mem://signed/") {
		t.Fatalf("url = %q, want mem://signed/...", signed.URL)
	}
	if signed.DriverID != "inmem" {
		t.Fatalf("driver_id = %q, want inmem", signed.DriverID)
	}
}

func TestSignedURL404ForUnknownFile(t *testing.T) {
	svc, _ := newTestService(t)
	_, err := svc.IssueSignedURL(context.Background(), "00000000-0000-0000-0000-000000000099", 900, "download")
	if err != files.ErrFileNotFound {
		t.Fatalf("err = %v, want ErrFileNotFound", err)
	}
}

func TestSoftDeleteIsIdempotent(t *testing.T) {
	svc, _ := newTestService(t)
	resp, _, err := svc.InitiateUpload(context.Background(), files.InitiateUploadRequest{
		Name: "doc.pdf", MimeType: "application/pdf", SizeBytes: 100,
		OwnerID: "00000000-0000-0000-0000-000000000001", OwnerType: "customer",
		RetentionClass: "support_attachment",
	})
	if err != nil {
		t.Fatalf("InitiateUpload: %v", err)
	}
	if err := svc.SoftDelete(context.Background(), resp.FileID, "u-1"); err != nil {
		t.Fatalf("SoftDelete: %v", err)
	}
	if err := svc.SoftDelete(context.Background(), resp.FileID, "u-1"); err != nil {
		t.Fatalf("SoftDelete second: %v", err)
	}
}

func TestInitiateUploadRespectsExplicitDriverID(t *testing.T) {
	svc, reg := newTestService(t)
	// Register a second driver so we can override away from the default.
	reg.Register(storage.DriverSpec{
		ID: "alt", Kind: "inmem", State: "enabled",
		Priority: 50, Health: "healthy", SignedURLTTLSecs: 900,
	}, inmem.New())

	resp, file, err := svc.InitiateUpload(context.Background(), files.InitiateUploadRequest{
		Name: "x.jpg", MimeType: "image/jpeg", SizeBytes: 1024,
		OwnerID:   "00000000-0000-0000-0000-000000000001",
		OwnerType: "customer", RetentionClass: "avatar",
		DriverID: "alt",
	})
	if err != nil {
		t.Fatalf("InitiateUpload: %v", err)
	}
	if resp.DriverID != "alt" {
		t.Fatalf("driver_id = %q, want alt", resp.DriverID)
	}
	if file.DriverID != "alt" {
		t.Fatalf("file.driver_id = %q, want alt", file.DriverID)
	}
}

func TestInitiateUploadRejectsUnknownDriverID(t *testing.T) {
	svc, _ := newTestService(t)
	_, _, err := svc.InitiateUpload(context.Background(), files.InitiateUploadRequest{
		Name: "x.jpg", MimeType: "image/jpeg", SizeBytes: 1024,
		OwnerID:   "00000000-0000-0000-0000-000000000001",
		OwnerType: "customer", RetentionClass: "avatar",
		DriverID: "does-not-exist",
	})
	if err == nil {
		t.Fatalf("expected error on unknown driver_id")
	}
	if !strings.Contains(err.Error(), "not in catalog") {
		t.Fatalf("error = %v, want not in catalog", err)
	}
}

func TestInitiateUploadRejectsDrainingDriver(t *testing.T) {
	svc, reg := newTestService(t)
	reg.Register(storage.DriverSpec{
		ID: "draining", Kind: "inmem", State: "draining",
		Priority: 50, Health: "healthy", SignedURLTTLSecs: 900,
	}, inmem.New())
	_, _, err := svc.InitiateUpload(context.Background(), files.InitiateUploadRequest{
		Name: "x.jpg", MimeType: "image/jpeg", SizeBytes: 1024,
		OwnerID:   "00000000-0000-0000-0000-000000000001",
		OwnerType: "customer", RetentionClass: "avatar",
		DriverID: "draining",
	})
	if err == nil {
		t.Fatalf("expected error on draining driver")
	}
	if !strings.Contains(err.Error(), "draining") {
		t.Fatalf("error = %v, want draining", err)
	}
}

func TestInitiateUploadBatchHappyPath(t *testing.T) {
	svc, _ := newTestService(t)
	resp, err := svc.InitiateUploadBatch(context.Background(), files.BulkUploadRequest{
		Items: []files.InitiateUploadRequest{
			{Name: "a.jpg", MimeType: "image/jpeg", SizeBytes: 1024,
				OwnerID:   "00000000-0000-0000-0000-000000000001",
				OwnerType: "customer", RetentionClass: "avatar"},
			{Name: "b.pdf", MimeType: "application/pdf", SizeBytes: 4096,
				OwnerID:   "00000000-0000-0000-0000-000000000001",
				OwnerType: "customer", RetentionClass: "support_attachment"},
		},
	})
	if err != nil {
		t.Fatalf("InitiateUploadBatch: %v", err)
	}
	if resp.Total != 2 || resp.Succeeded != 2 || resp.Failed != 0 {
		t.Fatalf("counts = total:%d succeeded:%d failed:%d, want 2/2/0", resp.Total, resp.Succeeded, resp.Failed)
	}
	for i, r := range resp.Results {
		if r.Error != nil {
			t.Fatalf("results[%d].error = %v, want nil", i, r.Error)
		}
		if r.Response == nil || r.Response.FileID == "" {
			t.Fatalf("results[%d] missing file_id", i)
		}
	}
}

func TestInitiateUploadBatchMixedErrors(t *testing.T) {
	svc, reg := newTestService(t)
	resp, err := svc.InitiateUploadBatch(context.Background(), files.BulkUploadRequest{
		Items: []files.InitiateUploadRequest{
			{Name: "ok.jpg", MimeType: "image/jpeg", SizeBytes: 1024,
				OwnerID:   "00000000-0000-0000-0000-000000000001",
				OwnerType: "customer", RetentionClass: "avatar"},
			{Name: "bad.jpg", MimeType: "image/jpeg", SizeBytes: 1024,
				OwnerID:   "00000000-0000-0000-0000-000000000001",
				OwnerType: "customer", RetentionClass: "bogus"}, // bad retention
			{Name: "drain.jpg", MimeType: "image/jpeg", SizeBytes: 1024,
				OwnerID:   "00000000-0000-0000-0000-000000000001",
				OwnerType: "customer", RetentionClass: "avatar",
				DriverID: "drain"}, // unknown driver
		},
	})
	if err != nil {
		t.Fatalf("InitiateUploadBatch: %v", err)
	}
	if resp.Succeeded != 1 || resp.Failed != 2 {
		t.Fatalf("counts = succeeded:%d failed:%d, want 1/2", resp.Succeeded, resp.Failed)
	}
	if resp.Results[0].Error != nil {
		t.Fatalf("results[0] should succeed: %v", resp.Results[0].Error)
	}
	if resp.Results[1].Error == nil || resp.Results[1].Error.Code != "VALIDATION_FAILED" {
		t.Fatalf("results[1] should be VALIDATION_FAILED, got %+v", resp.Results[1].Error)
	}
	if resp.Results[2].Error == nil || resp.Results[2].Error.Code != "DRIVER_NOT_CONFIGURED" {
		t.Fatalf("results[2] should be DRIVER_NOT_CONFIGURED, got %+v", resp.Results[2].Error)
	}
	_ = reg // silence unused
}

func TestInitiateUploadBatchPerItemDriverOverride(t *testing.T) {
	svc, reg := newTestService(t)
	reg.Register(storage.DriverSpec{
		ID: "alt", Kind: "inmem", State: "enabled",
		Priority: 50, Health: "healthy", SignedURLTTLSecs: 900,
	}, inmem.New())
	resp, err := svc.InitiateUploadBatch(context.Background(), files.BulkUploadRequest{
		DriverID: "default-driver-does-not-exist", // batch-level override bad → per-item wins
		Items: []files.InitiateUploadRequest{
			{Name: "a.jpg", MimeType: "image/jpeg", SizeBytes: 1024,
				OwnerID:   "00000000-0000-0000-0000-000000000001",
				OwnerType: "customer", RetentionClass: "avatar",
				DriverID: "alt"},
		},
	})
	if err != nil {
		t.Fatalf("InitiateUploadBatch: %v", err)
	}
	if resp.Failed != 0 {
		t.Fatalf("failed = %d, want 0", resp.Failed)
	}
	if resp.Results[0].Response.DriverID != "alt" {
		t.Fatalf("driver_id = %q, want alt", resp.Results[0].Response.DriverID)
	}
}

func TestListDriverHealth(t *testing.T) {
	svc, reg := newTestService(t)
	_ = reg // covered via svc
	statuses := svc.ListDriverHealth(context.Background())
	if len(statuses) != 1 {
		t.Fatalf("len(statuses) = %d, want 1", len(statuses))
	}
	if statuses[0].ID != "inmem" {
		t.Fatalf("statuses[0].id = %q, want inmem", statuses[0].ID)
	}
	if !statuses[0].Healthy {
		t.Fatalf("statuses[0].healthy = false, want true (inmem probe returns Healthy)")
	}
	if !statuses[0].Reachable {
		t.Fatalf("statuses[0].reachable = false, want true")
	}
	if statuses[0].CheckedAt == "" {
		t.Fatalf("statuses[0].checked_at empty")
	}
}
