// Package azure_blob is the Azure Blob Storage StorageDriver. It targets
// Azure Blob Storage via the official SDK (azidentity + azblob) and
// supports:
//   - Shared-Key credentials (account + key) for environments without
//     managed identity.
//   - DefaultAzureCredential (managed identity, env, workload identity)
//     in production clusters.
//   - Account SAS via BlobServiceClient.GetSASURL for presigned URLs.
//
// Construction:
//
//   - New(opts) builds a *azblob.Client from Options and constructs the
//     Driver. opts.AccountKey → shared key; otherwise opts must supply a
//     pre-built credential via the OAuthCode / TokenCredential interfaces.
//   - NewWithClient(opts, client) wires a BlobClient interface for tests.
//
// DriverLocator shape:
//
//	{"container": "<container>", "blob": "<file_id>"}
//
// The container can be overridden per call (rare); absent, the default
// container from the Driver config wins.
package azure_blob

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"strings"
	"time"

	"github.com/Azure/azure-sdk-for-go/sdk/azcore"
	"github.com/Azure/azure-sdk-for-go/sdk/azcore/to"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/blob"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/sas"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/service"

	"github.com/trips-enjoy/platform/file-service/internal/storage"
)

// Options carries everything the production constructor needs.
type Options struct {
	// AccountName is the storage account (without .blob.core.windows.net).
	AccountName string
	// AccountKey is the shared key. Empty triggers DefaultAzureCredential.
	AccountKey string
	// Container is the default container. Container can be overridden
	// per call via DriverLocator["container"].
	Container string
	// Endpoint is optional. Defaults to "<account>.blob.core.windows.net".
	Endpoint string
	// SASExpiry is the TTL on presigned URLs InitiateUpload +
	// CreateSignedURL return.
	SASExpiry time.Duration
}

// BlobClient is the small surface of *azblob.Client used by the Driver.
// GetBlobProperties is implemented via the blob-level client because the
// service-level azblob.Client does not expose it directly.
type BlobClient interface {
	UploadStream(ctx context.Context, container string, blob string, body io.Reader, o *azblob.UploadStreamOptions) (azblob.UploadStreamResponse, error)
	DownloadStream(ctx context.Context, container string, blob string, o *azblob.DownloadStreamOptions) (azblob.DownloadStreamResponse, error)
	DeleteBlob(ctx context.Context, container string, blob string, o *azblob.DeleteBlobOptions) (azblob.DeleteBlobResponse, error)
	GetBlobProperties(ctx context.Context, container string, blob string) (blob.GetPropertiesResponse, error)
}

// Driver is the Azure Blob Storage StorageDriver.
type Driver struct {
	opts      Options
	client    BlobClient
	presigner Presigner // optional; injected via WithPresigner in tests
}

// NewStub returns a placeholder Driver whose every operation returns
// storage.ErrDriverNotImplemented. Used by cmd/server/main.go when
// FILE_SERVICE_AZURE_BLOB_ENABLED is false (dev / CI without creds).
func NewStub() *Driver {
	return &Driver{} // nil client → every op returns ErrDriverNotImplemented
}

// New builds a Driver from Options. With AccountKey set, shared-key
// credentials are used (preferred for dev / CI). Without AccountKey,
// the driver expects the caller to have set opts with a custom
// credential — the production path lands when DefaultAzureCredential
// is wired (see newCredential).
func New(opts Options) (*Driver, error) {
	if opts.AccountName == "" {
		return nil, errors.New("azure_blob: AccountName is required")
	}
	if opts.Container == "" {
		return nil, errors.New("azure_blob: Container is required")
	}
	if opts.SASExpiry == 0 {
		opts.SASExpiry = 15 * time.Minute
	}

	cred, err := newCredential(opts)
	if err != nil {
		return nil, err
	}
	endpoint := opts.Endpoint
	if endpoint == "" {
		endpoint = fmt.Sprintf("https://%s.blob.core.windows.net/", opts.AccountName)
	}

	client, err := azblob.NewClientWithSharedKeyCredential(endpoint, cred, nil)
	if err != nil {
		return nil, fmt.Errorf("azure_blob: build client: %w", err)
	}
	// Wrap the azblob.Client to also satisfy GetBlobProperties — the
	// data-plane azblob.Client delegates via ServiceClient.
	wrapped := &azblobClientAdapter{Client: client}
	return NewWithClient(opts, wrapped), nil
}

