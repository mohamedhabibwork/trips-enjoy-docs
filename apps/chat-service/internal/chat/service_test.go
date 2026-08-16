package chat_test

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/trips-enjoy/platform/chat-service/internal/chat"
)

func newTestSetup() (*chat.MessageService, *chat.InMemoryRepository, uuid.UUID, uuid.UUID) {
	repo := chat.NewInMemoryRepository()
	svc := chat.NewMessageService(repo)
	rider := uuid.New()
	driver := uuid.New()
	thread := &chat.Thread{
		ID:          uuid.New(),
		TenantID:    "global",
		SubjectKind: chat.SubjectTrip,
		SubjectID:   uuid.New(),
		ThreadKind:  chat.ThreadRiderDriver,
		Status:     chat.StatusActive,
		CreatedBy:  rider,
		CreatedAt:  time.Now().UTC(),
		UpdatedAt:  time.Now().UTC(),
	}
	_ = repo.CreateThread(context.Background(), thread)
	_ = repo.AddParticipant(context.Background(), &chat.Participant{
		ThreadID: thread.ID, UserID: rider, Role: chat.RoleRider,
		JoinedAt: time.Now().UTC(),
	})
	_ = repo.AddParticipant(context.Background(), &chat.Participant{
		ThreadID: thread.ID, UserID: driver, Role: chat.RoleDriver,
		JoinedAt: time.Now().UTC(),
	})
	return svc, repo, thread.ID, rider
}

func TestSendMessageHappyPath(t *testing.T) {
	svc, _, threadID, rider := newTestSetup()
	correlationID := uuid.New()
	msg, err := svc.SendMessage(context.Background(), threadID, rider, "Hello!", chat.KindText, correlationID)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if msg.Body != "Hello!" {
		t.Errorf("body = %q", msg.Body)
	}
	if msg.MessageKind != chat.KindText {
		t.Errorf("kind = %q", msg.MessageKind)
	}
	if msg.SenderID != rider {
		t.Errorf("sender = %s", msg.SenderID)
	}
}

func TestSendMessageRejectsNonParticipant(t *testing.T) {
	svc, _, threadID, _ := newTestSetup()
	stranger := uuid.New()
	_, err := svc.SendMessage(context.Background(), threadID, stranger, "Hi", chat.KindText, uuid.New())
	if err == nil {
		t.Fatal("expected error for non-participant sender")
	}
}

func TestSendMessageRejectsEmptyBody(t *testing.T) {
	svc, _, threadID, rider := newTestSetup()
	_, err := svc.SendMessage(context.Background(), threadID, rider, "", chat.KindText, uuid.New())
	if err == nil {
		t.Fatal("expected error for empty body")
	}
}

func TestSendMessageRejectsUnknownMessageKind(t *testing.T) {
	svc, _, threadID, rider := newTestSetup()
	_, err := svc.SendMessage(context.Background(), threadID, rider, "x", chat.MessageKind("bogus"), uuid.New())
	if err == nil {
		t.Fatal("expected error for unknown message kind")
	}
}

func TestSendMessageRejectsInactiveThread(t *testing.T) {
	svc, repo, threadID, rider := newTestSetup()
	thread, _ := repo.GetThread(context.Background(), threadID)
	thread.Status = chat.StatusArchive
	_ = repo.UpdateThread(context.Background(), thread)
	_, err := svc.SendMessage(context.Background(), threadID, rider, "x", chat.KindText, uuid.New())
	if err == nil {
		t.Fatal("expected error for inactive thread")
	}
}

func TestSendMessageRejectsBlockedSender(t *testing.T) {
	svc, repo, threadID, rider := newTestSetup()
	driverList, _ := repo.ListParticipants(context.Background(), threadID)
	for _, p := range driverList {
		if p.Role == chat.RoleDriver {
			repo.AddBlock(p.UserID, rider)
		}
	}
	_, err := svc.SendMessage(context.Background(), threadID, rider, "x", chat.KindText, uuid.New())
	if err == nil {
		t.Fatal("expected error for blocked sender")
	}
}

func TestSendMessageRejectsUnknownThread(t *testing.T) {
	svc, _, _, rider := newTestSetup()
	_, err := svc.SendMessage(context.Background(), uuid.New(), rider, "x", chat.KindText, uuid.New())
	if err == nil {
		t.Fatal("expected error for unknown thread")
	}
}

