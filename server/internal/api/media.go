package api

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/go-chi/chi/v5"
	"wakelink/internal/db"
)

// presetSounds are the built-in sounds bundled with the app
// (android/.../sounds/SoundRef.kt PRESET_NAMES; keep in sync).
var presetSounds = map[string]bool{
	"beep": true, "digital": true, "chime": true, "rising": true, "bell": true, "marimba": true,
}

const maxMediaNameRunes = 40

// encryptedMediaMagic prefixes uploads encrypted by the app
// (android/.../crypto/MediaCrypto.kt).
var encryptedMediaMagic = []byte("WLE1")

// mediaCategory is one family of uploads: sounds (voice recordings and
// audio files) or images (photos, GIFs). They share storage, endpoints'
// shape and access rules, and differ in accepted formats and limits.
type mediaCategory struct {
	kinds      []string
	maxBytes   int64
	maxPerUser int
	sniff      func([]byte) string
	tooLarge   string
}

var (
	soundMedia = mediaCategory{
		kinds:      []string{"voice", "file"},
		maxBytes:   2 * 1024 * 1024, // the app enforces the same limit before uploading
		maxPerUser: 10,
		sniff:      sniffAudio,
		tooLarge:   "sound is too large (max 2 MB)",
	}
	imageMedia = mediaCategory{
		kinds:      []string{"image"},
		maxBytes:   3 * 1024 * 1024, // GIFs are uploaded as-is; photos are downscaled by the app
		maxPerUser: 10,
		sniff:      sniffImage,
		tooLarge:   "image is too large (max 3 MB)",
	}
)

func (c mediaCategory) has(kind string) bool {
	for _, k := range c.kinds {
		if k == kind {
			return true
		}
	}
	return false
}

type mediaView struct {
	ID        int64  `json:"id"`
	Kind      string `json:"kind"`
	Name      string `json:"name"`
	Mime      string `json:"mime"`
	Size      int64  `json:"size"`
	SHA256    string `json:"sha256"`
	CreatedAt int64  `json:"created_at"`
}

func newMediaView(m db.Media) mediaView {
	return mediaView{ID: m.ID, Kind: m.Kind, Name: m.Name, Mime: m.Mime, Size: m.Size, SHA256: m.SHA256, CreatedAt: m.CreatedAt}
}

// sniffAudio identifies the formats Android's MediaPlayer reliably plays,
// from the file's leading bytes rather than the client-supplied type.
// Returns "" for anything else.
func sniffAudio(b []byte) string {
	switch {
	case len(b) >= 12 && string(b[4:8]) == "ftyp":
		return "audio/mp4" // m4a / AAC-in-MP4, what the app records
	case len(b) >= 4 && string(b[:4]) == "OggS":
		return "audio/ogg"
	case len(b) >= 12 && string(b[:4]) == "RIFF" && string(b[8:12]) == "WAVE":
		return "audio/wav"
	case len(b) >= 4 && string(b[:4]) == "fLaC":
		return "audio/flac"
	case len(b) >= 3 && string(b[:3]) == "ID3":
		return "audio/mpeg"
	case len(b) >= 2 && b[0] == 0xFF && b[1]&0xF6 == 0xF0:
		return "audio/aac" // ADTS
	case len(b) >= 2 && b[0] == 0xFF && b[1]&0xE0 == 0xE0:
		return "audio/mpeg" // bare MPEG audio frame
	}
	return ""
}

// sniffImage accepts the formats Android decodes everywhere we run.
func sniffImage(b []byte) string {
	switch {
	case len(b) >= 3 && b[0] == 0xFF && b[1] == 0xD8 && b[2] == 0xFF:
		return "image/jpeg"
	case len(b) >= 8 && string(b[:8]) == "\x89PNG\r\n\x1a\n":
		return "image/png"
	case len(b) >= 6 && (string(b[:6]) == "GIF87a" || string(b[:6]) == "GIF89a"):
		return "image/gif"
	case len(b) >= 12 && string(b[:4]) == "RIFF" && string(b[8:12]) == "WEBP":
		return "image/webp"
	}
	return ""
}

// upload handles POST /v1/sounds?kind=voice|file&name=... and
// POST /v1/images?name=...; the body is the raw file.
func (s *Server) upload(c mediaCategory) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := userFromContext(r)

		kind := r.URL.Query().Get("kind")
		if len(c.kinds) == 1 && kind == "" {
			kind = c.kinds[0]
		}
		if !c.has(kind) {
			writeError(w, http.StatusBadRequest, "invalid kind")
			return
		}
		name := strings.TrimSpace(r.URL.Query().Get("name"))
		if name == "" || utf8.RuneCountInString(name) > maxMediaNameRunes {
			writeError(w, http.StatusBadRequest, "name is required (max 40 characters)")
			return
		}

		data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, c.maxBytes))
		if err != nil {
			var tooBig *http.MaxBytesError
			if errors.As(err, &tooBig) {
				writeError(w, http.StatusRequestEntityTooLarge, c.tooLarge)
				return
			}
			writeError(w, http.StatusBadRequest, "invalid request body")
			return
		}
		// Apps that encrypt end to end upload "WLE1"-prefixed ciphertext the
		// server can't (and shouldn't) look inside; plaintext uploads from
		// older apps are still format-checked.
		mime := c.sniff(data)
		if bytes.HasPrefix(data, encryptedMediaMagic) {
			mime = "application/octet-stream"
		}
		if mime == "" {
			writeError(w, http.StatusUnsupportedMediaType, "unsupported format")
			return
		}

		count, err := s.DB.CountMedia(r.Context(), user.ID, c.kinds)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "internal error")
			return
		}
		if count >= c.maxPerUser {
			writeError(w, http.StatusConflict, "too many items")
			return
		}

		sum := sha256.Sum256(data)
		created, err := s.DB.CreateMedia(r.Context(), user.ID, kind, name, mime, hex.EncodeToString(sum[:]), data)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "failed to save")
			return
		}
		writeJSON(w, http.StatusCreated, newMediaView(*created))
	}
}

