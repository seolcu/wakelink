# WakeLink server (`wakelinkd`)

The backend for [WakeLink](../README.md): pairing (mutual-consent invite
codes), per-direction wake permissions, and FCM-triggered wake pushes.

Full self-hosting instructions (including the Firebase project you'll need
to create) live in [`/docs/self-hosting.md`](../docs/self-hosting.md).

## Quick start (local dev, no real Firebase credentials)

```bash
cp config.example.env .env
echo "FCM_MOCK=true" >> .env
go run ./cmd/wakelinkd
```

## Tests

```bash
go test ./...
```
