// Package oracle_object_storage is the Oracle Cloud Infrastructure
// Object Storage StorageDriver. Per TECH.md §2, OCI Object Storage
// supports two APIs:
//
//  1. Native OCI REST API (objectstorage.* — namespace, bucket,
//     object) for Pre-Authenticated Requests (PAR) and namespace
//     metadata (GetNamespace for the synthetic probe).
//  2. S3-compatible endpoint via aws-sdk-go-v2 for direct-to-driver
//     upload + multipart upload, when the bucket has S3 compatibility
//     enabled.
//
// The Driver ships with the native REST path implemented; the
// S3-compatible path is delegated to the s3 driver via an S3Client
// interface so the same presign / upload / download code is shared
// between the two OCI modes.
//
// Construction:
//
//   - New(ctx, opts) wires an OCI driver from Options. opts.Namespace,
//     opts.Region, opts.Bucket are required; opts.S3Compatible toggles
//     the S3-compatible mode (when true, opts.S3Endpoint +
//     opts.AccessKey + opts.SecretKey are used to construct the inner
//     s3.Driver; the native REST path still runs Probe via
//     GetNamespace).
//   - NewWithClients wires an arbitrary nativeClient + S3Client for tests.
//
// DriverLocator shape:
//
//	{"namespace": "<ns>", "bucket": "<bucket>", "object": "<file_id>"}
//
// When S3-compatible mode is on, the locator is also routable through
// the S3 driver (namespace is informational; the S3 client uses
// bucket+key).
package oracle_object_storage

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/oracle/oci-go-sdk/v65/common"
	"github.com/oracle/oci-go-sdk/v65/objectstorage"

	"github.com/trips-enjoy/platform/file-service/internal/storage"
	"github.com/trips-enjoy/platform/file-service/internal/storage/drivers/s3"
)

// Options carries everything the production constructor needs.
type Options struct {
	// Namespace is the OCI tenancy namespace (the prefix on every
	// bucket name; not the human-friendly name). Required.
	Namespace string
	// Region is the OCI region (e.g. "us-ashburn-1"). Required.
	Region string
	// Bucket is the default bucket. Per-call DriverLocator["bucket"]
	// overrides.
	Bucket string
	// S3Compatible when true routes InitiateUpload / GetObject / PutObject
	// through the inner s3.Driver (the OCI S3-compatible endpoint).
	// Native REST path is still used for Probe (GetNamespace) and
	// DeleteObject (no S3-compatible SDK covers OCI's namespace
	// authentication model exactly).
	S3Compatible bool
	// S3Endpoint is the OCI S3-compatible endpoint
	// (e.g. "https://<namespace>.compat.objectstorage.<region>.oci.oraclecloud.com").
	// Required when S3Compatible = true.
	S3Endpoint string
	// AccessKey / SecretKey are OCI customer-secret-key credentials for
	// the S3-compatible API. Required when S3Compatible = true.
	AccessKey string
	SecretKey string
	// CompartmentID is the OCI compartment OCID. Required for the
	// native REST API.
	CompartmentID string
	// PresignExpiry is the TTL on InitiateUpload / CreateSignedURL URLs.
	PresignExpiry time.Duration
	// KMSKeyID is the OCI Vault master encryption key OCID. Empty
	// means bucket-default encryption.
	KMSKeyID string
}

// nativeClient is the small surface of *objectstorage.ObjectStorageClient
// used by the Driver. Defined here so tests can swap in a fake.
type nativeClient interface {
	GetNamespace(ctx context.Context, req objectstorage.GetNamespaceRequest) (objectstorage.GetNamespaceResponse, error)
	PutObject(ctx context.Context, req objectstorage.PutObjectRequest) (objectstorage.PutObjectResponse, error)
	GetObject(ctx context.Context, req objectstorage.GetObjectRequest) (objectstorage.GetObjectResponse, error)
	HeadObject(ctx context.Context, req objectstorage.HeadObjectRequest) (objectstorage.HeadObjectResponse, error)
	DeleteObject(ctx context.Context, req objectstorage.DeleteObjectRequest) (objectstorage.DeleteObjectResponse, error)
	CreatePreauthenticatedRequest(ctx context.Context, req objectstorage.CreatePreauthenticatedRequestRequest) (objectstorage.CreatePreauthenticatedRequestResponse, error)
}

