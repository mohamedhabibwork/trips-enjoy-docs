package local_fs_test

import (
	"bytes"
	"context"
	"io"
	"testing"
	"time"

	"github.com/trips-enjoy/platform/file-service/internal/storage"
	"github.com/trips-enjoy/platform/file-service/internal/storage/drivers/local_fs"
)

func TestRoundTrip(t *testing.T) {
	dir := t.TempDir()
	d, err := local_fs.New(dir, []byte("test-key"))
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	t.Cleanup(func() { _ = d.Shutdown(context.Background()) })

	locator := storage.DriverLocator{"path": "round-trip.bin"}
	payload := []byte("hello, local_fs")

	if err := d.PutObject(context.Background(), locator, bytes.NewReader(payload), ""); err != nil {
		t.Fatalf("PutObject: %v", err)
	}

	meta, err := d.HeadObject(context.Background(), locator)
	if err != nil {
		t.Fatalf("HeadObject: %v", err)
	}
	if meta.SizeBytes != int64(len(payload)) {
		t.Fatalf("SizeBytes = %d, want %d", meta.SizeBytes, len(payload))
	}
	if meta.SHA256 == "" {
		t.Fatalf("SHA256 empty")
	}

	rc, err := d.GetObject(context.Background(), locator)
	if err != nil {
		t.Fatalf("GetObject: %v", err)
	}
	defer rc.Close()
	got, err := io.ReadAll(rc)
	if err != nil {
		t.Fatalf("ReadAll: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("bytes = %q, want %q", got, payload)
	}

	if err := d.DeleteObject(context.Background(), locator); err != nil {
		t.Fatalf("DeleteObject: %v", err)
	}
}

func TestInitiateUploadReturnsHMACSignedURL(t *testing.T) {
	d, err := local_fs.New(t.TempDir(), []byte("test-key"))
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	t.Cleanup(func() { _ = d.Shutdown(context.Background()) })

	resp, err := d.InitiateUpload(context.Background(), storage.InitiateUploadRequest{
		FileID:    "abc-123",
		MimeType:  "image/jpeg",
		SizeBytes: 1024,
	})
	if err != nil {
		t.Fatalf("InitiateUpload: %v", err)
	}
	if resp.UploadURL == "" {
		t.Fatalf("UploadURL empty")
	}
	if resp.ExpiresAt.Before(time.Now()) {
		t.Fatalf("ExpiresAt = %v in the past", resp.ExpiresAt)
	}
}

func TestProbeHealthy(t *testing.T) {
	d, err := local_fs.New(t.TempDir(), []byte("k"))
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	t.Cleanup(func() { _ = d.Shutdown(context.Background()) })
	res := d.Probe(context.Background())
	if !res.Healthy {
		t.Fatalf("Probe not healthy: %v", res.Error)
	}
}
