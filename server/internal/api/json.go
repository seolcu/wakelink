package api

import (
	"encoding/json"
	"net/http"
)

func writeJSON(w http.ResponseWriter, status int, v interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

type errorResponse struct {
	Error string `json:"error"`
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, errorResponse{Error: message})
}

// maxRequestBodyBytes caps decoded request bodies -- generous for this
// API's small JSON payloads, but enough to stop an authenticated (or even
// unauthenticated, for /v1/register) caller from sending an arbitrarily
// large body.
const maxRequestBodyBytes = 64 * 1024

// readJSON decodes a JSON request body. It tolerates an empty body (no-op)
// so handlers that don't require a body can still safely call it if they
// later grow optional fields.
func readJSON(w http.ResponseWriter, r *http.Request, v interface{}) error {
	if r.ContentLength == 0 {
		return nil
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxRequestBodyBytes)
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	return dec.Decode(v)
}