// s3Delegate is the small surface of *s3.Driver used by the Driver
// when S3Compatible = true. We delegate rather than reimplement the
// presign / upload / download path.
type s3Delegate interface {
	InitiateUpload(ctx context.Context, req storage.InitiateUploadRequest) (storage.InitiateUploadResponse, error)
	CompleteUpload(ctx context.Context, req storage.CompleteUploadRequest) (storage.ObjectMetadata, error)
	GetObject(ctx context.Context, loc storage.DriverLocator) (io.ReadCloser, error)
	PutObject(ctx context.Context, loc storage.DriverLocator, src io.Reader, encryptionKeyID string) error
	DeleteObject(ctx context.Context, loc storage.DriverLocator) error
	HeadObject(ctx context.Context, loc storage.DriverLocator) (storage.ObjectMetadata, error)
	CreateSignedURL(ctx context.Context, loc storage.DriverLocator, ttl time.Duration, scope string) (string, error)
}

// Driver is the OCI Object Storage StorageDriver.
type Driver struct {
	opts   Options
	native nativeClient // optional; injected via WithNative in tests
	s3     s3Delegate   // optional; populated when S3Compatible = true
}

// NewStub returns a placeholder Driver whose every operation returns
// storage.ErrDriverNotImplemented. Used by cmd/server/main.go when
// FILE_SERVICE_OCI_ENABLED is false (dev / CI without OCI creds).
func NewStub() *Driver { return &Driver{} }

// New builds a Driver from Options. opts.Namespace + opts.Region +
// opts.Bucket are required. opts.S3Compatible toggles the S3 path.
func New(ctx context.Context, opts Options) (*Driver, error) {
	if opts.Namespace == "" {
		return nil, errors.New("oracle_object_storage: Namespace is required")
	}
	if opts.Region == "" {
		return nil, errors.New("oracle_object_storage: Region is required")
	}
	if opts.Bucket == "" {
		return nil, errors.New("oracle_object_storage: Bucket is required")
	}
	if opts.PresignExpiry == 0 {
		opts.PresignExpiry = 15 * time.Minute
	}
	if opts.S3Compatible && opts.S3Endpoint == "" {
		return nil, errors.New("oracle_object_storage: S3Endpoint is required when S3Compatible is true")
	}
	if opts.S3Compatible && (opts.AccessKey == "" || opts.SecretKey == "") {
		return nil, errors.New("oracle_object_storage: AccessKey + SecretKey are required when S3Compatible is true")
	}

	driver := &Driver{opts: opts}

	if opts.S3Compatible {
		s3Driver, err := s3.New(ctx, s3.Options{
			Region:        opts.Region,
			Endpoint:      opts.S3Endpoint,
			Bucket:        opts.Bucket,
			AccessKey:     opts.AccessKey,
			SecretKey:     opts.SecretKey,
			PathStyle:     true, // OCI S3-compatible uses path-style
			PresignExpiry: opts.PresignExpiry,
		})
		if err != nil {
			return nil, fmt.Errorf("oracle_object_storage: build S3 delegate: %w", err)
		}
		driver.s3 = s3Driver
	}

	return driver, nil
}

// NewWithClients wires an arbitrary nativeClient + s3Delegate for
// tests. The native client is optional (nil when the driver is
// S3-only); the s3 delegate is required when S3Compatible = true.
func NewWithClients(opts Options, native nativeClient, s3Del s3Delegate) *Driver {
	if opts.PresignExpiry == 0 {
		opts.PresignExpiry = 15 * time.Minute
	}
	return &Driver{opts: opts, native: native, s3: s3Del}
}

