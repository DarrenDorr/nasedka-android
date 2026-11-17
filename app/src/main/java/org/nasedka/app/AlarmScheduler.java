package org.nasedka.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One pending grouped alarm; each delivery schedules the next event for the full incubation period. */
public final class AlarmScheduler {
    private AlarmScheduler() {}
    private static final String SCHEDULED = "scheduled_keys";
    private static final String NEXT_AT = "next_alarm_at";
    private static final String LAST_DELIVERED = "last_delivered_alarm_at";
    private static final String TEST_AT = "diagnostic_test_at";
    private static final String TEST_FIRED = "diagnostic_test_fired_at";

    public static List<Reminder> plan(Context c, Batch batch) {
        List<LocalTime> checks = Prefs.times(Prefs.checks(c));
        List<LocalTime> turns = Prefs.times(Prefs.turns(c));
        List<LocalTime> sprays = Prefs.times(Prefs.sprays(c));
        return Plan.generate(batch, ZoneId.systemDefault(), checks, turns, sprays);
    }

    private static PendingIntent nextIntent(Context c, long at) {
        Intent i = new Intent(c, ReminderReceiver.class);
        i.setAction(ReminderReceiver.ACTION_TICK);
        i.setData(Uri.parse("incuba://alarm/next"));
        i.putExtra("scheduled_at", at);
        return PendingIntent.getBroadcast(c, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent testIntent(Context c, long at) {
        Intent i = new Intent(c, ReminderReceiver.class);
        i.setAction(ReminderReceiver.ACTION_TEST);
        i.setData(Uri.parse("incuba://diagnostic/test"));
        i.putExtra("scheduled_at", at);
        return PendingIntent.getBroadcast(c, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void sendAlarm(Context c, AlarmManager manager, long when, PendingIntent target) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, target);
            return;
        }
        try {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, target);
        } catch (SecurityException denied) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, target);
        }
    }

    /** Test alarm is independent of the incubation timetable and survives normal rescheduling. */
    public static synchronized long scheduleTest(Context c) {
        Context app = c.getApplicationContext();
        AlarmManager alarm = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) throw new IllegalStateException("Будильники Android недоступны");
        long at = System.currentTimeMillis() + 2L * 60L * 1000L;
        PendingIntent target = testIntent(app, at);
        alarm.cancel(target);
        sendAlarm(app, alarm, at, target);
        Prefs.get(app).edit().putLong(TEST_AT, at).putLong(TEST_FIRED, 0).apply();
        return at;
    }

    public static void testReceived(Context c, long scheduledAt) {
        Prefs.get(c).edit().putLong(TEST_FIRED, System.currentTimeMillis())
                .putLong("diagnostic_test_expected_at", scheduledAt).apply();
        Notifications.test(c);
    }

    private static void removeLegacyAlarms(Context app, AlarmManager alarm) {
        Set<String> previous = new HashSet<>(Prefs.get(app).getStringSet(SCHEDULED, new HashSet<>()));
        for (String key : previous) {
            Intent intent = new Intent(app, ReminderReceiver.class);
            intent.setAction(ReminderReceiver.ACTION_FIRE);
            intent.setData(Uri.parse("incuba://alarm/" + Uri.encode(key)));
            PendingIntent old = PendingIntent.getBroadcast(app, 0, intent,
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (old != null) { alarm.cancel(old); old.cancel(); }
        }
        if (!previous.isEmpty()) Prefs.get(app).edit().remove(SCHEDULED).apply();
    }

    /** Reconstructs missed/delayed events; shows only the newest actionable notifications. */
    public static synchronized void deliverTick(Context c, long expectedAt) {
        long now = System.currentTimeMillis();
        if (expectedAt <= 0 || expectedAt > now + 60_000) return;
        long last = Prefs.get(c).getLong(LAST_DELIVERED, 0);
        if (expectedAt <= last) return;
        long lower = Math.max(last, Math.max(expectedAt - 1000, now - 12L * 60L * 60L * 1000L));
        ArrayList<Reminder> due = new ArrayList<>();
        try (Db db = new Db(c)) {
            for (Batch b : db.allBatches()) {
                if (!b.active) continue;
                Set<String> done = db.doneKeys(b.id);
                for (Reminder r : plan(c, b)) {
                    if (r.at > lower && r.at <= now && !done.contains(r.key)) due.add(r);
                }
            }
        } catch (IllegalArgumentException badSettings) {
            Prefs.get(c).edit().putString("last_alarm_error", badSettings.getMessage()).apply();
            return;
        }
        due.sort(Comparator.comparingLong((Reminder r) -> r.at).reversed());
        // Do not flood the phone after a long delay; missed actions remain visible in batch details.
        if (Prefs.notifications(c)) {
            for (int i = Math.min(due.size(), 8) - 1; i >= 0; i--) {
                Notifications.show(c, due.get(i));
            }
        }
        Prefs.get(c).edit().putLong(LAST_DELIVERED, now)
                .putLong("last_alarm_expected_at", expectedAt)
                .putLong("last_alarm_lateness_ms", Math.max(0, now - expectedAt)).apply();
    }

    public static synchronized void reschedule(Context c) {
        Context app = c.getApplicationContext();
        AlarmManager alarm = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) return;
        removeLegacyAlarms(app, alarm);
        long now = System.currentTimeMillis();
        long previousAt = Prefs.get(app).getLong(NEXT_AT, 0);
        if (previousAt > 0 && previousAt <= now
                && previousAt > Prefs.get(app).getLong(LAST_DELIVERED, 0)) {
            // A delayed alarm may have been held by battery optimization.
            deliverTick(app, previousAt);
        }
        PendingIntent tick = nextIntent(app, 0);
        alarm.cancel(tick);
        if (!Prefs.notifications(app)) {
            Prefs.get(app).edit().remove(NEXT_AT).apply();
            return;
        }
        long nextAt = Long.MAX_VALUE;
        try (Db db = new Db(app)) {
            for (Batch b : db.allBatches()) {
                if (!b.active) continue;
                Set<String> done = db.doneKeys(b.id);
                for (Reminder r : plan(app, b)) {
                    if (r.at > now + 1000 && !done.contains(r.key) && r.at < nextAt) {
                        nextAt = r.at;
                    }
                }
            }
        } catch (IllegalArgumentException badSettings) {
            Prefs.get(app).edit().putString("last_alarm_error", badSettings.getMessage()).apply();
            return;
        }
        if (nextAt < Long.MAX_VALUE) {
            sendAlarm(app, alarm, nextAt, nextIntent(app, nextAt));
            Prefs.get(app).edit().putLong(NEXT_AT, nextAt).remove("last_alarm_error").apply();
        } else Prefs.get(app).edit().remove(NEXT_AT).apply();
    }

    public static void snooze(Context c, String key, long batchId, String type, String title, String message) {
        if (!Prefs.notifications(c)) return;
        AlarmManager alarm = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) return;
        long after15 = System.currentTimeMillis() + 15 * 60 * 1000L;
        Intent intent = new Intent(c, ReminderReceiver.class);
        intent.setAction(ReminderReceiver.ACTION_FIRE);
        intent.setData(Uri.parse("incuba://snooze/" + Uri.encode(key) + "/" + after15));
        intent.putExtra("key", key); intent.putExtra("batch", batchId);
        intent.putExtra("type", type); intent.putExtra("title", title); intent.putExtra("message", message);
        PendingIntent pending = PendingIntent.getBroadcast(c, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        sendAlarm(c, alarm, after15, pending);
    }
}
