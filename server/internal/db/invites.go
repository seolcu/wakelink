package db

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

func (d *DB) CreateInvite(ctx context.Context, code string, inviterUserID int64, ttl time.Duration) (*Invite, error) {
	now := time.Now().Unix()
	expiresAt := time.Now().Add(ttl).Unix()
	_, err := d.ExecContext(ctx,
		`INSERT INTO pairing_invites (code, inviter_user_id, expires_at, used, created_at) VALUES (?, ?, ?, 0, ?)`,
		code, inviterUserID, expiresAt, now,
	)
	if err != nil {
		return nil, err
	}
	return &Invite{Code: code, InviterUserID: inviterUserID, ExpiresAt: expiresAt, CreatedAt: now}, nil
}

func (d *DB) GetInvite(ctx context.Context, code string) (*Invite, error) {
	row := d.QueryRowContext(ctx,
		`SELECT code, inviter_user_id, expires_at, used, created_at FROM pairing_invites WHERE code = ?`,
		code,
	)
	var inv Invite
	var used int
	if err := row.Scan(&inv.Code, &inv.InviterUserID, &inv.ExpiresAt, &used, &inv.CreatedAt); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	inv.Used = used != 0
	return &inv, nil
}

func (d *DB) MarkInviteUsed(ctx context.Context, code string) error {
	_, err := d.ExecContext(ctx, `UPDATE pairing_invites SET used = 1 WHERE code = ?`, code)
	return err
}
