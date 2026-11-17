package org.nasedka.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

public final class Prefs {
    private Prefs() {}
    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences("incuba_settings", Context.MODE_PRIVATE);
    }
    public static boolean notifications(Context c) { return get(c).getBoolean("notifications", true); }
    public static String checks(Context c) { return get(c).getString("checks", "08:00,20:00"); }
    public static String turns(Context c) { return get(c).getString("turns", "08:00,12:00,16:00,20:00"); }
    public static String sprays(Context c) { return get(c).getString("sprays", "09:00,18:00"); }

    /** Strictly accepts HH:mm entries with no duplicates. */
    public static List<LocalTime> times(String input) {
        if (input == null || input.trim().isEmpty()) throw new IllegalArgumentException("Задайте хотя бы одно время.");
        LinkedHashSet<LocalTime> parsed = new LinkedHashSet<>();
        for (String part : input.split(",", -1)) {
            String candidate = part.trim();
            if (!candidate.matches("([01][0-9]|2[0-3]):[0-5][0-9]")) {
                throw new IllegalArgumentException("Используйте HH:mm через запятую, например 08:00,20:00.");
            }
            if (!parsed.add(LocalTime.parse(candidate))) throw new IllegalArgumentException("Уберите повторяющиеся часы.");
        }
        if (parsed.size() > 12) throw new IllegalArgumentException("Не более 12 уведомлений в сутки.");
        ArrayList<LocalTime> result = new ArrayList<>(parsed);
        Collections.sort(result);
        return result;
    }
}
