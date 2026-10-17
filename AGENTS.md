# AGENTS.md

This file provides guidance to coding agents when working with code in this repository.

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

Go isn't on the system `PATH` on this machine; a Go toolchain (1.26,
satisfies `go 1.25.0`) lives at `~/.local/share/go`:
`export PATH=~/.local/share/go/bin:$PATH`.

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

Gradle here (AGP 9.4.1 / Gradle 9.8.0, built-in Kotlin -- there is no
`kotlin-android` plugin) needs a **full JDK 17+** (with `javac`). The
system `/usr/lib/jvm/java-25-openjdk` on this machine is runtime-only and
fails with "does not provide the required capabilities: [JAVA_COMPILER]".
A working JDK 21 is at `~/.local/share/jdks/jdk-21.0.12.1+1` and the SDK at
`~/.local/share/android-sdk` (both installed outside the repo, under
`$XDG_DATA_HOME`, not `~/android-sdk` or similar).

```bash
export JAVA_HOME=~/.local/share/jdks/jdk-21.0.12.1+1   # any full JDK 17+ works
export ANDROID_HOME=~/.local/share/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
cd android

# Needs app/google-services.json to exist -- it's gitignored (real one
# has a live Firebase API key). Fresh checkout: use the placeholder.
cp app/google-services.json.example app/google-services.json

./gradlew lintDebug
./gradlew testDebugUnitTest   # single class: ./gradlew testDebugUnitTest --tests '*InviteLinkTest'
./gradlew assembleDebug
./gradlew assembleRelease   # only if keystore/wakelink-upload.properties exists (gitignored, not committed) -- otherwise skip; CI never runs this target
```

`app/src/test` holds plain-JVM unit tests for the pure logic (invite-link
parsing, API error mapping, ack-status judgement) -- keep that logic free
of Android framework types so it stays testable there. Such logic returns
string resource ids (`@StringRes Int`, or `ui/UiText` when it needs format
args) rather than text; the UI resolves them.

