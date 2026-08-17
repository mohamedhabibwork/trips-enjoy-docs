// Package inmem is an in-memory StorageDriver used for tests + offline dev.
// It satisfies the full StorageDriver interface but stores bytes in a
// process-local map; nothing is persisted across restarts.
package inmem

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"sync"
	"time"

	"github.com/trips-enjoy/platform/file-service/internal/storage"
)

// Driver is the in-memory StorageDriver implementation.
type Driver struct {
	mu      sync.RWMutex
	objects map[string][]byte
}

// New returns an empty in-memory driver.
func New() *Driver {
	return &Driver{objects: map[string][]byte{}}
}

// objectKey reduces a DriverLocator to a stable string. Real drivers use
// their SDK's addressing scheme; here we concatenate the well-known fields
// so two callers using the same locator hit the same byte slice.
func objectKey(loc storage.DriverLocator) string {
	if loc == nil {
		return ""
	}
	key, _ := loc["key"].(string)
	if key == "" {
		key, _ = loc["path"].(string)
	}
	bucket, _ := loc["bucket"].(string)
	if bucket != "" {
		return bucket + "/" + key
	}
	return key
}

func (d *Driver) InitiateUpload(_ context.Context, req storage.InitiateUploadRequest) (storage.InitiateUploadResponse, error) {
	return storage.InitiateUploadResponse{
		UploadURL:     fmt.Sprintf("mem://upload/%s", req.FileID),
		DriverLocator: storage.DriverLocator{"key": req.FileID, "bucket": "inmem"},
		ExpiresAt:     time.Now().Add(15 * time.Minute),
	}, nil
}

func (d *Driver) CompleteUpload(_ context.Context, req storage.CompleteUploadRequest) (storage.ObjectMetadata, error) {
	d.mu.RLock()
	defer d.mu.RUnlock()
	objectBytes, ok := d.objects[objectKey(req.DriverLocator)]
	if !ok {
		return storage.ObjectMetadata{Exists: false}, nil
	}
	sum := sha256.Sum256(objectBytes)
	return storage.ObjectMetadata{
		SizeBytes: int64(len(objectBytes)),
		SHA256:    hex.EncodeToString(sum[:]),
		ETag:      hex.EncodeToString(sum[:16]),
		Exists:    true,
	}, nil
}

func (d *Driver) GetObject(_ context.Context, loc storage.DriverLocator) (io.ReadCloser, error) {
	d.mu.RLock()
	defer d.mu.RUnlock()
	objectBytes, ok := d.objects[objectKey(loc)]
	if !ok {
		return nil, errors.New("object not found")
	}
	return io.NopCloser(bytes.NewReader(objectBytes)), nil
}

func (d *Driver) PutObject(_ context.Context, loc storage.DriverLocator, src io.Reader, _ string) error {
	objectBytes, err := io.ReadAll(src)
	if err != nil {
		return err
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	d.objects[objectKey(loc)] = objectBytes
	return nil
}

func (d *Driver) DeleteObject(_ context.Context, loc storage.DriverLocator) error {
	d.mu.Lock()
	defer d.mu.Unlock()
	delete(d.objects, objectKey(loc))
	return nil
}

func (d *Driver) HeadObject(ctx context.Context, loc storage.DriverLocator) (storage.ObjectMetadata, error) {
	return d.CompleteUpload(ctx, storage.CompleteUploadRequest{DriverLocator: loc})
}

func (d *Driver) CreateSignedURL(_ context.Context, loc storage.DriverLocator, ttl time.Duration, _ string) (string, error) {
	return fmt.Sprintf("mem://signed/%s?ttl=%ds", objectKey(loc), int(ttl.Seconds())), nil
}

func (d *Driver) Probe(_ context.Context) storage.ProbeResult {
	return storage.ProbeResult{Healthy: true, LatencyMS: 0}
}

func (d *Driver) Shutdown(_ context.Context) error { return nil }