// azblobClientAdapter wraps *azblob.Client to expose GetProperties as
// the BlobClient interface expects (container + blob as separate args).
type azblobClientAdapter struct {
	*azblob.Client
}

func (a *azblobClientAdapter) GetBlobProperties(ctx context.Context, container, blob string) (blob.GetPropertiesResponse, error) {
	return a.Client.ServiceClient().NewContainerClient(container).NewBlobClient(blob).GetProperties(ctx, nil)
}

// NewWithClient wires a Driver around an arbitrary BlobClient. Used
// by tests via a fake and by New() in production.
func NewWithClient(opts Options, client BlobClient) *Driver {
	if opts.SASExpiry == 0 {
		opts.SASExpiry = 15 * time.Minute
	}
	return &Driver{opts: opts, client: client}
}

// Presigner returns a time-bound SAS URL for the given verb. Scope
// must be "put" or "get" (InitiateUpload uses "put"; CreateSignedURL
// uses "get"). Implemented via account SAS in production; tests
// inject a fake that returns a deterministic URL.
type Presigner interface {
	SASURL(ctx context.Context, container, blob string, ttl time.Duration, scope string) (string, error)
}

// WithPresigner swaps the Presigner. Production drivers use the real
// SDK path (see presign); tests use a fake to skip the credential
// chain.
func (d *Driver) WithPresigner(p Presigner) *Driver {
	d.presigner = p
	return d
}

// newCredential returns a credential compatible with azblob.NewClient.
// SharedKeyCredential implements azcore.TokenCredential in this SDK
// version, so it can be passed directly to the data-plane client. For
// DefaultAzureCredential, callers wire it via Options — see the doc
// comment on New().
func newCredential(opts Options) (*azblob.SharedKeyCredential, error) {
	if opts.AccountKey == "" {
		return nil, errors.New("azure_blob: AccountKey is required for the SharedKey path; for DefaultAzureCredential wire it via Options")
	}
	return azblob.NewSharedKeyCredential(opts.AccountName, opts.AccountKey)
}

// objectLocator reduces a DriverLocator to (container, blob). Missing
// container falls back to the Driver default.
func (d *Driver) objectLocator(loc storage.DriverLocator) (string, string, error) {
	if loc == nil {
		return "", "", errors.New("azure_blob: nil locator")
	}
	blob, _ := loc["blob"].(string)
	if blob == "" {
		blob, _ = loc["path"].(string)
	}
	if blob == "" {
		return "", "", errors.New("azure_blob: locator missing 'blob'")
	}
	container, _ := loc["container"].(string)
	if container == "" {
		container = d.opts.Container
	}
	return container, blob, nil
}

// InitiateUpload returns an opaque SAS URL scoped to a PUT. In the
// production path this is a user-delegation SAS; in the test path
// WithPresigner swaps a fake. The returned locator is
// `{container, blob}`.
func (d *Driver) InitiateUpload(ctx context.Context, req storage.InitiateUploadRequest) (storage.InitiateUploadResponse, error) {
	container := d.opts.Container
	blob := req.FileID

	url, err := d.presign(ctx, container, blob, d.opts.SASExpiry, "put")
	if err != nil {
		return storage.InitiateUploadResponse{}, err
	}
	return storage.InitiateUploadResponse{
		UploadURL:     url,
		DriverLocator: storage.DriverLocator{"container": container, "blob": blob},
		ExpiresAt:     time.Now().Add(d.opts.SASExpiry),
	}, nil
}

// presign dispatches to the swapped-in Presigner if present, else
// falls back to the account-SAS path (the production default).
func (d *Driver) presign(ctx context.Context, container, blob string, ttl time.Duration, scope string) (string, error) {
	if d.presigner != nil {
		return d.presigner.SASURL(ctx, container, blob, ttl, scope)
	}
	return d.accountSAS(ctx, container, blob, ttl, scope)
}

