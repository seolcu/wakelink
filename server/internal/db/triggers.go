package db

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

func (d *DB) CreateTrigger(ctx context.Context, pairingID, fromUserID, toUserID int64) (*Trigger, error) {
	now := time.Now().Unix()
	res, err := d.ExecContext(ctx,
		`INSERT INTO triggers (pairing_id, from_user_id, to_user_id, created_at, send_status) VALUES (?, ?, ?, ?, 'pending')`,
		pairingID, fromUserID, toUserID, now,
	)
	if err != nil {
		return nil, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return nil, err
	}
	return &Trigger{ID: id, PairingID: pairingID, FromUserID: fromUserID, ToUserID: toUserID, CreatedAt: now, SendStatus: "pending"}, nil
}

func (d *DB) UpdateTriggerSendResult(ctx context.Context, triggerID int64, status, messageID, sendErr string) error {
	_, err := d.ExecContext(ctx,
		`UPDATE triggers SET send_status = ?, fcm_message_id = NULLIF(?, ''), send_error = NULLIF(?, '') WHERE id = ?`,
		status, messageID, sendErr, triggerID,
	)
	return err
}

func (d *DB) AckTrigger(ctx context.Context, triggerID, ackingUserID int64) error {
	res, err := d.ExecContext(ctx,
		`UPDATE triggers SET ack_received_at = ? WHERE id = ? AND to_user_id = ? AND ack_received_at IS NULL`,
		time.Now().Unix(), triggerID, ackingUserID,
	)
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

func (d *DB) GetTrigger(ctx context.Context, id int64) (*Trigger, error) {
	row := d.QueryRowContext(ctx,
		`SELECT id, pairing_id, from_user_id, to_user_id, created_at, fcm_message_id, send_status, send_error, ack_received_at FROM triggers WHERE id = ?`,
		id,
	)
	var t Trigger
	if err := row.Scan(&t.ID, &t.PairingID, &t.FromUserID, &t.ToUserID, &t.CreatedAt, &t.FCMMessageID, &t.SendStatus, &t.SendError, &t.AckReceivedAt); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &t, nil
}

func (d *DB) ListTriggersForPairing(ctx context.Context, pairingID int64, limit int) ([]Trigger, error) {
	rows, err := d.QueryContext(ctx,
		`SELECT id, pairing_id, from_user_id, to_user_id, created_at, fcm_message_id, send_status, send_error, ack_received_at
		 FROM triggers WHERE pairing_id = ? ORDER BY created_at DESC LIMIT ?`,
		pairingID, limit,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []Trigger
	for rows.Next() {
		var t Trigger
		if err := rows.Scan(&t.ID, &t.PairingID, &t.FromUserID, &t.ToUserID, &t.CreatedAt, &t.FCMMessageID, &t.SendStatus, &t.SendError, &t.AckReceivedAt); err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

// CountTriggersSince counts triggers sent *from* fromUserID within this
// pairing since the given unix timestamp -- used for the daily rate-limit
// cap.
func (d *DB) CountTriggersSince(ctx context.Context, pairingID, fromUserID int64, since int64) (int, error) {
	var count int
	err := d.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM triggers WHERE pairing_id = ? AND from_user_id = ? AND created_at >= ?`,
		pairingID, fromUserID, since,
	).Scan(&count)
	return count, err
}

// LastTriggerAt returns the unix timestamp of the most recent trigger sent
// from fromUserID within this pairing, or 0 if none exists -- used for the
// cooldown check.
func (d *DB) LastTriggerAt(ctx context.Context, pairingID, fromUserID int64) (int64, error) {
	var ts sql.NullInt64
	err := d.QueryRowContext(ctx,
		`SELECT MAX(created_at) FROM triggers WHERE pairing_id = ? AND from_user_id = ?`,
		pairingID, fromUserID,
	).Scan(&ts)
	if err != nil {
		return 0, err
	}
	if !ts.Valid {
		return 0, nil
	}
	return ts.Int64, nil
}
