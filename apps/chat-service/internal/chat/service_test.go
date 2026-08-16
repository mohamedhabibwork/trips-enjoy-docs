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
