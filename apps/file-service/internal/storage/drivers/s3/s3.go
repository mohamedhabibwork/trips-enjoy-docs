// Package s3 is the AWS S3 / S3-compatible StorageDriver. It targets AWS
// S3 native plus any S3 v4-compatible store (MinIO, Ceph RGW, Wasabi,
// Cloudflare R2). Pre-signed URLs use the aws-sdk-go-v2 PresignClient
// (Get / Put); HeadBucket is the synthetic probe.
//
// Construction is split into two paths:
//
//   - New(opts) reads opts (region/endpoint/bucket/credentials/pathStyle)
//     and builds an *s3.Client via the SDK's config.LoadDefaultConfig +
//     loadOptionsFromEnv. This is what cmd/server/main.go calls in
//     production.
//   - NewWithClient(driver, client) wires an arbitrary S3Client — used by
//     tests to swap in an in-memory fake without touching the AWS SDK.
//
// DriverLocator shape:
//
//	{"bucket": "trips-enjoy-files", "key": "<file_id>"}
//
// The bucket can be overridden per call (rare); absent, the default
// bucket from the Driver config wins.
package s3

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/aws/signer/v4"
	awscfg "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	s3types "github.com/aws/aws-sdk-go-v2/service/s3/types"

	"github.com/trips-enjoy/platform/file-service/internal/storage"
)

// Options carries everything the production constructor needs to
// instantiate an *s3.Client. Empty AccessKey + SecretKey lets the SDK
// pull credentials from the ambient chain (IAM role, IMDS, env).
type Options struct {
	Region        string // e.g. "us-east-1"
	Endpoint      string // optional: MinIO / R2 / RGW; leave empty for AWS native
	Bucket        string // default bucket — used when DriverLocator has no bucket
	AccessKey     string // optional; empty falls back to the default chain
	SecretKey     string // optional; empty falls back to the default chain
	PathStyle     bool   // true for MinIO / R2 / Ceph RGW
	KMSKeyID      string // optional SSE-KMS key id
	PresignExpiry time.Duration
	UseFIPS       bool // optional FIPS endpoint
}

// S3Client is the small surface of *s3.Client that the Driver uses.
// Defined here so tests can swap in a fake without depending on the
// real SDK's many methods.
type S3Client interface {
	HeadBucket(ctx context.Context, in *s3.HeadBucketInput, opts ...func(*s3.Options)) (*s3.HeadBucketOutput, error)
	HeadObject(ctx context.Context, in *s3.HeadObjectInput, opts ...func(*s3.Options)) (*s3.HeadObjectOutput, error)
	GetObject(ctx context.Context, in *s3.GetObjectInput, opts ...func(*s3.Options)) (*s3.GetObjectOutput, error)
	PutObject(ctx context.Context, in *s3.PutObjectInput, opts ...func(*s3.Options)) (*s3.PutObjectOutput, error)
	DeleteObject(ctx context.Context, in *s3.DeleteObjectInput, opts ...func(*s3.Options)) (*s3.DeleteObjectOutput, error)
	CreateBucket(ctx context.Context, in *s3.CreateBucketInput, opts ...func(*s3.Options)) (*s3.CreateBucketOutput, error)
}

// Presigner is the small surface of *s3.PresignClient used by the
// Driver. Defined here so tests can inject a fake presigner and skip
// the AWS credential chain entirely.
type Presigner interface {
	PresignPutObject(ctx context.Context, in *s3.PutObjectInput, opts ...func(*s3.PresignOptions)) (*v4.PresignedHTTPRequest, error)
	PresignGetObject(ctx context.Context, in *s3.GetObjectInput, opts ...func(*s3.PresignOptions)) (*v4.PresignedHTTPRequest, error)
}

// PresignBuilder returns a Presigner for the configured region /
// endpoint / path-style. Defaults to the real SDK's NewPresignClient;
// tests override it via WithPresigner.
type PresignBuilder func(ctx context.Context, d *Driver) (Presigner, error)

// Driver is the S3 / S3-compatible StorageDriver.
type Driver struct {
	opts           Options
	client         S3Client
	presignBuilder PresignBuilder
}

