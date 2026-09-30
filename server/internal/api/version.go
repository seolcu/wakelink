package api

import (
	"net/http"
	"runtime"
)

func (s *Server) handleVersion(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{
		"service":    "wakelinkd",
		"version":    s.Version,
		"go_version": runtime.Version(),
	})
}

// handleMetrics exposes coarse, non-PII counters (see internal/metrics) for
// a single-tenant deployment's own visibility -- no auth required, since it
// carries counts only, never user or pairing identifiers.
func (s *Server) handleMetrics(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]int64{
		"triggers_sent":   s.Metrics.TriggersSent.Load(),
		"triggers_failed": s.Metrics.TriggersFailed.Load(),
		"triggers_acked":  s.Metrics.TriggersAcked.Load(),
	})
}