func TestListMessagesHonorsLimit(t *testing.T) {
	svc, _, threadID, rider := newTestSetup()
	for i := 0; i < 5; i++ {
		_, _ = svc.SendMessage(context.Background(), threadID, rider, "msg", chat.KindText, uuid.New())
	}
	msgs, err := svc.ListMessages(context.Background(), threadID, 3, nil)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(msgs) != 3 {
		t.Errorf("len = %d, want 3", len(msgs))
	}
}

func TestSendMessageIncrementThreadVersion(t *testing.T) {
	svc, repo, threadID, rider := newTestSetup()
	_, _ = svc.SendMessage(context.Background(), threadID, rider, "x", chat.KindText, uuid.New())
	after, _ := repo.GetThread(context.Background(), threadID)
	if after.RowVersion < 1 {
		t.Errorf("RowVersion = %d, want >= 1", after.RowVersion)
	}
}

func TestAddAttachmentHappyPath(t *testing.T) {
	repo := chat.NewInMemoryRepository()
	msgID := uuid.New()
	a := &chat.MessageAttachment{
		ID:          uuid.New(),
		MessageID:   msgID,
		FileID:      uuid.New(),
		ContentType: "image/jpeg",
		SizeBytes:   1024,
		CreatedAt:   time.Now().UTC(),
	}
	if err := repo.AddAttachment(context.Background(), a); err != nil {
		t.Fatalf("add attachment: %v", err)
	}
	as, err := repo.ListAttachments(context.Background(), msgID)
	if err != nil {
		t.Fatalf("list attachments: %v", err)
	}
	if len(as) != 1 {
		t.Errorf("len = %d, want 1", len(as))
	}
	if as[0].ID != a.ID {
		t.Errorf("id mismatch")
	}
}

func TestAddAttachmentRejectsNegativeSizeBytes(t *testing.T) {
	repo := chat.NewInMemoryRepository()
	a := &chat.MessageAttachment{
		ID:          uuid.New(),
		MessageID:   uuid.New(),
		FileID:      uuid.New(),
		ContentType: "image/jpeg",
		SizeBytes:   -1,
		CreatedAt:   time.Now().UTC(),
	}
	if err := repo.AddAttachment(context.Background(), a); err == nil {
		t.Fatal("expected error for negative size_bytes")
	}
}

func TestAddAttachmentRejectsEmptyContentType(t *testing.T) {
	repo := chat.NewInMemoryRepository()
	a := &chat.MessageAttachment{
		ID:          uuid.New(),
		MessageID:   uuid.New(),
		FileID:      uuid.New(),
		ContentType: "",
		SizeBytes:   0,
		CreatedAt:   time.Now().UTC(),
	}
	if err := repo.AddAttachment(context.Background(), a); err == nil {
		t.Fatal("expected error for empty content_type")
	}
}

func TestAddAttachmentMultipleAttachmentsPerMessage(t *testing.T) {
	repo := chat.NewInMemoryRepository()
	msgID := uuid.New()
	for i := 0; i < 3; i++ {
		a := &chat.MessageAttachment{
			ID:          uuid.New(),
			MessageID:   msgID,
			FileID:      uuid.New(),
			ContentType: "image/png",
			SizeBytes:   int64((i + 1) * 100),
			CreatedAt:   time.Now().UTC(),
		}
		if err := repo.AddAttachment(context.Background(), a); err != nil {
			t.Fatalf("add %d: %v", i, err)
		}
	}
	as, _ := repo.ListAttachments(context.Background(), msgID)
	if len(as) != 3 {
		t.Errorf("len = %d, want 3", len(as))
	}
}

func TestAddAttachmentListForUnknownMessageReturnsEmpty(t *testing.T) {
	repo := chat.NewInMemoryRepository()
	as, err := repo.ListAttachments(context.Background(), uuid.New())
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(as) != 0 {
		t.Errorf("len = %d, want 0", len(as))
	}
}

// ReadState tests below.

// helper: seed a thread + 3 messages + return the message IDs in order.
func seedThreadWithMessages(t *testing.T) (*chat.InMemoryRepository, uuid.UUID, uuid.UUID, [3]uuid.UUID) {
	t.Helper()
	svc, repo, threadID, rider := newTestSetup()
	driver := uuid.New()
	_ = repo.AddParticipant(context.Background(), &chat.Participant{
		ThreadID: threadID, UserID: driver, Role: chat.RoleDriver,
		JoinedAt: time.Now().UTC(),
	})
	ids := [3]uuid.UUID{}
	base := time.Now().UTC().Add(-time.Hour)
	for i := 0; i < 3; i++ {
		m, err := svc.SendMessage(context.Background(), threadID, rider, "msg", chat.KindText, uuid.New())
		if err != nil {
			t.Fatalf("seed %d: %v", i, err)
		}
		// Force createdAt ordering — SendMessage uses time.Now().
		m.CreatedAt = base.Add(time.Duration(i) * time.Minute)
		_ = repo.CreateMessage(context.Background(), m)
		ids[i] = m.ID
	}
	return repo, threadID, driver, ids
}

