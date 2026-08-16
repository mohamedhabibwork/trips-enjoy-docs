package oracle_object_storage_test

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/oracle/oci-go-sdk/v65/objectstorage"

	"github.com/trips-enjoy/platform/file-service/internal/storage"
	driver "github.com/trips-enjoy/platform/file-service/internal/storage/drivers/oracle_object_storage"
)

// fakeNativeClient implements driver.nativeClient with an in-memory
// map. Covers the small surface used by the Driver: GetNamespace,
// PutObject, GetObject, HeadObject, DeleteObject, CreatePreauthenticatedRequest.
type fakeNativeClient struct {
	// nested namespace → bucket → object → bytes
	objects      map[string]map[string]map[string][]byte
	getNamespace string
	parPath      string
}

func newFakeNative() *fakeNativeClient {
	return &fakeNativeClient{
		objects:      map[string]map[string]map[string][]byte{},
		getNamespace: "test-namespace",
		parPath:      "/p/test/n/test-namespace/b/test-bucket/o/abc-123",
	}
}

func (f *fakeNativeClient) GetNamespace(_ context.Context, _ objectstorage.GetNamespaceRequest) (objectstorage.GetNamespaceResponse, error) {
	return objectstorage.GetNamespaceResponse{Value: &f.getNamespace}, nil
}

func (f *fakeNativeClient) PutObject(_ context.Context, req objectstorage.PutObjectRequest) (objectstorage.PutObjectResponse, error) {
	body, err := readAll(req.PutObjectBody)
	if err != nil {
		return objectstorage.PutObjectResponse{}, err
	}
	if f.objects[*req.NamespaceName] == nil {
		f.objects[*req.NamespaceName] = map[string]map[string][]byte{}
	}
	if f.objects[*req.NamespaceName][*req.BucketName] == nil {
		f.objects[*req.NamespaceName][*req.BucketName] = map[string][]byte{}
	}
	f.objects[*req.NamespaceName][*req.BucketName][*req.ObjectName] = body
	return objectstorage.PutObjectResponse{}, nil
}

func (f *fakeNativeClient) GetObject(_ context.Context, req objectstorage.GetObjectRequest) (objectstorage.GetObjectResponse, error) {
	ns, ok := f.objects[*req.NamespaceName]
	if !ok {
		return objectstorage.GetObjectResponse{}, notFoundErr()
	}
	bucket, ok := ns[*req.BucketName]
	if !ok {
		return objectstorage.GetObjectResponse{}, notFoundErr()
	}
	body, ok := bucket[*req.ObjectName]
	if !ok {
		return objectstorage.GetObjectResponse{}, notFoundErr()
	}
	return objectstorage.GetObjectResponse{Content: nopCloser(body)}, nil
}

func (f *fakeNativeClient) HeadObject(_ context.Context, req objectstorage.HeadObjectRequest) (objectstorage.HeadObjectResponse, error) {
	ns, ok := f.objects[*req.NamespaceName]
	if !ok {
		return objectstorage.HeadObjectResponse{}, notFoundErr()
	}
	bucket, ok := ns[*req.BucketName]
	if !ok {
		return objectstorage.HeadObjectResponse{}, notFoundErr()
	}
	body, ok := bucket[*req.ObjectName]
	if !ok {
		return objectstorage.HeadObjectResponse{}, notFoundErr()
	}
	length := int64(len(body))
	etag := `"oci-etag"`
	return objectstorage.HeadObjectResponse{ContentLength: &length, ETag: &etag}, nil
}

func (f *fakeNativeClient) DeleteObject(_ context.Context, req objectstorage.DeleteObjectRequest) (objectstorage.DeleteObjectResponse, error) {
	if ns, ok := f.objects[*req.NamespaceName]; ok {
		if bucket, ok := ns[*req.BucketName]; ok {
			delete(bucket, *req.ObjectName)
		}
	}
	return objectstorage.DeleteObjectResponse{}, nil
}

func (f *fakeNativeClient) CreatePreauthenticatedRequest(_ context.Context, _ objectstorage.CreatePreauthenticatedRequestRequest) (objectstorage.CreatePreauthenticatedRequestResponse, error) {
	uri := f.parPath
	resp := objectstorage.CreatePreauthenticatedRequestResponse{
		RawResponse: &http.Response{
			Request: &http.Request{URL: mustParseURL("https://objectstorage.us-ashburn-1.oraclecloud.com")},
		},
	}
	resp.AccessUri = &uri
	return resp, nil
}

// notFoundErr returns a synthetic 404-shaped error matching
// driver.isObjectNotFound's duck type.
func notFoundErr() error { return &fakeServiceErr{status: http.StatusNotFound} }

type fakeServiceErr struct{ status int }

func (e *fakeServiceErr) Error() string          { return "object not found" }
func (e *fakeServiceErr) GetHTTPStatusCode() int { return e.status }

func newTestNativeDriver() *driver.Driver {
	return driver.NewWithClients(driver.Options{
		Namespace:     "test-namespace",
		Region:        "us-ashburn-1",
		Bucket:        "test-bucket",
		CompartmentID: "ocid1.compartment.oc1..test",
		PresignExpiry: 5 * time.Minute,
	}, newFakeNative(), nil)
}

func ociLocator(namespace, bucket, object string) storage.DriverLocator {
	return storage.DriverLocator{"namespace": namespace, "bucket": bucket, "object": object}
}