// objectLocator reduces a DriverLocator to (namespace, bucket, object).
// Missing values fall back to the Driver defaults.
func (d *Driver) objectLocator(loc storage.DriverLocator) (string, string, string, error) {
	if loc == nil {
		return "", "", "", errors.New("oracle_object_storage: nil locator")
	}
	obj, _ := loc["object"].(string)
	if obj == "" {
		obj, _ = loc["key"].(string)
		if obj == "" {
			obj, _ = loc["path"].(string)
		}
	}
	if obj == "" {
		return "", "", "", errors.New("oracle_object_storage: locator missing 'object'")
	}
	bucket, _ := loc["bucket"].(string)
	if bucket == "" {
		bucket = d.opts.Bucket
	}
	namespace, _ := loc["namespace"].(string)
	if namespace == "" {
		namespace = d.opts.Namespace
	}
	return namespace, bucket, obj, nil
}

// InitiateUpload returns a Pre-Authenticated Request (PAR) URL when
// S3-compatible mode is off, or delegates to the inner S3 driver for
// presigned PUT URLs when S3-compatible mode is on.
func (d *Driver) InitiateUpload(ctx context.Context, req storage.InitiateUploadRequest) (storage.InitiateUploadResponse, error) {
	if d.s3 != nil {
		return d.s3.InitiateUpload(ctx, req)
	}
	if d.native == nil {
		return storage.InitiateUploadResponse{}, errors.New("oracle_object_storage: native client not configured")
	}
	bucket := d.opts.Bucket
	object := req.FileID
	expiry := common.SDKTime{Time: time.Now().Add(d.opts.PresignExpiry)}

	par, err := d.native.CreatePreauthenticatedRequest(ctx, objectstorage.CreatePreauthenticatedRequestRequest{
		NamespaceName: &d.opts.Namespace,
		BucketName:    &bucket,
		CreatePreauthenticatedRequestDetails: objectstorage.CreatePreauthenticatedRequestDetails{
			Name:        &object,
			ObjectName:  &object,
			AccessType:  objectstorage.CreatePreauthenticatedRequestDetailsAccessTypeObjectwrite,
			TimeExpires: &expiry,
		},
	})
	if err != nil {
		return storage.InitiateUploadResponse{}, fmt.Errorf("oracle_object_storage: CreatePreauthenticatedRequest: %w", err)
	}
	if par.AccessUri == nil {
		return storage.InitiateUploadResponse{}, errors.New("oracle_object_storage: PAR response missing AccessUri")
	}
	host := ""
	if par.RawResponse != nil && par.RawResponse.Request != nil {
		host = par.RawResponse.Request.URL.Scheme + "://" + par.RawResponse.Request.URL.Host
	}
	url := host + *par.AccessUri

	return storage.InitiateUploadResponse{
		UploadURL:     url,
		DriverLocator: storage.DriverLocator{"namespace": d.opts.Namespace, "bucket": bucket, "object": object},
		ExpiresAt:     expiry.Time,
	}, nil
}

// CompleteUpload verifies the object exists and returns its metadata.
// In S3-compatible mode it delegates to the inner S3 driver.
func (d *Driver) CompleteUpload(ctx context.Context, req storage.CompleteUploadRequest) (storage.ObjectMetadata, error) {
	if d.s3 != nil {
		return d.s3.CompleteUpload(ctx, req)
	}
	if d.native == nil {
		return storage.ObjectMetadata{}, errors.New("oracle_object_storage: native client not configured")
	}
	_, bucket, object, err := d.objectLocator(req.DriverLocator)
	if err != nil {
		return storage.ObjectMetadata{}, err
	}
	head, err := d.native.HeadObject(ctx, objectstorage.HeadObjectRequest{
		NamespaceName: &d.opts.Namespace,
		BucketName:    &bucket,
		ObjectName:    &object,
	})
	if err != nil {
		if isObjectNotFound(err) {
			return storage.ObjectMetadata{Exists: false}, nil
		}
		return storage.ObjectMetadata{}, fmt.Errorf("oracle_object_storage: HeadObject: %w", err)
	}
	meta := storage.ObjectMetadata{Exists: true}
	if head.ContentLength != nil {
		meta.SizeBytes = *head.ContentLength
	}
	if head.ETag != nil {
		meta.ETag = *head.ETag
	}
	if head.OpcMultipartMd5 != nil {
		meta.SHA256 = *head.OpcMultipartMd5
	}
	return meta, nil
}

