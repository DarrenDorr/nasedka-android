package org.nasedka.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

public final class Notifications {
    private Notifications() {}
    private static final String CH_DAILY = "incuba_daily";
    private static final String CH_URGENT = "incuba_important";

    public static void channels(Context c) {
        NotificationManager n = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (n == null) return;
        NotificationChannel daily = new NotificationChannel(CH_DAILY, "Регулярный уход", NotificationManager.IMPORTANCE_DEFAULT);
        daily.setDescription("Температура, влажность, вода, переворот и овоскопирование");
        NotificationChannel important = new NotificationChannel(CH_URGENT, "Ключевые этапы инкубации", NotificationManager.IMPORTANCE_HIGH);
        important.setDescription("Остановка переворота и ожидаемый вывод");
        n.createNotificationChannel(daily);
        n.createNotificationChannel(important);
    }

    private static PendingIntent action(Context c, String action, Reminder r) {
        Intent i = new Intent(c, ReminderReceiver.class);
        i.setAction(action);
        i.setData(Uri.parse("incuba://action/" + action + "/" + Uri.encode(r.key)));
        i.putExtra("key", r.key); i.putExtra("batch", r.batchId);
        i.putExtra("type", r.type); i.putExtra("title", r.title); i.putExtra("message", r.message);
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static int id(String key) { return key.hashCode() & 0x7FFFFFFF; }

    public static void show(Context c, Reminder r) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.setData(Uri.parse("incuba://batch/" + r.batchId));
        open.putExtra("batch_id", r.batchId);
        PendingIntent content = PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, r.important() ? CH_URGENT : CH_DAILY)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(r.title)
                .setContentText(r.message)
                .setStyle(new Notification.BigTextStyle().bigText(r.message))
                .setContentIntent(content)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setWhen(System.currentTimeMillis())
                .addAction(new Notification.Action.Builder(null, "Готово", action(c, ReminderReceiver.ACTION_DONE, r)).build())
                .addAction(new Notification.Action.Builder(null, "Через 15 минут", action(c, ReminderReceiver.ACTION_SNOOZE, r)).build())
                .build();
        manager.notify(id(r.key), n);
    }

    public static void dismiss(Context c, String key) {
        NotificationManager manager = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(id(key));
    }
}
