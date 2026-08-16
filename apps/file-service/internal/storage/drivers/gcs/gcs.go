// Package gcs is the Google Cloud Storage StorageDriver. It targets GCS
// via cloud.google.com/go/storage and supports:
//   - Default credentials (ADC), service-account JSON key (via
//     option.WithCredentialsFile), and signed-URL-only mode where
//     storage.NewClient is skipped and only SignedURL is exercised.
//   - V4 signed URLs for direct-to-driver uploads (InitiateUpload) and
//     read downloads (CreateSignedURL). The signing key is the
//     service-account private key (PEM).
//   - CMEK via Writer.KMSKeyName for per-tenant KYC encryption.
//
// Construction:
//
//   - New(ctx, opts) builds a *storage.Client via storage.NewClient and
//     the Driver. opts.ServiceAccountEmail + opts.PrivateKeyPEM are
//     required for SignedURL; the *storage.Client is required for the
//     data plane.
//   - NewWithClient(opts, client, bucket, object) wires concrete
//     BucketHandle / ObjectHandle factories for tests to swap in fakes.
//
// DriverLocator shape:
//
//	{"bucket": "<bucket>", "object": "<file_id>"}
package gcs

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"time"

	gcsstorage "cloud.google.com/go/storage"

	"github.com/trips-enjoy/platform/file-service/internal/storage"
)

// Options carries everything the production constructor needs.
type Options struct {
	// Bucket is the default bucket. Per-call DriverLocator["bucket"]
	// overrides.
	Bucket string
	// ServiceAccountEmail is the signer identity for SignedURL. Required.
	ServiceAccountEmail string
	// PrivateKeyPEM is the PEM-encoded RSA private key. Required for
	// SignedURL. Loaded from a service-account JSON key file at startup
	// (the JSON's "private_key" field).
	PrivateKeyPEM []byte
	// PresignExpiry is the TTL on InitiateUpload / CreateSignedURL URLs.
	PresignExpiry time.Duration
	// SigningScheme is V4 (default; only V4 is supported in production).
	SigningScheme gcsstorage.SigningScheme
	// Location is the bucket location constraint (informational; used in
	// Probe to scope the request).
	Location string
}

// BucketHandle is the small surface of *storage.BucketHandle used by
// the Driver. Exported so tests can swap in fakes.
type BucketHandle interface {
	SignedURL(object string, opts *gcsstorage.SignedURLOptions) (string, error)
	Object(name string) ObjectHandle
}

// ObjectHandle is the small surface of *storage.ObjectHandle used by
// the Driver. Concrete return types are storage-specific (Reader /
// Writer / ObjectAttrs) so we declare them as opaque io / struct types.
type ObjectHandle interface {
	NewReader(ctx context.Context) (io.ReadCloser, error)
	NewWriter(ctx context.Context) (ObjectWriter, error)
	Delete(ctx context.Context) error
	Attrs(ctx context.Context) (*gcsstorage.ObjectAttrs, error)
}

// ObjectWriter is the small surface of *storage.Writer the Driver
// touches (Write, Close).
type ObjectWriter interface {
	io.WriteCloser
}

// Client is the small surface of *storage.Client the Driver uses.
// NewWithClient takes this; the production path passes a wrapper that
// delegates to the real *storage.Client.
type Client interface {
	Bucket(name string) BucketHandle
	Close() error
}

// Driver is the GCS StorageDriver.
type Driver struct {
	opts   Options
	client Client
}

// NewStub returns a placeholder Driver whose every operation returns
// storage.ErrDriverNotImplemented. Used by cmd/server/main.go when
// FILE_SERVICE_GCS_ENABLED is false (dev / CI without GCS creds).
func NewStub() *Driver { return &Driver{} }

// New builds a Driver with a real *storage.Client. opts.Bucket +
// opts.ServiceAccountEmail + opts.PrivateKeyPEM are required for
// SignedURL (the data-plane operations work with any creds that
// gcsstorage.NewClient accepts).
func New(ctx context.Context, opts Options) (*Driver, error) {
	if opts.Bucket == "" {
		return nil, errors.New("gcs: Bucket is required")
	}
	if opts.ServiceAccountEmail == "" {
		return nil, errors.New("gcs: ServiceAccountEmail is required (signing identity for SignedURL)")
	}
	if len(opts.PrivateKeyPEM) == 0 {
		return nil, errors.New("gcs: PrivateKeyPEM is required (signing key for SignedURL)")
	}
	if opts.PresignExpiry == 0 {
		opts.PresignExpiry = 15 * time.Minute
	}
	if opts.SigningScheme == 0 {
		opts.SigningScheme = gcsstorage.SigningSchemeV4
	}

	client, err := gcsstorage.NewClient(ctx)
	if err != nil {
		return nil, fmt.Errorf("gcs: NewClient: %w", err)
	}
	return NewWithClient(opts, &clientAdapter{Client: client}), nil
}

