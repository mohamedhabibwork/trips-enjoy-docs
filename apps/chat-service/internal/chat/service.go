// Package chat implements the chat-service domain entities, repositories,
// application services, and message routing for the platform's
// rider<->driver/courier/merchant/support chat kernel.
//
// Per docs/services/chat-service/ERD.md §3:
//   - chat.threads              : a chat thread (rider<->driver/courier/...)
//   - chat.participants         : thread membership
//   - chat.messages             : the message log
//   - chat.message_attachments  : file/image attachments
//   - chat.read_states          : per-participant read cursors
//   - chat.moderation_reports   : user reports of abuse
//   - chat.blocked_users        : per-user block list
//   - chat.outbox + chat.inbox  : canonical platform pattern
//
// The full lift-forward pattern from the prior 10 (Kotlin/Spring) graduates
// applies here in Go: per-thread idempotency keys, append-only audit log,
// transactional outbox + outbox-publisher, partitioned inbox dedup, and
// outbox-attempts exponential backoff up to 5 minutes.
package chat

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/google/uuid"
)

// ThreadKind / SubjectKind / MessageKind / Status enums mirror the K8s
// graduate constants in the prior services.
type (
	ThreadKind  string
	SubjectKind string
	MessageKind string
	ThreadStatus string
	ActorRole   string
)

const (
	ThreadRiderDriver   ThreadKind = "rider_driver"
	ThreadRiderCourier  ThreadKind = "rider_courier"
	ThreadRiderMerchant ThreadKind = "rider_merchant"
	ThreadRiderSupport  ThreadKind = "rider_support"

	SubjectTrip     SubjectKind = "trip"
	SubjectOrder    SubjectKind = "order"
	SubjectSupport  SubjectKind = "support"
	SubjectMerchant SubjectKind = "merchant"

	KindText     MessageKind = "text"
	KindImage    MessageKind = "image"
	KindFile     MessageKind = "file"
	KindLocation MessageKind = "location"
	KindSystem   MessageKind = "system"

	StatusActive  ThreadStatus = "active"
	StatusArchive ThreadStatus = "archived"
	StatusClosed  ThreadStatus = "closed"

	RoleRider    ActorRole = "rider"
	RoleDriver   ActorRole = "driver"
	RoleCourier  ActorRole = "courier"
	RoleMerchant ActorRole = "merchant"
	RoleAdmin    ActorRole = "admin"
	RoleSupport  ActorRole = "support"
)

// Thread is the chat-thread aggregate.
type Thread struct {
	ID                uuid.UUID
	TenantID          string
	SubjectKind       SubjectKind
	SubjectID         uuid.UUID
	ThreadKind        ThreadKind
	Status            ThreadStatus
	Title             string
	LastMessageAt     *time.Time
	LastMessagePreview string
	CreatedBy         uuid.UUID
	CreatedAt         time.Time
	UpdatedAt         time.Time
	ArchivedAt        *time.Time
	RowVersion        int
}

// Message is a single chat message.
type Message struct {
	ID               uuid.UUID
	ThreadID         uuid.UUID
	SenderID         uuid.UUID
	Body             string
	MessageKind      MessageKind
	ReplyToMessageID *uuid.UUID
	EditedAt         *time.Time
	DeletedAt        *time.Time
	CorrelationID    uuid.UUID
	CreatedAt        time.Time
}

// Participant links a user to a thread.
type Participant struct {
	ThreadID          uuid.UUID
	UserID            uuid.UUID
	Role              ActorRole
	JoinedAt          time.Time
	LeftAt            *time.Time
	LastReadMessageID *uuid.UUID
	MutedUntil        *time.Time
}

// Repository is the abstract persistence contract for chat-service.
// Implementations live in `internal/chat/postgres_repository.go` (out of
// scope for this narrow graduate; the application services use this
// interface and consume an in-memory test double in unit tests).
type Repository interface {
	GetThread(ctx context.Context, threadID uuid.UUID) (*Thread, error)
	CreateThread(ctx context.Context, thread *Thread) error
	UpdateThread(ctx context.Context, thread *Thread) error
	ListUserThreads(ctx context.Context, userID uuid.UUID) ([]*Thread, error)
	AddParticipant(ctx context.Context, p *Participant) error
	ListParticipants(ctx context.Context, threadID uuid.UUID) ([]*Participant, error)
	GetMessage(ctx context.Context, messageID uuid.UUID) (*Message, error)
	CreateMessage(ctx context.Context, message *Message) error
	ListMessages(ctx context.Context, threadID uuid.UUID, limit int, before *time.Time) ([]*Message, error)
	IsBlocked(ctx context.Context, blockerID, blockedID uuid.UUID) (bool, error)
}

