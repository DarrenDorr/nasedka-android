# НАСЕДКА — ANDROID NOTIFICATION & UPDATE TEST MATRIX v1.0

**Назначение:** формализованные тест-кейсы, не результаты. Все кейсы ниже имеют `NOT_RUN`, пока их не выполнят с evidence. Актуально на 17.11.2026. Для реальных устройств хранить model/OEM, Android API, battery state, package flavor, permissions, tz, expected vs actual timestamp, log/screenshot, commit и build digest.

| ID | Android | Permission/state | Сценарий | Исходные условия | Ожидаемое поведение | Тип | Статус |
|---|---|---|---|---|---|---|---|
| T01 | Android 12 | all granted | ordinary running | single chicken batch | next reminder shown | automated | NOT_RUN |
| T02 | Android 13 | POST_NOTIFICATIONS denied | app opened | single batch | visible upcoming tasks and explanation; no crash | instrumentation | NOT_RUN |
| T03 | Android 13 | POST_NOTIFICATIONS granted then revoked | background | multiple batches | no false claim of delivered notification; UI fallback | device | NOT_RUN |
| T04 | Android 14 | SCHEDULE_EXACT_ALARM denied | cold install | user-defined exact time | legal fallback; no SecurityException | device | NOT_RUN |
| T05 | Android 14 | exact permission granted then revoked | running/background | user-defined reminder | reschedule/legal fallback | device | NOT_RUN |
| T06 | Android 16 | USE_EXACT_ALARM absent | Play flavor | timed reminders | no dependency on ineligible permission | manifest check | NOT_RUN |
| T07 | Android 16 | notifications allowed | battery saver enabled | same-day turn reminders | report observed latency; do not promise exactness | device | NOT_RUN |
| T08 | Android OEM skin | background restricted | locked device | candling reminder | delivery or clearly documented limitation/recovery | real-device | NOT_RUN |
| T09 | All | notification channel muted | app background | next planned event | diagnostic exposes muted channel | device | NOT_RUN |
| T10 | All | notifications granted | device reboot | future reminder | reconstructed from local DB; no duplicate | device | NOT_RUN |
| T11 | All | notifications granted | force stop then reopen | missed event | no guarantee during force stop; missed flag after reopen | device | NOT_RUN |
| T12 | All | notifications granted | package upgrade | active batch | same schedule preserved and rebuilt | upgrade test | NOT_RUN |
| T13 | All | notifications granted | timezone changes | active duck batch | consistent planned hatch and notification policy | unit+device | NOT_RUN |
| T14 | All | notifications granted | DST spring forward | event at nonexistent local time | documented deterministic resolution | unit | NOT_RUN |
| T15 | All | notifications granted | DST fall back | event in repeated local hour | one event, no duplicate | unit | NOT_RUN |
| T16 | All | notifications granted | manual clock forward 48h | active batch | missed events audited, no burst flood | device | NOT_RUN |
| T17 | All | notifications granted | manual clock backwards | active batch | no duplicate/reward/row corruption | device | NOT_RUN |
| T18 | All | notifications granted | Feb 29 leap year | chicken 21 days | correct day count and planned hatch | unit | NOT_RUN |
| T19 | All | notifications granted | Dec 31 / Jan 1 | turkey 28 days | correct day count / no year boundary error | unit | NOT_RUN |
| T20 | All | notifications granted | 23:59 batch start | quail 15-17 days | clear day-1 convention; no off-by-one | unit | NOT_RUN |
| T21 | All | notifications granted | multiple batches same name | two different species | unique reminders and correct batch labels | unit+integration | NOT_RUN |
| T22 | All | notifications granted | same reminder queued twice | duplicate work request | idempotent scheduling; one notification | unit+integration | NOT_RUN |
| T23 | All | notifications granted | batch completed | future events queued | cancelled old reminders | unit+device | NOT_RUN |
| T24 | All | notifications granted | batch deleted | future events queued | cancelled; no ghost notifications | unit+device | NOT_RUN |
| T25 | All | notifications granted | manual turn mode | every configured user interval | turn reminder obeys user settings | unit | NOT_RUN |
| T26 | All | notifications granted | auto turn mode | same profile | no manual turn instruction; optionally check automation | unit | NOT_RUN |
| T27 | All | notifications granted | 2 days before selected hatch | chicken 21d | stop turn notification on proper date | unit | NOT_RUN |
| T28 | All | notifications granted | duck 28–33d unspecified | range only | ask planned hatch day; do not silently choose day 28 | unit | NOT_RUN |
| T29 | All | notifications granted | candling day 7–8 | chicken batch | label accurate and complete action | unit | NOT_RUN |
| T30 | All | notifications granted | candling day 11–13 | goose batch | label accurate and complete action | unit | NOT_RUN |
| T31 | All | notifications granted | water refill 3 days | compatible manual profile | reminder marked reference not direct sensor | unit | NOT_RUN |
| T32 | All | notifications granted | manual measurement entered | temperature/humidity | stored as user reading, not sensor measured | unit | NOT_RUN |
| T33 | All | notifications granted | no sensor attached | temperature abnormal value unknown | no fake normal/false alarm from absent reading | unit | NOT_RUN |
| T34 | All | notifications granted | tap Complete on notification | background | actual action saved only upon accepted transaction | instrumentation | NOT_RUN |
| T35 | All | notifications granted | tap Snooze then reopen | pending action | stable event state; one new reminder | instrumentation | NOT_RUN |
| T36 | All | notifications granted | action skipped | late notification | skip not counted completed | unit | NOT_RUN |
| T37 | All | notifications granted | offline/no internet | all local features | journal, events, reminders unaffected | device | NOT_RUN |
| T38 | All | notifications granted | ads SDK unresponsive | all local features | no UI freeze/critical overlay | device | NOT_RUN |
| T39 | All | notifications granted | news server malicious HTML | news screen | no JS execution/file or update spoof | security unit | NOT_RUN |
| T40 | All | notifications granted | user restores export | schedule and journal | no duplicates; consistent migration | integration | NOT_RUN |
| T41 | All | notifications granted | low storage during backup | active batch | fail safely; original data intact | device | NOT_RUN |
| T42 | All | notifications granted | device storage almost full | migration | rollback/no silent data loss | device | NOT_RUN |
| T43 | All | notifications granted | update channel play | new Github release | no APK download/install; Play only | static+device | NOT_RUN |
| T44 | All | notifications granted | update channel rustore | update available | RuStore SDK / store; safe fallback | device | NOT_RUN |
| T45 | All | notifications granted | update channel github | valid signed release | APK verified then user-approved install | integration | NOT_RUN |
| T46 | All | notifications granted | update channel github | tampered manifest or APK | BLOCK install | security test | NOT_RUN |
| T47 | All | notifications granted | update channel github | wrong signing certificate | BLOCK install | security test | NOT_RUN |
| T48 | All | notifications granted | update channel github | downgrade or package mismatch | BLOCK install | security test | NOT_RUN |
| T49 | All | notifications granted | update metadata unreachable | launch | cached state, no blocking, backoff | unit+device | NOT_RUN |
| T50 | All | notifications granted | multiple activities reopen within 24h | GitHub check | at most one automatic check; manual refresh works | unit+integration | NOT_RUN |
| T51 | All | notifications granted | night time and quiet hours | high priority reminder | user preference respected except disclosed critical override | unit | NOT_RUN |
| T52 | All | notifications granted | in-app news ads displaying | tap task action | ad does not overlay/capture safety action | UI test | NOT_RUN |
| T53 | All | notifications granted | test emulator only | claimed all devices reliable | NOT_RUN on OEM; no universal PASS | process gate | NOT_RUN |
| T54 | All | notifications granted | new incubator profile update | existing active batches | original version/parameters remain unless user opts in | unit | NOT_RUN |
| T55 | All | notifications granted | phone reboot offline | active batch | recover without server and without data loss | device | NOT_RUN |
| T56 | All | notifications granted | user changes species mid-cycle | active reminders | explicit plan-change warning, correctly rederived events | unit+UI | NOT_RUN |
| T57 | All | notifications granted | app data backup restored to different timezone | active batch | readable explanation, no silent double-notifications | integration | NOT_RUN |
| T58 | All | notifications granted | manufacturer manual interval contradiction | automatic turn | shows model-specific VERIFY; never assigns universal 1h or 4h | unit | NOT_RUN |
| T59 | All | notifications granted | ad consent rejected | normal app usage | incubation core fully available; ads data flow gated | device | NOT_RUN |
| T60 | All | notifications granted | hard battery kill / force stopped by OS | active batch | no false promise of reliable alarm; recovery on reopen | device | NOT_RUN |

## Минимальная реальная матрица устройств

Для каждого релиза: Android 13, 14, 15, 16 (если поддерживаются) + хотя бы два OEM-производителя при наличии доступных устройств; отдельный тест OEM battery saver и запрещённых exact alarms. Эмуляторы полезны, но **не подтверждают** доставку на всех настоящих телефонах. Тестировать в магазине целевой flavor, а не debug flavor с другими permissions.

## Evidence row

`test_id, git_commit, apk_sha256, flavor, package_id, version_code, device_model, oem, android_api, timezone, doze_state, battery_saver, notification_grant, exact_alarm_grant, scheduled_at, expected_at, observed_at, result, log_path, screenshot_path, notes`

## Особые блокирующие ошибки

- Плановый день вывода и прекращение переворота определены неверно для выбранного вида/срока.
- При предоставленных доступных разрешениях уведомления систематически не работают на заявленной поддерживаемой ОС, а приложение этого не показывает и не предлагает fallback.
- Новые APK/обновления теряют активные партии/журнал и расписание или порождают дубликаты.
- Реклама/новости затрудняют доступ к действиям по уходу за яйцами.
- Play-сборка содержит GitHub APK self-update flow.
