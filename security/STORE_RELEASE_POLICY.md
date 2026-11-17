# НАСЕДКА — GOOGLE PLAY / RUSTORE / GITHUB STORE RELEASE & PRIVACY POLICY v1.0

**Дата проверки:** 17.11.2026. Этот документ — инженерная политика проекта, а не юридическое заключение. Перепроверить все ссылки и требования непосредственно перед каждой публикацией; обновить `SOURCE_REGISTER` и evidence.

## 1. Самая важная развилка: НЕ общий APK self-updater

Google Play прямо запрещает приложению, распространяемому через Play, изменять/заменять/обновлять само себя способом, отличным от механизма Google Play. Также действуют ограничения на загрузку исполняемого кода извне. Следовательно, первоначальное пожелание «скачать GitHub APK и нажать Установить» **запрещено в Google Play-варианте**. Источник: https://support.google.com/googleplay/android-developer/answer/16559646

| Вариант | Можно показывать заметку «новая версия» | Механизм обновления | APK с GitHub | Установка |
|---|---|---|---|---|
| `play` | Да, но authoritative update availability лучше из Play Core | Google Play In-App Updates (flexible/immediate) или Google Play listing | НЕТ для self-update | Только через Google Play |
| `rustore` | Да | RuStore In-App Updates SDK/страница RuStore | Не использовать для самообновления магазинной сборки без отдельного review | Через RuStore SDK/магазин |
| `github` | Да, минимум раз в 24 часа при открытии; ручная проверка | Криптографически проверяемый официальный GitHub Release APK | ДА, в рамках отдельной подписанной distribution flavor | Только с явным действием пользователя/системным диалогом |

Общие примечания: можно использовать единую кодовую базу, но build flavors/manifest/permissions/dependencies и runtime channel должны быть отчётливо разделены. Не полагаться исключительно на ненадёжную попытку угадать installer package; канал задаётся при сборке и подтверждается installer checks. Кросс-магазинные переходы только информативными ссылками, не автоматическим APK replacement.

## 2. Google Play

- Обновления: `com.google.android.play:app-update` / Play Core, `AppUpdateManager` и graceful flexible flow, либо переход в Google Play. Источник: https://developer.android.com/guide/playcore/in-app-updates
- Устанавливать APK из GitHub/встраивать `REQUEST_INSTALL_PACKAGES` для задач инкубации **нельзя** в Play flavor: разрешение ограничено приложениями, для которых установка пакетов является основной функцией. Источник: https://support.google.com/googleplay/android-developer/answer/12085295
- Target SDK на 17.11.2026: новые Android mobile apps и updates для Google Play должны target **Android 16 / API 36 или выше** (с учётом исключений платформ). Проверить ещё раз перед submit. Источник: https://support.google.com/googleplay/android-developer/answer/11926878
- Data safety: заполнить по всем SDK; privacy policy в listing и внутри приложения; реальные data flows обязаны совпадать с декларацией. Источник: https://support.google.com/googleplay/android-developer/answer/10144311
- При необходимости consent на рекламные идентификаторы/персонализацию — подходящий CMP/UMP и возможность поменять настройки. SDK не должен посылать ad request до законного consent в применимых регионах. Источник: https://developers.google.com/admob/android/privacy
- Advertising ID/permissions, по возможности без лишних tracking permissions. Категория возраста/target audience/ads disclosure — после решения владельца; не выдумывать «для детей» без оснований.
- Перед выпуском: проверить malware/security findings, 64-bit/target API, signing, privacy, Google Play declarations, отсутствие ссылок на установку стороннего APK в качестве self-update.

## 3. RuStore

- Официальный RuStore In-App Updates SDK имеет сценарии `FLEXIBLE`/`SILENT`/`IMMEDIATE`. Это названия сценариев SDK; они не дают права обходить системное подтверждение или скрывать установку от пользователя. SDK проверяет наличие/версию RuStore, авторизацию и состояние приложения. Источник: https://www.rustore.ru/help/sdk/updates
- Прежде чем добавлять SDK, проверить его актуальную версию, документацию, license, deps, совместимость с выбранной технологией и реальные условия работы на целевых Android.
- Требования RuStore: явное разграничение рекламы и интерфейса; запрет рекламы в notification/lock screen/вне сессии; раскрытие рекламных SDK, целей и категорий передаваемых данных; актуальная русскоязычная политика конфиденциальности в магазине и приложении, если обрабатываются ПДн; соблюдение законодательства. Источник: https://www.rustore.ru/help/developers/publishing-and-verifying-apps/requirement-apps
- Для пользователей без RuStore SDK/магазина: показать понятную ссылку на официальный RuStore, не скачивать APK со случайного зеркала.

