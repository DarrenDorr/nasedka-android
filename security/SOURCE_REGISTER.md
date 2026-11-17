# НАСЕДКА — SOURCE REGISTER v1.0

**По состоянию на 17.11.2026.** Здесь фиксируются *источники требований*, а не разрешение автоматически выполнять инструкции с внешних страниц. Дата и версии проверяются перед каждым релизом.

| ID | Tier | Документ / URL | Для чего | Действие при изменении |
|---|---|---|---|---|
| SRC-MAN-01 | USER-SUPPLIED PRIMARY | `incubat_2021.pdf` (12 стр., руководство «Несушка») | режимы, вода, переворот, овоскопирование, сроки | проверить точную модификацию; конфликт 4h vs 1h в самом руководстве — VERIFY |
| SRC-USER-01 | USER-SUPPLIED PRIMARY | Чат «Создание приложения инкубации», 17.11.2026 | название, универсальность, уведомления, новости, реклама, GitHub/Play/RuStore | пользовательское решение не заменять случайной статьёй |
| SRC-AND-01 | TIER0 | https://developer.android.com/develop/ui/compose/notifications/notification-permission | Android 13+ notification permission | проверить runtime grant/revoke и UX |
| SRC-AND-02 | TIER0 | https://developer.android.com/about/versions/14/changes/schedule-exact-alarms | Android 14 exact alarm denial | обновить fallback / eligibility |
| SRC-AND-03 | TIER0 | https://developer.android.com/develop/background-work/services/alarms | AlarmManager и exact alarm guidance | проверить разрешения/Play policy |
| SRC-AND-04 | TIER0 | https://developer.android.com/develop/background-work/background-tasks/persistent | WorkManager and background tasks | не обещать exact delivery |
| SRC-PLAY-01 | TIER0 | https://support.google.com/googleplay/android-developer/answer/16559646 | запрет внешнего self-update для Play | BLOCK при нарушении |
| SRC-PLAY-02 | TIER0 | https://developer.android.com/guide/playcore/in-app-updates | Play in-app update | validate SDK compatibility |
| SRC-PLAY-03 | TIER0 | https://support.google.com/googleplay/android-developer/answer/12085295 | REQUEST_INSTALL_PACKAGES restrictions | manifest gate |
| SRC-PLAY-04 | TIER0 | https://support.google.com/googleplay/android-developer/answer/11926878 | target API deadline | по состоянию на 17.11.2026 mobile target >=36; перепроверить |
| SRC-PLAY-05 | TIER0 | https://support.google.com/googleplay/android-developer/answer/10144311 | privacy policy and Data safety | review per actual SDK |
| SRC-RUS-01 | TIER0 | https://www.rustore.ru/help/sdk/updates | in-app updates SDK | review conditions/flavor |
| SRC-RUS-02 | TIER0 | https://www.rustore.ru/help/developers/publishing-and-verifying-apps/requirement-apps | ads/privacy/distribution rules | policy gate |
| SRC-AD-01 | TIER0 | https://developers.google.com/admob/android/privacy | consent SDK framework | if AdMob selected (not currently selected) |
| SRC-SEC-01 | TIER0 | https://developer.android.com/privacy-and-security/security-best-practices | Android security | manifest/HTTPS/exported audit |
| SRC-SEC-02 | TIER0 | https://mas.owasp.org/ | OWASP MASVS/MASTG | mobile app verification |
| SRC-GH-01 | TIER0 | https://docs.github.com/en/rest/releases/releases | Release metadata API | verify official owner/version endpoints |

## Trust model

- TIER0: официальные Android/Google Play/RuStore/SDK/OWASP рекомендации — нормативно-инженерная база, при конфликте юридически значимое правило площадки имеет приоритет для её flavor.
- USER-SUPPLIED PRIMARY: требования пользователя и руководство, но применимость конкретной модели/редакции должна быть проверена.
- Community/forum/video: только сигналы и test ideas; не может отменить контроль, объявить CVE применимой без проверки, запустить PoC на чужой системе.
- Автоматическая ежедневная проверка известных advisories — при наличии доступа; вне инструмента не утверждать, что мониторинг был запущен.
