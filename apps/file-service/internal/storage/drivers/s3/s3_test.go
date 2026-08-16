package s3_test

import (
	"context"
	"errors"
	"io"
	"strings"
	"testing"

	"github.com/aws/aws-sdk-go-v2/aws/signer/v4"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	s3types "github.com/aws/aws-sdk-go-v2/service/s3/types"

	"github.com/trips-enjoy/platform/file-service/internal/storage"
	driver "github.com/trips-enjoy/platform/file-service/internal/storage/drivers/s3"
)

// fakeS3 implements the S3Client interface with an in-memory map so
// the Driver can be exercised without AWS credentials. It covers every
// method on the S3Client surface used by the Driver so any future
// change to S3Client surfaces a compile error here.
type fakeS3 struct {
	objects map[string]map[string][]byte // bucket → key → bytes
	headErr error                        // optional injection
	delErr  error                        // optional injection
	putErr  error                        // optional injection
	getErr  error                        // optional injection
}

func newFakeS3() *fakeS3 { return &fakeS3{objects: map[string]map[string][]byte{}} }

func (f *fakeS3) HeadBucket(_ context.Context, in *s3.HeadBucketInput, _ ...func(*s3.Options)) (*s3.HeadBucketOutput, error) {
	if f.headErr != nil {
		return nil, f.headErr
	}
	if _, ok := f.objects[*in.Bucket]; !ok {
		f.objects[*in.Bucket] = map[string][]byte{}
	}
	return &s3.HeadBucketOutput{}, nil
}

func (f *fakeS3) HeadObject(_ context.Context, in *s3.HeadObjectInput, _ ...func(*s3.Options)) (*s3.HeadObjectOutput, error) {
	if f.headErr != nil {
		return nil, f.headErr
	}
	bucket := f.objects[*in.Bucket]
	if bucket == nil {
		return nil, &s3types.NotFound{}
	}
	body, ok := bucket[*in.Key]
	if !ok {
		return nil, &s3types.NotFound{}
	}
	length := int64(len(body))
	etag := `"etag-abc"`
	return &s3.HeadObjectOutput{ContentLength: &length, ETag: &etag}, nil
}

func (f *fakeS3) GetObject(_ context.Context, in *s3.GetObjectInput, _ ...func(*s3.Options)) (*s3.GetObjectOutput, error) {
	if f.getErr != nil {
		return nil, f.getErr
	}
	bucket := f.objects[*in.Bucket]
	if bucket == nil {
		return nil, &s3types.NoSuchKey{}
	}
	body, ok := bucket[*in.Key]
	if !ok {
		return nil, &s3types.NoSuchKey{}
	}
	return &s3.GetObjectOutput{Body: io.NopCloser(strings.NewReader(string(body)))}, nil
}

func (f *fakeS3) PutObject(_ context.Context, in *s3.PutObjectInput, _ ...func(*s3.Options)) (*s3.PutObjectOutput, error) {
	if f.putErr != nil {
		return nil, f.putErr
	}
	body, err := io.ReadAll(in.Body)
	if err != nil {
		return nil, err
	}
	if f.objects[*in.Bucket] == nil {
		f.objects[*in.Bucket] = map[string][]byte{}
	}
	f.objects[*in.Bucket][*in.Key] = body
	return &s3.PutObjectOutput{}, nil
}

func (f *fakeS3) DeleteObject(_ context.Context, in *s3.DeleteObjectInput, _ ...func(*s3.Options)) (*s3.DeleteObjectOutput, error) {
	if f.delErr != nil {
		return nil, f.delErr
	}
	if bucket := f.objects[*in.Bucket]; bucket != nil {
		delete(bucket, *in.Key)
	}
	return &s3.DeleteObjectOutput{}, nil
}

func (f *fakeS3) CreateBucket(_ context.Context, _ *s3.CreateBucketInput, _ ...func(*s3.Options)) (*s3.CreateBucketOutput, error) {
	return &s3.CreateBucketOutput{}, nil
}

func newTestDriver(fake *fakeS3) *driver.Driver {
	return driver.NewWithClient(driver.Options{
		Region:        "us-east-1",
		Bucket:        "test-bucket",
		PresignExpiry: 5 * 60_000_000_000, // 5 min
	}, fake).WithPresigner(fakePresignBuilder)
}

// fakePresigner satisfies the Presigner interface without touching
// the AWS credential chain. The URL it returns is opaque (anything
// starting with "https://") — tests don't parse it.
type fakePresigner struct{}

func (fakePresigner) PresignPutObject(_ context.Context, in *s3.PutObjectInput, _ ...func(*s3.PresignOptions)) (*v4.PresignedHTTPRequest, error) {
	url := "https://test-bucket.s3.amazonaws.com/" + *in.Key + "?X-Amz-Signature=fake"
	return &v4.PresignedHTTPRequest{URL: url}, nil
}
func (fakePresigner) PresignGetObject(_ context.Context, in *s3.GetObjectInput, _ ...func(*s3.PresignOptions)) (*v4.PresignedHTTPRequest, error) {
	url := "https://test-bucket.s3.amazonaws.com/" + *in.Key + "?X-Amz-Signature=fake-get"
	return &v4.PresignedHTTPRequest{URL: url}, nil
}

