package org.nasedka.app;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure scheduling rules, independent from Android for unit testing. */
public final class Plan {
    private Plan() {}

    private static void add(List<Reminder> output, Batch b, String type, String title,
                            String description, LocalDate day, LocalTime time, ZoneId zone) {
        long at = day.atTime(time).atZone(zone).toInstant().toEpochMilli();
        String key = b.id + ":" + type + ":" + day + ":" + time;
        output.add(new Reminder(key, b.id, at, type, title, description));
    }

    public static List<Reminder> generate(Batch b, ZoneId zone,
                                          List<LocalTime> checks, List<LocalTime> turns,
                                          List<LocalTime> sprays) {
        ArrayList<Reminder> result = new ArrayList<>();
        if (!b.active || b.duration < 3 || b.duration > 60 || b.startedAt <= 0) return result;
        ZonedDateTime started = Instant.ofEpochMilli(b.startedAt).atZone(zone);
        LocalDate day0 = started.toLocalDate();
        LocalTime bedtime = started.toLocalTime().withSecond(0).withNano(0);
        long dueAt = b.dueAt(zone);
        for (int offset = 0; offset < b.duration; offset++) {
            LocalDate date = day0.plusDays(offset);
            for (LocalTime time : checks) {
                add(result, b, "check", "Проверить условия · " + b.name,
                        "Проверьте температуру, влажность, воду и приток воздуха к вентиляционным отверстиям. Запишите измеренные показания. Охлаждение яиц отдельно не назначено.", date, time, zone);
            }
            if (b.manualTurn && offset < b.duration - 2) {
                for (LocalTime time : turns) {
                    add(result, b, "turn", "Перевернуть яйца · " + b.name,
                            "Ручной переворот. После выполнения нажмите «Готово». За два дня до вывода переворот прекращается.", date, time, zone);
                }
            }
            if (b.isNesushka() && offset > 0 && offset % 3 == 0) {
                add(result, b, "water", "Проверить и долить воду · " + b.name,
                        "По инструкции «Несушки» долив рекомендован раз в 3 дня. Проверьте влажность; перед открытием отключите 220 В.", date, LocalTime.of(10, 0), zone);
            }
            if (offset == 7 || offset == 12) {
                add(result, b, "candle", "Овоскопирование · " + b.name,
                        offset == 7 ? "Первый контроль развития: около 7–8 суток инкубации."
                                : "Второй контроль: примерно 11–13 суток инкубации.", date, LocalTime.of(18, 0), zone);
            }
            if (b.isNesushka() && b.spray && (Batch.DUCK.equals(b.species) || Batch.GOOSE.equals(b.species))
                    && offset >= b.duration / 2 && offset < b.duration - 2) {
                for (LocalTime time : sprays) {
                    add(result, b, "spray", "Проверить необходимость орошения · " + b.name,
                            "В инструкции рекомендовано орошение утиных и гусиных яиц во второй половине инкубации. Уточните режим породы.", date, time, zone);
                }
            }
        }
        LocalDate stopDate = day0.plusDays(b.duration - 2);
        add(result, b, "lockdown", "Пора остановить переворот · " + b.name,
                "До ожидаемого вывода 2 дня. Отключите устройство переворота, извлеките решётку и проверьте влажность.", stopDate, bedtime, zone);
        LocalDate due = day0.plusDays(b.duration);
        add(result, b, "hatch", "Ожидаемый вывод · " + b.name,
                "Расчётная дата вывода. Проверяйте состояние птенцов и фактические условия; срок приблизительный.", due, bedtime, zone);
        result.removeIf(r -> r.at < b.startedAt || r.at > dueAt);
        result.sort(Comparator.comparingLong((Reminder r) -> r.at).thenComparing(r -> r.key));
        return result;
    }
}
