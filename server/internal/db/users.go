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
		`SELECT id, display_name, fcm_token, api_key_hash, created_at FROM users WHERE api_key_hash = ?`,
		hash,
	)
	return scanUser(row)
}

func (d *DB) GetUserByID(ctx context.Context, id int64) (*User, error) {
	row := d.QueryRowContext(ctx,
		`SELECT id, display_name, fcm_token, api_key_hash, created_at FROM users WHERE id = ?`,
		id,
	)
	return scanUser(row)
}

func scanUser(row *sql.Row) (*User, error) {
	var u User
	if err := row.Scan(&u.ID, &u.DisplayName, &u.FCMToken, &u.APIKeyHash, &u.CreatedAt); err != nil {
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
