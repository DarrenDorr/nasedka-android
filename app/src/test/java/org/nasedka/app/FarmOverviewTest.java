package org.nasedka.app;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import static org.junit.Assert.*;

public class FarmOverviewTest {
    private Batch batch(boolean active, int eggs) {
        Batch b=new Batch(); b.active=active; b.eggs=eggs; return b;
    }

    @Test public void countsDoNotInventPoultryHeadcounts() {
        List<Batch> batches=Arrays.asList(batch(true,12),batch(false,15),batch(true,6));
        assertEquals(2,FarmOverview.activeCount(batches));
        assertEquals(18,FarmOverview.eggsInActiveIncubations(batches));
        assertEquals(0,FarmOverview.activeCount(null));
    }

    @Test public void nextActionsFiltersCompletedAndOldEntries() {
        long now=1900000000000L;
        Reminder done=new Reminder("done",1,now+1000,"check","A","");
        Reminder soon=new Reminder("soon",1,now+60000,"check","B","");
        Reminder late=new Reminder("late",1,now-30000,"check","C","");
        Reminder old=new Reminder("old",1,now-25*3600000L,"check","D","");
        List<Reminder> events=FarmOverview.nextActions(
                Arrays.asList(soon,old,done,late),
                new HashSet<>(Collections.singleton("done")),now,3);
        assertEquals(2,events.size());
        assertEquals("late",events.get(0).key);
        assertEquals("soon",events.get(1).key);
        assertEquals(1,FarmOverview.nextActions(events,Collections.emptySet(),now,1).size());
    }
}
