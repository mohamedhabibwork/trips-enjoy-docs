package files

import (
	"encoding/json"
	"sync"
	"time"
)

// Repo is the file-metadata repository. The default implementation
// (NewInMemoryRepo) keeps state in a map+mutex so the binary boots
// without a database; the migration SQL in apps/file-service/migrations
// defines the relational shape for the production pgx-backed repo that
// lands in a follow-up PR.
type Repo interface {
	Save(file *File) error
	Get(id string) (*File, bool)
	List() []*File
	Delete(id string) error
}

// InMemoryRepo is the default Repo implementation. It is goroutine-safe
// and supports soft-delete via File.Status == StatusDeleted + a separate
// "deleted at" marker on the metadata. Migrations are out of scope.
type InMemoryRepo struct {
	mu    sync.RWMutex
	byID  map[string]*File
	scans map[string]*ScanResult // file_id → scan
}

// NewInMemoryRepo returns an empty in-memory repository.
func NewInMemoryRepo() *InMemoryRepo {
	return &InMemoryRepo{byID: map[string]*File{}, scans: map[string]*ScanResult{}}
}

// Save upserts the file into the repo.
func (r *InMemoryRepo) Save(f *File) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.byID[f.ID] = f
	return nil
}

// Get returns the file by id.
func (r *InMemoryRepo) Get(id string) (*File, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	f, ok := r.byID[id]
	return f, ok
}

// List returns every file in the repo (no pagination in the dev scaffold).
func (r *InMemoryRepo) List() []*File {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]*File, 0, len(r.byID))
	for _, f := range r.byID {
		out = append(out, f)
	}
	return out
}

// Delete hard-deletes the file from the map (soft-delete semantics live
// on the File itself: status=deleted + retention_until in the past).
func (r *InMemoryRepo) Delete(id string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	delete(r.byID, id)
	return nil
}

// SaveScan records the latest scan result for a file.
func (r *InMemoryRepo) SaveScan(s *ScanResult) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.scans[s.FileID] = s
}

// GetScan returns the latest scan result for a file.
func (r *InMemoryRepo) GetScan(fileID string) (*ScanResult, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	s, ok := r.scans[fileID]
	return s, ok
}

// NewFile builds a File with timestamps + the supplied driver assignment.
// The repository does not interpret the driver_locator — it stores the
// caller-supplied JSON verbatim.
func NewFile(id, name, mime string, size int64, sha, ownerID, ownerType, retention, driverID, driverKind string, locator map[string]any, retentionUntil time.Time) *File {
	now := time.Now().UTC()
	locatorJSON, _ := json.Marshal(locator)
	return &File{
		ID:             id,
		Name:           name,
		MimeType:       mime,
		SizeBytes:      size,
		SHA256:         sha,
		OwnerID:        ownerID,
		OwnerType:      ownerType,
		RetentionClass: retention,
		Status:         StatusPending,
		DriverID:       driverID,
		DriverKind:     driverKind,
		DriverLocator:  locatorJSON,
		RetentionUntil: retentionUntil,
		CreatedAt:      now,
		UpdatedAt:      now,
	}
}