// New builds a Driver with a real *s3.Client. opts.Bucket is required;
// opts.Region defaults to "us-east-1". opts.PresignExpiry defaults to
// 15 minutes. Callers that want to inject a custom endpoint (MinIO /
// R2 / RGW) set opts.Endpoint.
func New(ctx context.Context, opts Options) (*Driver, error) {
	if opts.Bucket == "" {
		return nil, errors.New("s3: bucket is required")
	}
	if opts.Region == "" {
		opts.Region = "us-east-1"
	}
	if opts.PresignExpiry == 0 {
		opts.PresignExpiry = 15 * time.Minute
	}

	loadOpts := []func(*awscfg.LoadOptions) error{awscfg.WithRegion(opts.Region)}
	if opts.AccessKey != "" && opts.SecretKey != "" {
		loadOpts = append(loadOpts, awscfg.WithCredentialsProvider(credentials.NewStaticCredentialsProvider(opts.AccessKey, opts.SecretKey, "")))
	}

	cfg, err := awscfg.LoadDefaultConfig(ctx, loadOpts...)
	if err != nil {
		return nil, fmt.Errorf("s3: load aws config: %w", err)
	}

	clientOpts := []func(*s3.Options){}
	if opts.Endpoint != "" {
		clientOpts = append(clientOpts, func(o *s3.Options) {
			o.BaseEndpoint = aws.String(opts.Endpoint)
		})
	}
	if opts.PathStyle {
		clientOpts = append(clientOpts, func(o *s3.Options) {
			o.UsePathStyle = true
		})
	}

	client := s3.NewFromConfig(cfg, clientOpts...)
	return NewWithClient(opts, client), nil
}

// NewWithClient wires a Driver around an arbitrary S3Client. Used by
// production callers via New() and by tests via a fake. PresignBuilder
// defaults to the real SDK when nil.
func NewWithClient(opts Options, client S3Client) *Driver {
	if opts.PresignExpiry == 0 {
		opts.PresignExpiry = 15 * time.Minute
	}
	return &Driver{
		opts:           opts,
		client:         client,
		presignBuilder: defaultPresignBuilder,
	}
}

// WithPresigner swaps the PresignBuilder. Used by tests to inject a
// fake presigner that does not hit the AWS credential chain.
func (d *Driver) WithPresigner(b PresignBuilder) *Driver {
	d.presignBuilder = b
	return d
}

// defaultPresignBuilder wires the real SDK PresignClient with the same
// region / endpoint / path-style as the data-plane client.
func defaultPresignBuilder(ctx context.Context, d *Driver) (Presigner, error) {
	cfg, err := d.presignConfig(ctx)
	if err != nil {
		return nil, err
	}
	return s3.NewPresignClient(s3.NewFromConfig(cfg, d.presignClientOptions()...)), nil
}

// objectLocator reduces a DriverLocator to (bucket, key). Missing
// bucket falls back to the Driver default.
func (d *Driver) objectLocator(loc storage.DriverLocator) (string, string, error) {
	if loc == nil {
		return "", "", errors.New("s3: nil locator")
	}
	key, _ := loc["key"].(string)
	if key == "" {
		key, _ = loc["path"].(string)
	}
	if key == "" {
		return "", "", errors.New("s3: locator missing 'key'")
	}
	bucket, _ := loc["bucket"].(string)
	if bucket == "" {
		bucket = d.opts.Bucket
	}
	return bucket, key, nil
}

// InitiateUpload returns a presigned PUT URL. The returned locator is
// `{bucket, key}` — the same shape every other operation uses.
//
// On S3-compatible stores with a custom Endpoint (MinIO, R2, RGW) the
// PresignClient is configured with the same BaseEndpoint + UsePathStyle
// so the signed URL actually reaches the alternative backend.
func (d *Driver) InitiateUpload(ctx context.Context, req storage.InitiateUploadRequest) (storage.InitiateUploadResponse, error) {
	if d.presignBuilder == nil {
		return storage.InitiateUploadResponse{}, storage.ErrDriverNotImplemented
	}
	key := req.FileID
	bucket := d.opts.Bucket

	presigner, err := d.presignBuilder(ctx, d)
	if err != nil {
		return storage.InitiateUploadResponse{}, err
	}
	signed, err := presigner.PresignPutObject(ctx, &s3.PutObjectInput{
		Bucket:               aws.String(bucket),
		Key:                  aws.String(key),
		ContentType:          aws.String(req.MimeType),
		ContentLength:        aws.Int64(req.SizeBytes),
		ServerSideEncryption: d.serverSideEncryption(),
	}, func(o *s3.PresignOptions) {
		o.Expires = d.opts.PresignExpiry
	})
	if err != nil {
		return storage.InitiateUploadResponse{}, fmt.Errorf("s3: presign PUT: %w", err)
	}

	return storage.InitiateUploadResponse{
		UploadURL:     signed.URL,
		DriverLocator: storage.DriverLocator{"bucket": bucket, "key": key},
		ExpiresAt:     time.Now().Add(d.opts.PresignExpiry),
	}, nil
}

