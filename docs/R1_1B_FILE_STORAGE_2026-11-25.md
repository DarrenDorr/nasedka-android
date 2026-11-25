# R1.1b-2b — изолированная замена файлов и настроек

Дата 25.11.2026. PR #8. База 1521ecd3754f495e91575de880f14de9605e2244, 17/17 JVM PASS на 5cbf607, [evidence](../security/evidence/R1_1B_JVM_2026-11-25.json). Код НЕ подключён к BackupStore или Application.

## Реализация и контракт

- RestoreFileStorage реализует Storage протокола с выделенным private DB root/restore-v2. Входные SQLite-файлы должны быть предварительно проверены по схеме, целостности и relations, handles закрыты, sidecars отсутствуют. Это обязанности будущего интеграционного слоя; данный adapter не валидирует бизнес-данные и не создаёт входные SQLite.
- До PENDING сохраняет durable old/new copies двух файлов и canonical settings. SHA-256 шести файлов регистрируется в journal. Все три источника проверяются перед каждой заменой; copy→force→atomic rename→directory force. Оригиналы не перемещаются в качестве единственного rollback источника. После PENDING восстановление из старых копий повторяемо. Cleanup только terminal и после проверки live поколения.
- RestoreSettingsPort кодирует 5 переносимых полей + 4 scheduler timestamps, сохраняет отсутствие ключа, не затрагивает прочие prefs; strict encoding, checked commit(), readback. Target settings формирует будущий caller; generation guard и replay scheduled actions относятся к -3. Не утверждать защиту от всех устаревших уведомлений.
- Legacy .nasedka_restore_pending блокирует v2 до отдельного recovery; старые файлы не удаляются. Не считать совместимость legacy recovery завершённой.
- Требует внешнего эксклюзивного barrier на всю операцию и recovery до открытия DB. Не вызывает Android API на главном потоке по собственной инициативе. Нет автоматического fallback к non-atomic replace. Android directory force support ещё не доказана.
- Используются только фиксированные reserved имена, private sandbox; fail closed на nonregular paths и SQLite sidecars. Orphan work files могут заменяться при новой PREPARE только при отсутствии v2/legacy journal. Недостаток места приводит к IOException, но предварительная оценка ENOSPC и реальные fault tests ещё открыты.

## Испытания Android (NOT_RUN)
- restoreTwoSqliteFilesAndSettings
- recoverAfterOneFileReplacement
- failedSettingsWriteAfterBothDatabasesRecovers
- corruptRollbackSourceKeepsJournalAndOtherCopies
- legacyMarkerAndSidecarsBlockBeforeMutation

В тестах настоящие закрытые SQLite-файлы с синтетической таблицей sample и отдельные SharedPreferences в cache/temp root; не открывают реальные incuba/farm. Испытание отказа настроек — синтетическая IOException, не фактический false commit от ОС. Пересоздание adapter моделирует restart, не реальный process death. Workflow требует все 10 R1.1a + 5 новых методов, 0 failure/error/skip. Сборка и Android evidence ожидаются.

## Открытые условия

Полная проверка backup/SAF, настоящие схемы и checkpoint, точная проверка свободного места, повторный отказ во время rollback/cleanup, ошибки fsync, Android minSdk/physical device, legacy recovery, generation ID и barrier не завершены. R1-F01/F02 не закрыты. Не подключать и не merge до совместного подтверждения R1.1b-2/-3, затем -4. Утверждённые UI/PNG, SQLite v2 и внешний backup JSON v1 не изменены.

## Контрольная попытка 1 — FAIL, 25.11.2026
Commit `27483ba7f0bd8f04b4759ebb134e1dfa2a37325b`. [Build 45538262692](https://github.com/DarrenDorr/nasedka-android/actions/runs/45538262692), job 138950158636 SUCCESS, в том числе exact gate 17 JVM. [Android 45538262704](https://github.com/DarrenDorr/nasedka-android/actions/runs/45538262704), job 138950136582: 15 executed, 11 PASS, 4 FAIL, 0 errors/skipped. XML gate SKIPPED из-за gradle failure. ZIP artifact 14987463996 проверен: 5314 bytes, SHA-256 290b2223e116599e34ef28e489c1e5640188736a24fd9c1b0eecb8db80f5058e; XML SHA-256 7b5a6f415b6fc764dc5475b97203388b5b1d6f338525b83dcd7299eb27c26109. 10 BackupReliabilityTest PASS; legacyMarkerAndSidecarsBlockBeforeMutation PASS. Три случая restoreTwoSqliteFilesAndSettings, recoverAfterOneFileReplacement, corruptRollbackSourceKeepsJournalAndOtherCopies — IOException SQLite sidecar requires checkpoint and close в prepare:64, до PENDING. failedSettingsWriteAfterBothDatabasesRecovers — ожидает PENDING, получает NONE (ранний отказ; settings fault не достигнут). Конкретный вид sidecar прежнее сообщение не указало, не угадывать.

## Попытка 2 — корректировка и расширение испытаний
- RestoreSqlitePreparation: отдельный handoff на существующем файле, WAL checkpoint/TRUNCATE, DELETE journal mode, integrity check, закрытие, затем проверка остаточных журналов. Только при исключительном доступе, после успешного checkpoint и close разрешено убрать пустой WAL/rollback journal и rebuildable SHM; непустой WAL/rollback journal не удаляется. fsync файла и каталога. Не вызывать при незавершённом restore. Adapter сохраняет строгий независимый отказ при любых sidecars.
- Test fixture использует preparation, fault settings теперь явно проверяет достижение нужной точки. Добавлены diagnostics suffix, консервативная оценка места (старые/новые копии, крупнейший temp, настройки и резерв 1 МиБ); не резервирует пространство и не гарантирует отсутствие ENOSPC позднее.
- 6 дополнительных тестов: preparedWalPreservesCommittedRows, missingSqliteIsNotCreatedByPreparation, insufficientSpaceRejectsBeforeJournalOrLiveChanges, settingsFailureAfterWriteStillRollsBack, repeatedRecoveryAfterPartialRollback, committedRecoveryAfterPartialCleanupRetainsNewData. Включают реальные SQLite WAL/файлы, искусственный недостаток места и неоднозначный settings write, повторный rollback/cleanup; не являются настоящим power-loss или process kill. Всего 11 storage + 10 regression Android; 17 JVM без изменений. Новая проверка NOT_RUN.
- Legacy marker пока только блокируется: автоматическая миграция журнала не доказана. Требуется отдельно безопасное recovery, с учётом старой последовательности rename и возможного рассогласования; не удалять старый marker или copies для обхода. Настоящие ENOSPC/IO failure, повторное прерывание процесса, подключение к production остаются OPEN. Пользователь прямо требует завершить -2b перед -3; gate HOLD.
