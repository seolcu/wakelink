# Self-hosting WakeLink

WakeLink supports two ways to run:

1. **Use the default hosted instance** -- just install the official
   Play Store build. Nothing to configure.
2. **Run your own fully independent instance** -- described below.

## The one constraint that shapes everything here

Firebase Cloud Messaging (FCM) is scoped **per Firebase project**. A device
token registered by an app built against Firebase Project A can only be
pushed to by a backend holding Project A's service-account credentials.
Google does not let an unrelated backend send to another project's tokens.

The practical consequence: **you cannot self-host by just pointing the
official Play Store app at your own server URL.** The official app's FCM
tokens belong to the maintainer's Firebase project, and your backend has
no credentials for that project (nor should it -- that would be a serious
security boundary violation).

Real self-hosting means **building your own independent copy of the app**,
end to end:

1. Create your own Firebase project (console.firebase.google.com), add an
   Android app to it with package name `app.wakelink.android` (or your own
   fork's applicationId), and download its `google-services.json`.
2. Replace `android/app/google-services.json` (currently a placeholder --
   see the `_comment` field in that file) with your downloaded one.
3. In the same Firebase project, generate a service-account key
   (Project Settings -> Service Accounts -> Generate new private key).
   Keep this file out of git.
4. Run the backend against that key:
   ```bash
   cd server
   cp config.example.env .env
   # edit .env: point FCM_SERVICE_ACCOUNT_PATH at your downloaded key
   docker compose up -d --build
   ```
5. Build and sign your own APK from `android/` (Android Studio, or
   `./gradlew assembleRelease`) using this now-real `google-services.json`,
   and optionally override `DEFAULT_SERVER_BASE_URL` in
   `android/app/build.gradle.kts` to point at your own backend's public
   URL instead of the maintainer's default. Also change `UPDATE_REPO`
   there to your own fork's `owner/repo` (or `""` to turn the in-app
   updater off), and set `APK_DOWNLOAD_URL` in the server's `.env` --
   otherwise your users get offered the maintainer's releases, which
   their phones will refuse to install over your differently-signed APK.
6. Install that APK on both your and your contact's phones (sideload, or
   publish it yourself under your own Play Store listing / F-Droid, etc.).

From that point on, your instance is completely independent of the
maintainer's -- different Firebase project, different backend, different
data. There is no shared state between forks.

## Reverse proxy / TLS

`server/docker-compose.yml` binds the backend to `127.0.0.1:8080` only.
Put any reverse proxy you like in front of it for real public HTTPS
exposure (Caddy, nginx, Tailscale `serve`, Cloudflare Tunnel, ...) --
WakeLink's backend itself doesn't terminate TLS.

## Local development without any of this

If you just want to hack on the backend without a real Firebase project:

```bash
cd server
FCM_MOCK=true go run ./cmd/wakelinkd
```

Pushes are logged instead of sent -- see `internal/fcm/mock.go`.
