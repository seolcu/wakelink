package db

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

// CreatePairing creates a pairing between two users, storing them in
// canonical order (lower id first) so UNIQUE(user_a_id, user_b_id) catches
// duplicates regardless of who initiated the invite.
func (d *DB) CreatePairing(ctx context.Context, userID1, userID2 int64) (*Pairing, error) {
	a, b := userID1, userID2
	if a > b {
		a, b = b, a
	}
	now := time.Now().Unix()
	res, err := d.ExecContext(ctx,
		`INSERT INTO pairings (user_a_id, user_b_id, a_can_wake_b, b_can_wake_a, created_at) VALUES (?, ?, 1, 1, ?)`,
		a, b, now,
	)
	if err != nil {
		return nil, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return nil, err
	}
	return &Pairing{ID: id, UserAID: a, UserBID: b, ACanWakeB: true, BCanWakeA: true, CreatedAt: now}, nil
}

func (d *DB) GetPairing(ctx context.Context, id int64) (*Pairing, error) {
	row := d.QueryRowContext(ctx,
		`SELECT id, user_a_id, user_b_id, a_can_wake_b, b_can_wake_a, created_at, a_accepts_from_b, b_accepts_from_a FROM pairings WHERE id = ?`,
		id,
	)
	return scanPairing(row)
}

func (d *DB) GetPairingBetween(ctx context.Context, userID1, userID2 int64) (*Pairing, error) {
	a, b := userID1, userID2
	if a > b {
		a, b = b, a
	}
	row := d.QueryRowContext(ctx,
		`SELECT id, user_a_id, user_b_id, a_can_wake_b, b_can_wake_a, created_at, a_accepts_from_b, b_accepts_from_a FROM pairings WHERE user_a_id = ? AND user_b_id = ?`,
		a, b,
	)
	return scanPairing(row)
}

func scanPairing(row *sql.Row) (*Pairing, error) {
	var p Pairing
	var aCanWakeB, bCanWakeA, aAccepts, bAccepts int
	if err := row.Scan(&p.ID, &p.UserAID, &p.UserBID, &aCanWakeB, &bCanWakeA, &p.CreatedAt, &aAccepts, &bAccepts); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	p.ACanWakeB = aCanWakeB != 0
	p.BCanWakeA = bCanWakeA != 0
	p.AAcceptsFromB = aAccepts != 0
	p.BAcceptsFromA = bAccepts != 0
	return &p, nil
}

func (d *DB) ListPairingsForUser(ctx context.Context, userID int64, limit int) ([]Pairing, error) {
	rows, err := d.QueryContext(ctx,
		`SELECT id, user_a_id, user_b_id, a_can_wake_b, b_can_wake_a, created_at, a_accepts_from_b, b_accepts_from_a FROM pairings
		 WHERE user_a_id = ? OR user_b_id = ? ORDER BY created_at DESC LIMIT ?`,
		userID, userID, limit,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []Pairing
	for rows.Next() {
		var p Pairing
		var aCanWakeB, bCanWakeA, aAccepts, bAccepts int
		if err := rows.Scan(&p.ID, &p.UserAID, &p.UserBID, &aCanWakeB, &bCanWakeA, &p.CreatedAt, &aAccepts, &bAccepts); err != nil {
			return nil, err
		}
		p.ACanWakeB = aCanWakeB != 0
		p.BCanWakeA = bCanWakeA != 0
		p.AAcceptsFromB = aAccepts != 0
		p.BAcceptsFromA = bAccepts != 0
		out = append(out, p)
	}
	return out, rows.Err()
}

// SetPermission updates the wake-permission flag for the direction the
// requesting user controls -- i.e. "can requestingUserID wake the other
// side of this pairing". requestingUserID must be a member of the pairing.
func (d *DB) SetPermission(ctx context.Context, pairingID, requestingUserID int64, allowed bool) error {
	p, err := d.GetPairing(ctx, pairingID)
	if err != nil {
		return err
	}
	val := 0
	if allowed {
		val = 1
	}
	switch requestingUserID {
	case p.UserAID:
		_, err = d.ExecContext(ctx, `UPDATE pairings SET a_can_wake_b = ? WHERE id = ?`, val, pairingID)
	case p.UserBID:
		_, err = d.ExecContext(ctx, `UPDATE pairings SET b_can_wake_a = ? WHERE id = ?`, val, pairingID)
	default:
		return ErrNotFound
	}
	return err
}

// SetAcceptsFrom updates whether requestingUserID accepts wakes from the
// other side of this pairing -- the one switch the *receiving* member
// controls. requestingUserID must be a member of the pairing.
func (d *DB) SetAcceptsFrom(ctx context.Context, pairingID, requestingUserID int64, accepts bool) error {
	p, err := d.GetPairing(ctx, pairingID)
	if err != nil {
		return err
	}
	switch requestingUserID {
	case p.UserAID:
		_, err = d.ExecContext(ctx, `UPDATE pairings SET a_accepts_from_b = ? WHERE id = ?`, accepts, pairingID)
	case p.UserBID:
		_, err = d.ExecContext(ctx, `UPDATE pairings SET b_accepts_from_a = ? WHERE id = ?`, accepts, pairingID)
	default:
		return ErrNotFound
	}
	return err
}

func (d *DB) DeletePairing(ctx context.Context, pairingID, requestingUserID int64) error {
	p, err := d.GetPairing(ctx, pairingID)
	if err != nil {
		return err
	}
	if requestingUserID != p.UserAID && requestingUserID != p.UserBID {
		return ErrNotFound
	}
	// triggers.pairing_id is a plain (non-cascading) foreign key and
	// foreign_keys is enabled, so the pairing's trigger history has to go
	// first or the pairing delete itself fails.
	tx, err := d.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `DELETE FROM triggers WHERE pairing_id = ?`, pairingID); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM pairings WHERE id = ?`, pairingID); err != nil {
		return err
	}
	return tx.Commit()
}
