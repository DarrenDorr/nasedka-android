package org.nasedka.app;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class BackupSchemaContractTest {
    @Test public void allTablesIncludedExactlyOnce() {
        Set<String> all=new HashSet<>();
        for(String t:BackupStore.INCUBATION)assertTrue(all.add(t));
        for(String t:BackupStore.FARM)assertTrue(all.add(t));
        assertEquals(14,all.size());
        assertTrue(all.contains("done"));
        assertTrue(all.contains("incubator_transfers"));
        assertTrue(all.contains("farm_tasks"));
        assertTrue(all.contains("flock_notes"));
    }
    @Test public void formatIsVersioned() {
        assertEquals("nasedka-offline-full",BackupStore.FORMAT);
        assertEquals(1,BackupStore.VERSION);
    }
}
