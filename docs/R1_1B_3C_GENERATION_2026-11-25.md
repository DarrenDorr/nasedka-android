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

## Попытка 1 — HOLD, 67 PASS / 1 FAIL / 7 NOT_RUN
Commit `5f50a5b1f36bb1fc41f987eccb6b7c99ad58823f`. Build 45559829351/job 139020604270 SUCCESS, JVM 30/30 и Linux ENOSPC PASS. Android 45559829394/job 139020604568 FAIL, 36/37 ordinary PASS. ZIP 14997183482, 14996734116, 14997467969 скачаны, SHA-256 совпали с GitHub digest; 4 XML и Linux log разобраны. [Постоянный протокол](../security/evidence/R1_1B_3C1_ATTEMPT1_2026-11-25.json). Единственный failed method generationCommitAndRollbackPreserveCorrectUuid: NoSuchFileException incoming-inc.db при checkSpace/prepare второй операции (test line 395). Первый rollback и проверка old UUID/данных прошли до этой строки; вторая операция не дошла до PENDING. RestoreFileStorage.cleanup намеренно удаляет оба incoming файла после terminal phase. Ошибка fixture, не доказательство сбоя записи UUID. Четыре остальных новых метода PASS. Шесть реальных process-death и Android ENOSPC не стартовали из-за Gradle failure, обязательные gates отсутствующего evidence FAIL. Не засчитывать их за выполненные.

## Попытка 2 — исправление теста, NOT_RUN
В generationCommitAndRollbackPreserveCorrectUuid после rollback проверяются NONE и отсутствие обоих incoming файлов, заново создаются две synthetic SQLite fixtures для второй независимой операции, сохраняются все старые assertions; после успешного commit дополнительно проверяются NONE и удаление inputs. Production codec/storage/coordinator, security requirements и gates не изменены. 75 checks должны быть подтверждены повторно. Не переходить к -3c-2 до PASS; no merge or production wiring.
