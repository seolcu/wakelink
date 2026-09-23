# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

WakeLink: an Android app that rings a loud alarm on a trusted contact's
phone on demand, even through ringer silent mode, so a friend/partner/
family member can wake someone who forgot to set their own alarm. Two
components in this repo:

- `server/` -- Go backend (`wakelinkd`): mutual-consent pairing, per-direction
  wake permissions, FCM-triggered wake pushes, sqlite storage.
- `android/` -- single Kotlin/Compose Android app. There is **no**
  separate sender/receiver app -- every install can both wake contacts and
  be woken by them.

Read `README.md`, `docs/pairing-flow.md`, and `docs/self-hosting.md` for
the product-level design rationale; this file is command/architecture
reference only.

## Commands

### Backend (`server/`)

```bash
cd server
go build ./...
go vet ./...
go test ./... -v            # single test: go test ./internal/api/ -run TestRateLimitCooldown -v
gofmt -l .                  # should print nothing; CI fails the build otherwise

cp config.example.env .env && echo "FCM_MOCK=true" >> .env
go run ./cmd/wakelinkd      # local dev without real Firebase creds; pushes are logged, not sent
```

Real end-to-end testing (real FCM, still safe -- rejects any token that
isn't a real device's) needs a real Firebase project's service-account
JSON at `FCM_SERVICE_ACCOUNT_PATH` (drop `FCM_MOCK`); see
`docs/self-hosting.md`.

Container build: `docker build -t wake server/` (Dockerfile requires
`golang:1.25-alpine` or newer -- `go.mod` pins `go 1.25.0`; do not
downgrade the Dockerfile's Go version without also relaxing `go.mod`).

### Android (`android/`)

Gradle here needs **JDK 17-21** to run (AGP 8.5.2 / Gradle 8.9); a
system-default JDK 25 will fail. On this machine a working JDK 21 is at
`~/.local/share/jdks/jdk-21.0.12.1+1` and the SDK at
`~/.local/share/android-sdk` (both installed outside the repo, under
`$XDG_DATA_HOME`, not `~/android-sdk` or similar).

```bash
export JAVA_HOME=~/.local/share/jdks/jdk-21.0.12.1+1   # adjust if your JDK 17-21 lives elsewhere
export ANDROID_HOME=~/.local/share/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
cd android

# Needs app/google-services.json to exist -- it's gitignored (real one
# has a live Firebase API key). Fresh checkout: use the placeholder.
cp app/google-services.json.example app/google-services.json

./gradlew lintDebug
./gradlew assembleDebug
./gradlew assembleRelease   # only if keystore/wakelink-upload.properties exists (gitignored, not committed) -- otherwise skip; CI never runs this target
```

There is no Android unit/instrumented test suite yet -- `lintDebug` +
`assembleDebug` are the only automated checks (see
`.github/workflows/android.yml`).

To point a local build at your own backend instead of the deployed
default, override `DEFAULT_SERVER_BASE_URL` in `app/build.gradle.kts`'s
`defaultConfig` block, or pass `-PbuildConfigField...` -- but see the
Firebase project-scoping note below before assuming that alone makes
self-hosting work.

### CI

`.github/workflows/backend.yml` and `android.yml` run on every push
touching their respective directories: backend does
build+vet+test+gofmt; android does lint+assembleDebug (using the
`.example` placeholder Firebase config, never a real one).

## Architecture

### The core trick (why this app works at all)

Android's ringer "silent" mode mutes `STREAM_RING`/`STREAM_NOTIFICATION`
only -- it does **not** mute `STREAM_ALARM`, which is why built-in alarm
clock apps always ring through silent mode with no special permission.
`android/.../alarm/AlarmForegroundService.kt` plays the wake alert on
`STREAM_ALARM` for exactly this reason. DND "Total silence"
(`INTERRUPTION_FILTER_NONE`) is the *one* mode that also mutes
`STREAM_ALARM`; that's handled by a separate, opt-in Notification Policy
Access toggle in Settings (off by default), which temporarily overrides
the interruption filter while ringing and restores it afterward --
including a self-healing restore on next app start if the process died
mid-ring (see `SettingsRepository`'s pending-override flag and
`BootCompletedReceiver`).

### Trigger flow

FCM messages are **data-only** (no `notification` payload) on purpose --
`WakeFirebaseMessagingService` must be the one deciding how to alert
(`STREAM_ALARM` + full-screen intent), not the OS's default tray
display, or the whole bypass mechanism above doesn't apply.

```
sender's app --HTTPS(Bearer api_key)--> wakelinkd --FCM Admin SDK--> receiver's app
POST /v1/pairings/{id}/trigger              data-only, high-priority        WakeFirebaseMessagingService
                                                                             -> AlarmForegroundService
                                                                             -> RingingActivity (full-screen)
receiver's app --POST /v1/triggers/{id}/ack--> wakelinkd
```

The sender's UI must not claim success before the `ack` arrives --
"sent" and "actually rang" are different things (see `ContactsScreen`'s
trigger status handling and `handleAckTrigger` in `server/internal/api/trigger.go`).

### Pairing / consent model (`docs/pairing-flow.md` has the full sequence)

Symmetric, mutual-consent, N:N -- not a fixed sender/receiver pair. One
user generates a short-lived invite code (`POST /v1/invites`), the other
deliberately enters it (`POST /v1/invites/accept`); neither side can add
the other unilaterally. Each pairing tracks wake permission
**per-direction** (`a_can_wake_b` / `b_can_wake_a` in the `pairings`
table) so either side can revoke just their own outbound permission, or
delete the pairing outright, independently. `db.Pairing.CanWake()` is the
single source of truth for permission checks -- `handleTrigger` in
`server/internal/api/trigger.go` is the only caller that matters.

### Backend package layout

`server/internal/db` is thin, hand-written SQL (no ORM) against sqlite
(`modernc.org/sqlite`, pure Go, no cgo). `server/internal/fcm` defines a
`Sender` interface with a real Firebase Admin SDK implementation and a
`MockSender` (used when `FCM_MOCK=true` and in `api_test.go`) so tests
never need real credentials or network access. `server/internal/api`
holds all HTTP handlers plus the bearer-token auth middleware
(`requireAuth` in `middleware.go`, resolves `Authorization: Bearer
<key>` by SHA-256 hash lookup -- raw keys are never stored).
`server/internal/ratelimit` re-derives cooldown/daily-cap state from the
`triggers` table on every check rather than keeping in-memory counters,
so limits survive process restarts for free at this project's scale.

### Self-hosting constraint (read before touching `DEFAULT_SERVER_BASE_URL` or FCM)

FCM is scoped **per Firebase project**. A device token registered by a
build using one `google-services.json` can only be pushed to by a
backend holding *that same project's* service-account credentials.
Pointing an official build's `DEFAULT_SERVER_BASE_URL` at a different
backend does not work unless that backend also has the original
project's credentials -- real self-hosting means building your own APK
against your own Firebase project end-to-end. Full details in
`docs/self-hosting.md`; don't "fix" this by trying to make the server
URL swap alone sufficient.

### Deployment

Default hosted instance: `wakelinkd` runs on `oci-ops` (a host in an
unrelated private home-lab repo, `server` elsewhere on this machine --
not this repo), reverse-proxied publicly at `https://wake.seolcu.com` via
Caddy on `oci-edge`. That repo's `docs/services/wake.md` has the
ops-side runbook (compose file, Caddy block, DNS). This repo's own
`server/Dockerfile` + `server/docker-compose.yml` are what get built
there -- keep them buildable standalone (no dependency on anything
outside `server/`).
