package org.nasedka.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;

/**
 * Read-only Android audio diagnostics. Does not bypass Do Not Disturb, screen lock,
 * notification settings or the user's selected output volume.
 */
public final class AlertDiagnostics {
    private AlertDiagnostics() {}

    private static Ringtone testRingtone;

    private static String ringerLabel(int mode) {
        if (mode == AudioManager.RINGER_MODE_SILENT) return "без звука";
        if (mode == AudioManager.RINGER_MODE_VIBRATE) return "вибрация";
        if (mode == AudioManager.RINGER_MODE_NORMAL) return "обычный";
        return "не определён";
    }

    private static String interruptionLabel(int mode) {
        if (mode == NotificationManager.INTERRUPTION_FILTER_ALL) return "все уведомления";
        if (mode == NotificationManager.INTERRUPTION_FILTER_PRIORITY) return "только приоритетные";
        if (mode == NotificationManager.INTERRUPTION_FILTER_ALARMS) return "только будильники";
        if (mode == NotificationManager.INTERRUPTION_FILTER_NONE) return "ничего";
        return "не определён";
    }

    /** Indicates likely sound blockers, not the real result of a future background alert. */
    public static String describe(Context context) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        NotificationManager notifications = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (audio == null || notifications == null) return "Звуковая система недоступна.";

        int volume = audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION);
        String mode = ringerLabel(audio.getRingerMode());
        String dnd = interruptionLabel(notifications.getCurrentInterruptionFilter());
        Uri actual = RingtoneManager.getActualDefaultRingtoneUri(
                context, RingtoneManager.TYPE_NOTIFICATION);
        String selected = actual == null ? "не выбран" : "выбран";

        StringBuilder result = new StringBuilder()
                .append("Режим звонка: ").append(mode)
                .append("\nГромкость уведомлений: ").append(volume).append("/").append(max)
                .append("\n«Не беспокоить»: ").append(dnd)
                .append("\nСистемный звук уведомления: ").append(selected);

        if (audio.getRingerMode() != AudioManager.RINGER_MODE_NORMAL ||
                volume == 0 ||
                notifications.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL ||
                actual == null) {
            result.append("\nВнимание: настройки телефона могут блокировать звук.");
        }
        return result.toString();
    }

    /**
     * Explicit user-initiated sound preview using the IMPORTANT notification channel.
     * Does not autoplay in the background and does not change system audio settings.
     * @return status text; playback may still be inaudible under system policy.
     */
    public static synchronized String preview(Context context) {
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return "Служба уведомлений недоступна.";
        NotificationChannel channel = manager.getNotificationChannel(Notifications.CH_URGENT);
        if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE)
            return "Важный канал отключён или не создан. Проверьте его настройки.";
        if (!manager.areNotificationsEnabled())
            return "Сначала разрешите уведомления для приложения.";
        Uri sound = channel.getSound();
        if (sound == null || RingtoneManager.getActualDefaultRingtoneUri(
                context, RingtoneManager.TYPE_NOTIFICATION) == null &&
                sound.equals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))) {
            return "Для важных уведомлений не выбран звуковой сигнал. Выберите его в Android.";
        }
        try {
            if (testRingtone != null && testRingtone.isPlaying()) testRingtone.stop();
            testRingtone = RingtoneManager.getRingtone(context, sound);
            if (testRingtone == null) return "Система не смогла открыть звук канала.";
            testRingtone.play();
            return "Воспроизведение запрошено. Если тишина — проверьте громкость, режим и «Не беспокоить».";
        } catch (SecurityException | IllegalStateException | IllegalArgumentException error) {
            return "Не удалось воспроизвести системный звук. Выберите другой звук в настройках канала.";
        }
    }
}
