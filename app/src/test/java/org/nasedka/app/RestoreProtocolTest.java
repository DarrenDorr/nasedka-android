package org.nasedka.app;

import java.io.IOException;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.nasedka.app.RestoreProtocol.Phase.*;
import static org.nasedka.app.RestoreProtocol.Outcome.*;

public class RestoreProtocolTest {
    // Synthetic durable-state model. Throwing after an effect simulates an
    // ambiguous return; constructing a new protocol models restart, not real kill.
    private static final class Disk implements RestoreProtocol.Storage {
        RestoreProtocol.Phase phase = NONE;
        int inc=1, farm=1, prefs=1;
        boolean oldCopies, staged, prepared, corruptJournal;
        String fault;
        boolean after;
        int calls;
        void arm(String step, boolean after) {this.fault=step;this.after=after;}
        void hit(String step, boolean after) throws IOException {
            if (step.equals(fault) && this.after==after) {
                fault=null; throw new IOException("Injected "+step+" "+after);
            }
        }
        interface Effect {void run() throws IOException;}
        void step(String name, Effect effect) throws IOException {
            calls++; hit(name,false); effect.run(); hit(name,true);
        }
        @Override public RestoreProtocol.Phase readPhase() throws IOException {
            if(corruptJournal)throw new IOException("Corrupt journal");
            return phase;
        }
        @Override public void prepare() throws IOException {
            step("prepare",()->{
                if(phase!=NONE)throw new IOException("Pending");
                assertGeneration(1); oldCopies=true;staged=true;prepared=true;
            });
        }
        @Override public void publish(RestoreProtocol.Phase expected, RestoreProtocol.Phase next) throws IOException {
            step("publish_"+next,()->{
                if(phase!=expected)throw new IOException("Phase mismatch");
                if(next==PENDING&&(!oldCopies||!staged||!prepared))throw new IOException("Not prepared");
                if(next==COMMITTED)assertGeneration(2);
                if(next==ROLLED_BACK)assertGeneration(1);
                phase=next;
            });
        }
        @Override public void applyIncubation() throws IOException {step("applyInc",()->{requirePending();inc=2;});}
        @Override public void applyFarm() throws IOException {step("applyFarm",()->{requirePending();farm=2;});}
        @Override public void applySettings() throws IOException {step("applyPrefs",()->{requirePending();prefs=2;});}
        @Override public void verifyNew() throws IOException {step("verifyNew",()->verify(2));}
        @Override public void rollbackIncubation() throws IOException {step("rollbackInc",()->{requireCopies();inc=1;});}
        @Override public void rollbackFarm() throws IOException {step("rollbackFarm",()->{requireCopies();farm=1;});}
        @Override public void rollbackSettings() throws IOException {step("rollbackPrefs",()->{requireCopies();prefs=1;});}
        @Override public void verifyOld() throws IOException {step("verifyOld",()->verify(1));}
        @Override public void cleanup(RestoreProtocol.Phase terminal) throws IOException {
            step("cleanupCopies",()->{
                if(phase!=terminal || (terminal!=COMMITTED&&terminal!=ROLLED_BACK))throw new IOException("Early cleanup");
                oldCopies=false;
            });
            step("cleanupStage",()->staged=false);
        }
        @Override public void clear(RestoreProtocol.Phase terminal) throws IOException {
            step("clear",()->{
                if(phase!=terminal||oldCopies||staged)throw new IOException("Early clear");
                phase=NONE;
            });
        }
        void requirePending() throws IOException {if(phase!=PENDING||!staged)throw new IOException("Not staged");}
        void requireCopies() throws IOException {if(phase!=PENDING||!oldCopies)throw new IOException("Missing rollback");}
        void verify(int expected) throws IOException {
            if(inc!=expected||farm!=expected||prefs!=expected)throw new IOException("Mixed generation");
        }
        void assertGeneration(int expected) {
            assertEquals(expected,inc);assertEquals(expected,farm);assertEquals(expected,prefs);
        }
    }
    interface Action {void run() throws IOException;}
    private static void rejects(Action action) {
        try {action.run();fail("Expected IOException");}catch(IOException expected) {}
    }
    private static Disk interrupted() {
        Disk d=new Disk();d.arm("applyFarm",true);
        rejects(()->new RestoreProtocol(d).restore());
        assertEquals(PENDING,d.phase);return d;
    }

    @Test public void successfulRestoreCommitsAllThreeAndClearsJournal() throws Exception {
        Disk d=new Disk();
        assertEquals(NEW_GENERATION,new RestoreProtocol(d).restore());
        d.assertGeneration(2);assertEquals(NONE,d.phase);
        assertFalse(d.oldCopies);assertFalse(d.staged);
        assertEquals(NO_PENDING,new RestoreProtocol(d).recover());
    }

