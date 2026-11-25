# Наседка — актуальное состояние проекта (читать первым)

**Обновлено:** 2026-11-25. **Репозиторий:** `DarrenDorr/nasedka-android`, `main`. **Исходники по README:** `0.9.0-dev` (не равно проверенной release-сборке).

## Где остановились

**Завершено на уровне дизайна:** кремово-зелёный стиль, Главная и Инкубация как точные эталоны, концепции остальных модулей: Под наседкой, Поголовье, Молодняк, Корма, Сбор яиц, Финансы, Отчёты, Журнал, Новости, Настройки, Уведомления, Резервные копии, Исправление и история. Показан макет пустых состояний, ошибок, больших списков и сохранения черновиков.

**СЕЙЧАС (фактически выполнено):**

- Зарегистрированы 43 изображения, 3 `LOCKED` эталона, статус и SHA-256 каждого: `docs/design/ASSET_CATALOG.md`, `ASSET_CHECKSUMS.md`.
- Проверен оригинальный ZIP `Nasedka_Design_Vault_COMPLETE_2026-11-23.zip` и подготовлен **Nasedka_GitHub_Design_Assets_2026-11-23.zip**, 85,9 МиБ, SHA-256 `bda897b6dbe0bba800b26be41862623e77ba962e9cbbd30259eca58e7c647457`. В нём 43 оригинальных PNG с путями `docs/design/assets/DES-000.png…DES-042.png`, полный манифест и список хешей. **Все 43 оригинальных PNG включены в GitHub; локальные SHA-256, размеры и Git SHA-1 сверены с исходным архивом.** Оба ZIP также сохранены в личной ChatGPT Library: `/Наседка/Nasedka_Design_Vault_COMPLETE_2026-11-23.zip` и `/Наседка/Nasedka_GitHub_Design_Assets_2026-11-23.zip`.
- Выполнена **READ-ONLY сверка реального GitHub-кода** `0.9.0-dev` с утверждёнными экранами: [матрица наличия, пробелов и источников](design/IMPLEMENTATION_AUDIT_2026-11-23.md). Например, код рисует 5 нижних кнопок вместо 4, а учёт яиц пока не содержит складской модели движения.
- Добавлены [проверяющий скрипт](../tools/verify_design_assets.py), [GitHub Actions контроль 43 изображений](../.github/workflows/design-reference-integrity.yml) и [инструкция единовременного переноса PNG](design/ASSET_UPLOAD_GUIDE.md). Первоначальный запуск был ожидаемо неуспешным из-за отсутствия PNG. Новый результат GitHub Actions необходимо сверить отдельно.
- Java/Android-исходники, сертификаты подписи, текущая база и ранее согласованный внешний вид **не изменены**.

