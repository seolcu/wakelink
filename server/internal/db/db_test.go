package db

import (
	"context"
	"path/filepath"
	"testing"
	"time"
)

func newTestDB(t *testing.T) *DB {
	t.Helper()
	d, err := Open(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatalf("open db: %v", err)
	}
	if err := d.Migrate(); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	return d
}

func TestCreateUserAndLookupByAPIKeyHash(t *testing.T) {
	d := newTestDB(t)
	ctx := context.Background()

	user, err := d.CreateUser(ctx, "Alice", "somehash")
	if err != nil {
		t.Fatalf("create user: %v", err)
	}

	found, err := d.GetUserByAPIKeyHash(ctx, "somehash")
	if err != nil {
		t.Fatalf("lookup by hash: %v", err)
	}
	if found.ID != user.ID || found.DisplayName != "Alice" {
		t.Fatalf("unexpected user returned: %+v", found)
	}

	if _, err := d.GetUserByAPIKeyHash(ctx, "nonexistent"); err != ErrNotFound {
		t.Fatalf("expected ErrNotFound for unknown hash, got %v", err)
	}
}

func TestCreatePairingCanonicalOrderingAndUniqueConstraint(t *testing.T) {
	d := newTestDB(t)
	ctx := context.Background()

	alice, _ := d.CreateUser(ctx, "Alice", "alice-hash")
	bob, _ := d.CreateUser(ctx, "Bob", "bob-hash")

	// Pass the higher id first -- CreatePairing must still store them in
	// canonical (lower id first) order.
	pairing, err := d.CreatePairing(ctx, bob.ID, alice.ID)
	if err != nil {
		t.Fatalf("create pairing: %v", err)
	}
	if pairing.UserAID != alice.ID || pairing.UserBID != bob.ID {
		t.Fatalf("expected canonical ordering (alice=%d, bob=%d), got a=%d b=%d", alice.ID, bob.ID, pairing.UserAID, pairing.UserBID)
	}

	if _, err := d.CreatePairing(ctx, alice.ID, bob.ID); err == nil {
		t.Fatalf("expected duplicate pairing to violate the UNIQUE constraint")
	}
}

func TestSetPermissionRejectsNonMember(t *testing.T) {
	d := newTestDB(t)
	ctx := context.Background()

	alice, _ := d.CreateUser(ctx, "Alice", "alice-hash")
	bob, _ := d.CreateUser(ctx, "Bob", "bob-hash")
	carol, _ := d.CreateUser(ctx, "Carol", "carol-hash")

	pairing, err := d.CreatePairing(ctx, alice.ID, bob.ID)
	if err != nil {
		t.Fatalf("create pairing: %v", err)
	}

	if err := d.SetPermission(ctx, pairing.ID, carol.ID, false); err != ErrNotFound {
		t.Fatalf("expected ErrNotFound for non-member SetPermission, got %v", err)
	}
}

func TestAckTriggerDoubleAckReturnsErrNotFound(t *testing.T) {
	d := newTestDB(t)
	ctx := context.Background()

	alice, _ := d.CreateUser(ctx, "Alice", "alice-hash")
	bob, _ := d.CreateUser(ctx, "Bob", "bob-hash")
	pairing, _ := d.CreatePairing(ctx, alice.ID, bob.ID)

	trig, err := d.CreateTrigger(ctx, pairing.ID, bob.ID, alice.ID)
	if err != nil {
		t.Fatalf("create trigger: %v", err)
	}

	if err := d.AckTrigger(ctx, trig.ID, alice.ID); err != nil {
		t.Fatalf("first ack: %v", err)
	}
	if err := d.AckTrigger(ctx, trig.ID, alice.ID); err != ErrNotFound {
		t.Fatalf("expected ErrNotFound on double ack, got %v", err)
	}
}

func TestInviteExpiryAndMarkUsed(t *testing.T) {
	d := newTestDB(t)
	ctx := context.Background()

	alice, _ := d.CreateUser(ctx, "Alice", "alice-hash")

	inv, err := d.CreateInvite(ctx, "ABCD1234", alice.ID, 10*time.Minute)
	if err != nil {
		t.Fatalf("create invite: %v", err)
	}
	if inv.Used {
		t.Fatalf("freshly created invite should not be marked used")
	}

	if err := d.MarkInviteUsed(ctx, inv.Code); err != nil {
		t.Fatalf("mark used: %v", err)
	}
	found, err := d.GetInvite(ctx, inv.Code)
	if err != nil {
		t.Fatalf("get invite: %v", err)
	}
	if !found.Used {
		t.Fatalf("expected invite to be marked used")
	}

	expired, err := d.CreateInvite(ctx, "EXPIRED1", alice.ID, -1*time.Second)
	if err != nil {
		t.Fatalf("create expired invite: %v", err)
	}
	if expired.ExpiresAt >= time.Now().Unix() {
		t.Fatalf("expected expires_at to already be in the past")
	}
}
