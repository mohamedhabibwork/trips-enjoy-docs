package gcs_test

import (
	"bytes"
	"context"
	"io"
	"strings"
	"testing"
	"time"

	gcsstorage "cloud.google.com/go/storage"

	"github.com/trips-enjoy/platform/file-service/internal/storage"
	driver "github.com/trips-enjoy/platform/file-service/internal/storage/drivers/gcs"
)

// fakeClient implements driver.Client with an in-memory map.
type fakeClient struct {
	objects map[string]map[string][]byte // bucket → object → bytes
	putErr  error
	getErr  error
	delErr  error
}

func newFakeClient() *fakeClient { return &fakeClient{objects: map[string]map[string][]byte{}} }

func (f *fakeClient) Bucket(name string) driver.BucketHandle {
	return &fakeBucketHandle{client: f, bucket: name}
}

func (f *fakeClient) Close() error { return nil }

type fakeBucketHandle struct {
	client *fakeClient
	bucket string
}

func (f *fakeBucketHandle) SignedURL(object string, _ *gcsstorage.SignedURLOptions) (string, error) {
	return "https://fake.storage.googleapis.com/" + f.bucket + "/" + object + "?X-Goog-Signature=fake", nil
}

func (f *fakeBucketHandle) Object(name string) driver.ObjectHandle {
	return &fakeObjectHandle{client: f.client, bucket: f.bucket, object: name}
}

type fakeObjectHandle struct {
	client *fakeClient
	bucket string
	object string
}

func (f *fakeObjectHandle) NewReader(_ context.Context) (io.ReadCloser, error) {
	if f.client.getErr != nil {
		return nil, f.client.getErr
	}
	bucket := f.client.objects[f.bucket]
	if bucket == nil {
		return nil, gcsstorage.ErrObjectNotExist
	}
	body, ok := bucket[f.object]
	if !ok {
		return nil, gcsstorage.ErrObjectNotExist
	}
	return io.NopCloser(bytes.NewReader(body)), nil
}

func (f *fakeObjectHandle) NewWriter(_ context.Context) (driver.ObjectWriter, error) {
	if f.client.putErr != nil {
		return nil, f.client.putErr
	}
	return &fakeWriter{client: f.client, bucket: f.bucket, object: f.object}, nil
}

func (f *fakeObjectHandle) Delete(_ context.Context) error {
	if f.client.delErr != nil {
		return f.client.delErr
	}
	if bucket := f.client.objects[f.bucket]; bucket != nil {
		delete(bucket, f.object)
	}
	return nil
}

func (f *fakeObjectHandle) Attrs(_ context.Context) (*gcsstorage.ObjectAttrs, error) {
	bucket := f.client.objects[f.bucket]
	if bucket == nil {
		return nil, gcsstorage.ErrObjectNotExist
	}
	body, ok := bucket[f.object]
	if !ok {
		return nil, gcsstorage.ErrObjectNotExist
	}
	return &gcsstorage.ObjectAttrs{Size: int64(len(body)), Etag: `"gcs-etag"`}, nil
}

// fakeWriter satisfies io.WriteCloser; the real *storage.Writer also
// has KMSKeyName + Attrs() methods we ignore in the test fake.
type fakeWriter struct {
	client *fakeClient
	bucket string
	object string
	buf    bytes.Buffer
	closed bool
}

func (w *fakeWriter) Write(p []byte) (int, error) { return w.buf.Write(p) }
func (w *fakeWriter) Close() error {
	if w.closed {
		return nil
	}
	w.closed = true
	if w.client.objects[w.bucket] == nil {
		w.client.objects[w.bucket] = map[string][]byte{}
	}
	w.client.objects[w.bucket][w.object] = append([]byte(nil), w.buf.Bytes()...)
	return nil
}

func newTestDriver() *driver.Driver {
	return driver.NewWithClient(driver.Options{
		Bucket:              "test-bucket",
		ServiceAccountEmail: "tester@test.iam.gserviceaccount.com",
		PrivateKeyPEM:       []byte("-----BEGIN PRIVATE KEY-----\nfake\n-----END PRIVATE KEY-----\n"),
		PresignExpiry:       5 * time.Minute,
	}, newFakeClient())
}

func gcsLocator(bucket, object string) storage.DriverLocator {
	return storage.DriverLocator{"bucket": bucket, "object": object}
}

