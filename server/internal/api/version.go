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
// a deployment's own visibility. They are instance-wide, not per-user, but
// still sit behind auth so the public instance doesn't advertise its
// traffic to anyone who asks.
func (s *Server) handleMetrics(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]int64{
		"triggers_sent":   s.Metrics.TriggersSent.Load(),
		"triggers_failed": s.Metrics.TriggersFailed.Load(),
		"triggers_acked":  s.Metrics.TriggersAcked.Load(),
	})
}

// handleDownload gives the app a short, stable link to share ("install
// WakeLink: https://<server>/download") that always points at the newest
// release, wherever the APK itself is hosted.
func (s *Server) handleDownload(w http.ResponseWriter, r *http.Request) {
	http.Redirect(w, r, s.DownloadURL, http.StatusFound)
}