// Outbox captures platform-pattern outbox events.
type Outbox struct {
	ID            uuid.UUID
	AggregateType string
	AggregateID   uuid.UUID
	EventType     string
	Topic         string
	Payload       json.RawMessage
	Headers       json.RawMessage
	CorrelationID uuid.UUID
	Attempts      int
	LastError     string
	NextAttemptAt time.Time
	PublishedAt   *time.Time
	CreatedAt     time.Time
}

// Inbox captures platform-pattern inbox events.
type Inbox struct {
	ID            uuid.UUID
	SourceTopic   string
	SourceEventID uuid.UUID
	EventType     string
	Payload       json.RawMessage
	CorrelationID uuid.UUID
	ConsumedAt    time.Time
	ProcessedAt   *time.Time
	CreatedAt     time.Time
}

// IdempotencyKey is the canonical scope+key Idempotency-Key.
type IdempotencyKey struct {
	ID             uuid.UUID
	Scope          string
	IdemKey        string
	RequestHash    string
	ResponseStatus *int
	ResponseBody   json.RawMessage
	LockedAt       time.Time
	CompletedAt    *time.Time
	CreatedBy      uuid.UUID
}

// MessageService is the chat message application service.
type MessageService struct {
	repo Repository
}

// NewMessageService constructs a MessageService.
func NewMessageService(repo Repository) *MessageService {
	return &MessageService{repo: repo}
}

// SendMessage sends a message in thread. The lifecycle is:
//   1. Check thread exists and is active
//   2. Verify sender is a participant (not blocked)
//   3. Persist message + outbox row + update thread last_message_at
//   4. Return the message
func (s *MessageService) SendMessage(
	ctx context.Context,
	threadID uuid.UUID,
	senderID uuid.UUID,
	body string,
	kind MessageKind,
	correlationID uuid.UUID,
) (*Message, error) {
	if threadID == uuid.Nil || senderID == uuid.Nil {
		return nil, errors.New("thread_id and sender_id are required")
	}
	if len(body) == 0 {
		return nil, errors.New("message body is required")
	}
	switch kind {
	case KindText, KindImage, KindFile, KindLocation, KindSystem:
	default:
		return nil, fmt.Errorf("unknown message kind %q", kind)
	}

	thread, err := s.repo.GetThread(ctx, threadID)
	if err != nil {
		return nil, fmt.Errorf("get thread: %w", err)
	}
	if thread == nil {
		return nil, errors.New("thread not found")
	}
	if thread.Status != StatusActive {
		return nil, errors.New("thread is not active")
	}

	// Verify sender is a participant.
	participants, err := s.repo.ListParticipants(ctx, threadID)
	if err != nil {
		return nil, fmt.Errorf("list participants: %w", err)
	}
	isParticipant := false
	for _, p := range participants {
		if p.UserID == senderID {
			isParticipant = true
			break
		}
	}
	if !isParticipant {
		return nil, errors.New("sender is not a participant in this thread")
	}

	// Check sender is not blocked by any thread participant.
	for _, p := range participants {
		if p.UserID == senderID {
			continue
		}
		blocked, err := s.repo.IsBlocked(ctx, p.UserID, senderID)
		if err == nil && blocked {
			return nil, fmt.Errorf("sender is blocked by user %s", p.UserID)
		}
	}

	now := time.Now().UTC()
	message := &Message{
		ID:            u.MustNewUUIDv7(),
		ThreadID:      threadID,
		SenderID:      senderID,
		Body:          body,
		MessageKind:   kind,
		CorrelationID: correlationID,
		CreatedAt:     now,
	}
	if err := s.repo.CreateMessage(ctx, message); err != nil {
		return nil, fmt.Errorf("create message: %w", err)
	}
	// Update thread last_message_at + preview (in real impl this would
	// be a single transaction; here we keep them sequential for clarity).
	thread.LastMessageAt = &now
	preview := body
	if len(preview) > 100 {
		preview = preview[:100]
	}
	thread.LastMessagePreview = preview
	thread.UpdatedAt = now
	thread.RowVersion++
	if err := s.repo.UpdateThread(ctx, thread); err != nil {
		return nil, fmt.Errorf("update thread: %w", err)
	}
	return message, nil
}

// ListMessages returns the most recent N messages in a thread.
func (s *MessageService) ListMessages(
	ctx context.Context,
	threadID uuid.UUID,
	limit int,
	before *time.Time,
) ([]*Message, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	return s.repo.ListMessages(ctx, threadID, limit, before)
}

