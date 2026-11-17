package org.nasedka.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ReminderReceiver extends BroadcastReceiver {
    public static final String ACTION_FIRE = "org.nasedka.app.FIRE";
    public static final String ACTION_DONE = "org.nasedka.app.DONE";
    public static final String ACTION_SNOOZE = "org.nasedka.app.SNOOZE";
    public static final String ACTION_TICK = "org.nasedka.app.TICK";
    public static final String ACTION_TEST = "org.nasedka.app.TEST";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String incoming = intent.getAction();
        if (ACTION_TEST.equals(incoming)) {
            AlarmScheduler.testReceived(context, intent.getLongExtra("scheduled_at", 0));
            return;
        }
        if (ACTION_TICK.equals(incoming)) {
            AlarmScheduler.deliverTick(context, intent.getLongExtra("scheduled_at", 0));
            AlarmScheduler.reschedule(context);
            return;
        }
        String key = intent.getStringExtra("key");
        long batch = intent.getLongExtra("batch", -1);
        String type = intent.getStringExtra("type");
        String title = intent.getStringExtra("title");
        String message = intent.getStringExtra("message");
        if (key == null || batch < 1 || type == null || title == null || message == null) return;
        Reminder reminder = new Reminder(key, batch, System.currentTimeMillis(), type, title, message);
        String action = intent.getAction();
        if (ACTION_FIRE.equals(action)) {
            try (Db db = new Db(context)) {
                Batch b = db.batch(batch);
                if (b == null || !b.active || db.doneKeys(batch).contains(key)) return;
            }
            if (Prefs.notifications(context)) {
                Notifications.channels(context);
                Notifications.show(context, reminder);
            }
        } else if (ACTION_DONE.equals(action)) {
            try (Db db = new Db(context)) { db.complete(key, batch, title); }
            Notifications.dismiss(context, key);
        } else if (ACTION_SNOOZE.equals(action)) {
            AlarmScheduler.snooze(context, key, batch, type, title, message);
            Notifications.dismiss(context, key);
        }
        // Schedule the next rolling window even if the user marked an action done.
        AlarmScheduler.reschedule(context);
    }
}