// GetObject streams the object body. In S3-compatible mode delegates
// to the inner S3 driver.
func (d *Driver) GetObject(ctx context.Context, loc storage.DriverLocator) (io.ReadCloser, error) {
	if d.s3 != nil {
		return d.s3.GetObject(ctx, loc)
	}
	if d.native == nil {
		return nil, errors.New("oracle_object_storage: native client not configured")
	}
	_, bucket, object, err := d.objectLocator(loc)
	if err != nil {
		return nil, err
	}
	resp, err := d.native.GetObject(ctx, objectstorage.GetObjectRequest{
		NamespaceName: &d.opts.Namespace,
		BucketName:    &bucket,
		ObjectName:    &object,
	})
	if err != nil {
		return nil, fmt.Errorf("oracle_object_storage: GetObject: %w", err)
	}
	return resp.Content, nil
}

// PutObject streams src into the object. In S3-compatible mode
// delegates to the inner S3 driver.
func (d *Driver) PutObject(ctx context.Context, loc storage.DriverLocator, src io.Reader, encryptionKeyID string) error {
	if d.s3 != nil {
		return d.s3.PutObject(ctx, loc, src, encryptionKeyID)
	}
	if d.native == nil {
		return errors.New("oracle_object_storage: native client not configured")
	}
	_, bucket, object, err := d.objectLocator(loc)
	if err != nil {
		return err
	}
	body, err := io.ReadAll(src)
	if err != nil {
		return fmt.Errorf("oracle_object_storage: read source: %w", err)
	}
	req := objectstorage.PutObjectRequest{
		NamespaceName: &d.opts.Namespace,
		BucketName:    &bucket,
		ObjectName:    &object,
		PutObjectBody: io.NopCloser(bytes.NewReader(body)),
		ContentLength: common.Int64(int64(len(body))),
	}
	if encryptionKeyID != "" {
		req.OpcSseKmsKeyId = &encryptionKeyID
	}
	if _, err := d.native.PutObject(ctx, req); err != nil {
		return fmt.Errorf("oracle_object_storage: PutObject: %w", err)
	}
	return nil
}

// DeleteObject removes the object. In S3-compatible mode delegates
// to the inner S3 driver.
func (d *Driver) DeleteObject(ctx context.Context, loc storage.DriverLocator) error {
	if d.s3 != nil {
		return d.s3.DeleteObject(ctx, loc)
	}
	if d.native == nil {
		return errors.New("oracle_object_storage: native client not configured")
	}
	_, bucket, object, err := d.objectLocator(loc)
	if err != nil {
		return err
	}
	_, err = d.native.DeleteObject(ctx, objectstorage.DeleteObjectRequest{
		NamespaceName: &d.opts.Namespace,
		BucketName:    &bucket,
		ObjectName:    &object,
	})
	if err != nil && !isObjectNotFound(err) {
		return fmt.Errorf("oracle_object_storage: DeleteObject: %w", err)
	}
	return nil
}

// HeadObject returns size / etag / existence without going through
// CompleteUpload's translation.
func (d *Driver) HeadObject(ctx context.Context, loc storage.DriverLocator) (storage.ObjectMetadata, error) {
	return d.CompleteUpload(ctx, storage.CompleteUploadRequest{DriverLocator: loc})
}