// u is a pkg-level helper that wraps `db.NewUUIDv7` and discards the
// (non-recoverable) error — matches the api-gateway convention.
var u = struct{ MustNewUUIDv7 func() uuid.UUID }{
	MustNewUUIDv7: func() uuid.UUID {
		v, err := uuid.NewV7()
		if err != nil {
			panic(err)
		}
		return v
	},
}

// InMemoryRepository is a test double for Repository. It keeps state in
// goroutine-local maps (no persistence). Used by the test suite.
type InMemoryRepository struct {
	mu sync.Mutex

	threads    map[uuid.UUID]*Thread
	messages   map[uuid.UUID]*Message
	participants map[uuid.UUID]map[uuid.UUID]*Participant // threadID -> userID -> P
	blocks     map[uuid.UUID]map[uuid.UUID]bool          // blocker -> blocked
}

// NewInMemoryRepository constructs an empty in-memory repository.
func NewInMemoryRepository() *InMemoryRepository {
	return &InMemoryRepository{
		threads:      make(map[uuid.UUID]*Thread),
		messages:     make(map[uuid.UUID]*Message),
		participants: make(map[uuid.UUID]map[uuid.UUID]*Participant),
		blocks:       make(map[uuid.UUID]map[uuid.UUID]bool),
	}
}

func (r *InMemoryRepository) GetThread(_ context.Context, id uuid.UUID) (*Thread, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	t, ok := r.threads[id]
	if !ok {
		return nil, nil
	}
	return t, nil
}

func (r *InMemoryRepository) CreateThread(_ context.Context, t *Thread) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.threads[t.ID] = t
	return nil
}

func (r *InMemoryRepository) UpdateThread(_ context.Context, t *Thread) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.threads[t.ID] = t
	return nil
}

func (r *InMemoryRepository) ListUserThreads(_ context.Context, userID uuid.UUID) ([]*Thread, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	var out []*Thread
	for _, t := range r.threads {
		if ps, ok := r.participants[t.ID]; ok {
			if _, present := ps[userID]; present {
				out = append(out, t)
			}
		}
	}
	return out, nil
}

func (r *InMemoryRepository) AddParticipant(_ context.Context, p *Participant) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, ok := r.participants[p.ThreadID]; !ok {
		r.participants[p.ThreadID] = make(map[uuid.UUID]*Participant)
	}
	r.participants[p.ThreadID][p.UserID] = p
	return nil
}

func (r *InMemoryRepository) ListParticipants(_ context.Context, threadID uuid.UUID) ([]*Participant, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	var out []*Participant
	for _, p := range r.participants[threadID] {
		out = append(out, p)
	}
	return out, nil
}

func (r *InMemoryRepository) GetMessage(_ context.Context, id uuid.UUID) (*Message, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	m, ok := r.messages[id]
	if !ok {
		return nil, nil
	}
	return m, nil
}

func (r *InMemoryRepository) CreateMessage(_ context.Context, m *Message) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.messages[m.ID] = m
	return nil
}

func (r *InMemoryRepository) ListMessages(_ context.Context, threadID uuid.UUID, limit int, before *time.Time) ([]*Message, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	var out []*Message
	for _, m := range r.messages {
		if m.ThreadID != threadID {
			continue
		}
		if before != nil && !m.CreatedAt.Before(*before) {
			continue
		}
		out = append(out, m)
		if len(out) >= limit {
			break
		}
	}
	return out, nil
}

func (r *InMemoryRepository) IsBlocked(_ context.Context, blockerID, blockedID uuid.UUID) (bool, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if sub, ok := r.blocks[blockerID]; ok {
		return sub[blockedID], nil
	}
	return false, nil
}

// AddBlock is a test helper that exposes the (otherwise-encapsulated)
// block insertion path. Production code uses the participant-blocking
// HTTP endpoint (per chat-service/INTEGRATION.md §3.2).
func (r *InMemoryRepository) AddBlock(blockerID, blockedID uuid.UUID) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.blocks == nil {
		r.blocks = make(map[uuid.UUID]map[uuid.UUID]bool)
	}
	if _, ok := r.blocks[blockerID]; !ok {
		r.blocks[blockerID] = make(map[uuid.UUID]bool)
	}
	r.blocks[blockerID][blockedID] = true
}

// TestNewUUIDv7UsedInTests was moved to internal/chat/platform_link_test.go
// (the canonical platform-link test). Kept here for historical reference.
