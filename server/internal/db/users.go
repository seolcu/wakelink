package db

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

var ErrNotFound = errors.New("not found")

func (d *DB) CreateUser(ctx context.Context, displayName, apiKeyHash string) (*User, error) {
	now := time.Now().Unix()
	res, err := d.ExecContext(ctx,
		`INSERT INTO users (display_name, api_key_hash, created_at) VALUES (?, ?, ?)`,
		displayName, apiKeyHash, now,
	)
	if err != nil {
		return nil, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return nil, err
	}
	return &User{ID: id, DisplayName: displayName, APIKeyHash: apiKeyHash, CreatedAt: now}, nil
}

func (d *DB) GetUserByAPIKeyHash(ctx context.Context, hash string) (*User, error) {
	row := d.QueryRowContext(ctx,
		`SELECT id, display_name, fcm_token, api_key_hash, created_at, public_key FROM users WHERE api_key_hash = ?`,
		hash,
	)
	return scanUser(row)
}

func (d *DB) GetUserByID(ctx context.Context, id int64) (*User, error) {
	row := d.QueryRowContext(ctx,
		`SELECT id, display_name, fcm_token, api_key_hash, created_at, public_key FROM users WHERE id = ?`,
		id,
	)
	return scanUser(row)
}

func scanUser(row *sql.Row) (*User, error) {
	var u User
	if err := row.Scan(&u.ID, &u.DisplayName, &u.FCMToken, &u.APIKeyHash, &u.CreatedAt, &u.PublicKey); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &u, nil
}

func (d *DB) UpdateFCMToken(ctx context.Context, userID int64, token string) error {
	_, err := d.ExecContext(ctx, `UPDATE users SET fcm_token = ? WHERE id = ?`, token, userID)
	return err
}

// ClearFCMToken forgets a device token FCM has reported as no longer
// valid (app uninstalled, token rotated without an update reaching us),
// so later triggers fail fast with "not registered" instead of retrying
// a dead token forever.
func (d *DB) ClearFCMToken(ctx context.Context, userID int64) error {
	_, err := d.ExecContext(ctx, `UPDATE users SET fcm_token = NULL WHERE id = ?`, userID)
	return err
}

// SetPublicKey stores userID's device public key (see migration 0003).
func (d *DB) SetPublicKey(ctx context.Context, userID int64, publicKey string) error {
	_, err := d.ExecContext(ctx, `UPDATE users SET public_key = ? WHERE id = ?`, publicKey, userID)
	return err
}

func (d *DB) UpdateDisplayName(ctx context.Context, userID int64, displayName string) error {
	_, err := d.ExecContext(ctx, `UPDATE users SET display_name = ? WHERE id = ?`, displayName, userID)
	return err
}

// RotateAPIKeyHash overwrites userID's stored key hash, immediately
// invalidating whatever key was previously issued to them.
func (d *DB) RotateAPIKeyHash(ctx context.Context, userID int64, newHash string) error {
	res, err := d.ExecContext(ctx, `UPDATE users SET api_key_hash = ? WHERE id = ?`, newHash, userID)
	if err != nil {
		return err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if n == 0 {
		return ErrNotFound
	}
	return nil
}

// DeleteUser removes a user and everything that references them: their
// trigger history (sent and received), every pairing they're a member of,
// and any outstanding invites. Children go first because none of these
// foreign keys cascade.
func (d *DB) DeleteUser(ctx context.Context, userID int64) error {
	tx, err := d.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	stmts := []string{
		`DELETE FROM triggers WHERE from_user_id = ?1 OR to_user_id = ?1`,
		`DELETE FROM custom_media WHERE owner_user_id = ?1`,
		`DELETE FROM pairings WHERE user_a_id = ?1 OR user_b_id = ?1`,
		`DELETE FROM pairing_invites WHERE inviter_user_id = ?1`,
	}
	for _, q := range stmts {
		if _, err := tx.ExecContext(ctx, q, userID); err != nil {
			return err
		}
	}
	res, err := tx.ExecContext(ctx, `DELETE FROM users WHERE id = ?`, userID)
	if err != nil {
		return err
	}
	if n, err := res.RowsAffected(); err == nil && n == 0 {
		return ErrNotFound
	}
	return tx.Commit()
}