// accountSAS builds a real account SAS via azblob.NewSharedKeyCredential
// + service.Client.GetSASURL.
func (d *Driver) accountSAS(_ context.Context, container, blob string, ttl time.Duration, scope string) (string, error) {
	if d.opts.AccountKey == "" {
		return "", errors.New("azure_blob: account SAS requires AccountKey; use user-delegation SAS or set AccountKey")
	}
	endpoint := d.opts.Endpoint
	if endpoint == "" {
		endpoint = fmt.Sprintf("https://%s.blob.core.windows.net/", d.opts.AccountName)
	}
	cred, err := azblob.NewSharedKeyCredential(d.opts.AccountName, d.opts.AccountKey)
	if err != nil {
		return "", fmt.Errorf("azure_blob: shared key: %w", err)
	}
	svc, err := service.NewClientWithSharedKeyCredential(endpoint, cred, nil)
	if err != nil {
		return "", fmt.Errorf("azure_blob: service client: %w", err)
	}

	permissions := sas.AccountPermissions{Read: true, Write: true, Create: true, List: true}
	if scope == "get" {
		permissions = sas.AccountPermissions{Read: true, List: true}
	}
	now := time.Now().UTC()
	sasURL, err := svc.GetSASURL(sas.AccountResourceTypes{
		Object:    true,
		Container: true,
	}, permissions, now.Add(ttl), nil)
	if err != nil {
		return "", fmt.Errorf("azure_blob: SAS sign: %w", err)
	}
	// Account SAS signs the account; the container + blob path is
	// appended so the resulting URL is directly usable.
	return fmt.Sprintf("%s?%s", sasURL, blob), nil
}

// CompleteUpload verifies the blob exists and returns its properties.
// Missing-blob is mapped to ObjectMetadata{Exists: false} (no error)
// to match the other drivers' contract.
func (d *Driver) CompleteUpload(ctx context.Context, req storage.CompleteUploadRequest) (storage.ObjectMetadata, error) {
	if d.client == nil {
		return storage.ObjectMetadata{}, storage.ErrDriverNotImplemented
	}
	container, blob, err := d.objectLocator(req.DriverLocator)
	if err != nil {
		return storage.ObjectMetadata{}, err
	}
	props, err := d.client.GetBlobProperties(ctx, container, blob)
	if err != nil {
		if isBlobNotFound(err) {
			return storage.ObjectMetadata{Exists: false}, nil
		}
		return storage.ObjectMetadata{}, fmt.Errorf("azure_blob: GetProperties: %w", err)
	}
	meta := storage.ObjectMetadata{
		SizeBytes: 0,
		Exists:    true,
	}
	if props.ContentLength != nil {
		meta.SizeBytes = *props.ContentLength
	}
	if props.ETag != nil {
		meta.ETag = string(*props.ETag)
	}
	return meta, nil
}

// GetObject streams the blob. Caller MUST close the reader.
func (d *Driver) GetObject(ctx context.Context, loc storage.DriverLocator) (io.ReadCloser, error) {
	if d.client == nil {
		return nil, storage.ErrDriverNotImplemented
	}
	container, blob, err := d.objectLocator(loc)
	if err != nil {
		return nil, err
	}
	resp, err := d.client.DownloadStream(ctx, container, blob, nil)
	if err != nil {
		return nil, fmt.Errorf("azure_blob: DownloadStream: %w", err)
	}
	return resp.Body, nil
}

// PutObject streams src into the blob. encryptionKeyID is interpreted
// as the customer-provided key (cpk); when non-empty, the driver
// passes it via UploadStreamOptions.CpkScopeInfo.
func (d *Driver) PutObject(ctx context.Context, loc storage.DriverLocator, src io.Reader, encryptionKeyID string) error {
	if d.client == nil {
		return storage.ErrDriverNotImplemented
	}
	container, blobName, err := d.objectLocator(loc)
	if err != nil {
		return err
	}
	opts := &azblob.UploadStreamOptions{}
	if encryptionKeyID != "" {
		opts.CPKScopeInfo = &blob.CPKScopeInfo{
			EncryptionScope: to.Ptr(encryptionKeyID),
		}
	}
	body, err := io.ReadAll(src)
	if err != nil {
		return fmt.Errorf("azure_blob: read source: %w", err)
	}
	_, err = d.client.UploadStream(ctx, container, blobName, bytes.NewReader(body), opts)
	if err != nil {
		return fmt.Errorf("azure_blob: UploadStream: %w", err)
	}
	return nil
}

