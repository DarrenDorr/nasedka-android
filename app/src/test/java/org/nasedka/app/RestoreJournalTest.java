package org.nasedka.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static org.nasedka.app.RestoreProtocol.Phase.*;

public class RestoreJournalTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path root() { return temporary.getRoot().toPath(); }
    private Path live() { return root().resolve("restore-v2.journal"); }
    private Path temp() { return root().resolve("restore-v2.journal.tmp"); }
    private RestoreJournal journal() throws IOException { return new RestoreJournal(root()); }
    private static RestoreJournal.Entry pending() {
        String[] hashes = new String[6];
        for (int i=0; i<6; i++) {
            char[] chars = new char[64]; Arrays.fill(chars, (char)('a'+i)); hashes[i]=new String(chars);
        }
        return new RestoreJournal.Entry(UUID.randomUUID(), PENDING, hashes);
    }
    interface Action { void run() throws IOException; }
    private static void rejects(Action action) {
        try { action.run(); fail("Expected IOException"); } catch (IOException expected) {}
    }
    @Test public void commitSurvivesReopenAndClearsOnlyTerminal() throws Exception {
        RestoreJournal.Entry p=pending();
        assertNull(journal().read());
        journal().publish(NONE,p);
        assertEquals(PENDING,journal().read().phase);
        assertEquals(p.generation,journal().read().generation);
        assertArrayEquals(p.hashes(),journal().read().hashes());
        rejects(()->journal().clear(p));
        RestoreJournal.Entry c=p.withPhase(COMMITTED);
        journal().publish(PENDING,c);
        assertEquals(COMMITTED,journal().read().phase);
        journal().clear(c); assertNull(journal().read());
    }
    @Test public void rollbackTerminalSurvivesReopen() throws Exception {
        RestoreJournal.Entry p=pending(); journal().publish(NONE,p);
        RestoreJournal.Entry rolled=p.withPhase(ROLLED_BACK);
        journal().publish(PENDING,rolled); assertEquals(ROLLED_BACK,journal().read().phase);
        journal().clear(rolled); assertNull(journal().read());
    }
    @Test public void wrongGenerationOrHashesCannotPublishOrClear() throws Exception {
        RestoreJournal.Entry p=pending(); journal().publish(NONE,p);
        rejects(()->journal().publish(PENDING,pending().withPhase(COMMITTED)));
        String[] changed=p.hashes(); changed[0]=changed[1];
        RestoreJournal.Entry altered=new RestoreJournal.Entry(p.generation,COMMITTED,changed);
        rejects(()->journal().publish(PENDING,altered));
        assertEquals(PENDING,journal().read().phase);
        journal().publish(PENDING,p.withPhase(COMMITTED));
        rejects(()->journal().clear(altered));
        rejects(()->journal().clear(pending().withPhase(COMMITTED)));
        assertEquals(COMMITTED,journal().read().phase);
    }
    @Test public void invalidTransitionsDoNotChangeJournal() throws Exception {
        RestoreJournal.Entry p=pending();
        rejects(()->journal().publish(NONE,p.withPhase(COMMITTED))); assertNull(journal().read());
        journal().publish(NONE,p);
        rejects(()->journal().publish(NONE,pending()));
        rejects(()->journal().publish(PENDING,p));
        journal().publish(PENDING,p.withPhase(COMMITTED));
        rejects(()->journal().publish(COMMITTED,p.withPhase(ROLLED_BACK)));
        assertEquals(COMMITTED,journal().read().phase);
    }
    @Test public void damagedJournalIsNotTreatedAsMissing() throws Exception {
        RestoreJournal.Entry p=pending(); journal().publish(NONE,p);
        byte[] damaged=Files.readAllBytes(live());
        damaged[damaged.length-2]=(byte)(damaged[damaged.length-2]=='a'?'b':'a');
        Files.write(live(),damaged);
        rejects(()->journal().read());
        rejects(()->journal().publish(NONE,pending()));
        rejects(()->journal().clear(p.withPhase(COMMITTED)));
        assertArrayEquals(damaged,Files.readAllBytes(live()));
    }
    @Test public void orphanTemporaryDoesNotOverrideAuthoritativeJournal() throws Exception {
        Files.write(temp(),"incomplete".getBytes(StandardCharsets.US_ASCII));
        assertNull(journal().read()); RestoreJournal.Entry p=pending(); journal().publish(NONE,p);
        assertFalse(Files.exists(temp()));
        Files.write(temp(),"interrupted replacement".getBytes(StandardCharsets.US_ASCII));
        assertEquals(PENDING,journal().read().phase);
        journal().publish(PENDING,p.withPhase(ROLLED_BACK));
        assertEquals(ROLLED_BACK,journal().read().phase); assertFalse(Files.exists(temp()));
    }
    @Test public void oversizedOrNonRegularJournalFailsClosed() throws Exception {
        Files.write(live(),new byte[1025]); rejects(()->journal().read());
        Files.delete(live()); Files.createDirectory(live());
        rejects(()->journal().read()); rejects(()->journal().publish(NONE,pending()));
        assertTrue(Files.isDirectory(live()));
    }
    @Test public void missingDirectoryIsNotAnEmptyJournal() throws Exception {
        Path nested=Files.createDirectory(root().resolve("isolated"));
        RestoreJournal j=new RestoreJournal(nested); Files.delete(nested); rejects(j::read);
    }
}