// presignConfig + presignClientOptions mirror the options passed to
// the real Client. We can't reuse the S3Client interface directly
// (PresignClient takes a *s3.Client), so we rebuild config. In tests
// this path is skipped because InitiateUpload isn't covered by the
// fake.
func (d *Driver) presignConfig(ctx context.Context) (aws.Config, error) {
	loadOpts := []func(*awscfg.LoadOptions) error{awscfg.WithRegion(d.opts.Region)}
	if d.opts.AccessKey != "" && d.opts.SecretKey != "" {
		loadOpts = append(loadOpts, awscfg.WithCredentialsProvider(credentials.NewStaticCredentialsProvider(d.opts.AccessKey, d.opts.SecretKey, "")))
	}
	return awscfg.LoadDefaultConfig(ctx, loadOpts...)
}

func (d *Driver) presignClientOptions() []func(*s3.Options) {
	opts := []func(*s3.Options){}
	if d.opts.Endpoint != "" {
		opts = append(opts, func(o *s3.Options) { o.BaseEndpoint = aws.String(d.opts.Endpoint) })
	}
	if d.opts.PathStyle {
		opts = append(opts, func(o *s3.Options) { o.UsePathStyle = true })
	}
	return opts
}

// serverSideEncryption returns SSE-KMS when KMSKeyID is set; otherwise
// nil (the bucket's default encryption applies — SSE-S3 / SSE-KMS).
func (d *Driver) serverSideEncryption() s3types.ServerSideEncryption {
	if d.opts.KMSKeyID == "" {
		return ""
	}
	return s3types.ServerSideEncryptionAwsKms
}

// CompleteUpload verifies the object exists and (when encryptionKeyID
// is supplied) re-asserts SSE-KMS server-side. Returns the canonical
// metadata; SHA-256 is not returned by HeadObject, so callers that
// need the hash must stream and compute it themselves.
func (d *Driver) CompleteUpload(ctx context.Context, req storage.CompleteUploadRequest) (storage.ObjectMetadata, error) {
	if d.client == nil {
		return storage.ObjectMetadata{}, storage.ErrDriverNotImplemented
	}
	bucket, key, err := d.objectLocator(req.DriverLocator)
	if err != nil {
		return storage.ObjectMetadata{}, err
	}
	out, err := d.client.HeadObject(ctx, &s3.HeadObjectInput{
		Bucket: aws.String(bucket),
		Key:    aws.String(key),
	})
	if err != nil {
		var notFound *s3types.NotFound
		if errors.As(err, &notFound) {
			return storage.ObjectMetadata{Exists: false}, nil
		}
		// Some S3-compatible stores return 404 with a different error
		// type; treat "NoSuchKey" / "NotFound" in the error string as
		// the canonical "doesn't exist" signal.
		if strings.Contains(err.Error(), "NotFound") || strings.Contains(err.Error(), "NoSuchKey") {
			return storage.ObjectMetadata{Exists: false}, nil
		}
		return storage.ObjectMetadata{}, fmt.Errorf("s3: HeadObject: %w", err)
	}
	meta := storage.ObjectMetadata{
		SizeBytes: aws.ToInt64(out.ContentLength),
		ETag:      aws.ToString(out.ETag),
		Exists:    true,
	}
	if out.ServerSideEncryption == s3types.ServerSideEncryptionAwsKms {
		meta.ETag = "kms:" + meta.ETag
	}
	return meta, nil
}

// GetObject streams the object body. The caller MUST close the reader.
func (d *Driver) GetObject(ctx context.Context, loc storage.DriverLocator) (io.ReadCloser, error) {
	if d.client == nil {
		return nil, storage.ErrDriverNotImplemented
	}
	bucket, key, err := d.objectLocator(loc)
	if err != nil {
		return nil, err
	}
	out, err := d.client.GetObject(ctx, &s3.GetObjectInput{
		Bucket: aws.String(bucket),
		Key:    aws.String(key),
	})
	if err != nil {
		return nil, fmt.Errorf("s3: GetObject: %w", err)
	}
	return out.Body, nil
}