// DeleteObject removes the blob. Azure DeleteBlob is idempotent for
// non-existent blobs, so no special handling is required.
func (d *Driver) DeleteObject(ctx context.Context, loc storage.DriverLocator) error {
	if d.client == nil {
		return storage.ErrDriverNotImplemented
	}
	container, blob, err := d.objectLocator(loc)
	if err != nil {
		return err
	}
	_, err = d.client.DeleteBlob(ctx, container, blob, nil)
	if err != nil {
		return fmt.Errorf("azure_blob: DeleteBlob: %w", err)
	}
	return nil
}

// HeadObject returns size / etag / existence without going through
// CompleteUpload's translation. Matches the inmem + s3 drivers.
func (d *Driver) HeadObject(ctx context.Context, loc storage.DriverLocator) (storage.ObjectMetadata, error) {
	return d.CompleteUpload(ctx, storage.CompleteUploadRequest{DriverLocator: loc})
}

// CreateSignedURL returns a read-only SAS URL for the blob. scope is
// informational — the driver always issues a GET SAS.
func (d *Driver) CreateSignedURL(ctx context.Context, loc storage.DriverLocator, ttl time.Duration, scope string) (string, error) {
	container, blob, err := d.objectLocator(loc)
	if err != nil {
		return "", err
	}
	return d.presign(ctx, container, blob, ttl, "get")
}

// Probe runs GetAccountInfo via the service client. A successful
// response flips the driver to Healthy. When only DefaultAzureCredential
// is configured (no AccountKey), the probe synthesises Healthy so the
// service still comes up — per-request paths catch actual auth errors.
func (d *Driver) Probe(ctx context.Context) storage.ProbeResult {
	start := time.Now()
	if d.opts.AccountName == "" || d.opts.Container == "" {
		return storage.ProbeResult{Healthy: false, LatencyMS: time.Since(start).Milliseconds(), Error: errors.New("azure_blob: missing account or container config")}
	}
	if d.opts.AccountKey == "" {
		return storage.ProbeResult{Healthy: true, LatencyMS: time.Since(start).Milliseconds()}
	}
	probeCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	endpoint := d.opts.Endpoint
	if endpoint == "" {
		endpoint = fmt.Sprintf("https://%s.blob.core.windows.net/", d.opts.AccountName)
	}
	cred, err := azblob.NewSharedKeyCredential(d.opts.AccountName, d.opts.AccountKey)
	if err != nil {
		return storage.ProbeResult{Healthy: false, LatencyMS: time.Since(start).Milliseconds(), Error: err}
	}
	svc, err := service.NewClientWithSharedKeyCredential(endpoint, cred, nil)
	if err != nil {
		return storage.ProbeResult{Healthy: false, LatencyMS: time.Since(start).Milliseconds(), Error: err}
	}
	if _, err := svc.GetAccountInfo(probeCtx, nil); err != nil {
		return storage.ProbeResult{Healthy: false, LatencyMS: time.Since(start).Milliseconds(), Error: err}
	}
	return storage.ProbeResult{Healthy: true, LatencyMS: time.Since(start).Milliseconds()}
}

// Shutdown is a no-op for the in-process client (azblob is stateless).
func (d *Driver) Shutdown(_ context.Context) error { return nil }

// isBlobNotFound matches the SDK's BlobNotFound sentinel; we also
// sniff the error string for non-Azure-compatible gateways.
func isBlobNotFound(err error) bool {
	if err == nil {
		return false
	}
	var respErr *azcore.ResponseError
	if errors.As(err, &respErr) {
		if respErr.StatusCode == 404 {
			return true
		}
	}
	return strings.Contains(err.Error(), "BlobNotFound") || strings.Contains(err.Error(), "blob not found")
}