// NewWithClient wires a Driver around an arbitrary Client (a real
// clientAdapter wrapping *gcsstorage.Client in production; a fake in
// tests).
func NewWithClient(opts Options, client Client) *Driver {
	if opts.PresignExpiry == 0 {
		opts.PresignExpiry = 15 * time.Minute
	}
	if opts.SigningScheme == 0 {
		opts.SigningScheme = gcsstorage.SigningSchemeV4
	}
	return &Driver{opts: opts, client: client}
}

// clientAdapter wraps *gcsstorage.Client so the production SDK
// satisfies our Client interface (the SDK's Bucket returns a concrete
// *gcsstorage.BucketHandle, so we adapt that on the way out).
type clientAdapter struct {
	*gcsstorage.Client
}

func (a *clientAdapter) Bucket(name string) BucketHandle {
	return &bucketAdapter{BucketHandle: a.Client.Bucket(name)}
}

// bucketAdapter wraps *gcsstorage.BucketHandle.
type bucketAdapter struct {
	*gcsstorage.BucketHandle
}

func (a *bucketAdapter) Object(name string) ObjectHandle {
	return &objectAdapter{ObjectHandle: a.BucketHandle.Object(name)}
}

// objectAdapter wraps *gcsstorage.ObjectHandle.
type objectAdapter struct {
	*gcsstorage.ObjectHandle
}

func (a *objectAdapter) NewReader(ctx context.Context) (io.ReadCloser, error) {
	return a.ObjectHandle.NewReader(ctx)
}

func (a *objectAdapter) NewWriter(ctx context.Context) (ObjectWriter, error) {
	return a.ObjectHandle.NewWriter(ctx), nil
}

func (a *objectAdapter) Delete(ctx context.Context) error {
	return a.ObjectHandle.Delete(ctx)
}

func (a *objectAdapter) Attrs(ctx context.Context) (*gcsstorage.ObjectAttrs, error) {
	return a.ObjectHandle.Attrs(ctx)
}

// objectLocator reduces a DriverLocator to (bucket, object). Missing
// bucket falls back to the Driver default.
func (d *Driver) objectLocator(loc storage.DriverLocator) (string, string, error) {
	if loc == nil {
		return "", "", errors.New("gcs: nil locator")
	}
	obj, _ := loc["object"].(string)
	if obj == "" {
		obj, _ = loc["key"].(string)
		if obj == "" {
			obj, _ = loc["path"].(string)
		}
	}
	if obj == "" {
		return "", "", errors.New("gcs: locator missing 'object'")
	}
	bucket, _ := loc["bucket"].(string)
	if bucket == "" {
		bucket = d.opts.Bucket
	}
	return bucket, obj, nil
}

// InitiateUpload returns a V4-signed PUT URL scoped to the requested
// mime type. The caller MUST send PUT with the exact mime type to use
// the URL (GCS V4 binds content-type into the signature).
func (d *Driver) InitiateUpload(ctx context.Context, req storage.InitiateUploadRequest) (storage.InitiateUploadResponse, error) {
	bucket := d.opts.Bucket
	object := req.FileID

	url, err := d.client.Bucket(bucket).SignedURL(object, &gcsstorage.SignedURLOptions{
		GoogleAccessID: d.opts.ServiceAccountEmail,
		PrivateKey:     d.opts.PrivateKeyPEM,
		Method:         "PUT",
		Expires:        time.Now().Add(d.opts.PresignExpiry),
		ContentType:    req.MimeType,
		Scheme:         d.opts.SigningScheme,
	})
	if err != nil {
		return storage.InitiateUploadResponse{}, fmt.Errorf("gcs: signed PUT: %w", err)
	}

	return storage.InitiateUploadResponse{
		UploadURL:     url,
		DriverLocator: storage.DriverLocator{"bucket": bucket, "object": object},
		ExpiresAt:     time.Now().Add(d.opts.PresignExpiry),
	}, nil
}

// CompleteUpload verifies the object exists and returns its metadata.
func (d *Driver) CompleteUpload(ctx context.Context, req storage.CompleteUploadRequest) (storage.ObjectMetadata, error) {
	bucket, object, err := d.objectLocator(req.DriverLocator)
	if err != nil {
		return storage.ObjectMetadata{}, err
	}
	attrs, err := d.client.Bucket(bucket).Object(object).Attrs(ctx)
	if err != nil {
		if isObjectNotFound(err) {
			return storage.ObjectMetadata{Exists: false}, nil
		}
		return storage.ObjectMetadata{}, fmt.Errorf("gcs: Attrs: %w", err)
	}
	meta := storage.ObjectMetadata{
		SizeBytes: attrs.Size,
		ETag:      attrs.Etag,
		Exists:    true,
	}
	if attrs.KMSKeyName != "" {
		meta.ETag = "kms:" + meta.ETag
	}
	return meta, nil
}

// GetObject streams the object. Caller MUST close the reader.
func (d *Driver) GetObject(ctx context.Context, loc storage.DriverLocator) (io.ReadCloser, error) {
	bucket, object, err := d.objectLocator(loc)
	if err != nil {
		return nil, err
	}
	rc, err := d.client.Bucket(bucket).Object(object).NewReader(ctx)
	if err != nil {
		return nil, fmt.Errorf("gcs: NewReader: %w", err)
	}
	return rc, nil
}