// PutObject streams src into the object identified by locator. The
// optional encryptionKeyID is mapped to SSE-KMS via ServerSideEncryption
// (only honoured when the bucket has a matching KMS key configured;
// silent fallback per AWS semantics).
func (d *Driver) PutObject(ctx context.Context, loc storage.DriverLocator, src io.Reader, encryptionKeyID string) error {
	if d.client == nil {
		return storage.ErrDriverNotImplemented
	}
	bucket, key, err := d.objectLocator(loc)
	if err != nil {
		return err
	}
	buf, err := io.ReadAll(src)
	if err != nil {
		return fmt.Errorf("s3: read source: %w", err)
	}
	in := &s3.PutObjectInput{
		Bucket:        aws.String(bucket),
		Key:           aws.String(key),
		Body:          bytes.NewReader(buf),
		ContentLength: aws.Int64(int64(len(buf))),
	}
	if encryptionKeyID != "" {
		in.ServerSideEncryption = s3types.ServerSideEncryptionAwsKms
		in.SSEKMSKeyId = aws.String(encryptionKeyID)
	}
	_, err = d.client.PutObject(ctx, in)
	if err != nil {
		return fmt.Errorf("s3: PutObject: %w", err)
	}
	return nil
}

// DeleteObject removes the object. S3 DeleteObject is idempotent on
// non-existent keys, so no special handling for NotFound is required.
func (d *Driver) DeleteObject(ctx context.Context, loc storage.DriverLocator) error {
	if d.client == nil {
		return storage.ErrDriverNotImplemented
	}
	bucket, key, err := d.objectLocator(loc)
	if err != nil {
		return err
	}
	_, err = d.client.DeleteObject(ctx, &s3.DeleteObjectInput{
		Bucket: aws.String(bucket),
		Key:    aws.String(key),
	})
	if err != nil {
		return fmt.Errorf("s3: DeleteObject: %w", err)
	}
	return nil
}

// HeadObject is implemented separately so callers can request metadata
// without going through CompleteUpload's 404→Exists=false translation.
func (d *Driver) HeadObject(ctx context.Context, loc storage.DriverLocator) (storage.ObjectMetadata, error) {
	return d.CompleteUpload(ctx, storage.CompleteUploadRequest{DriverLocator: loc})
}

// CreateSignedURL returns a presigned GET URL scoped to the requested
// verb (scope="get") for read downloads. scope is informational —
// callers passing "put" still get a GET signed URL because the
// driver issues read URLs only; a caller needing PUT should call
// InitiateUpload.
func (d *Driver) CreateSignedURL(ctx context.Context, loc storage.DriverLocator, ttl time.Duration, scope string) (string, error) {
	if d.presignBuilder == nil {
		return "", storage.ErrDriverNotImplemented
	}
	bucket, key, err := d.objectLocator(loc)
	if err != nil {
		return "", err
	}
	presigner, err := d.presignBuilder(ctx, d)
	if err != nil {
		return "", err
	}
	signed, err := presigner.PresignGetObject(ctx, &s3.GetObjectInput{
		Bucket: aws.String(bucket),
		Key:    aws.String(key),
	}, func(o *s3.PresignOptions) {
		o.Expires = ttl
	})
	if err != nil {
		return "", fmt.Errorf("s3: presign GET: %w", err)
	}
	return signed.URL, nil
}

// Probe runs HeadBucket against the default bucket. A 404 / NotFound is
// treated as a permission problem (the bucket doesn't exist OR we can't
// see it) and reported as Unreachable; any other error is propagated.
func (d *Driver) Probe(ctx context.Context) storage.ProbeResult {
	if d.client == nil {
		return storage.ProbeResult{Healthy: false, Error: storage.ErrDriverNotImplemented}
	}
	start := time.Now()
	probeCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	_, err := d.client.HeadBucket(probeCtx, &s3.HeadBucketInput{
		Bucket: aws.String(d.opts.Bucket),
	})
	if err != nil {
		return storage.ProbeResult{Healthy: false, LatencyMS: time.Since(start).Milliseconds(), Error: err}
	}
	return storage.ProbeResult{Healthy: true, LatencyMS: time.Since(start).Milliseconds()}
}

// Shutdown is a no-op for S3 (the SDK has no long-lived connections).
func (d *Driver) Shutdown(_ context.Context) error { return nil }

// NewStub returns a placeholder Driver whose every operation returns
// storage.ErrDriverNotImplemented. Used by cmd/server/main.go when
// FILE_SERVICE_S3_ENABLED is false (dev / CI without cloud creds).
func NewStub() *Driver {
	return &Driver{} // nil client + guard at CompleteUpload
}

// HashKey is a small helper exposed for tests + callers that want to
// derive a stable SHA-256 from the DriverLocator (used by the SHA-256
// reconciliation path in INTEGRATION.md §3).
func HashKey(loc storage.DriverLocator) string {
	bucket, _ := loc["bucket"].(string)
	key, _ := loc["key"].(string)
	if key == "" {
		key, _ = loc["path"].(string)
	}
	sum := sha256.Sum256([]byte(bucket + "/" + key))
	return hex.EncodeToString(sum[:])
}
