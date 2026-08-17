// Package local_fs is the POSIX-filesystem StorageDriver. It is the
// driver used in dev / CI / edge deployments (per docs/services/file-service/
// README.md §1). Bytes live under FILE_SERVICE_LOCAL_FS_ROOT.
package local_fs

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/trips-enjoy/platform/file-service/internal/storage"
)

// Driver is the local-filesystem StorageDriver.
type Driver struct {
	root    aferoFS
	mu      sync.RWMutex
	signKey []byte
}

// aferoFS is the small subset of afero.File we need; the full afero.Fs
// would require the spf13/afero dependency here even though our public
// surface never uses it. Tests can swap a custom implementation in via
// afero, but production uses os-backed paths.
type aferoFS interface {
	Open(name string) (aferoFile, error)
	Create(name string) (aferoFile, error)
	Stat(name string) (os.FileInfo, error)
	Remove(name string) error
	MkdirAll(path string) error
}

type aferoFile interface {
	io.ReadWriteCloser
	Stat() (os.FileInfo, error)
}

// osFS is the real filesystem-backed implementation.
type osFS struct{}

// New returns a local_fs driver rooted at root. The directory is created
// (mkdir -p) on first use.
func New(root string, signKey []byte) (*Driver, error) {
	if root == "" {
		return nil, errors.New("local_fs root is required")
	}
	if err := os.MkdirAll(root, 0o755); err != nil {
		return nil, fmt.Errorf("create local_fs root: %w", err)
	}
	if len(signKey) == 0 {
		return nil, errors.New("local_fs signKey is required")
	}
	return &Driver{root: &osFSAdapter{root: root}, signKey: signKey}, nil
}

// osFSAdapter wraps os.RootFS semantics with our minimal interface so the
// public surface stays small while tests can drop in afero.
type osFSAdapter struct{ root string }

func (a *osFSAdapter) path(name string) string {
	return filepath.Join(a.root, filepath.FromSlash(name))
}

func (a *osFSAdapter) Open(name string) (aferoFile, error) {
	f, err := os.Open(a.path(name))
	if err != nil {
		return nil, err
	}
	return &osFile{f: f}, nil
}

func (a *osFSAdapter) Create(name string) (aferoFile, error) {
	f, err := os.Create(a.path(name))
	if err != nil {
		return nil, err
	}
	return &osFile{f: f}, nil
}

func (a *osFSAdapter) Stat(name string) (os.FileInfo, error) { return os.Stat(a.path(name)) }

func (a *osFSAdapter) Remove(name string) error { return os.Remove(a.path(name)) }

func (a *osFSAdapter) MkdirAll(path string) error { return os.MkdirAll(a.path(path), 0o755) }

type osFile struct{ f *os.File }

func (o *osFile) Read(p []byte) (int, error)  { return o.f.Read(p) }
func (o *osFile) Write(p []byte) (int, error) { return o.f.Write(p) }
func (o *osFile) Close() error                { return o.f.Close() }
func (o *osFile) Stat() (os.FileInfo, error)  { return o.f.Stat() }

// objectKey resolves a DriverLocator to a stable file path under root.
// local_fs stores the relative path on the locator under "path".
func objectKey(loc storage.DriverLocator) string {
	if loc == nil {
		return ""
	}
	if path, ok := loc["path"].(string); ok {
		return path
	}
	if key, ok := loc["key"].(string); ok {
		return key
	}
	return ""
}

// signTicket builds the short-lived HMAC ticket used for direct-to-driver
// upload URLs and signed read URLs. The signed payload is
// path|exp_unix_ms so a forged or stale ticket is rejected.
func (d *Driver) signTicket(path string, exp time.Time) string {
	mac := hmac.New(sha256.New, d.signKey)
	mac.Write([]byte(path))
	mac.Write([]byte{0})
	mac.Write([]byte(fmt.Sprintf("%d", exp.UnixMilli())))
	return hex.EncodeToString(mac.Sum(nil))
}

// verifyTicket is used by the reverse proxy (/local-fs-proxy/upload|stream)
// — exported for tests, not for handlers.
func (d *Driver) verifyTicket(path string, exp time.Time, ticket string) bool {
	return hmac.Equal([]byte(d.signTicket(path, exp)), []byte(ticket))
}

func (d *Driver) InitiateUpload(_ context.Context, req storage.InitiateUploadRequest) (storage.InitiateUploadResponse, error) {
	path := req.FileID
	exp := time.Now().Add(15 * time.Minute)
	ticket := d.signTicket(path, exp)
	return storage.InitiateUploadResponse{
		UploadURL:     fmt.Sprintf("/local-fs-proxy/upload?path=%s&exp=%d&ticket=%s", path, exp.UnixMilli(), ticket),
		DriverLocator: storage.DriverLocator{"path": path, "kind": "local_fs"},
		ExpiresAt:     exp,
	}, nil
}

func (d *Driver) CompleteUpload(_ context.Context, req storage.CompleteUploadRequest) (storage.ObjectMetadata, error) {
	path := objectKey(req.DriverLocator)
	info, err := d.root.Stat(path)
	if err != nil {
		if os.IsNotExist(err) {
			return storage.ObjectMetadata{Exists: false}, nil
		}
		return storage.ObjectMetadata{}, err
	}
	// Compute SHA-256 by streaming the file once.
	f, err := d.root.Open(path)
	if err != nil {
		return storage.ObjectMetadata{}, err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return storage.ObjectMetadata{}, err
	}
	return storage.ObjectMetadata{
		SizeBytes: info.Size(),
		SHA256:    hex.EncodeToString(h.Sum(nil)),
		ETag:      info.Name(),
		Exists:    true,
	}, nil
}

func (d *Driver) GetObject(_ context.Context, loc storage.DriverLocator) (io.ReadCloser, error) {
	return d.root.Open(objectKey(loc))
}

func (d *Driver) PutObject(_ context.Context, loc storage.DriverLocator, src io.Reader, _ string) error {
	f, err := d.root.Create(objectKey(loc))
	if err != nil {
		return err
	}
	defer f.Close()
	if _, err := io.Copy(f, src); err != nil {
		return err
	}
	return nil
}

func (d *Driver) DeleteObject(_ context.Context, loc storage.DriverLocator) error {
	return d.root.Remove(objectKey(loc))
}

func (d *Driver) HeadObject(ctx context.Context, loc storage.DriverLocator) (storage.ObjectMetadata, error) {
	return d.CompleteUpload(ctx, storage.CompleteUploadRequest{DriverLocator: loc})
}

func (d *Driver) CreateSignedURL(_ context.Context, loc storage.DriverLocator, ttl time.Duration, _ string) (string, error) {
	path := objectKey(loc)
	exp := time.Now().Add(ttl)
	return fmt.Sprintf("/local-fs-proxy/stream?path=%s&exp=%d&ticket=%s", path, exp.UnixMilli(), d.signTicket(path, exp)), nil
}

func (d *Driver) Probe(_ context.Context) storage.ProbeResult {
	info, err := d.root.Stat(".")
	if err != nil {
		return storage.ProbeResult{Healthy: false, Error: err}
	}
	return storage.ProbeResult{Healthy: info.IsDir(), LatencyMS: 0}
}

func (d *Driver) Shutdown(_ context.Context) error { return nil }
