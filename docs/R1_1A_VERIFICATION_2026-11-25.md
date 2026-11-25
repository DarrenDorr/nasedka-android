# Контроль R1.1a — 25.11.2026

## Решение допуска: HOLD
Пользователь потребовал завершить и проверить R1.1a прежде чем переходить к R1.1b. PR #7 остаётся открыт. Базовый проверенный патч c9d229e21a232470fd2837df184192a597bdcfb4, [CI run 45529467340](https://github.com/DarrenDorr/nasedka-android/actions/runs/45529467340), job 138920880371 SUCCESS: JVM, 3 debug APK и компиляция Android test APK. Нет evidence исполнения instrumentation. Сравнение с main не обнаружило изменений схем Db/FarmDb, UI, design assets или versionCode.

## Дополнительные статические находки и исправления
- R1.1a-V01: SQLite подставляет default/авто-ID для отсутствующих полей; JSON NULL в PK может создавать новый ID. Проверять все столбцы, различать отсутствие и допустимый NULL, отклонять NULL для PK/NOT NULL. Единственное послабление для отсутствующего hatched_at — farm v1.
- R1.1a-V02: JSONObject.optInt/getInt и Number.longValue могут приводить неверные типы/числа. Проверка целых на Integer/Long, обязательных строк на String, версия без приведения; неверное nest_checks не заменять по умолчанию. Отсутствующее nest_checks совместимо с историческим форматом.
- R1.1a-V03: сборка тестового APK не равна запуску. Новый workflow r1-android-sqlite.yml на чистом API 35 эмуляторе, без стабильных signing secrets; проверяет XML наличия всех 10 методов, исключает пустой/пропущенный PASS, сохраняет небольшие XML/log артефакты 3 дня. Никаких пользовательских данных. Обычный build workflow сохраняется.

## Матрица допуска
| Критерий | Доказательство | Состояние |
|---|---|---|
| Сборка всех вариантов и JVM исходного патча | run 45529467340 | PASS |
| Отсутствие изменений дизайна, схем и версии | diff c9d229e vs main | PASS статический |
| Полнота таблиц и совместимость v1 | тесты B06 | NOT_RUN Android |
| Полнота строк/типов и откат staging при отказе | три дополнительных теста | NOT_RUN Android |
| Round-trip 14 таблиц B01 (in-memory) | instrumentation | NOT_RUN Android |
| Ссылки/количество переноса B07 (часть) | instrumentation | NOT_RUN Android |
| M01 реконструированная incuba v1 / M02 архивная farm v1 | instrumentation | NOT_RUN Android |
| Сборка и Android runtime тесты новой ревизии | проверить новый HEAD, XML evidence | PENDING |
| Полный B01 SAF/restore, B02–B05/B08, M03, прочие бизнес-инварианты | R1.1b/R1.4 | OPEN, не выдавать за PASS |

Проверка на API 35 не доказывает поведение всех устройств. Исторический incuba v1 оригинал не найден; ограничение сохранено. Перенос в реальные данные/выпуск запрещён. В исходном экспорте остаются ограничения лимитов строк, 2000 символов и размера 24 МиБ; проверить взаимную применимость при дальнейшем полном round-trip, не менять без доказательства. R1.1a gate закрыть только после проверки нового CI и XML (все 10 тестов выполнены, 0 ошибок/skip). R1-F01/F02 и более широкая проверка бизнес-инвариантов открыты. При FAIL исправить и повторить; при отсутствии KVM/SDK зафиксировать BLOCKED, выполнить в изолированной среде. Не начинать R1.1b в текущем PR до допуска.

## Попытка 1: 25.11.2026, d1f1b6b — FAIL инфраструктуры / NOT_RUN тестов

- [Android build and tests, run 45530253199](https://github.com/DarrenDorr/nasedka-android/actions/runs/45530253199), job 138923488465: SUCCESS (сборка и JVM).
- [R1 Android SQLite verification, run 45530253337](https://github.com/DarrenDorr/nasedka-android/actions/runs/45530253337), job 138923488943: SDK + создание AVD step SUCCESS, Execute real Android SQLite tests FAIL (exit 124, 300 секунд ожидания adb); XML gate SKIPPED.
- Скачан и распакован [артефакт 14984916641](https://github.com/DarrenDorr/nasedka-android/actions/runs/45530253337/artifacts/14984916641), SHA-256 ZIP 8bf2d0b12219eb678c272a7af2f78e84ad313e0d87eae9d145070a3edafa1d74. Единственный файл r1-emulator.log, 414 байт. Сообщения Android Emulator 37.3.10.0:

```text
ERROR        | Unknown AVD name [r1_sqlite], use -list-avds to see valid list.
ERROR        | HOME is defined but there is no file r1_sqlite.ini in $HOME/.android/avd
ERROR        | (Note: Directories are searched in the order $ANDROID_AVD_HOME, $ANDROID_SDK_HOME/avd and $HOME/.android/avd)
```

Причина непосредственно подтверждена журналом: профиль AVD не найден при запуске. Конкретное место, куда avdmanager ранее записал профиль, не установлено. Ни один из десяти SQLite тестов не исполнен; это не FAIL бизнес-логики.

## Попытка 2 — изменение только инфраструктуры

- Явно задаются ANDROID_USER_HOME и ANDROID_AVD_HOME, передаются через GITHUB_ENV между шагами; профиль создаётся с --path.
- Проверка KVM, наличие ini/config.ini и вывод emulator -list-avds до запуска.
- ADB адресуется конкретному emulator-5554; контролируется завершение процесса и sys.boot_completed, проверяется API 35. При ошибке хвост лога выводится непосредственно в job и сохраняется в артефакте.
- Не увеличиваем время ожидания вместо исправления причины: при остановке процесса немедленный FAIL. Предельное ожидание загрузки 420 секунд только для живого процесса.
- Код приложения, схема, все 10 тестов и XML gate не изменены. Новый запуск ещё не подтверждён. Статус допуска HOLD, следующий шаг проверить новый HEAD PR #7 и полный XML evidence. При повторном infrastructure FAIL — локализовать ошибку по новым логам, без ложного PASS или обхода тестов.
