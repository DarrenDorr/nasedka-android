package org.nasedka.app;
import org.junit.Test;
import static org.junit.Assert.*;
import java.time.*;
import java.util.*;

public class DailyTimelineTest {
    private Batch create(ZoneId zone, LocalDate date) {
        Batch b=new Batch();b.id=7;b.duration=21;b.species=Batch.CHICKEN;
        b.startedAt=date.atTime(10,0).atZone(zone).toInstant().toEpochMilli();
        return b;
    }
    @Test public void localDaysAndSeparateHatchRowSurviveDst() {
        ZoneId z=ZoneId.of("Europe/Berlin");
        Batch b=create(z,LocalDate.of(2026,3,27));
        List<DailyTimeline.Day> rows=DailyTimeline.build(b,z,
                Collections.emptyList(),Collections.emptySet(),Collections.emptyList());
        assertEquals(22,rows.size());
        assertEquals(LocalDate.of(2026,4,16),rows.get(20).date);
        assertFalse(rows.get(20).expectedHatch);
        assertEquals(LocalDate.of(2026,4,17),rows.get(21).date);
        assertTrue(rows.get(21).expectedHatch);
    }
    @Test public void eventsAndFactsDoNotInventMeasurements() {
        ZoneId z=ZoneId.of("Asia/Krasnoyarsk");
        Batch b=create(z,LocalDate.of(2026,11,18));long at=b.startedAt;
        Reminder check=new Reminder("seven",7,at,"check","Проверить","");
        Reminder another=new Reminder("other",99,at,"check","Чужая","");
        DailyTimeline.Observation o=new DailyTimeline.Observation(at,37.8,null,"Осмотр");
        List<DailyTimeline.Day> days=DailyTimeline.build(b,z,
                Arrays.asList(check,another),Collections.emptySet(),Collections.singletonList(o));
        assertEquals(1,days.get(0).plan.size());
        assertEquals(0,days.get(0).completedCount());
        assertEquals(1,days.get(0).measuredCount());
        assertEquals(1,days.get(0).notesCount());
        assertEquals(Double.valueOf(37.8),days.get(0).latestTemperature());
        assertNull(days.get(0).latestHumidity());
        assertNull(days.get(1).latestTemperature());
        days.get(0).markCompleted(check);
        assertEquals(1,days.get(0).completedCount());
    }
    @Test public void hatchEventIsOnPredictedHatchDate() {
        ZoneId z=ZoneId.of("Asia/Krasnoyarsk");
        Batch b=create(z,LocalDate.of(2026,11,18));
        Reminder hatch=new Reminder("hatch",7,b.dueAt(z),"hatch","Ожидаемый вывод","");
        List<DailyTimeline.Day> days=DailyTimeline.build(b,z,
                Collections.singletonList(hatch),Collections.emptySet(),Collections.emptyList());
        assertEquals(0,days.get(20).plan.size());
        assertEquals(1,days.get(21).plan.size());
    }
    @Test public void eggDeclensions() {
        assertEquals("1 яйцо",DailyTimeline.eggs(1));
        assertEquals("2 яйца",DailyTimeline.eggs(2));
        assertEquals("5 яиц",DailyTimeline.eggs(5));
        assertEquals("11 яиц",DailyTimeline.eggs(11));
        assertEquals("104 яйца",DailyTimeline.eggs(104));
    }
}
