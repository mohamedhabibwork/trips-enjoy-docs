package storage_test

import (
	"context"
	"errors"
	"io"
	"testing"
	"time"

	"github.com/trips-enjoy/platform/file-service/internal/storage"
)

// stubDriver satisfies storage.StorageDriver for testing.
type stubDriver struct{ id string }

func (s *stubDriver) InitiateUpload(context.Context, storage.InitiateUploadRequest) (storage.InitiateUploadResponse, error) {
	return storage.InitiateUploadResponse{UploadURL: "stub://" + s.id}, nil
}
func (s *stubDriver) CompleteUpload(context.Context, storage.CompleteUploadRequest) (storage.ObjectMetadata, error) {
	return storage.ObjectMetadata{Exists: true}, nil
}
func (s *stubDriver) GetObject(context.Context, storage.DriverLocator) (io.ReadCloser, error) {
	return nil, nil
}
func (s *stubDriver) PutObject(context.Context, storage.DriverLocator, io.Reader, string) error {
	return nil
}
func (s *stubDriver) DeleteObject(context.Context, storage.DriverLocator) error { return nil }
func (s *stubDriver) HeadObject(context.Context, storage.DriverLocator) (storage.ObjectMetadata, error) {
	return storage.ObjectMetadata{}, nil
}
func (s *stubDriver) CreateSignedURL(context.Context, storage.DriverLocator, time.Duration, string) (string, error) {
	return "stub://signed/" + s.id, nil
}
func (s *stubDriver) Probe(context.Context) storage.ProbeResult {
	return storage.ProbeResult{Healthy: true}
}
func (s *stubDriver) Shutdown(context.Context) error { return nil }

func TestRegistryResolveAndDefault(t *testing.T) {
	r := storage.NewRegistry()
	r.Register(storage.DriverSpec{ID: "a", Kind: "stub", State: "enabled", Priority: 100}, &stubDriver{id: "a"})
	r.Register(storage.DriverSpec{ID: "b", Kind: "stub", State: "enabled", Priority: 200, IsDefault: true}, &stubDriver{id: "b"})

	if r.DefaultID() != "b" {
		t.Fatalf("DefaultID = %q, want b", r.DefaultID())
	}
	if d, err := r.Default(); err != nil || d == nil {
		t.Fatalf("Default: %v", err)
	}
	if _, err := r.Resolve("missing"); !errors.Is(err, storage.ErrDriverNotFound) {
		t.Fatalf("expected ErrDriverNotFound, got %v", err)
	}
}

func TestRegistryListSpecsSortedByPriority(t *testing.T) {
	r := storage.NewRegistry()
	r.Register(storage.DriverSpec{ID: "z", Kind: "stub", Priority: 300}, &stubDriver{})
	r.Register(storage.DriverSpec{ID: "a", Kind: "stub", Priority: 100}, &stubDriver{})
	r.Register(storage.DriverSpec{ID: "m", Kind: "stub", Priority: 200}, &stubDriver{})

	specs := r.ListSpecs()
	if len(specs) != 3 {
		t.Fatalf("len = %d, want 3", len(specs))
	}
	want := []string{"a", "m", "z"}
	for i, id := range want {
		if specs[i].ID != id {
			t.Fatalf("specs[%d] = %q, want %q", i, specs[i].ID, id)
		}
	}
}
