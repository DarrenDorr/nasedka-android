# R1.1b-2a — внутренний файловый журнал v2

Дата 25.11.2026. PR #8. База c40b09e, CI 45534712465 SUCCESS (JVM task и сборка, без сохранённого per-method XML). Не подключён к production restore.

- RestoreJournal: package-private Java NIO, фиксированные пути в выделенном приватном каталоге. UUID, phase, шесть SHA-256 старых/новых SQLite и canonical prefs bytes, SHA-256 journal record. Строгий формат, лимит 1024 bytes. Хеш защищает от случайного повреждения, не аутентифицирует злоумышленника с доступом к sandbox.
- Запись CREATE_NEW, force(true), ATOMIC_MOVE, directory force; без неатомарного fallback. Ошибка после rename — неоднозначный исход, recovery читает журнал. Поддержка Android ещё не испытана; power-loss durability не доказана.
- NONE только для отсутствующего journal в доступном каталоге. Повреждённый/нечитаемый/не-regular файл не считается отсутствующим. Orphan temp не является authoritative.
- Только NONE→PENDING, PENDING→COMMITTED/ROLLED_BACK; immutable UUID/хеши. clear только ожидаемого terminal, после проверки и cleanup adapter. Не удаляет базы и настройки.
- 8 JVM тестов на реальных временных файлах: reopen, terminal states, неверная операция/метаданные, corruption, orphan temp, oversized/nonregular, missing root. Это не process kill, power-loss или ENOSPC тесты.
- CI проверяет 17 точных имён методов в Gradle JUnit XML, 0 failures/errors/skipped, сохраняет небольшой артефакт 3 дня. Новая проверка NOT_RUN на момент записи.

Следующий шаг: CI + XML evidence; затем R1.1b-2b: canonical prefs encoding, durable old/staged copies, проверка хешей, Storage adapter, старый marker, Android file tests и fault injection. R1.1b-3: общий barrier, generation guard. Не подключать/merge частичное решение до проверки -2/-3. UI, design assets, SQLite v2 и backup JSON v1 не меняются. Java NIO — технический выбор, применимость на Android требует проверки.