    @Test public void beforeAndAfterEveryRestoreBoundaryRecoversCorrectGeneration() throws Exception {
        String[] steps={"prepare","publish_PENDING","applyInc","applyFarm","applyPrefs",
                "verifyNew","publish_COMMITTED","cleanupCopies","cleanupStage","clear"};
        for(String step:steps)for(boolean after:new boolean[]{false,true}) {
            Disk d=new Disk();d.arm(step,after);
            rejects(()->new RestoreProtocol(d).restore());
            // Durable commit is the only boundary after which the new state wins.
            boolean committed=Arrays.asList("cleanupCopies","cleanupStage","clear").contains(step)
                    || ("publish_COMMITTED".equals(step)&&after);
            RestoreProtocol.Phase persisted=d.phase;
            if(persisted==PENDING)assertTrue(d.oldCopies);
            RestoreProtocol.Outcome outcome=new RestoreProtocol(d).recover();
            if(persisted==PENDING)assertEquals(OLD_GENERATION,outcome);
            else if(persisted==COMMITTED)assertEquals(NEW_GENERATION,outcome);
            else assertEquals(NO_PENDING,outcome);
            d.assertGeneration(committed?2:1);assertEquals(NONE,d.phase);
            if(persisted!=NONE) {assertFalse(d.oldCopies);assertFalse(d.staged);}
        }
    }

    @Test public void repeatedInterruptionDuringRollbackIsRecoverable() throws Exception {
        String[] steps={"rollbackInc","rollbackFarm","rollbackPrefs","verifyOld",
                "publish_ROLLED_BACK","cleanupCopies","cleanupStage","clear"};
        for(String step:steps)for(boolean after:new boolean[]{false,true}) {
            Disk d=interrupted();d.arm(step,after);
            rejects(()->new RestoreProtocol(d).recover());
            if(d.phase==PENDING)assertTrue(d.oldCopies);
            RestoreProtocol.Phase persisted=d.phase;
            assertEquals(persisted==NONE?NO_PENDING:OLD_GENERATION,new RestoreProtocol(d).recover());
            d.assertGeneration(1);assertEquals(NONE,d.phase);
            assertFalse(d.oldCopies);assertFalse(d.staged);
        }
    }

    @Test public void settingsCommitFailureDoesNotCommitDatabases() throws Exception {
        Disk d=new Disk();d.arm("applyPrefs",false);
        rejects(()->new RestoreProtocol(d).restore());
        assertEquals(PENDING,d.phase);assertEquals(2,d.inc);assertEquals(2,d.farm);assertEquals(1,d.prefs);
        assertEquals(OLD_GENERATION,new RestoreProtocol(d).recover());d.assertGeneration(1);
    }

    @Test public void pendingRestoreRejectsAnotherRestoreWithoutWriting() {
        Disk d=interrupted();int calls=d.calls;
        rejects(()->new RestoreProtocol(d).restore());
        assertEquals(calls,d.calls);assertEquals(PENDING,d.phase);assertTrue(d.oldCopies);
    }

    @Test public void corruptJournalFailsClosed() {
        Disk d=interrupted();d.corruptJournal=true;int calls=d.calls;
        rejects(()->new RestoreProtocol(d).recover());
        rejects(()->new RestoreProtocol(d).restore());
        assertEquals(calls,d.calls);assertTrue(d.oldCopies);
    }

    @Test public void missingRollbackCopiesRemainPending() {
        Disk d=interrupted();d.oldCopies=false;
        rejects(()->new RestoreProtocol(d).recover());
        assertEquals(PENDING,d.phase);assertTrue(d.staged);
    }

    @Test public void corruptCommittedGenerationDoesNotCleanupOrRollback() {
        Disk d=new Disk();d.arm("publish_COMMITTED",true);
        rejects(()->new RestoreProtocol(d).restore());
        d.farm=99;
        rejects(()->new RestoreProtocol(d).recover());
        assertEquals(COMMITTED,d.phase);assertTrue(d.oldCopies);assertTrue(d.staged);
        assertEquals(99,d.farm);
    }

    @Test public void committedCleanupFailureNeverRollsBack() throws Exception {
        Disk d=new Disk();d.arm("cleanupCopies",true);
        rejects(()->new RestoreProtocol(d).restore());
        assertEquals(COMMITTED,d.phase);assertFalse(d.oldCopies);
        assertEquals(NEW_GENERATION,new RestoreProtocol(d).recover());
        d.assertGeneration(2);assertEquals(NONE,d.phase);
    }
}
