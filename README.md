# WakeLink

WakeLink rings a loud alarm on a trusted contact's phone on demand, even
if their phone is set to silent -- so if they forgot to set their own
alarm, you can still wake them up.

- **`server/`** -- the Go backend: mutual-consent pairing, per-direction
  wake permissions, and FCM-triggered wake pushes. See
  [`server/README.md`](server/README.md).
- **`android/`** -- the single Android app. Every install can both wake
  contacts and be woken by them (no separate "sender"/"receiver" app).

## How it works, in one paragraph

Android's ringer "silent" mode only mutes the ring/notification audio
streams -- it does not mute `STREAM_ALARM`, which is why a normal alarm
clock app always rings even in silent mode. WakeLink's receiving side
plays its alert on that same stream, with zero special permission needed.
Do Not Disturb's "Total silence" mode is the one thing that *also* mutes
`STREAM_ALARM` -- that's the one case handled by an explicit, opt-in
settings toggle (Notification Policy Access), not by default.

## Consent, by design

Pairing requires an explicit action on both sides (one side generates an
invite code, the other side deliberately enters it) -- nobody can be added
unilaterally. Either side can revoke wake permission, or unpair entirely,
at any time. The app's active/armed state is always shown as a visible,
persistent notification -- never hidden.

## Getting started

- Backend: [`server/README.md`](server/README.md)
- Self-hosting your own independent instance (including the Firebase
  project you'll need): [`docs/self-hosting.md`](docs/self-hosting.md)
- Android project: open `android/` in Android Studio. You'll need to
  replace the placeholder `android/app/google-services.json` with your
  own Firebase project's file before FCM will actually work.

## License

MIT -- see [`LICENSE`](LICENSE).

The Android app bundles the [Pretendard](https://github.com/orioncactus/pretendard)
font (SIL Open Font License 1.1) -- see
[`android/third_party/pretendard/LICENSE.txt`](android/third_party/pretendard/LICENSE.txt).