func fakePresignBuilder(_ context.Context, _ *driver.Driver) (driver.Presigner, error) {
	return fakePresigner{}, nil
}

func s3Locator(bucket, key string) storage.DriverLocator {
	return storage.DriverLocator{"bucket": bucket, "key": key}
}

func TestS3DriverRoundTrip(t *testing.T) {
	ctx := context.Background()
	d := newTestDriver(newFakeS3())
	loc := s3Locator("test-bucket", "abc-123")
	payload := []byte("hello, s3")

	if err := d.PutObject(ctx, loc, strings.NewReader(string(payload)), ""); err != nil {
		t.Fatalf("PutObject: %v", err)
	}

	meta, err := d.HeadObject(ctx, loc)
	if err != nil {
		t.Fatalf("HeadObject: %v", err)
	}
	if meta.SizeBytes != int64(len(payload)) {
		t.Fatalf("SizeBytes = %d, want %d", meta.SizeBytes, len(payload))
	}
	if meta.ETag != `"etag-abc"` {
		t.Fatalf("ETag = %q, want %q", meta.ETag, `"etag-abc"`)
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
	if string(got) != string(payload) {
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

func TestS3DriverCompleteUpload404IsExistsFalse(t *testing.T) {
	ctx := context.Background()
	d := newTestDriver(newFakeS3())
	loc := s3Locator("test-bucket", "missing")

	meta, err := d.CompleteUpload(ctx, storage.CompleteUploadRequest{DriverLocator: loc})
	if err != nil {
		t.Fatalf("CompleteUpload: %v", err)
	}
	if meta.Exists {
		t.Fatalf("Exists = true, want false")
	}
}

func TestS3DriverDeleteIsIdempotent(t *testing.T) {
	ctx := context.Background()
	d := newTestDriver(newFakeS3())
	loc := s3Locator("test-bucket", "missing")
	if err := d.DeleteObject(ctx, loc); err != nil {
		t.Fatalf("DeleteObject missing: %v", err)
	}
}

func TestS3DriverProbeHealthy(t *testing.T) {
	ctx := context.Background()
	d := newTestDriver(newFakeS3())
	res := d.Probe(ctx)
	if !res.Healthy {
		t.Fatalf("Probe not healthy: %v", res.Error)
	}
}

func TestS3DriverProbeUnhealthyOnHeadError(t *testing.T) {
	ctx := context.Background()
	fake := newFakeS3()
	fake.headErr = errors.New("simulated network error")
	d := newTestDriver(fake)
	res := d.Probe(ctx)
	if res.Healthy {
		t.Fatalf("Probe healthy, want unhealthy")
	}
	if res.Error == nil || !strings.Contains(res.Error.Error(), "simulated") {
		t.Fatalf("res.Error = %v, want simulated error", res.Error)
	}
}

func TestS3DriverLocatorMissingKey(t *testing.T) {
	ctx := context.Background()
	d := newTestDriver(newFakeS3())
	_, err := d.HeadObject(ctx, storage.DriverLocator{"bucket": "x"})
	if err == nil {
		t.Fatalf("expected error on locator without key")
	}
}

func TestS3DriverShutdownNoOp(t *testing.T) {
	d := newTestDriver(newFakeS3())
	if err := d.Shutdown(context.Background()); err != nil {
		t.Fatalf("Shutdown: %v", err)
	}
}

func TestS3DriverInitiateUploadReturnsLocator(t *testing.T) {
	ctx := context.Background()
	d := newTestDriver(newFakeS3())
	resp, err := d.InitiateUpload(ctx, storage.InitiateUploadRequest{
		FileID: "abc-123", MimeType: "image/jpeg", SizeBytes: 1024,
	})
	if err != nil {
		t.Fatalf("InitiateUpload: %v", err)
	}
	loc, ok := resp.DriverLocator["key"].(string)
	if !ok || loc != "abc-123" {
		t.Fatalf("locator.key = %v, want abc-123", resp.DriverLocator["key"])
	}
	if resp.UploadURL == "" {
		t.Fatalf("UploadURL empty")
	}
	if !strings.Contains(resp.UploadURL, "abc-123") {
		t.Fatalf("UploadURL does not include key: %q", resp.UploadURL)
	}
}

func TestS3DriverCreateSignedURL(t *testing.T) {
	ctx := context.Background()
	d := newTestDriver(newFakeS3())
	loc := s3Locator("test-bucket", "abc-123")
	url, err := d.CreateSignedURL(ctx, loc, 5*60_000_000_000, "get")
	if err != nil {
		t.Fatalf("CreateSignedURL: %v", err)
	}
	if !strings.Contains(url, "abc-123") {
		t.Fatalf("URL does not include key: %q", url)
	}
}