// list handles GET /v1/sounds and GET /v1/images: the caller's own uploads.
func (s *Server) list(c mediaCategory) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := userFromContext(r)
		items, err := s.DB.ListMedia(r.Context(), user.ID, c.kinds)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "internal error")
			return
		}
		out := make([]mediaView, 0, len(items))
		for _, m := range items {
			out = append(out, newMediaView(m))
		}
		writeJSON(w, http.StatusOK, out)
	}
}

func (s *Server) remove(c mediaCategory) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := userFromContext(r)
		id, err := strconv.ParseInt(chi.URLParam(r, "id"), 10, 64)
		if err != nil {
			writeError(w, http.StatusBadRequest, "invalid id")
			return
		}
		if err := s.DB.DeleteMedia(r.Context(), user.ID, id, c.kinds); err != nil {
			if errors.Is(err, db.ErrNotFound) {
				writeError(w, http.StatusNotFound, "not found")
				return
			}
			writeError(w, http.StatusInternalServerError, "internal error")
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// serve handles GET /v1/sounds/{id}/audio and GET /v1/images/{id}/data:
// for the owner, or for someone the owner is currently allowed to wake
// (the only people it would ever be shown/played to). Everyone else gets
// the same 404 as for an item that doesn't exist.
func (s *Server) serve(c mediaCategory) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := userFromContext(r)
		id, err := strconv.ParseInt(chi.URLParam(r, "id"), 10, 64)
		if err != nil {
			writeError(w, http.StatusBadRequest, "invalid id")
			return
		}
		m, err := s.DB.GetMedia(r.Context(), id)
		if err != nil {
			if errors.Is(err, db.ErrNotFound) {
				writeError(w, http.StatusNotFound, "not found")
				return
			}
			writeError(w, http.StatusInternalServerError, "internal error")
			return
		}
		if !c.has(m.Kind) {
			writeError(w, http.StatusNotFound, "not found")
			return
		}
		if m.OwnerUserID != user.ID {
			pairing, err := s.DB.GetPairingBetween(r.Context(), m.OwnerUserID, user.ID)
			if err != nil || !pairing.CanWake(m.OwnerUserID, user.ID) {
				writeError(w, http.StatusNotFound, "not found")
				return
			}
		}
		data, err := s.DB.GetMediaData(r.Context(), id)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "internal error")
			return
		}
		w.Header().Set("Content-Type", m.Mime)
		w.Header().Set("Content-Length", strconv.Itoa(len(data)))
		w.Header().Set("Cache-Control", "private, max-age=31536000, immutable") // content is fixed per id
		w.Header().Set("X-Content-Type-Options", "nosniff")
		_, _ = io.Copy(w, bytes.NewReader(data))
	}
}

var errInvalidMedia = errors.New("invalid sound or image")

// ownCustom checks raw is "custom:<id>" naming one of senderID's uploads in
// category c, and returns the push key "custom:<id>:<sha256>": the hash
// lets the receiver cache the file and verify the download.
func (s *Server) ownCustom(ctx context.Context, senderID int64, raw string, c mediaCategory) (string, error) {
	id, err := strconv.ParseInt(strings.TrimPrefix(raw, "custom:"), 10, 64)
	if !strings.HasPrefix(raw, "custom:") || err != nil || id <= 0 {
		return "", errInvalidMedia
	}
	m, err := s.DB.GetMedia(ctx, id)
	if errors.Is(err, db.ErrNotFound) || (err == nil && (m.OwnerUserID != senderID || !c.has(m.Kind))) {
		return "", errInvalidMedia
	}
	if err != nil {
		return "", err
	}
	return raw + ":" + m.SHA256, nil
}

// resolveTriggerSound validates the sound a sender asked for and returns
// the key to put in the push ("" for none).
func (s *Server) resolveTriggerSound(ctx context.Context, senderID int64, raw string) (string, error) {
	switch {
	case raw == "" || raw == "device":
		return raw, nil
	case strings.HasPrefix(raw, "preset:"):
		if !presetSounds[strings.TrimPrefix(raw, "preset:")] {
			return "", errInvalidMedia
		}
		return raw, nil
	}
	return s.ownCustom(ctx, senderID, raw, soundMedia)
}

// resolveTriggerImage validates the image a sender asked for ("" for none).
func (s *Server) resolveTriggerImage(ctx context.Context, senderID int64, raw string) (string, error) {
	if raw == "" {
		return "", nil
	}
	return s.ownCustom(ctx, senderID, raw, imageMedia)
}
