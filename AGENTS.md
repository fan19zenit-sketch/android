# Instructions for this repository

Keep the user's current scope; this repository also contains older backend material and unrelated work.

For PhotoBot cook workflow V2:

1. Read docs/photobot-cook-workflow-v2/README.md and app/AGENTS.md.
2. Specification ID: PHOTOBOT_COOK_WORKFLOW_V2_20261105. The canonical full specification is in fan19zenit-sketch/frontpad-parsers, operations/photo_platform/docs/cook_workflow_v2, branch codex/photobot-cook-workflow-v2-spec until merged.
3. Fetch/read that exact specification; do not assume documentation on another branch has loaded automatically. A full V2 task includes all S0–S8 across both repositories, not only the Android screen.
4. The active server implementation is frontpad-parsers/operations/photo_platform/apps/photo_backend. Do not implement the new server behavior in this repository's older backend copy.
5. Preserve newer commits and uncommitted user changes. Do not reset to an audit SHA.

Use isolated tests and a test backend. Do not print secrets, send production messages or install an APK on branch phones as an incidental verification step. Honor subsequent explicit user authorization for a prepared release.