func TestOCINativeRequiresConfig(t *testing.T) {
	_, err := driver.New(context.Background(), driver.Options{})
	if err == nil {
		t.Fatalf("expected error on empty config")
	}
	if !strings.Contains(err.Error(), "Namespace") {
		t.Fatalf("error = %v, want Namespace required", err)
	}
}

func TestOCINativeS3CompatibleRequiresEndpoint(t *testing.T) {
	_, err := driver.New(context.Background(), driver.Options{
		Namespace:    "ns",
		Region:       "us-ashburn-1",
		Bucket:       "b",
		S3Compatible: true,
	})
	if err == nil {
		t.Fatalf("expected error on missing S3Endpoint")
	}
	if !strings.Contains(err.Error(), "S3Endpoint") {
		t.Fatalf("error = %v, want S3Endpoint required", err)
	}
}

func TestOCINativeInitiateUploadReturnsPAR(t *testing.T) {
	d := newTestNativeDriver()
	resp, err := d.InitiateUpload(context.Background(), storage.InitiateUploadRequest{
		FileID: "abc-123", MimeType: "image/jpeg", SizeBytes: 1024,
	})
	if err != nil {
		t.Fatalf("InitiateUpload: %v", err)
	}
	if !strings.Contains(resp.UploadURL, "/p/test/n/test-namespace") {
		t.Fatalf("UploadURL does not include PAR path: %q", resp.UploadURL)
	}
	if resp.DriverLocator["object"] != "abc-123" {
		t.Fatalf("locator.object = %v, want abc-123", resp.DriverLocator["object"])
	}
}

func TestOCINativeCreateSignedURL(t *testing.T) {
	d := newTestNativeDriver()
	loc := ociLocator("test-namespace", "test-bucket", "abc-123")
	url, err := d.CreateSignedURL(context.Background(), loc, 5*time.Minute, "get")
	if err != nil {
		t.Fatalf("CreateSignedURL: %v", err)
	}
	if !strings.Contains(url, "objectstorage.us-ashburn-1.oraclecloud.com") {
		t.Fatalf("URL missing host: %q", url)
	}
	if !strings.Contains(url, "/p/test/") {
		t.Fatalf("URL missing PAR path: %q", url)
	}
}

func TestOCINativeProbe(t *testing.T) {
	d := newTestNativeDriver()
	res := d.Probe(context.Background())
	if !res.Healthy {
		t.Fatalf("Probe not healthy: %v", res.Error)
	}
}

func TestOCINativeShutdown(t *testing.T) {
	d := newTestNativeDriver()
	if err := d.Shutdown(context.Background()); err != nil {
		t.Fatalf("Shutdown: %v", err)
	}
}

func TestOCINativeCompleteUpload404IsExistsFalse(t *testing.T) {
	d := newTestNativeDriver()
	loc := ociLocator("test-namespace", "test-bucket", "missing")
	meta, err := d.CompleteUpload(context.Background(), storage.CompleteUploadRequest{DriverLocator: loc})
	if err != nil {
		t.Fatalf("CompleteUpload: %v", err)
	}
	if meta.Exists {
		t.Fatalf("Exists = true, want false")
	}
}

func TestOCINativeDeleteIsIdempotent(t *testing.T) {
	d := newTestNativeDriver()
	loc := ociLocator("test-namespace", "test-bucket", "missing")
	if err := d.DeleteObject(context.Background(), loc); err != nil {
		t.Fatalf("DeleteObject missing: %v", err)
	}
}

func TestOCINativeLocatorMissingObject(t *testing.T) {
	d := newTestNativeDriver()
	_, err := d.HeadObject(context.Background(), storage.DriverLocator{"namespace": "ns", "bucket": "b"})
	if err == nil {
		t.Fatalf("expected error on locator without object")
	}
}

func TestOCINativeRequiresNativeClient(t *testing.T) {
	d := driver.NewWithClients(driver.Options{
		Namespace: "ns", Region: "us-ashburn-1", Bucket: "b",
	}, nil, nil)
	_, err := d.InitiateUpload(context.Background(), storage.InitiateUploadRequest{FileID: "x"})
	if err == nil {
		t.Fatalf("expected error when native client missing")
	}
}

func TestOCINativeRoundTrip(t *testing.T) {
	ctx := context.Background()
	d := newTestNativeDriver()
	loc := ociLocator("test-namespace", "test-bucket", "abc-123")
	payload := []byte("hello, oci")

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
	if meta.ETag != `"oci-etag"` {
		t.Fatalf("ETag = %q, want %q", meta.ETag, `"oci-etag"`)
	}

	rc, err := d.GetObject(ctx, loc)
	if err != nil {
		t.Fatalf("GetObject: %v", err)
	}
	got, err := readAll(rc)
	_ = rc.Close()
	if err != nil {
		t.Fatalf("readAll: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("bytes = %q, want %q", got, payload)
	}

	if err := d.DeleteObject(ctx, loc); err != nil {
		t.Fatalf("DeleteObject: %v", err)
	}
}
func mustParseURL(s string) *url.URL {
	u, err := url.Parse(s)
	if err != nil {
		panic(err)
	}
	return u
}

func nopCloser(b []byte) io.ReadCloser { return io.NopCloser(bytes.NewReader(b)) }

func readAll(r io.Reader) ([]byte, error) { return io.ReadAll(r) }