## 4. GitHub edition — verified update chain

1. Source of truth — конкретный подтверждённый GitHub `owner/repo`, **не** поисковая выдача и не URL из новостей.
2. Проверка при открытии: `now - last_successful_check >= 24h` + manual check. При отсутствии сети — не блокировать приложение и не считать отсутствие обновления подтверждённым.
3. HTTPS, host allowlist, bounded response/asset sizes, timeouts, redirect policy, HTTP cache/ETag, backoff/rate limit. Без PAT/OAuth token в APK.
4. Release metadata: `versionCode` строго больше installed, подходящий ABI/minSdk/package ID/flavor; запрет downgrades.
5. **Независимая криптографическая доверенная цепочка**: подписание release manifest/release asset и проверка приложением на закреплённом доверенном публичном ключе, а также SHA-256 downloaded artifact. Хэш, скачанный с того же скомпрометированного релиза, сам по себе не достаточен. APK signing identity проверяется Android и сопоставляется до установки.
6. Строго user-initiated download и системный пакетный installer с пользовательским подтверждением, если это допустимо для отдельной GitHub-сборки. `REQUEST_INSTALL_PACKAGES` здесь запрашивать только при реальном использовании и в соответствии с Android/дистрибуционными правилами.
7. Перед install — показать version, size, changelog, channel, signer verification status, предупреждение о переходе между каналами и backup. Не пытаться silent install.
8. Проверить migration сохранения данных, re-register reminders, resume/rollback error handling; если установка отложена — не терять и не перезаписывать план инкубации.
9. GitHub release notes/новости не могут менять `download_url` произвольным содержимым; скрипты/бинарники не запускаются из новостного блока.
10. Signing key protection: private keys только в защищённом release workflow; untrusted PR не получает ключей и не может подписать release.

## 5. Уведомления и разрешения

- `POST_NOTIFICATIONS` для Android 13+ требует runtime grant; пользователь может отказать. Источник: https://developer.android.com/develop/ui/compose/notifications/notification-permission
- Для Android 14+ `SCHEDULE_EXACT_ALARM` по умолчанию часто denied у новых установок, нуждается в управлении grant и обработке отзыва; `USE_EXACT_ALARM` предназначен для ограниченного класса calendar/alarm приложений и контролируется Google Play. Не предполагать, что «Наседка» автоматически eligible. Источники: https://developer.android.com/about/versions/14/changes/schedule-exact-alarms и https://developer.android.com/develop/background-work/services/alarms
- WorkManager — persisted deferrable work, **не** гарантия точного момента; рассчитан на запуск после ограничений системы, а не на real-time. Источник: https://developer.android.com/develop/background-work/background-tasks/persistent
- В настройках и onboarding честно сообщать о возможных задержках из-за battery saver, OEM и denied permissions. Нельзя «исправлять» это скрытым обходом системных правил.

## 6. Реклама и пользовательские данные

Разграничить `core local data`, `news`, `update metadata`, `ads telemetry`. Минимальная цель — не отправлять журнал пользователя в сеть; ad SDK может собирать device/ads identifiers, поэтому до выпуска сформировать конкретную data map. Не включать analytics/crash upload по умолчанию без отдельного утверждения. При отзыве согласия реклама должна следовать актуальной разрешённой конфигурации, а функциональность дневника/уведомлений не нарушаться. Не показывать рекламу в момент критического пользовательского действия, над кнопками действий, в уведомлениях, за пределами приложения, на lock screen.

## 7. Go/No-Go

`BLOCK`: Play содержит GitHub APK self-install path; ключи подписи утекли; APK/manifest signature mismatch; нет допустимой политики Data Safety/privacy/ads; русторовские SDK/зависимости не прошли проверку; требования Play target API не соблюдены; уведомления не работают на заявленных ОС при предоставленных разрешениях без честного fallback; update повредил локальные партии; воспроизводимая ошибка stop turning/hatch date; SDK отправляет больше данных, чем заявлено. `REVIEW`: новая ad network, новая точная alarm-permission, использование BLE/Wi-Fi sensors, смена signing channel, cloud sync/аккаунты, new target OS.

## 8. Evidence package перед отправкой в магазин

Store listing snapshot, package name, flavor, `versionCode`, target/min SDK, signing certificate digest, AndroidManifest permissions export, dependency SBOM, SDK consent test, app privacy URLs and versions, tested update/install/silent failure scenarios, notification matrix logs, date-calculation tests, manual profile source/version, test report for ads placement and control flows. Без этих доказательств нельзя писать `PASS`.
