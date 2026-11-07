# Photo application instructions

For cook workflow V2, read ../docs/photobot-cook-workflow-v2/README.md and ANDROID.md, then the canonical TASK.md and DATA_CONTRACT.md in frontpad-parsers. Follow the complete S0–S8 task and update its PROGRESS.md.

## Required behavior

- Target "Фото в чистоту", clean flavor, com.example.photovchistotu. Shared source also serves legacy; preserve its behavior.
- Confirm the branch roster once per day after 11:00, checking resume and capture as well as first launch. Yesterday is a hint, not today's confirmation.
- Select multiple cooks/operators and one main cook. Calculate the count from selections.
- Show working cooks and the next-photo cook. A secondary selection applies to one locally saved photo only; no series mode.
- Reserve immutable capture metadata before CameraX; reset the one-shot after file and Room persistence, not after HTTP/MAX delivery. Errors and repeated Bluetooth events must not misattribute photos.
- Both foreground service and worker upload the saved photoContext using the same clientUploadId. A new day/roster must not relabel queued photos.
- Preserve history and queue with an explicit Room migration. Never use destructive migration or uninstall as an upgrade strategy.
- Require backend capability and metadata acknowledgement. A successful image upload alone does not prove that the server stored the cook.
- Keep provisional offline identities explicit; do not guess full names or merge people only by name.

## Verification and completion

Use controlled clock/camera/network boundaries and real persistence behavior where it matters. Verify migration, one-shot, retries, both upload paths and legacy compatibility. Report actual build/test results and unavailable SDK/signing inputs honestly.

The full task includes backend integration, QC, operator messages and reporting. Finish all accessible work without asking after every local stage; missing production IDs/lesson links are configuration inputs, not a reason to stop implementing locally. Production actions require their own user instruction.
