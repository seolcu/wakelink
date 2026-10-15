package db

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"time"
)

// Media is an uploaded recording, audio file or image, without its bytes.
type Media struct {
	ID          int64
	OwnerUserID int64
	Kind        string
	Name        string
	Mime        string
	Size        int64
	SHA256      string
	CreatedAt   int64
}

const mediaColumns = `id, owner_user_id, kind, name, mime, size, sha256, created_at`

func scanMedia(scan func(...any) error) (*Media, error) {
	var m Media
	if err := scan(&m.ID, &m.OwnerUserID, &m.Kind, &m.Name, &m.Mime, &m.Size, &m.SHA256, &m.CreatedAt); err != nil {
		return nil, err
	}
	return &m, nil
}

// kindFilter renders "kind IN (?, ?)" plus its args.
func kindFilter(kinds []string) (string, []any) {
	args := make([]any, len(kinds))
	for i, k := range kinds {
		args[i] = k
	}
	return "kind IN (" + strings.TrimSuffix(strings.Repeat("?, ", len(kinds)), ", ") + ")", args
}

func (d *DB) CreateMedia(ctx context.Context, ownerID int64, kind, name, mime, sha string, data []byte) (*Media, error) {
	now := time.Now().Unix()
	res, err := d.ExecContext(ctx,
		`INSERT INTO custom_media (owner_user_id, kind, name, mime, size, sha256, data, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		ownerID, kind, name, mime, len(data), sha, data, now,
	)
	if err != nil {
		return nil, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return nil, err
	}
	return &Media{ID: id, OwnerUserID: ownerID, Kind: kind, Name: name, Mime: mime, Size: int64(len(data)), SHA256: sha, CreatedAt: now}, nil
}

func (d *DB) CountMedia(ctx context.Context, ownerID int64, kinds []string) (int, error) {
	filter, args := kindFilter(kinds)
	var n int
	err := d.QueryRowContext(ctx, `SELECT COUNT(*) FROM custom_media WHERE owner_user_id = ? AND `+filter,
		append([]any{ownerID}, args...)...).Scan(&n)
	return n, err
}

func (d *DB) ListMedia(ctx context.Context, ownerID int64, kinds []string) ([]Media, error) {
	filter, args := kindFilter(kinds)
	rows, err := d.QueryContext(ctx,
		`SELECT `+mediaColumns+` FROM custom_media WHERE owner_user_id = ? AND `+filter+` ORDER BY created_at DESC, id DESC`,
		append([]any{ownerID}, args...)...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Media
	for rows.Next() {
		m, err := scanMedia(rows.Scan)
		if err != nil {
			return nil, err
		}
		out = append(out, *m)
	}
	return out, rows.Err()
}

func (d *DB) GetMedia(ctx context.Context, id int64) (*Media, error) {
	m, err := scanMedia(d.QueryRowContext(ctx, `SELECT `+mediaColumns+` FROM custom_media WHERE id = ?`, id).Scan)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return m, err
}

func (d *DB) GetMediaData(ctx context.Context, id int64) ([]byte, error) {
	var data []byte
	err := d.QueryRowContext(ctx, `SELECT data FROM custom_media WHERE id = ?`, id).Scan(&data)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return data, err
}

// DeleteMedia removes ownerID's item id if it's one of kinds; ErrNotFound otherwise.
func (d *DB) DeleteMedia(ctx context.Context, ownerID, id int64, kinds []string) error {
	filter, args := kindFilter(kinds)
	res, err := d.ExecContext(ctx, `DELETE FROM custom_media WHERE id = ? AND owner_user_id = ? AND `+filter,
		append([]any{id, ownerID}, args...)...)
	if err != nil {
		return err
	}
	if n, err := res.RowsAffected(); err == nil && n == 0 {
		return ErrNotFound
	}
	return err
}
