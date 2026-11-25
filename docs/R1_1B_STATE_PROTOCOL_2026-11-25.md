# R1.1b-1 — модель протокола и проверка отказов

Дата 25.11.2026. PR #8, базовый head c5937ee288d8017688e7fe3a58cf40e1b461ed04. R1.1a интегрирован в main 823e79ac63faca0752c785bdad696a10dd42477c, Android build run 45534286997 SUCCESS. Архитектура: [R1.1b-0](R1_1B_RESTORE_ARCHITECTURE_2026-11-25.md).

## Реализация

- `RestoreProtocol` — Java state machine с интерфейсом Storage; не зависит от Android и не вызывается текущим BackupStore. Единственный действующий алгоритм восстановления пока прежний. Интерфейс описывает обязательные контракты atomic/durable phase publication, проверок и идемпотентности.
- NONE → PENDING → COMMITTED → NONE; при recovery из PENDING: PENDING → ROLLED_BACK → NONE. Отдельная durable фаза ROLLED_BACK — уточнение архитектуры: нельзя удалять последние rollback copies, оставив journal PENDING, иначе повторный crash во время cleanup лишит recovery источника.
- До COMMITTED восстановление возвращает старое поколение, после COMMITTED — сохраняет новое. Повторный recovery после терминального состояния проверяет соответствующее поколение и повторяет cleanup. NONE не означает, что предыдущая попытка успешно завершилась.
- Ошибка операции/публикации не интерпретируется автоматически как rollback: Storage обязан читать durable phase, а вызывающий код обязан удерживать барьер и закрывать доступ, пока recovery не завершён. Corrupt journal / missing copies / verify failure остаются ошибками, копии не стираются.
- PREPARE не меняет live данные. Orphans до PENDING требуют безопасной обработки в реальном adapter; модель намеренно не удаляет произвольные файлы. Схемы SQLite v2, backup JSON v1, UI, manifest не менялись.

## Тесты (JVM, модель)

| Метод | Покрытие |
|---|---|
| successfulRestoreCommitsAllThreeAndClearsJournal | Все три составляющие нового поколения |
| beforeAndAfterEveryRestoreBoundaryRecoversCorrectGeneration | 10 точек × 2 положения = 20 сценариев (prepare, marker, 3 apply, verify, commit, cleanup 2 частей, clear) |
| repeatedInterruptionDuringRollbackIsRecoverable | 8 точек × 2 положения = 16 сценариев (3 rollback, verify, rolled_back, cleanup 2 частей, clear) |
| settingsCommitFailureDoesNotCommitDatabases | Ошибка до изменения prefs, откат обеих БД |
| pendingRestoreRejectsAnotherRestoreWithoutWriting | Запрет последовательного повторного restore при PENDING, не тест конкурентности |
| corruptJournalFailsClosed | Ошибка чтения journal без мутации |
| missingRollbackCopiesRemainPending | Невозможно восстановить: журнал остаётся |
| corruptCommittedGenerationDoesNotCleanupOrRollback | Не принимать повреждённое новое поколение |
| committedCleanupFailureNeverRollsBack | Повторная очистка после COMMITTED |

Статус на момент записи: код и тесты созданы, CI/evidence NOT_RUN. 36 вариантов failure injection входят в два метода, не считать их 36 независимыми JUnit-тестами. Модель имитирует до/после эффектов через IOException и новый экземпляр RestoreProtocol; не использует реальные SQLite, файлы, SharedPreferences, Android process kill, fsync или power-loss. Никакой вывод о полном B02–B05, B08 или реальном APK update не допускается. Набор JUnit должен быть исполнен существующим testGithubDebugUnitTest. Для evidence проверить отчёт/лог, не только зелёный build.

## Следующий шаг

После подтверждённого PASS модели — R1.1b-2: реализация file/prefs adapter с durable journal и старым marker, проверки повторного восстановления с реальными файлами, ограничений места и ошибок записи. R1.1b-3: единый barrier + audit всех точек доступа, защита stale notification. До совместного подтверждения не подключать частичную реализацию к production/не объединять PR #8. Существующий issue #5 открыт.
