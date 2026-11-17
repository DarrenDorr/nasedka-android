package org.nasedka.app;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** One local incubation run. No personal information is uploaded. */
public class Batch {
    public long id;
    public String name;
    public String species;
    public String incubatorModel = "Не указана";
    public String profileId = "nesushka"; // New brands use a separate profile and no Nesushka-specific water tables.
    public boolean isNesushka() { return "nesushka".equals(profileId); }
    public String profileLabel() { return isNesushka() ? "Несушка БИ-1/БИ-2" : "Другой инкубатор (ручной профиль)"; }
    public static final String[] INCUBATOR_MODELS = {"Не указана", "36", "63", "77", "104"};
    public int eggs;
    public int duration;
    public long startedAt;
    public boolean manualTurn;
    public boolean spray;
    public double targetTemp;
    public boolean active = true;
    public int hatched;

    public static final String CHICKEN = "Куры";
    public static final String DUCK = "Утки";
    public static final String GOOSE = "Гуси";
    public static final String TURKEY = "Индейки";
    public static final String QUAIL = "Перепела";
    public static final String OTHER = "Другая птица";
    public static final String[] SPECIES = {CHICKEN, DUCK, GOOSE, TURKEY, QUAIL, OTHER};

    public static int usualDays(String species) {
        switch (species) {
            case CHICKEN: return 21;
            case DUCK: return 30; // manual: 28-33
            case GOOSE: return 29; // manual: 28-30
            case TURKEY: return 28;
            case QUAIL: return 16; // manual: 15-17
            default: return 21; // editable placeholder; not a species recommendation
        }
    }

    public LocalDate startDate(ZoneId zone) {
        return Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate();
    }

    public long dueAt(ZoneId zone) {
        return Instant.ofEpochMilli(startedAt).atZone(zone).plusDays(duration).toInstant().toEpochMilli();
    }

    public int dayAt(long now, ZoneId zone) {
        long diff = ChronoUnit.DAYS.between(startDate(zone), Instant.ofEpochMilli(now).atZone(zone).toLocalDate());
        if (diff < 0) return 0;
        return (int) Math.min(duration, diff + 1);
    }

    public String waterGuidance() {
        if (!isNesushka()) return "Для этого инкубатора таблица воды пока не подтверждена. Уточните режим в руководстве модели.";
        switch (incubatorModel == null ? "" : incubatorModel) {
            case "36": return "Долив ~0,15 л раз в 3 дня · 2 ячейки\n45–50%: 1 ячейка; 58–65%: 1–2; 63–75%: 2.";
            case "63": return "Долив ~0,2 л раз в 3 дня · 6 ячеек\n45–50%: 2–3; 58–65%: 4–5; 63–75%: 6.";
            case "77": return "Долив ~0,2 л раз в 3 дня · 8 ячеек\n45–50%: 4; 58–65%: 5–6; 63–75%: 8.";
            case "104": return "Долив ~0,3 л раз в 3 дня · 6 ячеек\n45–50%: 3; 58–65%: 4–5; 63–75%: 6.";
            default: return "Укажите вместимость модели, чтобы увидеть таблицу долива воды.";
        }
    }

    public boolean humidityOutOfRange(double value, long measuredAt, ZoneId zone) {
        if (!isNesushka()) return false; // No brand-specific target is claimed for other incubators.
        long remaining = ChronoUnit.DAYS.between(
                Instant.ofEpochMilli(measuredAt).atZone(zone).toLocalDate(),
                Instant.ofEpochMilli(dueAt(zone)).atZone(zone).toLocalDate());
        boolean end = remaining <= 2;
        if (CHICKEN.equals(species)) return end ? value < 65 || value > 70 : value < 50 || value > 55;
        if ((DUCK.equals(species) || GOOSE.equals(species)) && end) return value < 70 || value > 80;
        return false; // The manual does not specify a single target for other stages/species.
    }

    public String humidityGuidance(long now, ZoneId zone) {
        if (!isNesushka()) return "Уточните диапазон в руководстве своего инкубатора и для породы";
        long until = ChronoUnit.DAYS.between(
                Instant.ofEpochMilli(now).atZone(zone).toLocalDate(),
                Instant.ofEpochMilli(dueAt(zone)).atZone(zone).toLocalDate());
        boolean hatchStage = until <= 2;
        if (CHICKEN.equals(species)) return hatchStage ? "65–70% · перед выводом" : "50–55% · основной этап";
        if (DUCK.equals(species) || GOOSE.equals(species)) {
            return hatchStage ? "70–80% · период вывода" : "Уточните норму для породы";
        }
        return "Уточните норму для породы";
    }
}
