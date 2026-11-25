# R1.1b-3c-1 — постоянное поколение в canonical settings
25.11.2026. Base bc9effa7fac03bae1b8636b524cdf3571503b780; -3b 70/70 PASS ([evidence](../security/evidence/R1_1B_3B_FINAL_2026-11-25.json)). This iteration begins -3c; no production wiring.

## Internal compatibility
Historical magic 0x4e535032 (NSP2) remains byte-identical for absent generation, including existing journal hashes. New magic 0x4e535033 (NSP3) adds canonical lowercase UUID via writeUTF after existing nine fields. Strict canonical re-encode check, reject unknown version, malformed/truncated/extra data before preference edit. Old snapshot explicitly removes generation; after recovery of such a snapshot, startup must initialize and verify a new generation before READY. Unknown historical marker ambiguity still blocks. No change to public backup JSON v1 or SQLite schemas.

target(validatedPublicSettings, previous) copies only five validated portable fields (caller normalizes old backup default nest_checks first), generates fresh UUID distinct from previous and ignores imported generation and scheduler keys. New target contains absent watermarks by design; unrelated keys untouched. This factory is not the backup validator. read/write include managed generation in the existing settings hash and journal rollback/commit. write checks commit boolean and canonical readback. **A successful readback is not proof of physical disk durability after failed commit.** Startup initialization and same-process ambiguous commit recovery must be designed and verified before use; do not mark READY from cached read alone. No direct access to Android's private implementation or deletion of prefs files.

## Tests NOT_RUN
- generationCommitAndRollbackPreserveCorrectUuid
- legacySettingsEncodingRemainsByteIdenticalAndRemovesGeneration
- malformedGenerationAndUnknownVersionRejectWithoutMutation
- targetGenerationIgnoresImportedUuidAndSchedulerState
- generationTokenChangesOnlyAfterVerifiedRestoreOutcome
Six existing actual process death/restart cases and Android ENOSPC now use distinct old/new persistent UUID in real SharedPreferences, checked via exact canonical bytes after independent restart. The previous 22 storage tests remain (legacy format fixtures retained); Linux probe remains independent. 30 JVM + 37 Android + 6 process cases + 2 ENOSPC = 75 checks. No evidence until CI runs.

## Remaining -3c
-3c-2: isolated startup/recovery policy (fresh/partial/legacy/v2 conflicts), checked durable initialization and failed/ambiguous commit handling; generation-qualified notification identities and guard, startup admission. Do not integrate runtime callbacks until coverage matrix A01–A13, no partial barrier. R1-F01/F02 remain OPEN. No merge until joint -3/-4. Physical power loss and physical device remain unproven.
