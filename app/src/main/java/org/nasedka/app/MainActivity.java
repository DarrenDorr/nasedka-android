package org.nasedka.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", new Locale("ru"));
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd.MM, HH:mm", new Locale("ru"));
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", new Locale("ru"));
    private static final int REQUEST_EXPORT = 77;
    private static final String STATE_TAB = "nasedka_tab";
    private static final String STATE_BATCH = "nasedka_selected_batch";
    private static final String STATE_EXPORT_BATCH = "nasedka_export_batch";
    private static final String STATE_FARM_PAGE = "nasedka_farm_page";
    private static final int TAB_FARM = 0, TAB_INCUBATION = 1, TAB_JOURNAL = 2;
    private static final int TAB_NEWS = 3, TAB_SETTINGS = 4, TAB_GUIDES = 5, TAB_NESTS = 6;
    private final ZoneId zone = ZoneId.systemDefault();
    private LinearLayout root;
    private LinearLayout body;
    private LinearLayout bottom;
    private int tab = 0;
    private long selectedBatch = -1;
    private long exportingBatch = -1;
    private int farmPage = 0;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Notifications.channels(this);
        root = Ui.vertical(this); root.setBackgroundColor(Ui.BACKGROUND);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(0, i.top, 0, i.bottom);
                return insets;
            });
        } else root.setFitsSystemWindows(true);
        setContentView(root);
        ScrollView scrolling = new ScrollView(this);
        scrolling.setFillViewport(true);
        scrolling.setClipToPadding(false);
        body = Ui.vertical(this);
        body.setPadding(Ui.dp(this, 18), Ui.dp(this, 24), Ui.dp(this, 18), Ui.dp(this, 22));
        scrolling.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scrolling, new LinearLayout.LayoutParams(-1, 0, 1));
        bottom = Ui.horizontal(this);
        bottom.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        bottom.setBackgroundColor(Color.WHITE);
        root.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
        if (savedInstanceState == null) {
            fromIntent(getIntent());
        } else {
            // Recreate navigation state after a non-rotation lifecycle restart.
            tab = savedInstanceState.getInt(STATE_TAB, TAB_FARM);
            selectedBatch = savedInstanceState.getLong(STATE_BATCH, -1);
            exportingBatch = savedInstanceState.getLong(STATE_EXPORT_BATCH, -1);
            farmPage = savedInstanceState.getInt(STATE_FARM_PAGE, 0);
        }
        render();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_TAB, tab);
        outState.putLong(STATE_BATCH, selectedBatch);
        outState.putLong(STATE_EXPORT_BATCH, exportingBatch);
        outState.putInt(STATE_FARM_PAGE, farmPage);
        super.onSaveInstanceState(outState);
    }

    /**
     * Rotation and window-size changes do not recreate this Activity.
     * Keep the active AlertDialog, checklist checkboxes, EditTexts and date picker
     * in place; Android remeasures the existing native views for the new bounds.
     * Do NOT call render() here: it would reset screen/scroll state needlessly.
     */
    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override protected void onResume() {
        super.onResume();
        AlarmScheduler.reschedule(this);
        if (body != null) render();
        refreshRemote(false);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent); fromIntent(intent); render();
    }

    private void fromIntent(Intent intent) {
        if (intent != null && intent.hasExtra("batch_id")) {
            selectedBatch = intent.getLongExtra("batch_id", -1);
            tab = TAB_INCUBATION;
        }
    }

    @Override public void onBackPressed() {
        if (selectedBatch > 0) { selectedBatch = -1; tab = TAB_INCUBATION; render(); }
        else if (tab == TAB_NESTS) { tab = TAB_INCUBATION; render(); }
        else if (tab == TAB_FARM && farmPage != 0) { farmPage = 0; render(); }
        else if (tab == TAB_GUIDES) { tab = TAB_INCUBATION; render(); }
        else if (tab != TAB_FARM) { tab = TAB_FARM; render(); }
        else super.onBackPressed();
    }

    private void refreshRemote(boolean force) {
        RemoteFeed.refresh(this, force, message -> {
            if (body != null) render();
            if (force) toast(message);
        });
        RemoteFeed.refreshNews(this, force, message -> {
            if (body != null && tab == TAB_NEWS) render();
            if (force) toast(message);
        });
    }

    private void render() {
        if (body == null) return;
        body.removeAllViews();
        bottom.removeAllViews();
        if (selectedBatch > 0 && tab == TAB_INCUBATION) showDetails();
        else if (tab == TAB_NESTS) farmScreens().render(FarmScreens.NESTS);
        else if (tab == TAB_FARM && farmPage != 0) farmScreens().render(farmPage);
        else if (tab == TAB_FARM) showFarmHome();
        else if (tab == TAB_INCUBATION) showIncubationHome();
        else if (tab == TAB_JOURNAL) showJournal();
        else if (tab == TAB_NEWS) showNews();
        else if (tab == TAB_GUIDES) showGuides();
        else showSettings();
        navigation();
    }

    private void navigation() {
        String[] names = {"Главная", "Инкубация", "Журнал", "Новости", "Настройки"};
        for (int i = 0; i < names.length; i++) {
            int index = i;
            boolean selected = tab == i || ((tab == TAB_GUIDES || tab == TAB_NESTS) && i == TAB_INCUBATION);
            TextView item = Ui.text(this, names[i], 11, selected ? Ui.GREEN : Ui.MUTED, selected);
            item.setGravity(Gravity.CENTER); item.setMinHeight(Ui.dp(this, 44));
            item.setBackground(Ui.bg(selected ? 0xFFE9F5EB : Color.WHITE, 12, 0, 0, this));
            bottom.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
            item.setClickable(true); item.setFocusable(true);
            item.setOnClickListener(v -> { tab = index; selectedBatch = -1; farmPage=0; render(); });
        }
    }

    private void title(String headline, String sub) {
        Ui.addMargin(body, Ui.text(this, headline, 28, Ui.DARK, true), this, 4);
        if (sub != null) Ui.addMargin(body, Ui.text(this, sub, 14, Ui.MUTED, false), this, 22);
    }

    private TextView button(String name, boolean filled, Runnable click) {
        TextView b = Ui.button(this, name, filled);
        b.setOnClickListener(v -> click.run());
        return b;
    }

    private void section(String name) {
        Ui.space(body, this, 18);
        Ui.addMargin(body, Ui.text(this, name, 19, Ui.DARK, true), this, 13);
    }

    private void row(LinearLayout parent, String label, String value) {
        LinearLayout line = Ui.horizontal(this);
        TextView a = Ui.text(this, label, 14, Ui.MUTED, false);
        TextView b = Ui.text(this, value, 14, Ui.DARK, true);
        b.setGravity(Gravity.END);
        line.addView(a, new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        Ui.addMargin(parent, line, this, 10);
    }

    private List<Batch> all() { try (Db db = new Db(this)) { return db.allBatches(); } }

    private FarmScreens farmScreens() {
        return new FarmScreens(this, body, this::render,
                () -> {
                    if (tab == TAB_NESTS) tab = TAB_INCUBATION;
                    else { tab = TAB_FARM; farmPage = 0; }
                    render();
                },
                page -> {
                    if (page == FarmScreens.NESTS) tab = TAB_NESTS;
                    else { tab = TAB_FARM; farmPage = page; }
                    render();
                });
    }

    private void showFarmHome() {
        title("Наседка", "Моё хозяйство · всё важное в одном месте");
        List<Batch> batches = all();
        int active = FarmOverview.activeCount(batches);
        int eggs = FarmOverview.eggsInActiveIncubations(batches);
        LinearLayout hero = Ui.card(this);
        hero.setBackground(Ui.bg(0xFFE3F0E6, 20, 0, 0, this));
        Ui.addMargin(hero, Ui.text(this, "МОЁ ХОЗЯЙСТВО", 12, Ui.GREEN, true), this, 7);
        Ui.addMargin(hero, Ui.text(this, active == 0 ? "Начнём с инкубации"
                : "Активных инкубаций: " + active, 23, Ui.DARK, true), this, 9);
        Ui.addMargin(hero, Ui.text(this, active == 0
                ? "Добавьте первую закладку, и здесь появятся ближайшие действия."
                : "Яиц в активных закладках: " + eggs
                    + ". Это не количество птиц в хозяйстве.", 14, Ui.MUTED, false), this, 13);
        hero.addView(button("Перейти к инкубации", true, () -> { tab = TAB_INCUBATION; render(); }));
        Ui.addMargin(body, hero, this, 14);

        if (!Prefs.notifications(this) || !hasNotificationPermission()) {
            LinearLayout warning = Ui.card(this);
            Ui.addMargin(warning, Ui.text(this, "Напоминания требуют настройки",
                    16, Ui.AMBER, true), this, 8);
            Ui.addMargin(warning, Ui.text(this, "Проверьте разрешение уведомлений.",
                    14, Ui.MUTED, false), this, 10);
            warning.addView(button("Открыть настройки", false, () -> { tab = TAB_SETTINGS; render(); }));
            Ui.addMargin(body, warning, this, 12);
        }

        farmScreens().appendHome();

        section("Ближайшие действия");
        long now = System.currentTimeMillis();
        List<Reminder> candidates = new ArrayList<>();
        Set<String> completed = new HashSet<>();
        try (Db db = new Db(this)) {
            for (Batch b : batches) if (b.active) {
                completed.addAll(db.doneKeys(b.id));
                candidates.addAll(AlarmScheduler.plan(this, b));
            }
        } catch (IllegalArgumentException badSettings) {
            Ui.addMargin(body, Ui.text(this, "Ошибка времени напоминаний: "
                    + badSettings.getMessage(), 14, Ui.AMBER, false), this, 12);
            return;
        }
        List<Reminder> upcoming = FarmOverview.nextActions(candidates, completed, now, 4);
        if (upcoming.isEmpty()) Ui.addMargin(body, Ui.text(this, active == 0
                ? "Пока нет активных инкубаций."
                : "В ближайшие двое суток незавершённых действий нет.",
                14, Ui.MUTED, false), this, 12);
        for (Reminder r : upcoming) {
            LinearLayout task = Ui.card(this);
            Ui.addMargin(task, Ui.text(this,
                    (r.at < now ? "ТРЕБУЕТ ВНИМАНИЯ · " : "ЗАПЛАНИРОВАНО · ") + format(r.at),
                    12, r.at < now ? Ui.AMBER : Ui.GREEN, true), this, 6);
            Ui.addMargin(task, Ui.text(this, r.title, 15, Ui.DARK, true), this, 10);
            task.addView(button("Открыть закладку", false, () -> {
                tab = TAB_INCUBATION; selectedBatch = r.batchId; render();
            }));
            Ui.addMargin(body, task, this, 10);
        }
        Ui.addMargin(body, Ui.text(this,
                "Задачи поголовья и ухода доступны в разделе «Моё хозяйство → Задачи». "
                + "Для них пока не настроены отдельные Android-уведомления.",
                12, Ui.MUTED, false), this, 10);
    }

    private void showIncubationHome() {
        title("Инкубация", "Выберите способ или откройте существующую закладку");
        LinearLayout choices = Ui.card(this);
        Ui.addMargin(choices, Ui.text(this, "Способ инкубации", 18, Ui.DARK, true), this, 8);
        Ui.addMargin(choices, Ui.text(this,
                "В инкубаторе — подбор модели, подготовка и календарь. Под наседкой — отдельный дневник птицы и гнезда.",
                13, Ui.MUTED, false), this, 12);
        Ui.addMargin(choices, button("В инкубаторе · подготовка и закладка",
                true, this::beginIncubatorPreparation), this, 9);
        choices.addView(button("Под наседкой · дневник и закладки", false,
                () -> { tab = TAB_NESTS; render(); }));
        Ui.addMargin(body, choices, this, 13);
        List<Batch> batches = all();
        long count = batches.stream().filter(b -> b.active).count();
        LinearLayout hero = Ui.card(this);
        hero.setBackground(Ui.bg(0xFFE3F0E6, 20, 0, 0, this));
        Ui.addMargin(hero, Ui.text(this, "ТЕКУЩИЕ ЗАКЛАДКИ", 12, Ui.GREEN, true), this, 7);
        Ui.addMargin(hero, Ui.text(this, count + " активных закладок", 24, Ui.DARK, true), this, 8);
        Ui.addMargin(hero, Ui.text(this,
                count == 0 ? "Добавьте первую закладку — календарь напоминаний появится автоматически."
                           : "Контроль условий, события и записи собраны в одном месте.",
                14, Ui.MUTED, false), this, 15);
        hero.addView(button("Подсказки по «Несушке»", false, () -> { tab = TAB_GUIDES; render(); }));
        Ui.addMargin(body, hero, this, 14);
        if (RemoteFeed.hasUpdate(this)) {
            LinearLayout upgrade = Ui.card(this);
            RemoteFeed.Release r=RemoteFeed.cachedRelease(this);
            Ui.addMargin(upgrade, Ui.text(this, "Доступна новая версия: "+r.version, 16, Ui.GREEN, true), this, 9);
            upgrade.addView(button("Скачать обновление", false, () -> ChannelUpdater.offer(this,r)));
            Ui.addMargin(body, upgrade, this, 12);
        }

        if (!Prefs.notifications(this) || !hasNotificationPermission()) {
            LinearLayout warn = Ui.card(this);
            Ui.addMargin(warn, Ui.text(this, "Уведомления пока не настроены", 16, Ui.AMBER, true), this, 8);
            Ui.addMargin(warn, Ui.text(this,
                    "Разрешите уведомления, иначе телефон не покажет напоминания.", 14, Ui.MUTED, false), this, 9);
            warn.addView(button("Настроить уведомления", false, () -> {tab=4;render();}));
            Ui.addMargin(body, warn, this, 12);
        }
        section("Мои закладки");
        if (batches.isEmpty()) {
            Ui.addMargin(body, Ui.text(this, "Пока нет закладок. Начните с даты закладки яиц.", 15, Ui.MUTED, false), this, 14);
        }
        for (Batch b : batches) {
            LinearLayout card = Ui.card(this);
            int day = b.dayAt(System.currentTimeMillis(), zone);
            Ui.addMargin(card, Ui.text(this, b.name, 19, Ui.DARK, true), this, 5);
            Ui.addMargin(card, Ui.text(this, b.species + " • " + DailyTimeline.eggs(b.eggs) + " • " + (b.active ? "День " + day + " из " + b.duration : "Завершена"),
                    14, Ui.MUTED, false), this, 10);
            row(card, "Ожидаемый вывод", niceDate(b.dueAt(zone)));
            if (b.active) progress(card, Math.min(1f, (float) day / b.duration));
            Ui.space(card, this, 9);
            card.addView(button("Открыть закладку", false, () -> {selectedBatch=b.id;render();}));
            Ui.addMargin(body, card, this, 12);
        }
        section("Важно знать");
        LinearLayout info = Ui.card(this);
        info.addView(Ui.text(this,
                "Приложение напоминает о проверках и хранит ваши записи. Оно не измеряет температуру, влажность и питание без внешних датчиков.",
                14, Ui.MUTED, false));
        Ui.addMargin(body, info, this, 4);
    }

    private void progress(LinearLayout parent, float progress) {
        LinearLayout track = Ui.horizontal(this);
        track.setMinimumHeight(Ui.dp(this, 9));
        track.setBackground(Ui.bg(0xFFDAE8DF, 8, 0, 0, this));
        TextView fill = new TextView(this);
        fill.setBackground(Ui.bg(Ui.GREEN, 8, 0, 0, this));
        track.addView(fill, new LinearLayout.LayoutParams(0, Ui.dp(this, 9), Math.max(0.001f, progress)));
        track.addView(new View(this), new LinearLayout.LayoutParams(0, Ui.dp(this, 9), Math.max(0.001f, 1f-progress)));
        parent.addView(track, new LinearLayout.LayoutParams(-1, Ui.dp(this, 9)));
    }

    private void showDetails() {
        Batch b;
        try (Db db = new Db(this)) { b = db.batch(selectedBatch); }
        if (b == null) {selectedBatch=-1;showIncubationHome();return;}
        Ui.addMargin(body, button("← Все закладки", false, () -> {selectedBatch = -1;render();}), this, 14);
        title(b.name, b.species + " • " + DailyTimeline.eggs(b.eggs));
        LinearLayout hero = Ui.card(this);
        hero.setBackground(Ui.bg(0xFFE3F0E6, 18, 0, 0, this));
        int day = b.dayAt(System.currentTimeMillis(), zone);
        Ui.addMargin(hero, Ui.text(this, b.active ? "ДЕНЬ ИНКУБАЦИИ" : "ЗАКЛАДКА ЗАВЕРШЕНА", 13, Ui.GREEN, true), this, 7);
        Ui.addMargin(hero, Ui.text(this, b.active ? day + " / " + b.duration : b.hatched + " выведено из " + b.eggs, 32, Ui.DARK, true), this, 10);
        if (b.active) progress(hero, Math.min(1f, (float) day / b.duration));
        Ui.space(hero, this, 14);
        row(hero, "Дата закладки", niceDate(b.startedAt));
        row(hero, "Ожидаемый вывод", niceDate(b.dueAt(zone)));
        row(hero, "Переворот", b.manualTurn ? "Ручной" : "Автоматический");
        row(hero, "Инкубатор", b.profileLabel());
        row(hero, "Модель", b.incubatorModel);
        Ui.addMargin(body, hero, this, 10);
        Ui.addMargin(body, button("Таблица инкубации · день за днём", true,
                () -> showDailyTimeline(b)), this, 14);
        LinearLayout params = Ui.card(this);
        Ui.addMargin(params, Ui.text(this, "Режим и измерения", 17, Ui.DARK, true), this, 12);
        row(params, "Целевая температура", String.format(Locale.getDefault(), "%.1f °C", b.targetTemp));
        Ui.addMargin(params, Ui.text(this, "Это ваша настройка; 38,0 °C — заводское значение терморегулятора из инструкции, не универсальная норма.", 12, Ui.MUTED, false), this, 8);
        Ui.addMargin(params, Ui.text(this, "Влажность: " + b.humidityGuidance(System.currentTimeMillis(), zone), 14, Ui.DARK, true), this, 12);
        Ui.addMargin(params, Ui.text(this, "Уход за водой:\n" + b.waterGuidance(), 12, Ui.MUTED, false), this, 14);
        Ui.addMargin(params, Ui.text(this,
                "Воздухообмен: " + (b.isNesushka()
                ? "в штатном режиме следите, чтобы вентиляционные отверстия оставались свободными."
                : "сверьте требования с инструкцией своей модели."),
                12, Ui.MUTED, false), this, 10);
        Db.Entry latest = null;
        try (Db db = new Db(this)) {
            List<Db.Entry> recent = db.entries(b.id, 20);
            for (Db.Entry e : recent) if (e.temperature != null || e.humidity != null) {latest=e;break;}
        }
        if (latest != null) {
            String values = (latest.temperature == null ? "—" : latest.temperature + " °C") + "  /  "
                    + (latest.humidity == null ? "—" : latest.humidity + " %");
            Ui.addMargin(params, Ui.text(this, "Последняя запись: " + values, 15, Ui.GREEN, true), this, 7);
            Ui.addMargin(params, Ui.text(this, format(latest.time), 12, Ui.MUTED, false), this, 10);
            if (latest.temperature != null && Math.abs(latest.temperature - b.targetTemp) >= 0.7) {
                Ui.addMargin(params, Ui.text(this, "⚠ Зафиксировано отклонение от заданной температуры. Проверьте датчик и устройство.", 14, Ui.AMBER, true), this, 10);
            }
            if (latest.humidity != null && b.humidityOutOfRange(latest.humidity, latest.time, zone)) {
                Ui.addMargin(params, Ui.text(this, "⚠ Влажность отличается от диапазона в инструкции. Проверьте измерение и уровень воды.", 14, Ui.AMBER, true), this, 10);
            }
        }
        if (b.active) params.addView(button("+ Записать температуру и влажность", true, () -> record(b)));
        Ui.addMargin(body, params, this, 8);

        section("Ближайшие действия");
        if (!b.active) {
            Ui.addMargin(body, Ui.text(this, "Закладка закрыта. Напоминания остановлены.", 14, Ui.MUTED, false), this, 12);
        } else {
            List<Reminder> reminders = AlarmScheduler.plan(this, b);
            Set<String> done;
            try (Db db = new Db(this)) { done = db.doneKeys(b.id); }
            long now = System.currentTimeMillis();
            int shown = 0;
            for (Reminder r : reminders) {
                if (done.contains(r.key) || r.at < now - 36 * 3600000L || r.at > now + 72 * 3600000L) continue;
                LinearLayout action = Ui.card(this);
                Ui.addMargin(action, Ui.text(this, (r.at < now ? "НУЖНО ВЫПОЛНИТЬ · " : "ЗАПЛАНИРОВАНО · ") + format(r.at), 12,
                        r.at < now ? Ui.AMBER : Ui.GREEN, true), this, 6);
                Ui.addMargin(action, Ui.text(this, r.title.replace(" · " + b.name, ""), 16, Ui.DARK, true), this, 5);
                Ui.addMargin(action, Ui.text(this, r.message, 13, Ui.MUTED, false), this, 10);
                action.addView(button("✓ Отметить выполненным", false, () -> {
                    try (Db db = new Db(this)) { db.complete(r); }
                    Notifications.dismiss(this, r.key); AlarmScheduler.reschedule(this); render();
                }));
                Ui.addMargin(body, action, this, 10);
                if (++shown >= 3) break;
            }
            if (shown == 0) Ui.addMargin(body, Ui.text(this, "В ближайшие 3 дня незавершённых действий нет.", 14, Ui.MUTED, false), this, 12);
        }
        section("История наблюдений");
        List<Db.Entry> entries;
        try (Db db = new Db(this)) { entries = db.entries(b.id, 6); }
        if (entries.isEmpty()) Ui.addMargin(body, Ui.text(this, "Записей пока нет. Добавьте первые показания.", 14, Ui.MUTED, false), this, 12);
        for (Db.Entry e : entries) {
            LinearLayout c = Ui.card(this);
            Ui.addMargin(c, Ui.text(this, format(e.time), 13, Ui.GREEN, true), this, 5);
            String values = (e.temperature != null ? "🌡 " + e.temperature + " °C   " : "") +
                    (e.humidity != null ? "Влажность " + e.humidity + "%" : "");
            if (!values.isEmpty()) Ui.addMargin(c, Ui.text(this, values, 15, Ui.DARK, true), this, 4);
            if (!e.text.isEmpty()) c.addView(Ui.text(this, e.text, 13, Ui.MUTED, false));
            Ui.addMargin(body, c, this, 9);
        }
        Ui.space(body, this, 12);
        Ui.addMargin(body, button("Ключевые даты и рекомендации", false, () -> fullCalendar(b)), this, 8);
        Ui.addMargin(body, button("Экспортировать историю в CSV", false, () -> exportStart(b)), this, 8);
        Ui.addMargin(body, button("Изменить закладку", false, () -> editBatch(b)), this, 8);
        if (b.active) Ui.addMargin(body, button("Завершить инкубацию", false, () -> finish(b)), this, 8);
        if (!b.active && b.hatched>0) {
            boolean transferred;
            try(FarmDb db=new FarmDb(this)) { transferred=db.incubatorTransferred(b.id); }
            if(transferred) Ui.addMargin(body,Ui.text(this,
                    "✓ Молодняк из этой закладки уже учтён в поголовье.",
                    13,Ui.GREEN,true),this,8);
            else Ui.addMargin(body,button("Перенести птенцов в поголовье",false,
                    ()->farmScreens().transferIncubator(b)),this,8);
        }
        else Ui.addMargin(body, button("Возобновить закладку", false, () -> {
            try(FarmDb db=new FarmDb(this)) {
                if(db.incubatorTransferred(b.id)) {
                    new AlertDialog.Builder(this)
                            .setMessage("Молодняк из этого вывода уже перенесён в поголовье. "
                                    + "Повторное открытие партии может нарушить историю. "
                                    + "Для нового вывода создайте новую закладку.")
                            .setPositiveButton("Понятно",null).show();
                    return;
                }
            }
            new AlertDialog.Builder(this).setMessage("Вернуть закладку в активные? Будущие напоминания снова появятся.")
                    .setNegativeButton("Отмена", null).setPositiveButton("Возобновить", (dialog, which) -> {
                        b.active = true; try (Db db = new Db(this)) { db.saveBatch(b); }
                        AlarmScheduler.reschedule(this); render();
                    }).show();
        }), this, 8);
        Ui.addMargin(body, button("Удалить закладку и журнал", false, () -> {
            new AlertDialog.Builder(this).setTitle("Удалить без восстановления?")
                    .setMessage("Закладка, журнал и все выполненные задачи будут удалены с устройства.")
                    .setNegativeButton("Отмена", null).setPositiveButton("Удалить", (dialog, which) -> {
                        try (Db db = new Db(this)) { db.deleteBatch(b.id); }
                        selectedBatch = -1; AlarmScheduler.reschedule(this); render();
                    }).show();
        }), this, 8);
    }


    private void showDailyTimeline(Batch batch) {
        List<DailyTimeline.Observation> observed = new ArrayList<>();
        Set<String> finished;
        try (Db db = new Db(this)) {
            finished = db.doneKeys(batch.id);
            for (Db.Entry e : db.entries(batch.id, 100000))
                observed.add(new DailyTimeline.Observation(e.time, e.temperature,
                        e.humidity, e.text));
        }
        List<DailyTimeline.Day> days = DailyTimeline.build(batch, zone,
                AlarmScheduler.plan(this, batch), finished, observed);
        long now=System.currentTimeMillis();
        java.time.LocalDate today=Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        LinearLayout content=Ui.vertical(this);
        content.setPadding(Ui.dp(this,14),Ui.dp(this,12),Ui.dp(this,14),Ui.dp(this,12));
        Ui.addMargin(content,Ui.text(this,
                "Каждая строка — отдельный день. Дата вывода показана отдельно. "
                + "Незаписанные показатели не заменяются нулями. Нажмите на день для подробностей.",
                13,Ui.MUTED,false),this,12);
        if(!batch.active) Ui.addMargin(content,Ui.text(this,
                "Закладка завершена: показаны сохранённые записи. Исторический график "
                + "уведомлений мог отличаться от текущих настроек.",
                12,Ui.AMBER,false),this,12);
        for(DailyTimeline.Day day:days) {
            LinearLayout card=Ui.card(this);
            boolean current=day.date.equals(today);
            if(current)card.setBackground(Ui.bg(0xFFE3F0E6,16,1,Ui.STROKE,this));
            String label=day.expectedHatch?"Ожидаемый вывод":"День "+day.dayNumber;
            if(current)label+=" · сегодня";
            Ui.addMargin(card,Ui.text(this,label+" · "+day.date.format(DATE),
                    16,Ui.DARK,true),this,6);
            TextView status=Ui.text(this,dayLine(day),12,Ui.MUTED,false);
            Ui.addMargin(card,status,this,6);
            TextView indicator=Ui.text(this,"Подробнее ▾",13,Ui.GREEN,true);
            Ui.addMargin(card,indicator,this,8);
            LinearLayout details=Ui.vertical(this);
            details.setVisibility(View.GONE);
            if(day.plan.isEmpty()) Ui.addMargin(details,Ui.text(this,
                    "Запланированных действий нет.",12,Ui.MUTED,false),this,8);
            for(Reminder reminder:day.plan) {
                LinearLayout item=Ui.card(this);
                Ui.addMargin(item,Ui.text(this,format(reminder.at)+" · "+
                        reminder.title.replace(" · "+batch.name,""),13,Ui.DARK,true),this,4);
                Ui.addMargin(item,Ui.text(this,reminder.message,12,Ui.MUTED,false),this,5);
                TextView doneStatus=Ui.text(this,day.completed(reminder)?"✓ Выполнено":
                        reminder.at<=now?"Ожидает выполнения":"Запланировано",
                        12,day.completed(reminder)?Ui.GREEN:Ui.MUTED,true);
                Ui.addMargin(item,doneStatus,this,6);
                if(batch.active && !day.completed(reminder) && reminder.at<=now) {
                    TextView mark=button("✓ Отметить выполненным",false,()->{
                        try(Db db=new Db(this)) {db.complete(reminder);}
                        day.markCompleted(reminder);
                        doneStatus.setText("✓ Выполнено");doneStatus.setTextColor(Ui.GREEN);
                        status.setText(dayLine(day));
                        Notifications.dismiss(this,reminder.key);
                        AlarmScheduler.reschedule(this);
                    });
                    mark.setOnClickListener(v->{
                        try(Db db=new Db(this)) {db.complete(reminder);}
                        day.markCompleted(reminder);
                        doneStatus.setText("✓ Выполнено");doneStatus.setTextColor(Ui.GREEN);
                        status.setText(dayLine(day));mark.setVisibility(View.GONE);
                        Notifications.dismiss(this,reminder.key);
                        AlarmScheduler.reschedule(this);
                    });
                    Ui.addMargin(item,mark,this,4);
                }
                Ui.addMargin(details,item,this,7);
            }
            if(day.facts.isEmpty())Ui.addMargin(details,Ui.text(this,
                    "Фактических записей нет.",12,Ui.MUTED,false),this,8);
            for(DailyTimeline.Observation observation:day.facts) {
                String values=(observation.temperature==null?"":"Температура "+
                        observation.temperature+" °C  ")+(observation.humidity==null?"":
                        "Влажность "+observation.humidity+" %");
                if(!values.isEmpty())Ui.addMargin(details,Ui.text(this,
                        format(observation.at)+" · "+values,12,Ui.GREEN,true),this,5);
                if(!observation.note.trim().isEmpty())Ui.addMargin(details,Ui.text(this,
                        format(observation.at)+" · "+observation.note,
                        12,Ui.MUTED,false),this,7);
            }
            if(batch.active && current)Ui.addMargin(details,
                    button("Записать показания сейчас",false,()->record(batch)),this,6);
            card.addView(details);
            indicator.setOnClickListener(v->{
                boolean open=details.getVisibility()!=View.VISIBLE;
                details.setVisibility(open?View.VISIBLE:View.GONE);
                indicator.setText(open?"Свернуть ▴":"Подробнее ▾");
            });
            Ui.addMargin(content,card,this,9);
        }
        ScrollView scroll=new ScrollView(this);
        scroll.addView(content);
        new AlertDialog.Builder(this)
                .setTitle("Дневник · "+batch.name)
                .setView(scroll).setPositiveButton("Закрыть",null).show();
    }

    private String dayLine(DailyTimeline.Day day) {
        String s="План "+day.plan.size()+" · Выполнено "+day.completedCount();
        s+=" · "+(day.measuredCount()==0?"Измерения: не записано":
                "Измерений: "+day.measuredCount());
        if(day.latestTemperature()!=null)s+=" · "+day.latestTemperature()+" °C";
        if(day.latestHumidity()!=null)s+=" · "+day.latestHumidity()+" %";
        if(day.notesCount()>0)s+=" · заметок: "+day.notesCount();
        return s;
    }

    private void fullCalendar(Batch b) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = Ui.vertical(this);
        content.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 14));
        Ui.addMargin(content, Ui.text(this,
                "ВОЗДУХООБМЕН И ПРОВЕТРИВАНИЕ ЯИЦ", 16, Ui.DARK, true), this, 9);
        Ui.addMargin(content, Ui.text(this,
                "1. Постоянная вентиляция: " + VentilationAdvice.exchange(b),
                13, Ui.MUTED, false), this, 12);
        Ui.addMargin(content, Ui.text(this,
                "2. Отдельное охлаждение яиц: " + VentilationAdvice.cooling(b),
                13, Ui.MUTED, false), this, 9);
        Ui.addMargin(content, Ui.text(this,
                VentilationAdvice.source(b), 12, Ui.GREEN, true), this, 14);
        Ui.addMargin(content, Ui.text(this,
                "Ежедневные проверки: " + Prefs.checks(this),
                14, Ui.DARK, true), this, 7);
        Ui.addMargin(content, Ui.text(this,
                b.manualTurn ? "Ручной переворот: " + Prefs.turns(this)
                        : "Переворот автоматический: проверяйте работу механизма самостоятельно",
                14, Ui.MUTED, false), this, 12);
        Set<String> done;
        try (Db db = new Db(this)) { done = db.doneKeys(b.id); }
        for (Reminder r : AlarmScheduler.plan(this, b)) {
            if (r.type.equals("check") || r.type.equals("turn") || r.type.equals("spray")) continue;
            Ui.addMargin(content, Ui.text(this, format(r.at) + "  " + (done.contains(r.key) ? "✓ " : "") + r.title.replace(" · " + b.name,""),
                    14, done.contains(r.key) ? Ui.GREEN : Ui.DARK, true), this, 5);
            Ui.addMargin(content, Ui.text(this, r.message, 12, Ui.MUTED, false), this, 12);
        }
        scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("План: " + b.name).setView(scroll)
                .setPositiveButton("Закрыть", null).show();
    }

    private String niceDate(long time) {
        return Instant.ofEpochMilli(time).atZone(zone).format(DATE);
    }
    private String format(long time) {
        return Instant.ofEpochMilli(time).atZone(zone).format(SHORT);
    }

    private void showJournal() {
        title("Журнал наблюдений", "История ручных измерений и заметок");
        List<Db.Entry> entries;
        try (Db db = new Db(this)) { entries = db.entries(0, 100); }
        if (entries.isEmpty()) {
            LinearLayout empty = Ui.card(this);
            Ui.addMargin(empty, Ui.text(this, "Записей пока нет",
                    17, Ui.DARK, true), this, 8);
            Ui.addMargin(empty, Ui.text(this,
                    "Откройте закладку в разделе «Инкубация», чтобы записать "
                    + "фактическую температуру, влажность или заметку. "
                    + "Показания вводятся вручную.",
                    14, Ui.MUTED, false), this, 12);
            empty.addView(button("Открыть инкубацию", false, () -> {
                tab = TAB_INCUBATION; selectedBatch = -1; render();
            }));
            Ui.addMargin(body, empty, this, 10);
        }
        for (Db.Entry e : entries) {
            Batch b;
            try (Db db = new Db(this)) { b = db.batch(e.batchId); }
            LinearLayout card = Ui.card(this);
            Ui.addMargin(card, Ui.text(this, (b == null ? "Закладка" : b.name) + " • " + format(e.time), 13, Ui.GREEN, true), this, 8);
            String s = (e.temperature == null ? "" : "Температура: " + e.temperature + " °C   ") +
                       (e.humidity == null ? "" : "Влажность: " + e.humidity + " %");
            if (!s.isEmpty()) Ui.addMargin(card, Ui.text(this, s, 15, Ui.DARK, true), this, 7);
            if (!e.text.isEmpty()) card.addView(Ui.text(this, e.text, 14, Ui.MUTED, false));
            Ui.addMargin(body, card, this, 10);
        }
    }

    private void guideCard(String name, String instructions, String source) {
        LinearLayout c = Ui.card(this);
        Ui.addMargin(c, Ui.text(this, name, 17, Ui.DARK, true), this, 10);
        Ui.addMargin(c, Ui.text(this, instructions, 14, Ui.MUTED, false), this, 12);
        c.addView(Ui.text(this, source, 12, Ui.GREEN, true));
        Ui.addMargin(body, c, this, 12);
    }

    private void showGuides() {
        Ui.addMargin(body, button("← К инкубации", false, () -> { tab = TAB_INCUBATION; render(); }), this, 12);
        title("Понятные подсказки", "Основа — руководство инкубатора «Несушка»");
        guideCard("1. Установите инкубатор",
                "Обеспечьте приток воздуха, исключите сквозняки и прямое солнце. Температура помещения — желательно 20–25 °C, недопустимо ниже 18 или выше 28 °C. Не ставьте на пол.", "Инструкция · стр. 1");
        guideCard("2. Подготовьте воду и яйца",
                "Для углублений рекомендуется тёплая вода около 35 °C. До закладки прогрейте инкубатор. Яйца должны быть чистыми, немытыми, без дефектов; для контроля переворота сделайте метки «О» и «Х». Перед открытием крышки отключайте 220 В.", "Инструкция · стр. 4, 6");
        guideCard("3. Следите за влажностью",
                "Для куриных яиц руководство рекомендует 50–55%, за два дня до вывода — 65–70%. Для уток и гусей на выводе — до 70–80%. Проверять и доливать воду рекомендуется примерно каждые три дня. Объём и количество ячеек зависят от модели.", "Инструкция · стр. 4–5");
        guideCard("Воздухообмен и проветривание яиц",
                VentilationAdvice.exchange(new Batch())
                + " " + VentilationAdvice.cooling(new Batch()),
                VentilationAdvice.source(new Batch()));
        guideCard("4. Проверяйте развитие",
                "Первое просвечивание — около 7–8 суток, второе — около 11–13 суток. Фиксируйте результаты. Не удаляйте сомнительные яйца только по одному неуверенному наблюдению: при необходимости обратитесь к специалисту.", "Инструкция · стр. 7");
        guideCard("5. Остановите переворот",
                "За два дня до предполагаемого вывода яйца перестают переворачивать, а устройство и решётку переворота убирают согласно инструкции. Для ручного переворота часы напоминаний можно изменить в настройках.", "Инструкция · стр. 6, 12");
        guideCard("6. Если выключилось электричество",
                "Проверьте фактическую температуру и предусмотренное моделью резервное питание. В руководстве приведены рекомендации для кратких и длительных отключений, но телефон не определит отключение самостоятельно. Не перекрывайте вентиляцию без оценки безопасности и состояния оборудования.", "Инструкция · стр. 7");
        guideCard("7. После вывода",
                "Подготовьте сухое, тёплое место для птенцов и контролируйте их состояние. По завершении отключите инкубатор от сети, очистите и тщательно просушите согласно руководству.", "Инструкция · стр. 9");
        guideCard("Важное про температуру и переворот",
                "38,0 °C указано как заводская настройка терморегулятора, а не универсальный режим для всех видов. Интервал автоматического переворота в разных разделах руководства указан по-разному — 1 или 4 часа. Проверьте своё устройство и настройки.", "Инструкция · стр. 5–6, 12");
    }

    private void showNews() {
        title("Новости Наседки", "Обновления, новые модели и полезные материалы");
        List<RemoteFeed.News> items=RemoteFeed.news(this);
        if (items.isEmpty()) Ui.addMargin(body, Ui.text(this, "Новости появятся после первой публикации на GitHub.", 15, Ui.MUTED, false), this, 16);
        for (RemoteFeed.News item:items) {
            LinearLayout card=Ui.card(this);
            Ui.addMargin(card, Ui.text(this, item.date, 12, Ui.GREEN, true), this, 8);
            Ui.addMargin(card, Ui.text(this, item.title, 18, Ui.DARK, true), this, 9);
            card.addView(Ui.text(this,item.text,14,Ui.MUTED,false));
            Ui.addMargin(body,card,this,12);
        }
        Ui.space(body,this,6);
        Ui.addMargin(body, button("Обновить новости", false, () -> refreshRemote(true)), this, 9);
        Ui.addMargin(body,Ui.text(this,"Новости загружаются из официального открытого GitHub-репозитория. Последняя полученная лента доступна без интернета. Реклама в новостях пока не включена.",12,Ui.MUTED,false),this,10);
    }

    private void settingBlock(String title, String value, Runnable action) {
        LinearLayout c = Ui.card(this);
        Ui.addMargin(c, Ui.text(this, title, 16, Ui.DARK, true), this, 7);
        Ui.addMargin(c, Ui.text(this, value, 14, Ui.MUTED, false), this, 12);
        c.addView(button("Изменить", false, action));
        Ui.addMargin(body, c, this, 10);
    }

    private boolean hasNotificationPermission() {
        boolean runtimeGranted = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        return runtimeGranted && manager != null && manager.areNotificationsEnabled();
    }
    private boolean exactAllowed() {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager m = (AlarmManager) getSystemService(ALARM_SERVICE);
        return m != null && m.canScheduleExactAlarms();
    }

    private void showSettings() {
        title("Настройки", "Уведомления, обновления и информация");
        settingBlock("Проверка условий", Prefs.checks(this) + " ежедневно", () -> timesDialog("checks", "Проверка условий", Prefs.checks(this)));
        settingBlock("Ручной переворот", Prefs.turns(this) + " · пока до вывода более двух дней", () -> timesDialog("turns", "Напоминания о перевороте", Prefs.turns(this)));
        settingBlock("Орошение (утки и гуси)", Prefs.sprays(this) + " · если включено в закладке", () -> timesDialog("sprays", "Напоминания об орошении", Prefs.sprays(this)));
        section("Системные разрешения");
        LinearLayout c = Ui.card(this);
        Ui.addMargin(c, Ui.text(this, "Уведомления: " + (hasNotificationPermission() ? "разрешены" : "не разрешены"), 15, Ui.DARK, true), this, 8);
        Ui.addMargin(c, Ui.text(this, "Точные будильники: " + (exactAllowed() ? "доступны" : "ограничены системой"), 15, Ui.DARK, true), this, 11);
        Ui.addMargin(c, Ui.text(this, "Без разрешения на точные напоминания Android может доставить их с задержкой. При режиме энергосбережения задержки возможны и с разрешением.", 13, Ui.MUTED, false), this, 13);
        Ui.addMargin(c, button("Разрешить уведомления", false, this::askNotificationPermission), this, 8);
        Ui.addMargin(c, button("Открыть разрешения уведомлений Android", false,
                this::openAppNotificationSettings), this, 8);
        c.addView(button("Настроить точные напоминания", false, this::askExactAlarm));
        Ui.addMargin(body, c, this, 12);
        LinearLayout n = Ui.card(this);
        Switch enabled = new Switch(this);
        enabled.setText("Планировать напоминания в приложении");
        enabled.setChecked(Prefs.notifications(this));
        enabled.setOnCheckedChangeListener((buttonView, checked) -> {
            Prefs.get(this).edit().putBoolean("notifications", checked).apply();
            AlarmScheduler.reschedule(this);
        });
        n.addView(enabled);
        Ui.addMargin(body, n, this, 12);
        section("Звук и экран уведомлений");
        Notifications.channels(this);
        LinearLayout alerts = Ui.card(this);
        Ui.addMargin(alerts, Ui.text(this,
                "Уход: " + Notifications.channelState(this, Notifications.CH_DAILY),
                14, Ui.DARK, true), this, 9);
        Ui.addMargin(alerts, Ui.text(this,
                "Важные события: " + Notifications.channelState(this, Notifications.CH_URGENT),
                14, Ui.DARK, true), this, 12);
        Ui.addMargin(alerts, Ui.text(this,
                AlertDiagnostics.describe(this), 13, Ui.MUTED, false), this, 12);
        Ui.addMargin(alerts, button("Прослушать системный сигнал", false, () ->
                toast(AlertDiagnostics.preview(this))), this, 8);
        Ui.addMargin(alerts, button("Открыть громкость и звуки Android", false, () -> {
            try { startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS)); }
            catch (ActivityNotFoundException unavailable) {
                toast("Откройте Настройки → Звуки и вибрация.");
            }
        }), this, 10);
        Ui.addMargin(alerts, Ui.text(this,
                "Звук и вибрация зависят от настроек Android: громкости уведомлений, "
                + "режима «Не беспокоить» и параметров каждого канала. "
                + "Для подсветки заблокированного экрана на HONOR включите: "
                + "Настройки → Уведомления → Другие настройки уведомлений → Включение экрана. "
                + "Экран может не включаться при некоторых настройках телефона.",
                13, Ui.MUTED, false), this, 12);
        Ui.addMargin(alerts, button("Настроить звук важных событий", false,
                () -> openChannelSettings(Notifications.CH_URGENT)), this, 8);
        Ui.addMargin(alerts, button("Настроить звук проверок", false,
                () -> openChannelSettings(Notifications.CH_DAILY)), this, 8);
        alerts.addView(button("Открыть настройки уведомлений телефона", false, () -> {
            try { Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS); i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()); startActivity(i); }
            catch (ActivityNotFoundException e) {
                toast("Найдите раздел «Уведомления» в настройках телефона.");
            }
        }));
        Ui.addMargin(body, alerts, this, 12);
        section("Проверка фоновых уведомлений");
        LinearLayout test = Ui.card(this);
        Ui.addMargin(test, Ui.text(this,
                "Сначала проверьте уведомление сразу, затем назначьте тест через 2 минуты и заблокируйте экран.",
                14, Ui.MUTED, false), this, 12);
        Ui.addMargin(test, button("Показать тест сейчас", false, () -> {
            Notifications.channels(this);
            Notifications.test(this);
            toast(hasNotificationPermission() ? "Команда показа отправлена Android." : "Сначала разрешите уведомления.");
        }), this, 8);
        Ui.addMargin(test, button("Тест через 2 минуты", true, () -> {
            try {
                AlarmScheduler.scheduleTest(this);
                render();
                toast("Тест назначен. Заблокируйте экран и не открывайте Наседку 3–5 минут.");
            } catch (RuntimeException issue) {
                toast("Не удалось назначить тест: " + issue.getMessage());
            }
        }), this, 12);
        long planned = Prefs.get(this).getLong("diagnostic_test_at", 0);
        long fired = Prefs.get(this).getLong("diagnostic_test_fired_at", 0);
        if (planned > 0) {
            String result = fired > 0 ? ("Получен сигнал: " + format(fired)
                    + " (задержка " + Math.max(0, (fired - planned) / 1000) + " сек.)")
                    : ("Ожидается: " + format(planned)
                    + (System.currentTimeMillis() > planned ? " · время прошло, сигнал пока не зарегистрирован" : ""));
            Ui.addMargin(test, Ui.text(this, result, 13, fired > 0 ? Ui.GREEN : Ui.MUTED, fired > 0), this, 10);
        }
        long late = Prefs.get(this).getLong("last_alarm_lateness_ms", -1);
        if (late >= 0) Ui.addMargin(test, Ui.text(this,
                "Последнее напоминание: задержка " + late / 1000 + " сек.", 12, Ui.MUTED, false), this, 7);
        Ui.addMargin(test, button("Открыть настройки энергосбережения", false, () -> {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (ActivityNotFoundException e) { toast("Найдите «Оптимизация батареи» в настройках телефона."); }
        }), this, 8);
        Ui.addMargin(body, test, this, 12);
        section("Новости и обновления");
        LinearLayout remote=Ui.card(this);
        Ui.addMargin(remote,Ui.text(this,"Канал: "+RemoteFeed.channel().toUpperCase(Locale.ROOT),15,Ui.DARK,true),this,8);
        Ui.addMargin(remote,Ui.text(this,"Репозиторий: "+(RemoteFeed.repository(this).isEmpty()?"не задан":RemoteFeed.repository(this)),13,Ui.MUTED,false),this,9);
        Ui.addMargin(remote,Ui.text(this,ChannelUpdater.label(this),13,Ui.MUTED,false),this,12);
        Ui.addMargin(remote,button("Проверить обновления и новости",false,()->refreshRemote(true)),this,8);
        if (RemoteFeed.isGithub()) {
            RemoteFeed.Release release=RemoteFeed.cachedRelease(this);
            if(RemoteFeed.hasUpdate(this)&&release!=null)
                Ui.addMargin(remote,button("Скачать новую версию "+release.version,true,()->ChannelUpdater.offer(this,release)),this,8);
            if (ChannelUpdater.hasPending(this))
                Ui.addMargin(remote,button("Продолжить установку APK",
                        false,()->ChannelUpdater.pending(this)),this,8);
        } else {
            Ui.addMargin(remote,button("Открыть магазин для обновлений",false,()->ChannelUpdater.pending(this)),this,8);
        }
        Ui.addMargin(remote,Ui.text(this,"Автоматическая проверка официального GitHub — не чаще раза за 24 часа при запуске; ручная проверка доступна всегда. Для магазинных версий установка APK через GitHub запрещена. SDK Google Play/RuStore пока не подключены.",12,Ui.MUTED,false),this,6);
        Ui.addMargin(body,remote,this,12);
        section("О приложении");
        LinearLayout about = Ui.card(this);
        about.addView(Ui.text(this, "Наседка • "+BuildConfig.VERSION_NAME+"\nОткрытый исходный код (Apache 2.0). Журнал остаётся на устройстве. Экспорт CSV доступен в каждой закладке.\n\nПервая поддерживаемая линейка — «Несушка». Для других инкубаторов доступен ручной профиль без неподтверждённых норм. Поддержка новых моделей будет расширяться.\n\nРекламных SDK и отслеживания пока нет. Реклама будет добавлена отдельно, только после настройки политики конфиденциальности и согласий пользователей.\n\nПриложение — помощник по расписанию, а не система дистанционного управления нагревом или замена инструкции и измерительных приборов.", 14, Ui.MUTED, false));
        Ui.addMargin(body, about, this, 4);
    }

    private void timesDialog(String key, String label, String current) {
        LinearLayout box = Ui.vertical(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), 0);
        Ui.addMargin(box, Ui.text(this, "Введите часы через запятую в формате HH:mm", 14, Ui.MUTED, false), this, 10);
        EditText field = Ui.field(this, "08:00,20:00", current, false);
        box.addView(field);
        AlertDialog d = new AlertDialog.Builder(this).setTitle(label).setView(box)
                .setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null).create();
        d.setOnShowListener(dialog -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                List<LocalTime> times = Prefs.times(field.getText().toString());
                ArrayList<String> values = new ArrayList<>();
                for (LocalTime t : times) values.add(t.toString());
                Prefs.get(this).edit().putString(key, String.join(",", values)).apply();
                AlarmScheduler.reschedule(this); d.dismiss(); render();
            } catch (IllegalArgumentException problem) { field.setError(problem.getMessage()); }
        }));
        d.show();
    }

    private void openChannelSettings(String channelId) {
        try {
            Intent i = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            i.putExtra(Settings.EXTRA_CHANNEL_ID, channelId);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            try {
                Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                startActivity(i);
            } catch (ActivityNotFoundException unavailable) {
                toast("Откройте Настройки телефона → Уведомления → Наседка.");
            }
        }
    }

    private void openAppNotificationSettings() {
        try {
            Intent settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            settings.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(settings);
        } catch (ActivityNotFoundException unavailable) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException missing) {
                toast("Откройте настройки телефона → Приложения → Наседка → Уведомления.");
            }
        }
    }

    private void askNotificationPermission() {
        if (hasNotificationPermission()) {
            toast("Уведомления уже разрешены.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED
                && !Prefs.get(this).getBoolean("requested_post_notifications", false)) {
            Prefs.get(this).edit().putBoolean("requested_post_notifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        } else {
            // On HONOR/MagicOS a repeated request may not display a system dialog.
            openAppNotificationSettings();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100) { AlarmScheduler.reschedule(this); render(); }
    }

    private void askExactAlarm() {
        if (Build.VERSION.SDK_INT < 31 || exactAllowed()) { toast("Точные напоминания уже доступны."); return; }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("Откройте системные настройки приложения → будильники и напоминания.");
        }
    }


    /** Selection for a new batch. Editing an existing batch never repeats this wizard. */
    private static final class PreparationChoice {
        final boolean nesushka;
        final String model;

        PreparationChoice(boolean nesushka, String model) {
            this.nesushka = nesushka;
            this.model = model;
        }

        String note() {
            return IncubationPreparation.journalNote(nesushka, model);
        }
    }

    private void beginIncubatorPreparation() {
        LinearLayout form = Ui.vertical(this);
        form.setPadding(Ui.dp(this, 22), Ui.dp(this, 8),
                Ui.dp(this, 22), Ui.dp(this, 10));
        Ui.addMargin(form, Ui.text(this, "Выберите оборудование. Для неизвестных моделей подсказки и таблицы «Несушки» не применяются.",
                14, Ui.MUTED, false), this, 12);
        Ui.label(form, this, "Тип инкубатора");
        Spinner type = new Spinner(this);
        type.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Несушка БИ-1/БИ-2", "Другая модель — ручной профиль"}));
        Ui.addMargin(form, type, this, 12);
        TextView capacityLabel=Ui.text(this,"Вместимость «Несушки» (яиц)",13,Ui.MUTED,true);
        Ui.addMargin(form,capacityLabel,this,3);
        Spinner capacity = new Spinner(this);
        capacity.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"36", "63", "77", "104"}));
        Ui.addMargin(form, capacity, this, 12);
        TextView manualLabel=Ui.text(this,"Название другой модели",13,Ui.MUTED,true);
        Ui.addMargin(form,manualLabel,this,3);
        EditText custom = Ui.field(this, "Например, Мой инкубатор", "", false);
        Ui.addMargin(form, custom, this, 12);
        Runnable switchFields = () -> {
            boolean other = type.getSelectedItemPosition() == 1;
            capacityLabel.setVisibility(other ? View.GONE : View.VISIBLE);
            capacity.setVisibility(other ? View.GONE : View.VISIBLE);
            manualLabel.setVisibility(other ? View.VISIBLE : View.GONE);
            custom.setVisibility(other ? View.VISIBLE : View.GONE);
        };
        type.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                 int position, long id) { switchFields.run(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        switchFields.run();
        AlertDialog select = new AlertDialog.Builder(this)
                .setTitle("Подготовка · шаг 1 из 3")
                .setView(form)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Далее", null).create();
        select.setOnShowListener(ignore -> select.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    boolean nesushka = type.getSelectedItemPosition() == 0;
                    String selected = nesushka ? capacity.getSelectedItem().toString()
                                               : custom.getText().toString().trim();
                    if (selected.isEmpty() || selected.length() > 80) {
                        if (!nesushka) custom.setError("Укажите название до 80 символов.");
                        else toast("Выберите вместимость инкубатора.");
                        return;
                    }
                    select.dismiss();
                    showPreparationChecklist(new PreparationChoice(nesushka, selected));
                }));
        select.show();
    }

    private void showPreparationChecklist(PreparationChoice choice) {
        LinearLayout form = Ui.vertical(this);
        form.setPadding(Ui.dp(this, 20), Ui.dp(this, 8),
                Ui.dp(this, 20), Ui.dp(this, 10));
        String label = choice.nesushka ? "Несушка БИ-1/БИ-2 · " + choice.model + " яиц"
                                      : "Другая модель · " + choice.model;
        Ui.addMargin(form, Ui.text(this, label, 17, Ui.DARK, true), this, 8);
        Ui.addMargin(form, Ui.text(this,
                choice.nesushka ? "Проверочный список по руководству «Несушки» (2021). Отметьте фактически выполненные действия."
                                : "Общий список готовности. Правила, нормы воды и режимы именно вашего инкубатора необходимо сверить с его инструкцией.",
                13, Ui.MUTED, false), this, 12);
        ArrayList<CheckBox> boxes = new ArrayList<>();
        for (IncubationPreparation.Step step : IncubationPreparation.steps(choice.nesushka)) {
            LinearLayout item = Ui.card(this);
            CheckBox checked = new CheckBox(this);
            checked.setText(step.title);
            checked.setTextColor(Ui.DARK);
            checked.setTextSize(15);
            checked.setContentDescription(step.title + ". " + step.explanation);
            Ui.addMargin(item, checked, this, 5);
            Ui.addMargin(item, Ui.text(this, step.explanation,
                    13, Ui.MUTED, false), this, 6);
            item.addView(Ui.text(this, step.source,
                    12, Ui.GREEN, false));
            Ui.addMargin(form, item, this, 10);
            boxes.add(checked);
        }
        Ui.addMargin(form, Ui.text(this,
                "Перед открытием крышки отключайте инкубатор от электросети. Приложение не измеряет температуру и влажность самостоятельно. Отметки сохранятся в журнале только после создания закладки.",
                13, Ui.AMBER, true), this, 8);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Подготовка · шаг 2 из 3")
                .setView(scroll)
                .setNegativeButton("Назад", (d, which) -> beginIncubatorPreparation())
                .setPositiveButton("К закладке", null).create();
        dialog.setOnShowListener(ignore -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    for (CheckBox checked : boxes) {
                        if (!checked.isChecked()) {
                            toast("Отметьте выполненные пункты. Незавершённую подготовку можно продолжить позже.");
                            return;
                        }
                    }
                    dialog.dismiss();
                    editBatch(null, choice);
                }));
        dialog.show();
    }

    private void editBatch(Batch existing) {
        editBatch(existing, null);
    }

    private void editBatch(Batch existing, PreparationChoice prepared) {
        Batch original = existing;
        Calendar start = Calendar.getInstance();
        if (existing != null) start.setTimeInMillis(existing.startedAt);
        LinearLayout form = Ui.vertical(this);
        form.setPadding(Ui.dp(this, 22), Ui.dp(this, 6), Ui.dp(this, 22), Ui.dp(this, 14));
        Ui.label(form, this, "Название закладки");
        EditText name = Ui.field(this, "Например, Куры — ноябрь", existing == null ? "" : existing.name, false);
        Ui.addMargin(form, name, this, 12);
        Ui.label(form, this, "Вид птицы");
        Spinner species = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Batch.SPECIES);
        species.setAdapter(adapter);
        int sIndex = 0;
        if (existing != null) for (int i=0;i<Batch.SPECIES.length;i++) if (Batch.SPECIES[i].equals(existing.species)) sIndex=i;
        species.setSelection(sIndex);
        Ui.addMargin(form, species, this, 10);
        Ui.label(form, this, "Тип инкубатора");
        Spinner profile=new Spinner(this);
        profile.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Несушка БИ-1/БИ-2", "Другой инкубатор (ручной профиль)"}));
        profile.setSelection(prepared != null ? (prepared.nesushka ? 0 : 1)
                : (existing != null && !existing.isNesushka() ? 1 : 0));
        Ui.addMargin(form,profile,this,10);
        TextView nesushkaLabel=Ui.text(this, "Модель инкубатора «Несушка» (по вместимости)",13,Ui.MUTED,true);
        Ui.addMargin(form,nesushkaLabel,this,3);
        Spinner model = new Spinner(this);
        model.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Batch.INCUBATOR_MODELS));
        String selectedModel = prepared != null && prepared.nesushka
                ? prepared.model : (existing != null ? existing.incubatorModel : "");
        for (int i = 0; i < Batch.INCUBATOR_MODELS.length; i++) {
            if (Batch.INCUBATOR_MODELS[i].equals(selectedModel)) model.setSelection(i);
        }
        Ui.addMargin(form, model, this, 10);
        TextView otherLabel=Ui.text(this,"Название другой модели (произвольное)",13,Ui.MUTED,true);
        Ui.addMargin(form,otherLabel,this,3);
        EditText customModel=Ui.field(this,"Например, Инкубатор дома",
                prepared != null && !prepared.nesushka ? prepared.model
                        : (existing != null && !existing.isNesushka() ? existing.incubatorModel : ""),false);
        Ui.addMargin(form,customModel,this,12);
        Runnable visibility=()->{boolean custom=profile.getSelectedItemPosition()==1;
            model.setVisibility(custom?View.GONE:View.VISIBLE);
            nesushkaLabel.setVisibility(custom?View.GONE:View.VISIBLE);
            customModel.setVisibility(custom?View.VISIBLE:View.GONE);
            otherLabel.setVisibility(custom?View.VISIBLE:View.GONE);
        };
        profile.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id){visibility.run();}
            @Override public void onNothingSelected(AdapterView<?> parent){}
        });
        visibility.run();
        if (prepared != null) {
            profile.setEnabled(false);
            model.setEnabled(false);
            customModel.setEnabled(false);
            Ui.addMargin(form, Ui.text(this,
                    "Модель выбрана на шаге 1, подготовка отмечена на шаге 2. Чтобы сменить модель, отмените создание и пройдите подготовку заново.",
                    12, Ui.MUTED, false), this, 9);
        }
        Ui.label(form, this, "Количество яиц");
        EditText eggs = Ui.field(this, "Количество", existing == null ? "" : String.valueOf(existing.eggs), true);
        Ui.addMargin(form, eggs, this, 10);
        Ui.label(form, this, "Срок инкубации в днях (редактируется)");
        EditText duration = Ui.field(this, "Дней", String.valueOf(existing == null ? 21 : existing.duration), true);
        Ui.addMargin(form, duration, this, 12);
        TextView date = Ui.button(this, "", false);
        Runnable updateDate = () -> date.setText("Дата и время: " + start.get(Calendar.DAY_OF_MONTH) + "." + (start.get(Calendar.MONTH)+1) + "." + start.get(Calendar.YEAR) + "  " + String.format(Locale.getDefault(), "%02d:%02d", start.get(Calendar.HOUR_OF_DAY), start.get(Calendar.MINUTE)));
        updateDate.run();
        date.setOnClickListener(v -> new DatePickerDialog(this, (picker, y, m, d) -> {
            start.set(Calendar.YEAR, y); start.set(Calendar.MONTH, m); start.set(Calendar.DAY_OF_MONTH, d);
            new TimePickerDialog(this, (picker2, h, min) -> {
                start.set(Calendar.HOUR_OF_DAY, h); start.set(Calendar.MINUTE, min); start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0);
                updateDate.run();
            }, start.get(Calendar.HOUR_OF_DAY), start.get(Calendar.MINUTE), true).show();
        }, start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH)).show());
        Ui.addMargin(form, date, this, 13);
        Ui.label(form, this, "Целевая температура, °C");
        EditText temp = Ui.field(this, "Введите по инструкции своего вида птицы", existing == null ? "" : String.valueOf(existing.targetTemp).replace('.', ','), true);
        temp.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        Ui.addMargin(form, temp, this, 7);
        Ui.addMargin(form, Ui.text(this, "Для «Несушки» 38,0 °C — заводская установка терморегулятора, а не универсальная норма. Уточните температуру по виду птицы и инструкции модели.", 12, Ui.MUTED, false), this, 14);
        Switch manual = new Switch(this);
        manual.setText("Ручной переворот: напоминать");
        manual.setChecked(existing != null && existing.manualTurn);
        Ui.addMargin(form, manual, this, 13);
        Switch spray = new Switch(this);
        spray.setText("Напоминать об орошении (утки, гуси)");
        spray.setChecked(existing != null && existing.spray);
        Ui.addMargin(form, spray, this, 10);
        final boolean[] first = {true};
        species.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                String chosen = Batch.SPECIES[pos];
                boolean aquatic = Batch.DUCK.equals(chosen) || Batch.GOOSE.equals(chosen);
                spray.setVisibility(aquatic ? View.VISIBLE : View.GONE);
                if (!first[0]) duration.setText(String.valueOf(Batch.usualDays(chosen)));
                first[0] = false;
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        ScrollView scroller = new ScrollView(this);
        scroller.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null && prepared != null ? "Подготовка · шаг 3 из 3" : (existing == null ? "Новая закладка" : "Изменить закладку"))
                .setView(scroller)
                .setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String n = name.getText().toString().trim();
                if (n.isEmpty() || n.length() > 80) throw new IllegalArgumentException("Укажите название до 80 символов.");
                int amount = Integer.parseInt(eggs.getText().toString().trim());
                int days = Integer.parseInt(duration.getText().toString().trim());
                String targetText = temp.getText().toString().trim();
                if (targetText.isEmpty()) throw new IllegalArgumentException(
                        "Введите целевую температуру по инструкции своего инкубатора и вида птицы.");
                double target = Double.parseDouble(targetText.replace(',', '.'));
                if (amount < 1 || amount > 5000) throw new IllegalArgumentException("Количество яиц: от 1 до 5000.");
                if (days < 3 || days > 60) throw new IllegalArgumentException("Срок: от 3 до 60 суток.");
                if (!Double.isFinite(target) || target < 20 || target > 45) throw new IllegalArgumentException("Температура: от 20 до 45 °C. Уточните режим своего инкубатора.");
                if (profile.getSelectedItemPosition()==0 && (target<33 || target>43))
                    throw new IllegalArgumentException("Для «Несушки» предел установки регулятора: 33–43 °C.");
                Batch b = original == null ? new Batch() : original;
                b.name=n; b.species=species.getSelectedItem().toString();
                b.profileId=profile.getSelectedItemPosition()==0?"nesushka":"other";
                b.incubatorModel=b.isNesushka()?model.getSelectedItem().toString():customModel.getText().toString().trim();
                if(!b.isNesushka() && b.incubatorModel.isEmpty())throw new IllegalArgumentException("Укажите название своей модели.");
                if(b.incubatorModel.length()>80)throw new IllegalArgumentException("Название модели: не более 80 символов.");
                b.eggs=amount;
                b.duration=days; b.startedAt=start.getTimeInMillis(); b.targetTemp=target;
                b.manualTurn=manual.isChecked(); b.spray=b.isNesushka() && spray.isChecked() && (Batch.DUCK.equals(b.species)||Batch.GOOSE.equals(b.species));
                try (Db db = new Db(this)) {
                    if (original == null && prepared != null) {
                        db.savePreparedBatch(b, prepared.note());
                    } else {
                        db.saveBatch(b);
                    }
                }
                selectedBatch=b.id; tab=TAB_INCUBATION;
                AlarmScheduler.reschedule(this); dialog.dismiss(); render();
            } catch (IllegalArgumentException error) {
                new AlertDialog.Builder(this).setMessage(error.getMessage() == null ? "Проверьте введённые числа." : error.getMessage()).setPositiveButton("Понятно", null).show();
            }
        }));
        dialog.show();
    }

    private void record(Batch batch) {
        LinearLayout form = Ui.vertical(this);
        form.setPadding(Ui.dp(this, 22), Ui.dp(this, 8), Ui.dp(this, 22), Ui.dp(this, 14));
        Ui.label(form, this, "Фактическая температура, °C (можно пропустить)");
        EditText t = Ui.field(this, "Например, 37,8", "", true);
        Ui.addMargin(form, t, this, 12);
        Ui.label(form, this, "Фактическая влажность, % (можно пропустить)");
        EditText h = Ui.field(this, "Например, 53", "", true);
        Ui.addMargin(form, h, this, 12);
        Ui.label(form, this, "Заметка");
        EditText note = Ui.field(this, "Например, долил воду", "", false);
        form.addView(note);
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Записать наблюдение")
                .setView(form).setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null).create();
        d.setOnShowListener(ignore -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String ts=t.getText().toString().trim(), hs=h.getText().toString().trim(), n=note.getText().toString().trim();
                Double temp=ts.isEmpty()?null:Double.parseDouble(ts.replace(',','.'));
                Double hum=hs.isEmpty()?null:Double.parseDouble(hs.replace(',','.'));
                if (temp==null && hum==null && n.isEmpty()) throw new IllegalArgumentException("Заполните хотя бы одно поле.");
                if (temp!=null && (!Double.isFinite(temp) || temp < 0 || temp > 60)) throw new IllegalArgumentException("Температура: 0–60 °C.");
                if (hum!=null && (!Double.isFinite(hum) || hum < 0 || hum > 100)) throw new IllegalArgumentException("Влажность: 0–100%.");
                try (Db db = new Db(this)) { db.record(batch.id, temp, hum, n); }
                d.dismiss(); render();
            } catch (IllegalArgumentException error) {
                new AlertDialog.Builder(this).setMessage(error.getMessage() == null ? "Проверьте значения." : error.getMessage()).setPositiveButton("Понятно",null).show();
            }
        }));
        d.show();
    }

    private void finish(Batch b) {
        EditText hatched = Ui.field(this, "Количество выведенных", "0", true);
        LinearLayout form=Ui.vertical(this);
        form.setPadding(Ui.dp(this, 22), Ui.dp(this, 8), Ui.dp(this, 22), Ui.dp(this, 6));
        Ui.label(form, this, "Сколько птенцов вывелось?"); form.addView(hatched);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Завершить инкубацию")
                .setMessage("После завершения новые напоминания для этой закладки отправляться не будут.")
                .setView(form).setNegativeButton("Отмена", null)
                .setPositiveButton("Завершить", null).create();
        d.setOnShowListener(ignore -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int n=Integer.parseInt(hatched.getText().toString().trim());
                if(n<0||n>b.eggs) throw new IllegalArgumentException("От 0 до " + b.eggs + " птенцов.");
                b.hatched=n; b.active=false;
                try (Db db=new Db(this)) {db.saveBatch(b);}
                AlarmScheduler.reschedule(this);d.dismiss();render();
            } catch (IllegalArgumentException ex) {hatched.setError(ex.getMessage()==null?"Введите число":ex.getMessage());}
        }));
        d.show();
    }

    private void exportStart(Batch b) {
        exportingBatch=b.id;
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE, "nasedka_" + b.id + ".csv");
        try {startActivityForResult(i, REQUEST_EXPORT);} catch(ActivityNotFoundException e) {toast("Приложение для сохранения файлов не найдено.");}
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode==REQUEST_EXPORT && resultCode==RESULT_OK && data!=null && data.getData()!=null) {
            try {
                CsvExport.write(this, exportingBatch, data.getData());
                toast("CSV сохранён.");
            } catch (Exception e) {new AlertDialog.Builder(this).setMessage("Ошибка сохранения: " + e.getMessage()).setPositiveButton("Понятно",null).show();}
        }
    }

    private void toast(String message) {Toast.makeText(this, message, Toast.LENGTH_LONG).show();}
}