The app is localized: all user-facing text lives in
`res/values/strings.xml` (English -- the default and the fallback for
unsupported languages) and `res/values-ko/strings.xml` (Korean). Never
hardcode UI text in Kotlin; add every new string to both files.
Korean tone: buttons, labels, statuses, chips and settings/setup
explanations are short noun phrases ("서로 깨우기 가능", "끄려면 잠금 해제
필요", "~ 중", "~됨"); only text that speaks to the user -- dialog bodies,
errors, warnings, empty states -- uses 해요체, kept to one or two short
sentences. Don't end every line in ~해요.
`generateLocaleConfig` exposes both languages in Android 13+'s per-app
language setting. There is no
instrumented (on-device) test suite; anything touching audio, DND,
notifications or the lock screen has to be checked on a real phone.

To point a local build at your own backend instead of the deployed
default, override `DEFAULT_SERVER_BASE_URL` in `app/build.gradle.kts`'s
`defaultConfig` block, or pass `-PbuildConfigField...` -- but see the
Firebase project-scoping note below before assuming that alone makes
self-hosting work.

### Releasing the APK

Bump `versionCode` and `versionName` in `android/app/build.gradle.kts`,
commit and push, connect a phone over adb, then run
`scripts/release-android.sh [notes.md]`. It refuses to run on a
dirty/unpushed tree or an existing tag, builds `assembleRelease` with the
upload key, installs and launches that exact APK on the phone and aborts
on a crash (R8 minification breaks reflection-based libraries in ways
debug builds never show -- 0.2.0-0.2.2 crashed on launch from WorkManager's
Room database; see `proguard-rules.pro`), and only then publishes GitHub release
`v<versionName>` with the APK attached as exactly `wakelink.apk`. The
in-app updater (`android/.../update/AppUpdater.kt`) reads
`releases/latest` of `BuildConfig.UPDATE_REPO`, compares the tag against
`VERSION_NAME`, and hands that asset to the system installer;
`https://wake.seolcu.com/download` (`APK_DOWNLOAD_URL`, `GET /download`)
redirects to `releases/latest/download/wakelink.apk`. Release notes are
bilingual, English first: a `## English` section, then `## 한국어` with the
same content -- the update dialog (`update/ReleaseNotes.kt`) splits on
exactly those headings, renders the markdown subset we use (headings,
bullets, quotes, `**bold**`) and shows the app's language first.
`UpdateCheckWorker` also checks every 12 hours in the background and posts
a one-time "new version" notification, for people who rarely open the app. Always sign with the
same key -- Android rejects an update signed differently, and a debug
build can't be updated to a release build (uninstall first).

The gitignored release secrets are kept in the maintainer's Proton Drive
under `/my-files/wakelink-secrets/`, laid out at the same paths as in the
repo: `android/app/google-services.json`,
`android/keystore/wakelink-upload.{jks,properties}` and
`server/secrets/firebase-service-account.json` (the last is only for
real-FCM testing, not releases). To release from another machine, from
the repo root:

```bash
proton-drive auth login
proton-drive fs download -d merge -f replace /my-files/wakelink-secrets/android /my-files/wakelink-secrets/server .
```

If the keystore or Firebase config ever changes, upload the new copy
there too (`proton-drive fs upload -f replace <file> <drive folder>`).

### CI

`.github/workflows/backend.yml` and `android.yml` run on every push
touching their respective directories: backend does
build+vet+test+gofmt; android does lint+unit tests+assembleDebug (using the
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
receiver's app --POST /v1/triggers/{id}/ack--> wakelinkd       (it rang)
receiver's app --POST /v1/triggers/{id}/dismiss--> wakelinkd   (they stopped it: "they're up")
```

The sender's UI must not claim success before the `ack` arrives --
"sent" and "actually rang" are different things. `ContactsScreen` polls
`GET /v1/triggers` after a trigger until the ack shows up (judged by
`ackCheckFor` in `contacts/TriggerAck.kt`), and the receiver only acks
once `MediaPlayer` is actually playing (see `handleAckTrigger` in
`server/internal/api/trigger.go`). If FCM reports the recipient's token
as unregistered, the server clears it so later triggers fail fast with 422.

After the ack, the card keeps polling (only while the screen is open, ~6
minutes) for `dismissed_at`. The receiver sends `dismiss` for every wake
behind the current ring when the *user* stops it -- every
`ACTION_STOP` is user-initiated (Stop button, notification action,
switching receiving off); the 5-minute auto-stop calls `stopRinging()`
directly and must never dismiss, or "they're up" would lie.

`AlarmForegroundService.ringingFrom` (a `StateFlow`) is the single
"is it ringing, and who for" signal; `RingingActivity` closes itself when
it goes null, whichever way the alarm ended (Stop action, 5-minute
auto-stop, disarmed). The DND startup self-heal runs in
`WakeLinkApplication.onCreate` as well as on boot
(`data/DndRestore.kt`), and the service joins it before applying a new
override so the two can't race.

If the push can't start the foreground service (Android 12+ background
start restrictions, vendor battery savers), `WakeFirebaseMessagingService`
falls back to a full-screen notification whose `RingingActivity` starts
the service from the foreground -- a wake must never fail silently.

### Direct Boot (ringing before the first unlock after a reboot)

The whole ring path (`WakeFirebaseMessagingService`,
`AlarmForegroundService`, `RingingActivity`, `StopAlarmReceiver`,
`BootCompletedReceiver`) is `directBootAware`, so an overnight
auto-restart doesn't silence the morning. Consequences to respect:

- `SettingsRepository` lives in **device-protected** SharedPreferences
  (migrated once from the old DataStore). `TokenStore`, `DeviceKeys`,
  `MediaKeyStore` and WorkManager are credential-protected: check
  `isUserUnlocked()` before touching them on anything that can run locked
  (`WakeLinkApplication.onCreate` splits work into locked-safe and
  `onUserUnlocked()`).
- An alarm that rings while locked (or offline) queues its ack (and
  dismissal) in `SettingsRepository`; `AckSender.flushPending` sends them on
  `BOOT_COMPLETED` (= first unlock) or the next app start.
- Sealed wake details can't be opened while locked, so such a wake rings
  with the receiver's own sound and an "unknown sender" name.

### Sounds, pictures and volume

- The receiver owns loudness: `volumePercent` sets `STREAM_ALARM` for the
  ring and restores it after; optional crescendo ramps the player volume.
- Built-in sounds are synthesized by `scripts/gen-ringtones.py` into
  `res/raw/ringtone_<name>.ogg` (Ogg Vorbis: plays on every supported
  version). Preset names are duplicated in `sounds/SoundRef.kt`
  (`PRESET_NAMES`) and `server/internal/api/media.go` (`presetSounds`).
- Sound keys: `device`, `preset:<name>`, `custom:<id>` (sender side),
  `custom:<id>:<sha256>` (in a wake). `chooseSound()` applies the
  receiver's `SoundPolicy`; `AlarmForegroundService.startPlayback` then
  falls back chosen -> own -> device tones -> bundled beep, so a missing or
  undownloadable sound never means silence.
- Senders can upload voice recordings (`RecordDialog`, <= 15 s AAC) and
  audio files (<= 2 MB), and pictures (`images/`, photos downscaled to
  1280 px JPEG, GIFs kept as-is up to 3 MB). The picture loads alongside
  the sound and shows on `RingingActivity` when ready (`ringingImage`).
  Receivers can turn pictures off (`showImages`).
- A wake's message and picture outlive the alarm: `AlarmForegroundService`
  saves them to `data/ReceivedWakes` (device-protected, newest 30) and,
  once the ring ends, posts a quiet notification per wake
  (`inbox/WakeMessageNotifier`, channel `wake_messages`) that opens the
  "received messages" screen (`inbox/InboxScreen`, top bar of
  `ContactsScreen`). Entries keep the image ref + content key, so a picture
  the cache has since dropped is downloaded again.
- `MediaCache` (`SoundCache`, `ImageCache`) keeps decrypted copies in
  device-protected storage, named by the server-side SHA-256; it verifies
  that hash (and the AES-GCM tag) on download.

### End-to-end encryption

Each device has an HPKE key pair (`crypto/DeviceKeys.kt`, Tink, private
half in credential-protected storage wrapped by an Android Keystore key);
the public half is published via `PUT /v1/me/public-key` and handed to
contacts in `GET /v1/pairings` (`other_public_key`).

- Uploads are encrypted on the phone with a random per-upload key
  (`MediaCrypto`, "WLE1" + AES-256-GCM); the server stores ciphertext only
  and the key stays in `MediaKeyStore`. Losing it (reinstall) makes that
  upload unusable on this phone.
- `WakeComposer` seals the sender's name, sound, picture, their content
  keys and the optional one-off message (<= 100 chars, shown on
  `RingingActivity`) to the recipient's key (`SealedWake`) and sends only `sealed`; the
  server relays it in the push without being able to read it, and the push
  then carries no plaintext name/sound/image. Contacts whose app has no key
  get a plain wake with none of the extras.
- Known limit: keys are distributed by the server (trust on first use), so
  a malicious server could substitute one; there is no key verification UI.

### Readiness

There is no always-on notification (it was dropped in 0.3.1 as noise; the
app deletes the old channel on start). Whether this phone can be woken is
shown, with a switch, at the top of `ContactsScreen` instead.

That "receiving wake-ups" switch (`armed`) is stored on the phone (the
alarm path reads it, even before unlock) **and** mirrored to the server via
`ArmedSync` (`PUT /v1/me/accepting`, re-sent on every unlocked start).
Always change it through `ArmedSync.set`, which also silences a wake that's
ringing at the time. Self-test alarms (trigger id < 0) ignore the switch.

Switching it off asks for how long (`status/PauseWakeDialog.kt`: hours, or
until switched back on), because a forgotten switch silently defeats the
app. A pause's end time is honored in three independent places, so any one
of them failing doesn't strand the user: `SettingsRepository.armed` counts an
ended pause as on, the server does too (`resume_at` on `PUT
/v1/me/accepting`, stored as `users.accepting_resumes_at`, checked via
`User.AcceptsWakesAt`), and `ArmedResumeReceiver` (an inexact AlarmManager
alarm, re-set by `ArmedSync.resumeIfDue` on every process start) switches it
back on for real.

Each pairing also has the receiver's per-contact consent
(`a_accepts_from_b` / `b_accepts_from_a`, set via `PATCH
/v1/pairings/{id}` `i_accept_from_them` by the person being woken, checked
with `Pairing.Accepts()`), distinct from the waker's `CanWake` permission.
Contacts see both switches folded into one `they_accept_wakes` flag (so a
sender can't tell "paused for everyone" from "blocked just me"), and
`handleTrigger` refuses wakes with 409 while either is off -- otherwise a
wake would be dropped on the receiver while the sender waits for an ack.

`status/SetupScreen.kt` is the "get ready" checklist (notifications,
full-screen alarms, battery optimization, background restriction / Samsung
sleeping apps -- detected via `isBackgroundRestricted` and the restricted
standby bucket -- and DND access when "ring through Total silence" is on).
MainActivity opens it after registering and on any launch where
`DeviceReadiness.allReady` is false; `ContactsScreen` additionally pops a
dialog when a problem appears while the app is in use. Prefer dialogs and
screens over banners for anything that would make a wake fail.

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

A `wakelink://invite/CODE` or `https://<server>/invite/CODE` link only
*pre-fills* the code on the pairing screen
(`MainActivity.pendingInviteCode`, parsed by `parseInviteCode`); never make
it auto-accept, or a link alone could pair someone without their explicit
action. The app shares the https form (`inviteWebLink`).

`/v1/me` (GET/PATCH/DELETE) covers the account: display name (validated by
`normalizeDisplayName`, max 40 runes -- mirrored by
`MAX_DISPLAY_NAME_LENGTH` in the app) and full account deletion. `POST
/v1/me/rotate-key` swaps the caller's API key in place (old key dies
immediately, pairings survive). `GET /version` (public) exposes build
info; `GET /v1/metrics` (any authenticated user) exposes instance-wide
in-memory trigger counters (`internal/metrics`, reset on restart).

None of the foreign keys cascade and `foreign_keys` is on, so any delete
must remove children first, in a transaction (`DeletePairing`,
`DeleteUser`). Expired invites, and wake history older than
`TRIGGER_RETENTION_DAYS` (default 90), are purged hourly by `main.go`.

### Backend package layout

`server/internal/db` is thin, hand-written SQL (no ORM) against sqlite
(`modernc.org/sqlite`, pure Go, no cgo). `server/internal/fcm` defines a
`Sender` interface with a real Firebase Admin SDK implementation and a
`MockSender` (used when `FCM_MOCK=true` and in `api_test.go`) so tests
never need real credentials or network access. `server/internal/api`
holds all HTTP handlers (uploads in `media.go`: one generic set of
handlers per `mediaCategory`, sounds and images) plus the bearer-token auth middleware
(`requireAuth` in `middleware.go`, resolves `Authorization: Bearer
<key>` by SHA-256 hash lookup -- raw keys are never stored).
`server/internal/ratelimit` re-derives cooldown/daily-cap state from the
`triggers` table on every check rather than keeping in-memory counters,
so limits survive process restarts for free at this project's scale.
`ratelimit.Window` is the in-memory exception: it throttles unauthenticated
`/v1/register` per client IP (`REGISTER_LIMIT_PER_HOUR`, default 10). The
client IP comes only from the proxy-set `X-Forwarded-For` (the
`forwardedFor` middleware); never trust `X-Real-IP`/`True-Client-IP`,
which clients can set freely.

`server/internal/web` is the public website, embedded with `go:embed`
(no build step), in Korean and English: `templates/<lang>/*.html` share
`templates/base.html`, and `lang.go` picks the language per request
(`?lang=` link, then the `lang` cookie it sets, then `Accept-Language`;
English for anything else). Keep the two languages' pages in sync, and
their app mockups in sync with the app's real strings. It serves the
landing page at `/`, the `/invite/{code}`
page behind shared invite links (format check only, never a DB lookup),
`/assets/*`, `robots.txt`, and `/.well-known/assetlinks.json`, which
vouches for the release key (`ANDROID_CERT_SHA256`, defaults to the
upstream key) so the app's `autoVerify` intent filter for
`https://<inviteHost>/invite/` opens links directly. Signing with a new key
means updating that fingerprint too. Pages send a strict CSP
(`script-src 'self'`, no inline styles/scripts -- keep it that way).
`assets/og.png` (link-preview image) and the PNG icons are rendered from
`assets/logo.svg`; regenerate them if the mark changes.

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