func TestMarkAsReadHappyPath(t *testing.T) {
	repo, threadID, driver, ids := seedThreadWithMessages(t)
	now := time.Now().UTC()
	rs, err := repo.MarkAsRead(context.Background(), threadID, driver, ids[2], now)
	if err != nil {
		t.Fatalf("mark as read: %v", err)
	}
	if rs.LastReadMessageID == nil || *rs.LastReadMessageID != ids[2] {
		t.Errorf("cursor = %v, want %s", rs.LastReadMessageID, ids[2])
	}
	if rs.LastReadAt == nil || !rs.LastReadAt.Equal(now) {
		t.Errorf("last_read_at = %v, want %s", rs.LastReadAt, now)
	}
	got, err := repo.GetReadState(context.Background(), threadID, driver)
	if err != nil {
		t.Fatalf("get read state: %v", err)
	}
	if got == nil || got.LastReadMessageID == nil || *got.LastReadMessageID != ids[2] {
		t.Errorf("GetReadState returned %+v", got)
	}
}

func TestMarkAsReadUpdatesCursorMonotonically(t *testing.T) {
	repo, threadID, driver, ids := seedThreadWithMessages(t)
	// Advance to ids[1] first.
	_, err := repo.MarkAsRead(context.Background(), threadID, driver, ids[1], time.Now().UTC())
	if err != nil {
		t.Fatalf("mark ids[1]: %v", err)
	}
	// Try to move back to ids[0] — must be a no-op (cursor stays at ids[1]).
	rs, err := repo.MarkAsRead(context.Background(), threadID, driver, ids[0], time.Now().UTC().Add(time.Second))
	if err != nil {
		t.Fatalf("mark ids[0]: %v", err)
	}
	if rs.LastReadMessageID == nil || *rs.LastReadMessageID != ids[1] {
		t.Errorf("cursor regressed to %v, want %s", rs.LastReadMessageID, ids[1])
	}
	// Advance to ids[2] — must move forward.
	rs, err = repo.MarkAsRead(context.Background(), threadID, driver, ids[2], time.Now().UTC().Add(2*time.Second))
	if err != nil {
		t.Fatalf("mark ids[2]: %v", err)
	}
	if rs.LastReadMessageID == nil || *rs.LastReadMessageID != ids[2] {
		t.Errorf("cursor = %v, want %s", rs.LastReadMessageID, ids[2])
	}
}

func TestGetReadStateReturnsNilIfNotFound(t *testing.T) {
	repo, threadID, driver, _ := seedThreadWithMessages(t)
	rs, err := repo.GetReadState(context.Background(), threadID, driver)
	if err != nil {
		t.Fatalf("get read state: %v", err)
	}
	if rs != nil {
		t.Errorf("expected nil read state for untouched user, got %+v", rs)
	}
}

func TestMarkAsReadRejectsUnknownMessage(t *testing.T) {
	repo, threadID, driver, _ := seedThreadWithMessages(t)
	_, err := repo.MarkAsRead(context.Background(), threadID, driver, uuid.New(), time.Now().UTC())
	if err != chat.ErrUnknownMessage {
		t.Errorf("err = %v, want ErrUnknownMessage", err)
	}
}

func TestMarkAsReadIdempotency(t *testing.T) {
	repo, threadID, driver, ids := seedThreadWithMessages(t)
	now := time.Now().UTC()
	rs1, err := repo.MarkAsRead(context.Background(), threadID, driver, ids[2], now)
	if err != nil {
		t.Fatalf("mark 1: %v", err)
	}
	rs2, err := repo.MarkAsRead(context.Background(), threadID, driver, ids[2], now)
	if err != nil {
		t.Fatalf("mark 2: %v", err)
	}
	if rs1.LastReadMessageID == nil || rs2.LastReadMessageID == nil ||
		*rs1.LastReadMessageID != *rs2.LastReadMessageID {
		t.Errorf("idempotent read state mismatch: %+v vs %+v", rs1, rs2)
	}
}
