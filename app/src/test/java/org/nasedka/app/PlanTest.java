package org.nasedka.app;

import org.junit.Test;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import static org.junit.Assert.*;

public class PlanTest {
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final LocalDate START = LocalDate.of(2026, 11, 17);
    private static final List<LocalTime> CHECKS = Arrays.asList(LocalTime.of(8, 0), LocalTime.of(20, 0));
    private static final List<LocalTime> TURNS = Arrays.asList(LocalTime.of(8, 0), LocalTime.of(12, 0), LocalTime.of(16, 0), LocalTime.of(20, 0));

    private Batch batch(boolean manual) {
        Batch b = new Batch(); b.id=42; b.name="Тест"; b.species=Batch.CHICKEN;
        b.eggs=36; b.duration=21; b.targetTemp=38.0; b.active=true;
        b.manualTurn=manual;
        b.startedAt=START.atTime(10, 0).atZone(ZONE).toInstant().toEpochMilli();
        return b;
    }
    private List<Reminder> reminders(Batch b) {return Plan.generate(b,ZONE,CHECKS,TURNS,TURNS);}

    @Test public void stopsTurningExactlyTwoDaysBeforeExpectedHatch() {
        List<Reminder> r=reminders(batch(true));
        long lastTurn = r.stream().filter(x -> x.type.equals("turn")).mapToLong(x -> x.at).max().orElseThrow();
        LocalDate last = java.time.Instant.ofEpochMilli(lastTurn).atZone(ZONE).toLocalDate();
        assertEquals(START.plusDays(18), last);
        Reminder stop = r.stream().filter(x -> x.type.equals("lockdown")).findFirst().orElseThrow();
        assertEquals(START.plusDays(19), java.time.Instant.ofEpochMilli(stop.at).atZone(ZONE).toLocalDate());
        Reminder hatch = r.stream().filter(x -> x.type.equals("hatch")).findFirst().orElseThrow();
        assertEquals(START.plusDays(21), java.time.Instant.ofEpochMilli(hatch.at).atZone(ZONE).toLocalDate());
    }

    @Test public void automaticTurnHasNoManualTurnAlarms() {
        assertTrue(reminders(batch(false)).stream().noneMatch(x -> x.type.equals("turn")));
    }

    @Test public void candlingAndWaterDatesFollowManual() {
        List<Reminder> r=reminders(batch(false));
        List<LocalDate> candleDates=r.stream().filter(x -> x.type.equals("candle"))
                .map(x -> java.time.Instant.ofEpochMilli(x.at).atZone(ZONE).toLocalDate()).collect(Collectors.toList());
        assertEquals(Arrays.asList(START.plusDays(7), START.plusDays(12)),candleDates);
        List<LocalDate> waterDates=r.stream().filter(x -> x.type.equals("water"))
                .map(x -> java.time.Instant.ofEpochMilli(x.at).atZone(ZONE).toLocalDate()).collect(Collectors.toList());
        assertEquals(Arrays.asList(3L,6L,9L,12L,15L,18L).stream().map(START::plusDays).collect(Collectors.toList()),waterDates);
    }

    @Test public void humidityGuidanceSwitchesOnLastTwoDays() {
        Batch b=batch(false);
        long main=START.plusDays(10).atStartOfDay(ZONE).toInstant().toEpochMilli();
        long finish=START.plusDays(19).atStartOfDay(ZONE).toInstant().toEpochMilli();
        assertTrue(b.humidityGuidance(main,ZONE).contains("50–55"));
        assertTrue(b.humidityGuidance(finish,ZONE).contains("65–70"));
    }

    @Test public void speciesDurationsAreDefaultsAndEditable() {
        assertEquals(21,Batch.usualDays(Batch.CHICKEN));
        assertEquals(29,Batch.usualDays(Batch.GOOSE));
        assertEquals(30,Batch.usualDays(Batch.DUCK));
        assertEquals(28,Batch.usualDays(Batch.TURKEY));
        assertEquals(16,Batch.usualDays(Batch.QUAIL));
        Batch b=batch(false); b.duration=24;
        assertEquals(START.plusDays(24),java.time.Instant.ofEpochMilli(b.dueAt(ZONE)).atZone(ZONE).toLocalDate());
    }

    @Test public void inactiveBatchHasNoReminders() {
        Batch b=batch(true); b.active=false;
        assertTrue(reminders(b).isEmpty());
    }

    @Test public void keysAreUniqueAndStable() {
        List<Reminder> first=reminders(batch(true));
        List<Reminder> second=reminders(batch(true));
        assertEquals(first.size(),first.stream().map(r -> r.key).distinct().count());
        assertEquals(first.stream().map(r->r.key).collect(Collectors.toList()),
                     second.stream().map(r->r.key).collect(Collectors.toList()));
    }

    @Test public void otherIncubatorDoesNotReceiveNesushkaWaterSchedule() {
        Batch b=batch(false);b.profileId="other";b.incubatorModel="Другая модель";
        assertTrue(reminders(b).stream().noneMatch(r->r.type.equals("water")));
        assertTrue(b.waterGuidance().contains("руководстве"));
        assertFalse(b.humidityOutOfRange(10.0,b.startedAt,ZONE));
    }

    @Test public void duckSprayingIsOnlySecondHalfBeforeStop() {
        Batch b=batch(false); b.species=Batch.DUCK; b.duration=30; b.spray=true;
        List<Reminder> sprays=reminders(b).stream().filter(r->r.type.equals("spray")).collect(Collectors.toList());
        assertEquals(TURNS.size()*(28-15),sprays.size());
        assertTrue(sprays.stream().allMatch(r->r.at>=START.plusDays(15).atStartOfDay(ZONE).toInstant().toEpochMilli()));
    }
}
