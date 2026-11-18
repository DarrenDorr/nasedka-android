package org.nasedka.app;
import org.junit.Test;
import static org.junit.Assert.*;
import java.time.*;
import java.util.*;

public class NestCarePlanTest {
    @Test public void onlyHenChecksNotIncubatorControls() {
        ZoneId z=ZoneId.of("Asia/Krasnoyarsk");
        FarmDb.Nest n=new FarmDb.Nest();n.id=2;n.name="Клуша";n.active=true;n.days=21;
        n.startedAt=LocalDate.of(2026,11,18).atTime(12,0).atZone(z).toInstant().toEpochMilli();
        List<Reminder> list=NestCarePlan.generate(n,z,
                Collections.singletonList(LocalTime.of(9,0)),Collections.emptySet());
        assertEquals(21,list.size()); // No check before starting at noon + 20 remaining + hatch.
        assertTrue(list.stream().noneMatch(r->r.type.equals("turn")
                ||r.type.equals("water")||r.type.equals("spray")));
        assertEquals("nest_hatch",list.get(list.size()-1).type);
    }
    @Test public void recordedDaySuppressesRoutineCheck() {
        ZoneId z=ZoneId.of("Europe/Berlin");
        FarmDb.Nest n=new FarmDb.Nest();n.id=10;n.name="Гнездо";
        n.active=true;n.days=21;
        n.startedAt=LocalDate.of(2026,3,27).atStartOfDay(z).toInstant().toEpochMilli();
        Set<LocalDate> done=new HashSet<>(Collections.singletonList(LocalDate.of(2026,3,28)));
        List<Reminder> list=NestCarePlan.generate(n,z,
                Collections.singletonList(LocalTime.of(9,0)),done);
        assertEquals(21,list.size()); // 20 routine checks + hatch
        assertTrue(list.stream().noneMatch(r->Instant.ofEpochMilli(r.at).atZone(z)
                .toLocalDate().equals(LocalDate.of(2026,3,28))));
    }
}