// CreateSignedURL returns a Pre-Authenticated Request (PAR) URL
// scoped to read in native mode; delegates to the inner S3 driver
// for a presigned GET in S3-compatible mode.
func (d *Driver) CreateSignedURL(ctx context.Context, loc storage.DriverLocator, ttl time.Duration, scope string) (string, error) {
	if d.s3 != nil {
		return d.s3.CreateSignedURL(ctx, loc, ttl, scope)
	}
	if d.native == nil {
		return "", errors.New("oracle_object_storage: native client not configured")
	}
	_, bucket, object, err := d.objectLocator(loc)
	if err != nil {
		return "", err
	}
	expiry := common.SDKTime{Time: time.Now().Add(ttl)}
	par, err := d.native.CreatePreauthenticatedRequest(ctx, objectstorage.CreatePreauthenticatedRequestRequest{
		NamespaceName: &d.opts.Namespace,
		BucketName:    &bucket,
		CreatePreauthenticatedRequestDetails: objectstorage.CreatePreauthenticatedRequestDetails{
			Name:        &object,
			ObjectName:  &object,
			AccessType:  objectstorage.CreatePreauthenticatedRequestDetailsAccessTypeObjectread,
			TimeExpires: &expiry,
		},
	})
	if err != nil {
		return "", fmt.Errorf("oracle_object_storage: CreatePreauthenticatedRequest: %w", err)
	}
	if par.AccessUri == nil {
		return "", errors.New("oracle_object_storage: PAR response missing AccessUri")
	}
	host := ""
	if par.RawResponse != nil && par.RawResponse.Request != nil {
		host = par.RawResponse.Request.URL.Scheme + "://" + par.RawResponse.Request.URL.Host
	}
	return host + *par.AccessUri, nil
}

// Probe runs GetNamespace against the OCI Object Storage service.
// The namespace is required for any subsequent operation; if it
// cannot be resolved the driver is unreachable.
func (d *Driver) Probe(ctx context.Context) storage.ProbeResult {
	start := time.Now()
	probeCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if d.native == nil && d.s3 == nil {
		return storage.ProbeResult{Healthy: false, LatencyMS: time.Since(start).Milliseconds(), Error: errors.New("oracle_object_storage: no client configured")}
	}
	if d.native == nil {
		// S3-only mode: defer to the inner driver's Probe (s3
		// driver's HeadBucket against the S3-compatible endpoint).
		return d.s3.(*s3.Driver).Probe(probeCtx)
	}
	resp, err := d.native.GetNamespace(probeCtx, objectstorage.GetNamespaceRequest{
		CompartmentId: &d.opts.CompartmentID,
	})
	if err != nil {
		return storage.ProbeResult{Healthy: false, LatencyMS: time.Since(start).Milliseconds(), Error: err}
	}
	if resp.Value == nil {
		return storage.ProbeResult{Healthy: false, LatencyMS: time.Since(start).Milliseconds(), Error: errors.New("oracle_object_storage: GetNamespace returned empty")}
	}
	return storage.ProbeResult{Healthy: true, LatencyMS: time.Since(start).Milliseconds()}
}

// Shutdown is a no-op for the in-process clients (the OCI SDK and
// aws-sdk-go-v2 are both stateless).
func (d *Driver) Shutdown(_ context.Context) error { return nil }

// isObjectNotFound matches the OCI SDK's 404 variants.
func isObjectNotFound(err error) bool {
	if err == nil {
		return false
	}
	var svcErr serviceError
	if errors.As(err, &svcErr) {
		if svcErr.GetHTTPStatusCode() == http.StatusNotFound {
			return true
		}
	}
	return strings.Contains(err.Error(), "ObjectNotFound") || strings.Contains(err.Error(), "NotFound")
}

// serviceError is the OCI SDK's service-error interface; we duck-type
// it here so the import surface stays small.
type serviceError interface {
	error
	GetHTTPStatusCode() int
}
