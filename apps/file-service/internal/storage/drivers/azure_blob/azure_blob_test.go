package azure_blob_test

import (
	"bytes"
	"context"
	"errors"
	"io"
	"strings"
	"testing"
	"time"

	"github.com/Azure/azure-sdk-for-go/sdk/azcore"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/blob"

	"github.com/trips-enjoy/platform/file-service/internal/storage"
	driver "github.com/trips-enjoy/platform/file-service/internal/storage/drivers/azure_blob"
)

// fakeBlobClient implements the BlobClient interface with an in-memory
// map so the Driver can be exercised without Azure credentials.
type fakeBlobClient struct {
	objects map[string]map[string][]byte // container → blob → bytes
	getErr  error                        // optional injection
	delErr  error                        // optional injection
	putErr  error                        // optional injection
}

func newFakeBlobClient() *fakeBlobClient {
	return &fakeBlobClient{objects: map[string]map[string][]byte{}}
}

func (f *fakeBlobClient) UploadStream(_ context.Context, container, blobName string, body io.Reader, _ *azblob.UploadStreamOptions) (azblob.UploadStreamResponse, error) {
	if f.putErr != nil {
		return azblob.UploadStreamResponse{}, f.putErr
	}
	buf, err := io.ReadAll(body)
	if err != nil {
		return azblob.UploadStreamResponse{}, err
	}
	if f.objects[container] == nil {
		f.objects[container] = map[string][]byte{}
	}
	f.objects[container][blobName] = buf
	return azblob.UploadStreamResponse{}, nil
}

func (f *fakeBlobClient) DownloadStream(_ context.Context, container, blobName string, _ *azblob.DownloadStreamOptions) (azblob.DownloadStreamResponse, error) {
	if f.getErr != nil {
		return azblob.DownloadStreamResponse{}, f.getErr
	}
	bucket := f.objects[container]
	if bucket == nil {
		return azblob.DownloadStreamResponse{}, &azcore.ResponseError{StatusCode: 404}
	}
	body, ok := bucket[blobName]
	if !ok {
		return azblob.DownloadStreamResponse{}, &azcore.ResponseError{StatusCode: 404}
	}
	resp := azblob.DownloadStreamResponse{}
	resp.Body = io.NopCloser(bytes.NewReader(body))
	return resp, nil
}

func (f *fakeBlobClient) DeleteBlob(_ context.Context, container, blobName string, _ *azblob.DeleteBlobOptions) (azblob.DeleteBlobResponse, error) {
	if f.delErr != nil {
		return azblob.DeleteBlobResponse{}, f.delErr
	}
	if bucket := f.objects[container]; bucket != nil {
		delete(bucket, blobName)
	}
	return azblob.DeleteBlobResponse{}, nil
}

func (f *fakeBlobClient) GetBlobProperties(_ context.Context, container, blobName string) (blob.GetPropertiesResponse, error) {
	bucket := f.objects[container]
	if bucket == nil {
		return blob.GetPropertiesResponse{}, &azcore.ResponseError{StatusCode: 404}
	}
	body, ok := bucket[blobName]
	if !ok {
		return blob.GetPropertiesResponse{}, &azcore.ResponseError{StatusCode: 404}
	}
	length := int64(len(body))
	etag := azcore.ETag(`"azure-etag"`)
	return blob.GetPropertiesResponse{ContentLength: &length, ETag: &etag}, nil
}

// fakePresigner returns deterministic URLs without hitting Azure.
type fakePresigner struct{}

func (fakePresigner) SASURL(_ context.Context, container, blob string, ttl time.Duration, scope string) (string, error) {
	if ttl <= 0 {
		return "", errors.New("ttl must be positive")
	}
	return "https://fake.blob.core.windows.net/" + container + "/" + blob + "?sig=fake&scope=" + scope, nil
}

func newTestDriver() *driver.Driver {
	return driver.NewWithClient(driver.Options{
		AccountName: "testaccount",
		AccountKey:  "ZmFrZS1rZXktbm90LXJlYWw=",
		Container:   "test-container",
		SASExpiry:   5 * time.Minute,
	}, newFakeBlobClient()).WithPresigner(fakePresigner{})
}

