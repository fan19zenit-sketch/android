# Mobile support operations

This is not the production photo backend. Do not deploy `backend/` from this repo.
See `docs/android-3.1-remote-support.md` for the security boundary and QA limits.

Run commands over SSH from `/opt/photo-mobile-support` with these paths (not secret values):

```sh
export SUPPORT_DB=/var/lib/photo-mobile-support/support.sqlite3
export SUPPORT_REGISTRY=/root/photo_chat_backend/data/devices.json
export SUPPORT_SECRET_FILE=/var/lib/photo-mobile-support/secret
venv/bin/python -m mobile_support.admin inventory
venv/bin/python -m mobile_support.admin configure --device DEVICE_UUID --json-file /root/settings.json --ttl 900
```

Example settings: `{"volume_capture":false,"retry_seconds":60}`. Only these fields
and `uploads_paused` are accepted. Pauses have maximum TTL 900 seconds, other
settings 86400 seconds. Reapplying via CLI is an explicit new change, audit logged.
Never use support to silently remove queued photos or trigger the camera.

After the signed release is independently checked and placed in the official
HTTPS downloads directory, create a release JSON containing exactly:
`package`, `version_code`, `version_name`, `min_sdk`, `url`, `sha256`.
`url` must be a direct `.apk` under `https://194-55-235-241.sslip.io/downloads/`.
Publish with `admin release --json-file /root/release.json`; remove an offer with
`admin withdraw --package com.example.photovchistotu`. Merely uploading an APK to
GitHub does not publish an in-app update. No release was offered during deployment.

`admin reset --device DEVICE_UUID` clears only the support enrollment/config, not
photos or the production registration. Use only when deliberately rebinding a
replacement/reinstalled device; existing installations cannot steal each other's binding.
Never expose inventory or the SQLite/secret backups on a public downloads path.

Daily local backups are managed by `photo-mobile-support-backup.timer`. For a
restore, stop only `photo-mobile-support.service`, retain the current state, restore
the matching SQLite AND secret from a local private archive, remove only stale WAL/SHM
files belonging to the restored support database while the service is stopped,
and restart support. Keep owner/root permissions private. Do not restart MAX or
the photo backend for a support restore. An off-server backup plan is separate.

Initial Caddy backup: `/root/photo-mobile-support-backups/20260905-113147/Caddyfile`.
For rollback, remove only the added mobile route blocks from the current Caddy
configuration, validate and reload. Do not blindly restore the full old file if
other services have changed since then. Preserve the HTTPS photo route while 3.1
phones are using it, even if diagnostics is disabled. Stopping support alone does
not stop photo upload or evaluation.
