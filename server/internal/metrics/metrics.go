// Package metrics holds a handful of coarse, in-process counters -- enough
// visibility for a single-tenant/self-hosted deployment without pulling in
// a full metrics stack (Prometheus etc). Counts only, no user or pairing
// identifiers, so it's safe to expose unauthenticated.
package metrics

import "sync/atomic"

type Counters struct {
	TriggersSent   atomic.Int64
	TriggersFailed atomic.Int64
	TriggersAcked  atomic.Int64
}