func TestGCSRoundTrip(t *testing.T) {
	ctx := context.Background()
	d := newTestDriver()
	loc := gcsLocator("test-bucket", "abc-123")
	payload := []byte("hello, gcs")

	if err := d.PutObject(ctx, loc, bytes.NewReader(payload), ""); err != nil {
		t.Fatalf("PutObject: %v", err)
	}

	meta, err := d.HeadObject(ctx, loc)
	if err != nil {
		t.Fatalf("HeadObject: %v", err)
	}
	if meta.SizeBytes != int64(len(payload)) {
		t.Fatalf("SizeBytes = %d, want %d", meta.SizeBytes, len(payload))
	}
	if meta.ETag != `"gcs-etag"` {
		t.Fatalf("ETag = %q, want %q", meta.ETag, `"gcs-etag"`)
	}
	if !meta.Exists {
		t.Fatalf("meta.Exists = false, want true")
	}

	rc, err := d.GetObject(ctx, loc)
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

	if err := d.DeleteObject(ctx, loc); err != nil {
		t.Fatalf("DeleteObject: %v", err)
	}

	meta, err = d.HeadObject(ctx, loc)
	if err != nil {
		t.Fatalf("HeadObject after delete: %v", err)
	}
	if meta.Exists {
		t.Fatalf("meta.Exists = true after delete, want false")
	}
}

func TestGCSPutWithCMEK(t *testing.T) {
	ctx := context.Background()
	d := newTestDriver()
	loc := gcsLocator("test-bucket", "kyc-789")
	payload := []byte("kyc-bytes")
	if err := d.PutObject(ctx, loc, bytes.NewReader(payload), "kms-key-1"); err != nil {
		t.Fatalf("PutObject: %v", err)
	}
	meta, err := d.HeadObject(ctx, loc)
	if err != nil {
		t.Fatalf("HeadObject: %v", err)
	}
	if meta.SizeBytes != int64(len(payload)) {
		t.Fatalf("SizeBytes = %d, want %d", meta.SizeBytes, len(payload))
	}
}

func TestGCSCompleteUpload404IsExistsFalse(t *testing.T) {
	ctx := context.Background()
	d := newTestDriver()
	loc := gcsLocator("test-bucket", "missing")
	meta, err := d.CompleteUpload(ctx, storage.CompleteUploadRequest{DriverLocator: loc})
	if err != nil {
		t.Fatalf("CompleteUpload: %v", err)
	}
	if meta.Exists {
		t.Fatalf("Exists = true, want false")
	}
}

func TestGCSDeleteIsIdempotent(t *testing.T) {
	ctx := context.Background()
	d := newTestDriver()
	loc := gcsLocator("test-bucket", "missing")
	if err := d.DeleteObject(ctx, loc); err != nil {
		t.Fatalf("DeleteObject missing: %v", err)
	}
}

func TestGCSProbeHealthy(t *testing.T) {
	ctx := context.Background()
	d := newTestDriver()
	res := d.Probe(ctx)
	if !res.Healthy {
		t.Fatalf("Probe not healthy: %v", res.Error)
	}
}

func TestGCSProbeUnhealthyOnMissingConfig(t *testing.T) {
	ctx := context.Background()
	d := driver.NewWithClient(driver.Options{
		Bucket:              "", // intentionally empty
		ServiceAccountEmail: "tester@test.iam.gserviceaccount.com",
		PrivateKeyPEM:       []byte("k"),
	}, newFakeClient())
	res := d.Probe(ctx)
	if res.Healthy {
		t.Fatalf("Probe healthy, want unhealthy")
	}
}

func TestGCSLocatorMissingObject(t *testing.T) {
	ctx := context.Background()
	d := newTestDriver()
	_, err := d.HeadObject(ctx, storage.DriverLocator{"bucket": "x"})
	if err == nil {
		t.Fatalf("expected error on locator without object")
	}
}

func TestGCSShutdownNoError(t *testing.T) {
	d := newTestDriver()
	if err := d.Shutdown(context.Background()); err != nil {
		t.Fatalf("Shutdown: %v", err)
	}
}

func TestGCSInitiateUploadReturnsLocator(t *testing.T) {
	ctx := context.Background()
	d := newTestDriver()
	resp, err := d.InitiateUpload(ctx, storage.InitiateUploadRequest{
		FileID: "abc-123", MimeType: "image/jpeg", SizeBytes: 1024,
	})
	if err != nil {
		t.Fatalf("InitiateUpload: %v", err)
	}
	if resp.DriverLocator["object"] != "abc-123" {
		t.Fatalf("locator.object = %v, want abc-123", resp.DriverLocator["object"])
	}
	if !strings.Contains(resp.UploadURL, "abc-123") {
		t.Fatalf("UploadURL does not include object: %q", resp.UploadURL)
	}
}

func TestGCSCreateSignedURL(t *testing.T) {
	ctx := context.Background()
	d := newTestDriver()
	loc := gcsLocator("test-bucket", "abc-123")
	url, err := d.CreateSignedURL(ctx, loc, 5*time.Minute, "get")
	if err != nil {
		t.Fatalf("CreateSignedURL: %v", err)
	}
	if !strings.Contains(url, "abc-123") {
		t.Fatalf("URL does not include object: %q", url)
	}
}
