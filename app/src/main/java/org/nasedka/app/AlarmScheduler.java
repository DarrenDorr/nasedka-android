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

/** Rolling local alarm window; no remote server, no background service. */
public final class AlarmScheduler {
    private AlarmScheduler() {}
    private static final String SCHEDULED = "scheduled_keys";
    private static final int MAX_ALARMS = 120;
    private static final long WINDOW = 14L * 24 * 60 * 60 * 1000;

    public static List<Reminder> plan(Context c, Batch batch) {
        List<LocalTime> checks = Prefs.times(Prefs.checks(c));
        List<LocalTime> turns = Prefs.times(Prefs.turns(c));
        List<LocalTime> sprays = Prefs.times(Prefs.sprays(c));
        return Plan.generate(batch, ZoneId.systemDefault(), checks, turns, sprays);
    }

    private static PendingIntent alarmIntent(Context c, Reminder r) {
        Intent intent = new Intent(c, ReminderReceiver.class);
        intent.setAction(ReminderReceiver.ACTION_FIRE);
        intent.setData(Uri.parse("incuba://alarm/" + Uri.encode(r.key)));
        intent.putExtra("key", r.key);
        intent.putExtra("batch", r.batchId);
        intent.putExtra("type", r.type);
        intent.putExtra("title", r.title);
        intent.putExtra("message", r.message);
        return PendingIntent.getBroadcast(c, 0, intent,
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

    public static synchronized void reschedule(Context c) {
        Context app = c.getApplicationContext();
        AlarmManager alarm = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) return;
        Set<String> previous = new HashSet<>(Prefs.get(app).getStringSet(SCHEDULED, new HashSet<>()));
        for (String key : previous) {
            Intent intent = new Intent(app, ReminderReceiver.class);
            intent.setAction(ReminderReceiver.ACTION_FIRE);
            intent.setData(Uri.parse("incuba://alarm/" + Uri.encode(key)));
            PendingIntent old = PendingIntent.getBroadcast(app, 0, intent,
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (old != null) { alarm.cancel(old); old.cancel(); }
        }
        Set<String> scheduled = new HashSet<>();
        if (!Prefs.notifications(app)) {
            Prefs.get(app).edit().putStringSet(SCHEDULED, scheduled).apply();
            return;
        }
        long now = System.currentTimeMillis();
        ArrayList<Reminder> candidates = new ArrayList<>();
        try (Db db = new Db(app)) {
            for (Batch b : db.allBatches()) {
                if (!b.active) continue;
                Set<String> done = db.doneKeys(b.id);
                for (Reminder r : plan(app, b)) {
                    if (r.at > now + 1000L && r.at < now + WINDOW && !done.contains(r.key)) candidates.add(r);
                }
            }
        } catch (IllegalArgumentException invalidSettings) {
            // Invalid settings should not crash boot; user can correct them in Settings.
            return;
        }
        candidates.sort(Comparator.comparingLong((Reminder r) -> r.at));
        for (int i = 0; i < Math.min(MAX_ALARMS, candidates.size()); i++) {
            Reminder r = candidates.get(i);
            sendAlarm(app, alarm, r.at, alarmIntent(app, r));
            scheduled.add(r.key);
        }
        Prefs.get(app).edit().putStringSet(SCHEDULED, scheduled).apply();
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
