# R1.1b-2b — аудит долговечности журнала
25.11.2026. Основа fe2da619baaa03a6af2543cdfa2d3831439f39c5, 54/54 PASS, [evidence](../security/evidence/R1_1B_ANDROID_ENOSPC_2026-11-25.json). Предварительное замечание PR #8 issuecomment-7153038866.

## R1B-A01 — terminal visible != durable
В RestoreJournal.publish сначала atomic rename, затем directory force. Если force бросает IOException, текущий процесс может увидеть COMMITTED/ROLLED_BACK, хотя запись каталога не подтверждена. Старый adapter при recovery мог начать cleanup только по видимому phase. При последующем power loss теоретически возможен возврат PENDING при уже удалённых rollback sources. Риск по анализу, не воспроизведённая аппаратная потеря питания.
Исправление: confirmTerminal(expected) сверяет UUID, phase, hashes, force файла и каталога, повторно сверяет запись; вызывается до первого удаления источников в cleanup. Повторный отказ оставляет копии. Обязателен внешний barrier. Не ослабляет atomic move.

## R1B-A02 — ambiguous clear
После unlink с неуспешным directory force видимое NONE не является подтверждённой долговечной записью отсутствия. До допуска дальнейших операций readPhase() синхронизирует DB root (включая отсутствие legacy marker и запись work dir), вызывает confirmAbsent с повторным чтением; исключение остаётся блокером. Не обещает recovery неизвестного исторического состояния legacy без marker.

## Тесты (NOT_RUN)
- ambiguousCommittedForceRetainsCopiesUntilResynced
- ambiguousRolledBackForceRetainsCopiesUntilResynced
- ambiguousJournalRemovalRequiresDurableAbsence
Все тесты Android используют реальные файлы и отдельные prefs; package-private DirectorySync seam выдаёт IOException вместо конкретного вызова force, прочие вызовы выполняют оригинальную синхронизацию. Это моделирование ошибки fsync, не отказ физического контроллера. Tests assert that injected call actually reached, exact exception, copies retained, repeated recovery and expected generation. Старый Linux probe остаётся совместимым с обычным конструктором.

## Gate
17 JVM + 32 Android + 6 process cases + 2 ENOSPC = 57. Новая редакция NOT_RUN. После подтверждения провести итоговый audit, не считать hardware power loss доказанным. Production code modules changed only within isolated adapter and journal, not BackupStore/Application or UI. No merge or -3 until current gate PASS.

## Итоговая проверка 25.11.2026 — PASS_R1_1B_2B_ISOLATED_SCOPE
Commit `3a0a2ee395ddc677e8a0a1aa1c21ad46703fd60a`, build 45548741852 / job 138984545222 SUCCESS, Android 45548741811 / job 138984544963 SUCCESS. Три ZIP SHA-256 подтверждены, XML 17 JVM + 32 Android без ошибок и пропусков; JSON 6 independent process restarts и 2 actual bounded ENOSPC PASS. Все три A01/A02 methods PASS. [Evidence](../security/evidence/R1_1B_2B_FINAL_2026-11-25.json), [закрытие и ограничения](R1_1B_2B_CLOSURE_2026-11-25.md). Исторические HOLD/NOT_RUN записи выше сохраняются как история, текущий статус заменён. Изолированный scope закрыт; R1.1b-3 ещё не начат, production wiring/merge запрещены до совместной проверки. Physical power loss, full-device/SharedPreferences ENOSPC и реальный аппаратный I/O fault не доказаны; B02–B05 и full SAF/upgrade остаются открытыми в интеграционном объёме.
