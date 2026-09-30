package ratelimit

import (
	"context"
	"path/filepath"
	"testing"
	"time"

	"wakelink/internal/db"
)

func newTestDB(t *testing.T) *db.DB {
	t.Helper()
	d, err := db.Open(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatalf("open db: %v", err)
	}
	if err := d.Migrate(); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	return d
}

func setUpPairing(t *testing.T, database *db.DB) (pairingID, fromUserID int64) {
	t.Helper()
	ctx := context.Background()
	alice, err := database.CreateUser(ctx, "Alice", "alice-hash")
	if err != nil {
		t.Fatalf("create alice: %v", err)
	}
	bob, err := database.CreateUser(ctx, "Bob", "bob-hash")
	if err != nil {
		t.Fatalf("create bob: %v", err)
	}
	pairing, err := database.CreatePairing(ctx, alice.ID, bob.ID)
	if err != nil {
		t.Fatalf("create pairing: %v", err)
	}
	return pairing.ID, bob.ID
}

func TestCheckBlocksWithinCooldown(t *testing.T) {
	database := newTestDB(t)
	ctx := context.Background()
	pairingID, fromUserID := setUpPairing(t, database)

	if _, err := database.CreateTrigger(ctx, pairingID, fromUserID, fromUserID); err != nil {
		t.Fatalf("create trigger: %v", err)
	}

	l := &Limiter{Cooldown: time.Hour, DailyCap: 20}
	if err := l.Check(ctx, database, pairingID, fromUserID); err != ErrCooldown {
		t.Fatalf("expected ErrCooldown, got %v", err)
	}
}

func TestCheckAllowsAfterCooldownWindow(t *testing.T) {
	database := newTestDB(t)
	ctx := context.Background()
	pairingID, fromUserID := setUpPairing(t, database)

	trig, err := database.CreateTrigger(ctx, pairingID, fromUserID, fromUserID)
	if err != nil {
		t.Fatalf("create trigger: %v", err)
	}

	longAgo := time.Now().Add(-time.Hour).Unix()
	if _, err := database.ExecContext(ctx, `UPDATE triggers SET created_at = ? WHERE id = ?`, longAgo, trig.ID); err != nil {
		t.Fatalf("backdate trigger: %v", err)
	}

	l := &Limiter{Cooldown: time.Minute, DailyCap: 20}
	if err := l.Check(ctx, database, pairingID, fromUserID); err != nil {
		t.Fatalf("expected no error once cooldown has elapsed, got %v", err)
	}
}

func TestCheckBlocksAtDailyCap(t *testing.T) {
	database := newTestDB(t)
	ctx := context.Background()
	pairingID, fromUserID := setUpPairing(t, database)

	l := &Limiter{Cooldown: 0, DailyCap: 2}

	for i := 0; i < 2; i++ {
		if err := l.Check(ctx, database, pairingID, fromUserID); err != nil {
			t.Fatalf("check %d: expected no error, got %v", i, err)
		}
		if _, err := database.CreateTrigger(ctx, pairingID, fromUserID, fromUserID); err != nil {
			t.Fatalf("create trigger %d: %v", i, err)
		}
	}

	if err := l.Check(ctx, database, pairingID, fromUserID); err != ErrDailyCap {
		t.Fatalf("expected ErrDailyCap after reaching the cap, got %v", err)
	}
}
