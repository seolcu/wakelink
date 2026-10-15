package api

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"

	"wakelink/internal/db"
)

// fakeM4A is just enough of an MP4 header to pass sniffAudio.
var fakeM4A = append([]byte("\x00\x00\x00\x18ftypM4A \x00\x00\x00\x00"), bytes.Repeat([]byte{1}, 100)...)

func doRaw(t *testing.T, h http.Handler, method, path, apiKey string, body []byte) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(method, path, bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/octet-stream")
	if apiKey != "" {
		req.Header.Set("Authorization", "Bearer "+apiKey)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func uploadSound(t *testing.T, h http.Handler, apiKey string, data []byte) mediaView {
	t.Helper()
	rec := doRaw(t, h, http.MethodPost, "/v1/sounds?kind=voice&name=Wake%20up", apiKey, data)
	if rec.Code != http.StatusCreated {
		t.Fatalf("upload: status %d body %s", rec.Code, rec.Body.String())
	}
	var v mediaView
	if err := json.Unmarshal(rec.Body.Bytes(), &v); err != nil {
		t.Fatalf("decode upload: %v", err)
	}
	return v
}

func TestTriggerCarriesPresetSound(t *testing.T) {
	s, sender := newTestServer(t)
	s.RateLimit.Cooldown = 0
	router := s.Router()
	_, bob, pairingID := pairUp(t, router)
	path := pairingPath(pairingID) + "/trigger"

	if rec := doJSON(t, router, http.MethodPost, path, bob.APIKey, nil); rec.Code != http.StatusCreated {
		t.Fatalf("trigger without body: status %d body %s", rec.Code, rec.Body.String())
	}
	if rec := doJSON(t, router, http.MethodPost, path, bob.APIKey, triggerRequest{Sound: "preset:chime"}); rec.Code != http.StatusCreated {
		t.Fatalf("trigger with preset: status %d body %s", rec.Code, rec.Body.String())
	}
	if got := []string{sender.messages[0].Sound, sender.messages[1].Sound}; got[0] != "" || got[1] != "preset:chime" {
		t.Fatalf("pushed sounds = %q", got)
	}

	for _, bad := range []string{"preset:nope", "custom:abc", "file:///etc/passwd", "custom:999"} {
		if rec := doJSON(t, router, http.MethodPost, path, bob.APIKey, triggerRequest{Sound: bad}); rec.Code != http.StatusBadRequest {
			t.Errorf("sound %q: expected 400, got %d", bad, rec.Code)
		}
	}
}

func TestUploadValidation(t *testing.T) {
	s, _ := newTestServer(t)
	router := s.Router()
	alice := register(t, router, "Alice", "alice-token")

	if rec := doRaw(t, router, http.MethodPost, "/v1/sounds?kind=voice&name=x", alice.APIKey, []byte("<html>not audio</html>")); rec.Code != http.StatusUnsupportedMediaType {
		t.Errorf("non-audio: expected 415, got %d", rec.Code)
	}
	big := append(append([]byte{}, fakeM4A...), make([]byte, int(soundMedia.maxBytes))...)
	if rec := doRaw(t, router, http.MethodPost, "/v1/sounds?kind=voice&name=x", alice.APIKey, big); rec.Code != http.StatusRequestEntityTooLarge {
		t.Errorf("too big: expected 413, got %d", rec.Code)
	}
	if rec := doRaw(t, router, http.MethodPost, "/v1/sounds?kind=song&name=x", alice.APIKey, fakeM4A); rec.Code != http.StatusBadRequest {
		t.Errorf("bad kind: expected 400, got %d", rec.Code)
	}
	if rec := doRaw(t, router, http.MethodPost, "/v1/sounds?kind=voice&name=", alice.APIKey, fakeM4A); rec.Code != http.StatusBadRequest {
		t.Errorf("no name: expected 400, got %d", rec.Code)
	}

	created := uploadSound(t, router, alice.APIKey, fakeM4A)
	sum := sha256.Sum256(fakeM4A)
	if created.Mime != "audio/mp4" || created.SHA256 != hex.EncodeToString(sum[:]) || created.Size != int64(len(fakeM4A)) {
		t.Fatalf("unexpected upload result %+v", created)
	}
	for i := 1; i < soundMedia.maxPerUser; i++ {
		uploadSound(t, router, alice.APIKey, fakeM4A)
	}
	if rec := doRaw(t, router, http.MethodPost, "/v1/sounds?kind=voice&name=x", alice.APIKey, fakeM4A); rec.Code != http.StatusConflict {
		t.Errorf("over the cap: expected 409, got %d", rec.Code)
	}

	rec := doJSON(t, router, http.MethodGet, "/v1/sounds", alice.APIKey, nil)
	var list []mediaView
	_ = json.Unmarshal(rec.Body.Bytes(), &list)
	if len(list) != soundMedia.maxPerUser {
		t.Fatalf("expected %d sounds listed, got %d", soundMedia.maxPerUser, len(list))
	}
}

func TestCustomSoundTriggerAndDownload(t *testing.T) {
	s, sender := newTestServer(t)
	router := s.Router()
	alice, bob, pairingID := pairUp(t, router)
	carol := register(t, router, "Carol", "carol-token")

	snd := uploadSound(t, router, bob.APIKey, fakeM4A)
	carolSnd := uploadSound(t, router, carol.APIKey, fakeM4A)
	idStr := strconv.FormatInt(snd.ID, 10)

	// Bob wakes Alice with his recording: the push names it with its hash.
	rec := doJSON(t, router, http.MethodPost, pairingPath(pairingID)+"/trigger", bob.APIKey, triggerRequest{Sound: "custom:" + idStr})
	if rec.Code != http.StatusCreated {
		t.Fatalf("trigger: status %d body %s", rec.Code, rec.Body.String())
	}
	if got, want := sender.messages[0].Sound, "custom:"+idStr+":"+snd.SHA256; got != want {
		t.Fatalf("pushed sound %q, want %q", got, want)
	}
	// Nobody can send someone else's recording.
	s.RateLimit.Cooldown = 0
	rec = doJSON(t, router, http.MethodPost, pairingPath(pairingID)+"/trigger", bob.APIKey,
		triggerRequest{Sound: "custom:" + strconv.FormatInt(carolSnd.ID, 10)})
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("someone else's sound: expected 400, got %d", rec.Code)
	}

	audioPath := "/v1/sounds/" + idStr + "/audio"
	for name, key := range map[string]string{"owner": bob.APIKey, "woken contact": alice.APIKey} {
		rec := doJSON(t, router, http.MethodGet, audioPath, key, nil)
		if rec.Code != http.StatusOK || !bytes.Equal(rec.Body.Bytes(), fakeM4A) || rec.Header().Get("Content-Type") != "audio/mp4" {
			t.Fatalf("%s download: status %d type %q", name, rec.Code, rec.Header().Get("Content-Type"))
		}
	}
	if rec := doJSON(t, router, http.MethodGet, audioPath, carol.APIKey, nil); rec.Code != http.StatusNotFound {
		t.Fatalf("stranger download: expected 404, got %d", rec.Code)
	}

	// Once Bob may no longer wake Alice, she can't fetch his sounds either.
	rec = doJSON(t, router, http.MethodPatch, pairingPath(pairingID), bob.APIKey, patchPairingRequest{ICanWakeThem: boolPtr(false)})
	if rec.Code != http.StatusOK && rec.Code != http.StatusNoContent {
		t.Fatalf("revoke: status %d body %s", rec.Code, rec.Body.String())
	}
	if rec := doJSON(t, router, http.MethodGet, audioPath, alice.APIKey, nil); rec.Code != http.StatusNotFound {
		t.Fatalf("after revoke: expected 404, got %d", rec.Code)
	}

	// Only the owner can delete, and deleting an account deletes its sounds.
	if rec := doJSON(t, router, http.MethodDelete, "/v1/sounds/"+idStr, alice.APIKey, nil); rec.Code != http.StatusNotFound {
		t.Fatalf("non-owner delete: expected 404, got %d", rec.Code)
	}
	if rec := doJSON(t, router, http.MethodDelete, "/v1/me", carol.APIKey, nil); rec.Code != http.StatusNoContent {
		t.Fatalf("delete account: status %d", rec.Code)
	}
	if _, err := s.DB.GetMedia(t.Context(), carolSnd.ID); !errors.Is(err, db.ErrNotFound) {
		t.Fatalf("account deletion should remove its sounds, got %v", err)
	}
	if rec := doJSON(t, router, http.MethodDelete, "/v1/sounds/"+idStr, bob.APIKey, nil); rec.Code != http.StatusNoContent {
		t.Fatalf("owner delete: status %d", rec.Code)
	}
}

func boolPtr(b bool) *bool { return &b }

var fakePNG = append([]byte("\x89PNG\r\n\x1a\n"), bytes.Repeat([]byte{2}, 100)...)

func TestImagesUploadTriggerAndDownload(t *testing.T) {
	s, sender := newTestServer(t)
	router := s.Router()
	alice, bob, pairingID := pairUp(t, router)
	carol := register(t, router, "Carol", "carol-token")

	if rec := doRaw(t, router, http.MethodPost, "/v1/images?name=x", bob.APIKey, fakeM4A); rec.Code != http.StatusUnsupportedMediaType {
		t.Fatalf("audio as image: expected 415, got %d", rec.Code)
	}
	rec := doRaw(t, router, http.MethodPost, "/v1/images?name=Morning", bob.APIKey, fakePNG)
	if rec.Code != http.StatusCreated {
		t.Fatalf("upload image: status %d body %s", rec.Code, rec.Body.String())
	}
	var img mediaView
	_ = json.Unmarshal(rec.Body.Bytes(), &img)
	if img.Kind != "image" || img.Mime != "image/png" {
		t.Fatalf("unexpected image %+v", img)
	}
	snd := uploadSound(t, router, bob.APIKey, fakeM4A)
	imgID := strconv.FormatInt(img.ID, 10)

	// Images and sounds are listed separately.
	var imgs, snds []mediaView
	_ = json.Unmarshal(doJSON(t, router, http.MethodGet, "/v1/images", bob.APIKey, nil).Body.Bytes(), &imgs)
	_ = json.Unmarshal(doJSON(t, router, http.MethodGet, "/v1/sounds", bob.APIKey, nil).Body.Bytes(), &snds)
	if len(imgs) != 1 || len(snds) != 1 || imgs[0].ID != img.ID || snds[0].ID != snd.ID {
		t.Fatalf("lists mixed up: images %+v sounds %+v", imgs, snds)
	}

	rec = doJSON(t, router, http.MethodPost, pairingPath(pairingID)+"/trigger", bob.APIKey,
		triggerRequest{Sound: "preset:bell", Image: "custom:" + imgID})
	if rec.Code != http.StatusCreated {
		t.Fatalf("trigger with image: status %d body %s", rec.Code, rec.Body.String())
	}
	if got, want := sender.messages[0].Image, "custom:"+imgID+":"+img.SHA256; got != want {
		t.Fatalf("pushed image %q, want %q", got, want)
	}

	// A sound id isn't an image (and vice versa).
	s.RateLimit.Cooldown = 0
	sndID := strconv.FormatInt(snd.ID, 10)
	if rec := doJSON(t, router, http.MethodPost, pairingPath(pairingID)+"/trigger", bob.APIKey, triggerRequest{Image: "custom:" + sndID}); rec.Code != http.StatusBadRequest {
		t.Fatalf("sound as image: expected 400, got %d", rec.Code)
	}
	if rec := doJSON(t, router, http.MethodPost, pairingPath(pairingID)+"/trigger", bob.APIKey, triggerRequest{Sound: "custom:" + imgID}); rec.Code != http.StatusBadRequest {
		t.Fatalf("image as sound: expected 400, got %d", rec.Code)
	}
	if rec := doJSON(t, router, http.MethodGet, "/v1/sounds/"+imgID+"/audio", alice.APIKey, nil); rec.Code != http.StatusNotFound {
		t.Fatalf("image via sounds endpoint: expected 404, got %d", rec.Code)
	}

	dataPath := "/v1/images/" + imgID + "/data"
	if rec := doJSON(t, router, http.MethodGet, dataPath, alice.APIKey, nil); rec.Code != http.StatusOK || !bytes.Equal(rec.Body.Bytes(), fakePNG) {
		t.Fatalf("woken contact download: status %d", rec.Code)
	}
	if rec := doJSON(t, router, http.MethodGet, dataPath, carol.APIKey, nil); rec.Code != http.StatusNotFound {
		t.Fatalf("stranger download: expected 404, got %d", rec.Code)
	}
	if rec := doJSON(t, router, http.MethodDelete, "/v1/sounds/"+imgID, bob.APIKey, nil); rec.Code != http.StatusNotFound {
		t.Fatalf("deleting an image via /v1/sounds: expected 404, got %d", rec.Code)
	}
	if rec := doJSON(t, router, http.MethodDelete, "/v1/images/"+imgID, bob.APIKey, nil); rec.Code != http.StatusNoContent {
		t.Fatalf("delete image: status %d", rec.Code)
	}
}

func TestSniffImage(t *testing.T) {
	cases := map[string]string{
		"\xff\xd8\xff\xe0":         "image/jpeg",
		"\x89PNG\r\n\x1a\n":        "image/png",
		"GIF89a":                   "image/gif",
		"GIF87a":                   "image/gif",
		"RIFF\x00\x00\x00\x00WEBP": "image/webp",
		"<svg>":                    "",
	}
	for in, want := range cases {
		if got := sniffImage([]byte(in)); got != want {
			t.Errorf("sniffImage(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestSniffAudio(t *testing.T) {
	cases := map[string]string{
		"\x00\x00\x00\x20ftypisom": "audio/mp4",
		"OggS\x00\x02":             "audio/ogg",
		"RIFF\x24\x00\x00\x00WAVE": "audio/wav",
		"fLaC\x00":                 "audio/flac",
		"ID3\x04\x00":              "audio/mpeg",
		"\xff\xfb\x90\x64":         "audio/mpeg",
		"\xff\xf1\x50\x80":         "audio/aac",
		"<!doctype html>":          "",
		"":                         "",
	}
	for in, want := range cases {
		if got := sniffAudio([]byte(in)); got != want {
			t.Errorf("sniffAudio(%q) = %q, want %q", in, got, want)
		}
	}
}
