package org.nasedka.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

/**
 * Android notification channels control sound and vibration. The user always has the final
 * say in system settings (including silent mode, DND, lock-screen visibility and channel mute).
 */
public final class Notifications {
    private Notifications() {}

    // v2 channel IDs are intentional: Android does not allow apps to change sound/importance of
    // channels that were already created in v0.2.1-v0.2.2. Old channel IDs are not reused.
    public static final String CH_DAILY = "nasedka_care_sound_v2";
    public static final String CH_URGENT = "nasedka_important_sound_v2";

    private static NotificationChannel buildChannel(String id, String title, String description,
                                                    int importance, long[] vibration) {
        NotificationChannel channel = new NotificationChannel(id, title, importance);
        channel.setDescription(description);
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        if (sound != null) channel.setSound(sound, attributes);
        channel.enableVibration(true);
        channel.setVibrationPattern(vibration);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        return channel;
    }

    public static void channels(Context c) {
        NotificationManager manager = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        manager.createNotificationChannel(buildChannel(CH_DAILY,
                "Проверки и уход — со звуком",
                "Проверки условий, вода, ручной переворот и овоскопирование",
                NotificationManager.IMPORTANCE_DEFAULT,
                new long[]{0, 220, 160, 220}));
        manager.createNotificationChannel(buildChannel(CH_URGENT,
                "Важные этапы и тест — со звуком",
                "Остановка переворота, вывод и проверка фоновых уведомлений",
                NotificationManager.IMPORTANCE_HIGH,
                new long[]{0, 350, 190, 350}));
    }

    /** Describes channel state, not guaranteed playback under DND/muted device. */
    public static String channelState(Context c, String id) {
        NotificationManager manager = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return "Недоступно";
        NotificationChannel channel = manager.getNotificationChannel(id);
        if (channel == null) return "Не создан";
        if (!manager.areNotificationsEnabled())
            return "В приложении запрещены уведомления Android";
        if (channel.getImportance() == NotificationManager.IMPORTANCE_NONE)
            return "Отключён именно этот канал";
        String sound = channel.getSound() == null ? "без звука" : "звук настроен";
        String banners = channel.getImportance() >= NotificationManager.IMPORTANCE_HIGH
                ? ", высокий приоритет" : "";
        return sound + banners + (channel.shouldVibrate() ? ", вибрация" : "");
    }

    private static PendingIntent action(Context c, String action, Reminder r) {
        Intent i = new Intent(c, ReminderReceiver.class);
        i.setAction(action);
        i.setData(Uri.parse("incuba://action/" + action + "/" + Uri.encode(r.key)));
        i.putExtra("key", r.key);
        i.putExtra("batch", r.batchId);
        i.putExtra("type", r.type);
        i.putExtra("title", r.title);
        i.putExtra("message", r.message);
        return PendingIntent.getBroadcast(c, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static int id(String key) { return key.hashCode() & 0x7FFFFFFF; }

    public static void show(Context c, Reminder r) {
        if (Build.VERSION.SDK_INT >= 33 &&
                c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled()) return;
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.setData(Uri.parse("incuba://batch/" + r.batchId));
        open.putExtra("batch_id", r.batchId);
        PendingIntent content = PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notice = new Notification.Builder(c, r.important() ? CH_URGENT : CH_DAILY)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(r.title)
                .setContentText(r.message)
                .setStyle(new Notification.BigTextStyle().bigText(r.message))
                .setContentIntent(content)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setWhen(System.currentTimeMillis())
                .addAction(new Notification.Action.Builder(null, "Готово",
                        action(c, ReminderReceiver.ACTION_DONE, r)).build())
                .addAction(new Notification.Action.Builder(null, "Через 15 минут",
                        action(c, ReminderReceiver.ACTION_SNOOZE, r)).build())
                .build();
        manager.notify(id(r.key), notice);
    }

    public static void test(Context c) {
        if (Build.VERSION.SDK_INT >= 33 &&
                c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled()) return;
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(c, 10001, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notice = new Notification.Builder(c, CH_URGENT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Наседка: проверка звука и уведомлений")
                .setContentText("Если звук не слышен, проверьте канал уведомлений и режим телефона.")
                .setContentIntent(content)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build();
        // A notification merely updated under the same ID might not sound again.
        manager.cancel(10001);
        manager.notify(10001, notice);
    }

    public static void dismiss(Context c, String key) {
        NotificationManager manager = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(id(key));
    }
}