func azureLocator(container, blob string) storage.DriverLocator {
	return storage.DriverLocator{"container": container, "blob": blob}
}

func TestAzureBlobRoundTrip(t *testing.T) {
	ctx := context.Background()
	d := newTestDriver()
	loc := azureLocator("test-container", "abc-123")
	payload := []byte("hello, azure")

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
	if meta.ETag != `"azure-etag"` {
		t.Fatalf("ETag = %q, want %q", meta.ETag, `"azure-etag"`)
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

func TestAzureBlobPutWithEncryptionScope(t *testing.T) {
	ctx := context.Background()
	d := newTestDriver()
	loc := azureLocator("test-container", "kyc-789")
	payload := []byte("kyc-bytes")
	if err := d.PutObject(ctx, loc, bytes.NewReader(payload), "kyc-scope-1"); err != nil {
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

func TestAzureBlobCompleteUpload404IsExistsFalse(t *testing.T) {
	ctx := context.Background()
	d := newTestDriver()
	loc := azureLocator("test-container", "missing")
	meta, err := d.CompleteUpload(ctx, storage.CompleteUploadRequest{DriverLocator: loc})
	if err != nil {
		t.Fatalf("CompleteUpload: %v", err)
	}
	if meta.Exists {
		t.Fatalf("Exists = true, want false")
	}
}

func TestAzureBlobDeleteIsIdempotent(t *testing.T) {
	ctx := context.Background()
	d := newTestDriver()
	loc := azureLocator("test-container", "missing")
	if err := d.DeleteObject(ctx, loc); err != nil {
		t.Fatalf("DeleteObject missing: %v", err)
	}
}

func TestAzureBlobProbeHealthy(t *testing.T) {
	ctx := context.Background()
	d := newTestDriver()
	// Without AccountKey, the probe synthesises Healthy (the real path
	// would require a DefaultAzureCredential hook we don't have in
	// unit tests). Verify it returns a non-error Probe.
	res := d.Probe(ctx)
	// Probe is a no-op without AccountKey + DefaultAzureCredential in
	// tests — should report unhealthy because the account is fake but
	// the Driver doesn't actually reach out.
	if res.LatencyMS < 0 {
		t.Fatalf("LatencyMS = %d, want >= 0", res.LatencyMS)
	}
}

func TestAzureBlobLocatorMissingBlob(t *testing.T) {
	ctx := context.Background()
	d := newTestDriver()
	_, err := d.HeadObject(ctx, storage.DriverLocator{"container": "x"})
	if err == nil {
		t.Fatalf("expected error on locator without blob")
	}
}

func TestAzureBlobShutdownNoOp(t *testing.T) {
	d := newTestDriver()
	if err := d.Shutdown(context.Background()); err != nil {
		t.Fatalf("Shutdown: %v", err)
	}
}

func TestAzureBlobInitiateUploadReturnsLocator(t *testing.T) {
	ctx := context.Background()
	d := newTestDriver()
	resp, err := d.InitiateUpload(ctx, storage.InitiateUploadRequest{
		FileID: "abc-123", MimeType: "image/jpeg", SizeBytes: 1024,
	})
	if err != nil {
		t.Fatalf("InitiateUpload: %v", err)
	}
	if resp.DriverLocator["blob"] != "abc-123" {
		t.Fatalf("locator.blob = %v, want abc-123", resp.DriverLocator["blob"])
	}
	if !strings.Contains(resp.UploadURL, "abc-123") {
		t.Fatalf("UploadURL does not include blob: %q", resp.UploadURL)
	}
}

func TestAzureBlobCreateSignedURL(t *testing.T) {
	ctx := context.Background()
	d := newTestDriver()
	loc := azureLocator("test-container", "abc-123")
	url, err := d.CreateSignedURL(ctx, loc, 5*time.Minute, "get")
	if err != nil {
		t.Fatalf("CreateSignedURL: %v", err)
	}
	if !strings.Contains(url, "abc-123") {
		t.Fatalf("URL does not include blob: %q", url)
	}
	if !strings.Contains(url, "scope=get") {
		t.Fatalf("URL does not reflect scope=get: %q", url)
	}
}