**Перенос графики:** 43/43 файла, 90 069 348 байт; сверены размеры и Git blob SHA-1 с исходным архивом, в котором совпали 43 SHA-256. [Манифест](design/IMAGE_MANIFEST.csv), [каталог с прямыми ссылками](design/ASSET_CATALOG.md), [проверка](../tools/verify_design_assets.py). Задача [#6](https://github.com/DarrenDorr/nasedka-android/issues/6) отслеживает итог CI; статус CI проверять по фактическому запуску.

**R1 — аудит исходников выполнен 25.11.2026:** [реестр рисков и матрица испытаний](R1_RELIABILITY_AUDIT_2026-11-25.md). Зафиксированы 8 находок, включая 3 P0, и 21 сценарий проверки. Android-код/дизайн не изменены. [Android CI](https://github.com/DarrenDorr/nasedka-android/actions/runs/45526396771) SUCCESS; реальные R1 проверки NOT_RUN.

**R1.1a — валидация и SQLite-тесты:** пользователь согласовал начало реализации. В рабочей ветке PR #7 исправлена проверка обязательных таблиц и связей переноса, добавлены Android instrumentation tests; [отчёт R1.1a](R1_1A_BACKUP_VALIDATION_2026-11-25.md). Исполнение на Android NOT_RUN; сборка тестового APK проверяется отдельно. Никакой смены схемы, версии формата или дизайна.

**R1.1a контроль:** [CI исходного патча c9d229e](https://github.com/DarrenDorr/nasedka-android/actions/runs/45529467340) SUCCESS (компиляция APK тестов, не их исполнение). При повторном аудите усилены строгие типы/полнота строк, добавлены 3 теста и отдельный workflow Android SQLite. [Контрольный отчёт](R1_1A_VERIFICATION_2026-11-25.md).

**Контрольный запуск d1f1b6b:** [сборка](https://github.com/DarrenDorr/nasedka-android/actions/runs/45530253199) PASS; [Android SQLite](https://github.com/DarrenDorr/nasedka-android/actions/runs/45530253337) FAIL инфраструктуры, тесты NOT_RUN. Из архива диагностики 14984916641 установлена точная причина: Unknown AVD name r1_sqlite. Исправлены общий явный ANDROID_AVD_HOME, проверка профиля и ранняя диагностика остановки эмулятора. См. контрольный отчёт.

**R1.1a — ЗАКРЫТ В РАМКАХ УТВЕРЖДЁННОГО ОБЪЁМА / PASS:** на commit `02afaf7` сборка [45533374043](https://github.com/DarrenDorr/nasedka-android/actions/runs/45533374043) SUCCESS, Android 15/API 35 [45533374022](https://github.com/DarrenDorr/nasedka-android/actions/runs/45533374022) SUCCESS, XML скачан и проверен: 10/10, 0 failures/errors/skipped. [Постоянное доказательство](../security/evidence/R1_1A_ANDROID_SQLITE_2026-11-25.json), [контрольный отчёт](R1_1A_VERIFICATION_2026-11-25.md). Допуск к R1.1b выдан только в границах R1.1a; не подтверждает полный импорт или релиз.

**R1.1b-0 — проект архитектуры:** подготовлен [план согласованного восстановления](R1_1B_RESTORE_ARCHITECTURE_2026-11-25.md) в отдельной рабочей ветке `feature/r1-1b-restore-safety-20261125`. Включает барьер доступа, внутренний durable journal, recovery, fault injection и критерии B02–B05. Android-код не изменён, новые проверки NOT_RUN. R1.1a PR #7 не изменён.

**Интеграция R1.1a:** PR #7 объединён в `main` (merge commit `823e79ac63faca0752c785bdad696a10dd42477c`); финальный [build](https://github.com/DarrenDorr/nasedka-android/actions/runs/45533839572) и [SQLite](https://github.com/DarrenDorr/nasedka-android/actions/runs/45533839632) для 062c8b4 SUCCESS, XML gate SUCCESS. 

**R1.1b-1 — модель протокола реализована:** `RestoreProtocol.java`, `RestoreProtocolTest.java`, [описание модели и критерии](R1_1B_STATE_PROTOCOL_2026-11-25.md). Девять JVM-тестов, включая 36 вариантов сбоя до/после границ. Протокол не подключён к BackupStore; новый CI NOT_RUN до проверки. PR #8 Draft.

**R1.1b-1 контроль:** CI [45534712465](https://github.com/DarrenDorr/nasedka-android/actions/runs/45534712465) SUCCESS на c40b09e; job 138938361639 подтверждает исполнение testGithubDebugUnitTest и сборку всех APK. Отдельные XML прежний workflow не сохранял. Введён XML gate 9 protocol + 8 journal тестов; per-method evidence PENDING.

**R1.1b-2a — внутренний файловый журнал:** реализован изолированный RestoreJournal и 8 JVM-тестов на временных файлах, [отчёт](R1_1B_FILE_JOURNAL_2026-11-25.md). Не подключён к BackupStore, Android runtime durability NOT_RUN.

**R1.1b-1/-2a — PASS JVM scope:** [CI 45537467265](https://github.com/DarrenDorr/nasedka-android/actions/runs/45537467265) SUCCESS; скачан архив 14986844104, SHA-256 совпал, оба XML разобраны: 9+8=17, 0 failures/errors/skipped. [Постоянное evidence](../security/evidence/R1_1B_JVM_2026-11-25.json). Не Android durability evidence.

**R1.1b-2b — изолированный файловый adapter:** реализованы RestoreFileStorage/RestoreSettingsPort, реальные SQLite-файлы и отдельные prefs в 5 Android-тестах. [Отчёт](R1_1B_FILE_STORAGE_2026-11-25.md). Новый код не подключён к BackupStore; legacy marker блокирует новый протокол, отдельный recovery ещё открыт. Android CI NOT_RUN.

**R1.1b-2b попытка 1 — HOLD:** build [45538262692](https://github.com/DarrenDorr/nasedka-android/actions/runs/45538262692) PASS (17 JVM); Android [45538262704](https://github.com/DarrenDorr/nasedka-android/actions/runs/45538262704) 15 executed, 11 PASS / 4 FAIL, XML проверен. Ошибка подготовки SQLite sidecars до PENDING. Добавлены явная SQLite preparation, preflight свободного места, 6 Android-тестов и точный XML gate 10+11. [Контрольный отчёт](R1_1B_FILE_STORAGE_2026-11-25.md).

**R1.1b-2b попытка 2 — PASS в проверенном объёме:** 17 JVM + 21 Android, 0 failures/errors/skipped; [build](https://github.com/DarrenDorr/nasedka-android/actions/runs/45540385042), [Android](https://github.com/DarrenDorr/nasedka-android/actions/runs/45540384959), [evidence](../security/evidence/R1_1B_STORAGE_ATTEMPT2_2026-11-25.json). Обе ZIP SHA-256 совпали с GitHub, XML проверены.

**R1.1b-2b продолжение:** изолированный RestoreLegacyRecovery с консервативным выбором прежней пары, проверкой v2/relations и сохранением всех старых копий. 5 дополнительных Android-тестов, включая все 5 префиксов старых rename; новый CI NOT_RUN.

**R1.1b-2b попытка 3 — PASS в проверенном объёме:** commit `a6cc7b9e8a4cdd770babad7340b63b61f248254f`; [build 45540890875](https://github.com/DarrenDorr/nasedka-android/actions/runs/45540890875), [Android 45540890833](https://github.com/DarrenDorr/nasedka-android/actions/runs/45540890833) SUCCESS. 17 JVM + 26 Android = 43 PASS, 0 failures/errors/skipped. Оба ZIP digest и три XML проверены, все имена методов сверены с исходниками. [Постоянный протокол](../security/evidence/R1_1B_STORAGE_ATTEMPT3_2026-11-25.json). Все 5 legacy tests PASS. Эта запись только документирует проверку; production code не меняется.

**R1.1b-2b продолжение аварийных испытаний:** повторный CI документационного a5948fc SUCCESS ([build 45543274191](https://github.com/DarrenDorr/nasedka-android/actions/runs/45543274191), [Android 45543274189](https://github.com/DarrenDorr/nasedka-android/actions/runs/45543274189)). Добавлены 3 Android tests реального запрета записи, 6 отдельными instrumentation-процессами actual SIGKILL/restart, 1 bounded Linux tmpfs ENOSPC probe. [План и ограничения](R1_1B_CRASH_IO_2026-11-25.md). Новый код только тестовый/CI, production не изменён. NOT_RUN.

**R1.1b-2b аварийные испытания, попытка 1:** build [45545039054](https://github.com/DarrenDorr/nasedka-android/actions/runs/45545039054) SUCCESS; 17 JVM PASS, bounded Linux ENOSPC PASS. Android [45545039049](https://github.com/DarrenDorr/nasedka-android/actions/runs/45545039049) job FAIL после успешных 29 обычных тестов: Test runner not installed, 6 process cases NOT_RUN. Три ZIP и XML/log проверены; 47/53 подтверждены, 6 не выполнены. [Evidence](../security/evidence/R1_1B_CRASH_IO_ATTEMPT1_2026-11-25.json).

**Попытка 2:** явная установка обоих APK после connectedAndroidTest, проверка регистрации runner и target, PID через успешный ps, раздельные обязательные gates ordinary XML / process JSON. Production и тестируемая логика не менялись. NOT_RUN.

**R1.1b-2b аварийные испытания, попытка 2 — PASS в проверенном объёме:** [build 45546747538](https://github.com/DarrenDorr/nasedka-android/actions/runs/45546747538) и [Android 45546747497](https://github.com/DarrenDorr/nasedka-android/actions/runs/45546747497) SUCCESS. 46 ordinary methods + 6 actual kill/restart + 1 Linux ENOSPC = 53/53. Три ZIP, XML, JSON PID/outcomes и Linux log проверены. [Evidence](../security/evidence/R1_1B_CRASH_IO_ATTEMPT2_2026-11-25.json).

**Android ENOSPC — новая проверка:** отдельный CI-only tmpfs 8 MiB внутри disposable emulator, root только для provisioning, SELinux Enforcing, st_dev/capacity guards, настоящая ошибка записи Android и rollback после освобождения места. Инфраструктура ещё NOT_RUN; если root/mount/SELinux/namespace блокирует, сохранить точную причину, не ослаблять защиту и не заполнять /data.

**R1.1b-2b 54/54 PASS в проверенном объёме:** commit `fe2da619baaa03a6af2543cdfa2d3831439f39c5`, [build 45547940828](https://github.com/DarrenDorr/nasedka-android/actions/runs/45547940828) и [Android 45547940810](https://github.com/DarrenDorr/nasedka-android/actions/runs/45547940810) SUCCESS. 17 JVM + 29 Android + 6 real process death + Linux/Android ENOSPC. Три ZIP SHA-256, XML, JSON и mount/witness подтверждены. [Evidence](../security/evidence/R1_1B_ANDROID_ENOSPC_2026-11-25.json).

**Аудит -2b: R1B-A01 — исправление durability gate:** видимый terminal journal после rename при ошибке directory force не гарантирует устойчивость к power loss; теперь обязательный confirmTerminal до cleanup. R1B-A02 — видимое NONE после неоднозначного unlink требует синхронизации каталога до открытия доступа. 3 Android теста с injected directory-force IOException на реальных файлах; реальные физические ошибки fsync не имитируются ОС. [Отчёт](R1_1B_DURABILITY_AUDIT_2026-11-25.md). Новая редакция NOT_RUN.

**R1.1b-2b — ЗАВЕРШЁН В ИЗОЛИРОВАННОМ ОБЪЁМЕ (PASS_R1_1B_2B_ISOLATED_SCOPE).** Проверенный commit `3a0a2ee395ddc677e8a0a1aa1c21ad46703fd60a`; [build 45548741852](https://github.com/DarrenDorr/nasedka-android/actions/runs/45548741852), [Android 45548741811](https://github.com/DarrenDorr/nasedka-android/actions/runs/45548741811) SUCCESS. 17 JVM + 32 Android + 6 actual process death + 2 ENOSPC = 57/57, 0 failures/errors/skipped. A01/A02 — три новых Android метода PASS. [Evidence](../security/evidence/R1_1B_2B_FINAL_2026-11-25.json), [итоговый акт и границы допуска](R1_1B_2B_CLOSURE_2026-11-25.md). R1.1b-2a/-2b завершены в предусмотренном изолированном объёме; не равнозначно закрытию R1-F01/F02, полному R1.1b или релизу.

**R1.1b-3a — инвентаризация и контракт (DESIGN_RECORDED, 25.11.2026).** Пользователь разрешил продолжить R1.1b-3. Повторный CI документального закрытия `a3ff8f0` SUCCESS: [build 45549364572](https://github.com/DarrenDorr/nasedka-android/actions/runs/45549364572), [Android 45549364563](https://github.com/DarrenDorr/nasedka-android/actions/runs/45549364563); доказательство 57/57 относится к ранее независимо проверенному 3a0a2ee. [Инвентаризация и спецификация координатора](R1_1B_3_COORDINATOR_2026-11-25.md), [машиночитаемая матрица входов](../security/R1_1B_3_ACCESS_INVENTORY_2026-11-25.json). Зафиксированы 13 групп входов, stale UI/SAF/PendingIntent, worker RemoteFeed/ChannelUpdater, lock-order, startup, prefs generation, правила отказа/повтора и 14 групп контрольных сценариев. Это source review и проект, не подтверждение охвата production. Исполняемые файлы не изменены.

**R1.1b-3b — ядро реализовано, CI NOT_RUN:** DataAccessCoordinator.java, 13 JVM tests C01–C08, точный XML gate и артефакты. [Отчёт](R1_1B_3B_CORE_2026-11-25.md). Только изолированный компонент: nonblocking относительно длительной операции, короткий metadata monitor, LIFO SHORT, ticket reserve/claim/cancel, thread-bound lease, fail-closed restore/recovery, process-local epoch + UUID token, атомарный token admission. `finishVerified` — явное доверенное утверждение caller после durable verification, не самостоятельная проверка storage. 

**R1.1b-3b — PASS_R1_1B_3B_ISOLATED_SCOPE:** commit `bc9effa7fac03bae1b8636b524cdf3571503b780`; [build 45558879096](https://github.com/DarrenDorr/nasedka-android/actions/runs/45558879096), [Android 45558879109](https://github.com/DarrenDorr/nasedka-android/actions/runs/45558879109) SUCCESS. 70/70, 13/13 coordinator methods, 3 ZIP SHA-256 и 4 XML + JSON/log/witness проверены. [Evidence](../security/evidence/R1_1B_3B_FINAL_2026-11-25.json). Предыдущий Android docs-only run 45558557547 cancelled superseded. Пользователь разрешил переход при PASS.

**R1.1b-3c-1 — внутренний generation codec и target factory:** новая редакция RestoreSettingsPort (NSP2 unchanged when absent, NSP3 with canonical UUID); старый snapshot удаляет generation, unknown/malformed reject; локальный target UUID не берётся из импортируемого JSON; scheduler watermarks сбрасываются, unrelated prefs preserved. 5 новых Android tests + actual process-death и Android ENOSPC fixtures теперь включают UUID. [Отчёт](R1_1B_3C_GENERATION_2026-11-25.md). NOT_RUN; production wiring не менялось.

**R1.1b-3c-1 попытка 1 — HOLD:** commit `5f50a5b1f36bb1fc41f987eccb6b7c99ad58823f`, [build 45559829351](https://github.com/DarrenDorr/nasedka-android/actions/runs/45559829351) SUCCESS, 30 JVM + Linux ENOSPC PASS; [Android 45559829394](https://github.com/DarrenDorr/nasedka-android/actions/runs/45559829394) FAIL, 36/37 ordinary PASS, 6 process death + Android ENOSPC NOT_RUN. 67 PASS / 1 FAIL / 7 NOT_RUN. Три ZIP digest, четыре XML и Linux log проверены. Причина: generationCommitAndRollbackPreserveCorrectUuid после корректного rollback повторно использовал уже удалённые incoming DB; ошибка до второго PENDING. Четыре остальных новых метода PASS. [Evidence](../security/evidence/R1_1B_3C1_ATTEMPT1_2026-11-25.json).

**Попытка 2:** только test fixture fix — проверка NONE/отсутствия incoming после rollback, создание новой пары incoming перед вторым restore, проверка NONE/cleanup после commit; прежние assertions сохранены, production и CI gates без изменений. NOT_RUN.

**R1.1b-3c-1 — PASS_R1_1B_3C1_ISOLATED_SCOPE:** commit `095a362044daebead10c7248cdb9717de9da3e6a`; [build 45563186712](https://github.com/DarrenDorr/nasedka-android/actions/runs/45563186712), [Android 45563186688](https://github.com/DarrenDorr/nasedka-android/actions/runs/45563186688) SUCCESS, 75/75, 5/5 new methods PASS. Three ZIP hashes, four XML, process JSON and ENOSPC evidence checked. [Evidence](../security/evidence/R1_1B_3C1_FINAL_2026-11-25.json). User authorized transition on PASS.

**R1.1b-3c-2a — isolated startup policy + settings quarantine implemented, NOT_RUN:** RestoreStartupGate.java with backend contract, 11 JVM model tests; RestoreSettingsPort.initializeGeneration and process-local sticky uncertainty on false/throw/mismatch with 3 Android methods. 89 checks target. [Report](R1_1B_3C2_STARTUP_2026-11-25.md). Same Android SharedPreferences object identity only, must share application instance and coordinator. No actual startup backend, no production wiring, no hardware durability claim.

**R1.1b-3c-2a — PASS_R1_1B_3C2A_ISOLATED_SCOPE:** commit `d568d2ba4e1c1461d011e93bb1631a73760de924`, [build 45563831754](https://github.com/DarrenDorr/nasedka-android/actions/runs/45563831754), [Android 45563831719](https://github.com/DarrenDorr/nasedka-android/actions/runs/45563831719) SUCCESS, 89/89, three ZIP/five XML/process JSON/mount/witness verified. [Evidence](../security/evidence/R1_1B_3C2A_FINAL_2026-11-25.json). User authorized next.

**R1.1b-3c-2b-1 — concrete startup filesystem backend, NOT_RUN:** RestoreStartupFiles.java, non-destructive SQLite corruption handler in preparation/legacy validation, 8 real Android startup methods with actual v2 schemas. Existing directory required; fresh/partial initialization only when no generation/restore evidence; known stage preserved if interrupted; unknown work files fail closed; valid v2 recovery before live access. Legacy remnants deliberately still block; v1 migration and legacy durable disposition and independent ambiguous-commit restart remain pending. [Report](R1_1B_3C2B_FILES_2026-11-25.md).

**СЛЕДУЮЩИЙ конкретный шаг:** verify 41 JVM + 48 Android + 6 process death + 2 ENOSPC = 97 checks; validate artifacts, fix failures. Then -3c-2b-2 durable legacy remnant classification, safe existing v1 migration policy, actual process restart after simulated ambiguous commit, remaining audit (including PENDING publication). Then -3c-3, -3d, -3e, -4. No partial barrier/merge. PR #8 Draft, не merge. R1-F01/F02 остаются OPEN; 57/57 изолированных -2b сохраняются как baseline. Не менять утверждённый дизайн, public JSON v1, SQLite schema v2. Физическая power-loss/flash durability не доказана. Дизайн не менять. Полный SAF, конкурентность, crash recovery и реальное обновление остаются в последующих испытаниях R1. 

**ПЛАН РЕАЛИЗАЦИИ (только по отдельному согласию на изменение приложения):** R1 — тесты резервных копий, миграций, фоновых уведомлений и сохранения черновиков; R2 — перенос точного визуального каркаса (4 кнопки и Главная); R3 — связанный учёт яиц, единый журнал и история исправлений. Не возвращаться к созданию нового дизайна без запроса.

## Приоритет документов

1. [Дизайн и правила использования эталонов](design/README.md).
2. [Реестр экранов](design/SCREEN_REGISTRY.md).
3. [Журнал решений](design/DECISIONS.md).
4. [Полный каталог графики](design/ASSET_CATALOG.md) и [контрольные суммы](design/ASSET_CHECKSUMS.md).
5. `README.md` и `ROADMAP.md` репозитория — реализация по документации, требует фактической проверки кода и Android.
6. Полный портативный архив графики и документации, сохранённый в разговоре. Он содержит оригиналы, хеши и каталог.

## Правила, которые нельзя менять молча

- **Внешний вид:** утверждённый исходный шаблон с пятью телефонами и точные кадры Главной/Инкубации; никаких новых тем, самостоятельного редизайна и повторного согласования уже утверждённого.
- **Навигация:** четыре нижние кнопки `Главная · Новости · Журнал · Настройки`; Инкубация, Поголовье, Корма, Финансы и Отчёты — из Главной; центр напоминаний — по колокольчику.
- **Общие данные:** один бизнес-факт — одна операция, а связанные карточки, остатки, финансы, журнал и аналитика пересчитываются без дублей.
- **Инкубатор:** сначала конкретная модель, затем реальные опции; постраничная иллюстрированная инструкция с открываемой официальной ссылкой; ручной ввод измерений.
- **Под наседкой:** две исходные ситуации «готовим» / «птица сама села»; неизвестная дата не превращается в точную; нет механических советов из инкубатора.
- **Поголовье:** вывод и перенос в группу ровно один раз; переводы групп не меняют сумму.
- **Фактические исходы:** из 104 яиц 6 удалено, 98 к выводу, 90 вылупилось, 8 исходов не установлены без наблюдений; осмотр сам по себе яйца не убирает.
- **Резервные копии и изменения:** не стирать журнал и связанные данные; историю корректировок сохранять; перед восстановлением проверять файл; черновик при повороте сохранять.
- **Конфиденциальность:** без обязательной регистрации, GPS и адреса; регион только необязательный и позднее. Все советы рекомендательные, при проблемах обращаться к специалисту/ветеринару.
- **Иконка приложения** — в самом конце; код сейчас не менять.

## Режим продолжения

Каждую следующую задачу начинаем с чтения этого файла и каталога. В конце этапа обновляем **завершено → сейчас → следующий конкретный шаг → что требует тестирования**. Новое изображение получает ID, файл, SHA-256, статус утверждения и запись в истории решений; старое не затирается.

**Известная неопределённость:** последний макет нестандартных состояний (23.11) показан, но пользователь не утвердил его отдельно как пиксельный эталон. Его статус — `PRESENTED`. Статус `APPROVED_CONCEPT` у остальных экранов не означает, что все демонстрационные цифры и подписи на картинке корректны.

## Архив исходной проектной документации (25.11.2026)

- [Исторический снимок](design/archive/2026-11-23/), включая расширенные спецификации Главной и Инкубации, полный UI-аудит и первоначальную матрицу реализации.
- [Доказательство импорта](design/ASSET_IMPORT_EVIDENCE_2026-11-25.md); [коммит](https://github.com/DarrenDorr/nasedka-android/commit/282b38ad921e852fbe87fd9360cd60b9e467fca0).
- [Проверка CI](https://github.com/DarrenDorr/nasedka-android/actions/runs/45526224964): SUCCESS; контроль целостности завершён.