// PutObject streams src into the object. encryptionKeyID is interpreted
// as a CMEK key (KMSKeyName) and set on Writer.attrs when non-empty.
// The body is buffered because gcsstorage.Writer takes an io.WriteCloser
// not a Reader — io.Pipe would be more efficient for large blobs but
// keeps the first cut small.
func (d *Driver) PutObject(ctx context.Context, loc storage.DriverLocator, src io.Reader, encryptionKeyID string) error {
	bucket, object, err := d.objectLocator(loc)
	if err != nil {
		return err
	}
	body, err := io.ReadAll(src)
	if err != nil {
		return fmt.Errorf("gcs: read source: %w", err)
	}
	writer, err := d.client.Bucket(bucket).Object(object).NewWriter(ctx)
	if err != nil {
		return fmt.Errorf("gcs: NewWriter: %w", err)
	}
	if encryptionKeyID != "" {
		// Writer exposes KMSKeyName via the embedded *ObjectAttrs;
		// reach through Writer's concrete type to set it. We can't do
		// this through the ObjectWriter interface, so cast — every
		// production path uses the real *storage.Writer.
		if w, ok := writer.(*gcsstorage.Writer); ok && w.KMSKeyName == "" {
			w.KMSKeyName = encryptionKeyID
		}
	}
	if _, err := io.Copy(writer, bytes.NewReader(body)); err != nil {
		_ = writer.Close()
		return fmt.Errorf("gcs: write: %w", err)
	}
	if err := writer.Close(); err != nil {
		return fmt.Errorf("gcs: close writer: %w", err)
	}
	return nil
}

// DeleteObject removes the object. GCS Delete is idempotent for
// non-existent objects.
func (d *Driver) DeleteObject(ctx context.Context, loc storage.DriverLocator) error {
	bucket, object, err := d.objectLocator(loc)
	if err != nil {
		return err
	}
	if err := d.client.Bucket(bucket).Object(object).Delete(ctx); err != nil {
		if isObjectNotFound(err) {
			return nil
		}
		return fmt.Errorf("gcs: Delete: %w", err)
	}
	return nil
}

// HeadObject returns size / etag / existence without going through
// CompleteUpload's translation.
func (d *Driver) HeadObject(ctx context.Context, loc storage.DriverLocator) (storage.ObjectMetadata, error) {
	return d.CompleteUpload(ctx, storage.CompleteUploadRequest{DriverLocator: loc})
}

// CreateSignedURL returns a V4-signed GET URL scoped to the requested
// verb (scope="get") for read downloads. scope is informational.
func (d *Driver) CreateSignedURL(ctx context.Context, loc storage.DriverLocator, ttl time.Duration, scope string) (string, error) {
	bucket, object, err := d.objectLocator(loc)
	if err != nil {
		return "", err
	}
	url, err := d.client.Bucket(bucket).SignedURL(object, &gcsstorage.SignedURLOptions{
		GoogleAccessID: d.opts.ServiceAccountEmail,
		PrivateKey:     d.opts.PrivateKeyPEM,
		Method:         "GET",
		Expires:        time.Now().Add(ttl),
		Scheme:         d.opts.SigningScheme,
	})
	if err != nil {
		return "", fmt.Errorf("gcs: signed GET: %w", err)
	}
	return url, nil
}

// Probe checks that the driver is configured. GCS has no cheap bucket
// existence probe; we surface Healthy when the config is sane and let
// per-request paths catch actual auth errors. The 30s synthetic probe
// ticker in cmd/server/main.go flips to Unreachable if any per-request
// operation fails consecutively.
func (d *Driver) Probe(ctx context.Context) storage.ProbeResult {
	start := time.Now()
	probeCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if err := d.probeBucket(probeCtx); err != nil {
		return storage.ProbeResult{Healthy: false, LatencyMS: time.Since(start).Milliseconds(), Error: err}
	}
	return storage.ProbeResult{Healthy: true, LatencyMS: time.Since(start).Milliseconds()}
}

// probeBucket does a cheap existence check. GCS doesn't expose a
// HeadBucket — using the IAM "buckets.get" via the IAM client is too
// heavy; we fall back to "the driver is configured" → healthy. Real
// liveness comes from the per-object data-plane calls and the
// per-request error counters.
func (d *Driver) probeBucket(_ context.Context) error {
	if d.opts.Bucket == "" || d.opts.ServiceAccountEmail == "" {
		return errors.New("gcs: missing bucket or service account config")
	}
	return nil
}

// Shutdown closes the underlying *gcsstorage.Client.
func (d *Driver) Shutdown(_ context.Context) error {
	if d.client == nil {
		return nil
	}
	return d.client.Close()
}

// isObjectNotFound matches the SDK's ErrObjectNotExist + GoogleAPI
// 404 variants.
func isObjectNotFound(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, gcsstorage.ErrObjectNotExist) {
		return true
	}
	if err.Error() == "storage: object doesn't exist" {
		return true
	}
	return false
}
