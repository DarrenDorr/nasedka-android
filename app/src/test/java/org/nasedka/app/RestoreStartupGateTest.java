package org.nasedka.app;

import org.junit.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;
import static org.nasedka.app.RestoreProtocol.Phase.*;
import static org.nasedka.app.DataAccessCoordinator.State.*;

public class RestoreStartupGateTest {
    private static final UUID OLD=UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID NEW=UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static class Disk implements RestoreStartupGate.Backend {
        final DataAccessCoordinator c=new DataAccessCoordinator();
        final List<String> steps=new ArrayList<>();
        RestoreProtocol.Phase phase=NONE;
        boolean marker,legacy,artifacts,inc=true,farm=true;
        boolean retainMarker,failRecovery,failValidation,failGeneration,losePairOnValidation;
        UUID generation=OLD;
        void step(String s){assertEquals(DataAccessCoordinator.State.RESTORE,c.state());assertNull(c.tryShort());steps.add(s);}
        public RestoreStartupGate.Inspection inspect(){step("inspect");return new RestoreStartupGate.Inspection(phase,marker,legacy,artifacts,inc,farm);}
        public void recoverLegacy()throws IOException{step("legacy");if(failRecovery)throw new IOException("recovery");marker=retainMarker;inc=farm=true;}
        public void recoverV2()throws IOException{step("v2");if(failRecovery)throw new IOException("recovery");phase=NONE;inc=farm=true;generation=NEW;}
        public UUID readGeneration(){step("read-generation");return generation;}
        public void initializeMissing(RestoreStartupGate.Inspection before){
            step("initialize-missing");assertFalse(before.pair());assertEquals(inc,before.incubation);assertEquals(farm,before.farm);inc=farm=true;
        }
        public void validatePair()throws IOException{step("validate");if(failValidation)throw new IOException("integrity");if(losePairOnValidation)farm=false;}
        public UUID initializeGeneration()throws IOException{step("initialize-generation");generation=NEW;if(failGeneration)throw new IOException("commit false");return generation;}
    }
    private static void blocked(Disk d)throws Exception{
        try{RestoreStartupGate.attempt(d.c,d);fail("Expected startup failure");}catch(IOException expected){}
        assertEquals(RECOVERY_REQUIRED,d.c.state());assertNull(d.c.tryShort());
    }
    private static void ready(Disk d,UUID expected)throws Exception{
        assertTrue(RestoreStartupGate.attempt(d.c,d));assertEquals(READY,d.c.state());
        try(DataAccessCoordinator.Lease l=d.c.tryShort()){assertNotNull(l);assertEquals(expected,l.capture().generation());}
    }
    @Test public void freshInstallInitializesPairBeforeGeneration()throws Exception{
        Disk d=new Disk();d.inc=d.farm=false;d.generation=null;ready(d,NEW);
        assertTrue(d.steps.indexOf("initialize-missing")<d.steps.indexOf("validate"));
        assertTrue(d.steps.indexOf("validate")<d.steps.indexOf("initialize-generation"));
    }
    @Test public void oldPartialPairCanInitializeOnlyMissingFile()throws Exception{
        Disk d=new Disk();d.farm=false;d.generation=null;ready(d,NEW);assertTrue(d.steps.contains("initialize-missing"));
    }
    @Test public void establishedPartialPairCannotCreateReplacement()throws Exception{
        Disk d=new Disk();d.farm=false;blocked(d);assertFalse(d.steps.contains("initialize-missing"));
    }
    @Test public void orphanRestoreArtifactsBlockPartialInitialization()throws Exception{
        Disk d=new Disk();d.farm=false;d.generation=null;d.artifacts=true;blocked(d);assertFalse(d.steps.contains("initialize-missing"));
    }
    @Test public void existingGenerationIsNotReissued()throws Exception{
        Disk d=new Disk();ready(d,OLD);assertFalse(d.steps.contains("initialize-generation"));assertFalse(d.steps.contains("initialize-missing"));
    }
    @Test public void v2RecoveryPrecedesAnyGenerationRead()throws Exception{
        for(RestoreProtocol.Phase phase:new RestoreProtocol.Phase[]{PENDING,COMMITTED,ROLLED_BACK}){
            Disk d=new Disk();d.phase=phase;d.artifacts=true;ready(d,NEW);
            assertTrue(d.steps.indexOf("v2")<d.steps.indexOf("read-generation"));
        }
    }
    @Test public void conflictingJournalsAndUnclassifiedLegacyRemnantsBlock()throws Exception{
        Disk d=new Disk();d.marker=true;d.phase=PENDING;blocked(d);assertFalse(d.steps.contains("legacy"));assertFalse(d.steps.contains("v2"));
        d=new Disk();d.legacy=true;blocked(d);assertFalse(d.steps.contains("read-generation"));
        d=new Disk();d.marker=true;d.artifacts=true;blocked(d);assertFalse(d.steps.contains("legacy"));
    }
    @Test public void legacyRecoveryMustResolveMarkerAndClassifyRemnants()throws Exception{
        Disk d=new Disk();d.marker=true;d.retainMarker=true;blocked(d);assertFalse(d.steps.contains("read-generation"));
        d=new Disk();d.marker=true;d.legacy=true;blocked(d);assertTrue(d.steps.contains("legacy"));assertFalse(d.steps.contains("read-generation"));
        d=new Disk();d.marker=true;d.generation=null;ready(d,NEW);assertTrue(d.steps.indexOf("legacy")<d.steps.indexOf("read-generation"));
    }
    @Test public void failedRecoveryOrValidationNeverPublishesReady()throws Exception{
        Disk d=new Disk();d.phase=PENDING;d.failRecovery=true;blocked(d);assertFalse(d.steps.contains("read-generation"));
        d=new Disk();d.failValidation=true;blocked(d);assertFalse(d.steps.contains("initialize-generation"));
        d=new Disk();d.losePairOnValidation=true;blocked(d);
    }
    @Test public void generationCommitFailureNeverPublishesReady()throws Exception{
        Disk d=new Disk();d.generation=null;d.failGeneration=true;blocked(d);assertEquals(NEW,d.generation);
        // The real port must quarantine ambiguous cached values; this fake
        // backend deliberately models only the failure on this attempt.
    }
    @Test public void admissionRejectsBusyOrAlreadyReadyWithoutBackendCalls()throws Exception{
        Disk d=new Disk();DataAccessCoordinator.Ticket t=d.c.tryReserve(DataAccessCoordinator.Work.RECOVERY);
        assertFalse(RestoreStartupGate.attempt(d.c,d));assertTrue(d.steps.isEmpty());assertTrue(d.c.cancel(t));
        ready(d,OLD);int before=d.steps.size();assertFalse(RestoreStartupGate.attempt(d.c,d));assertEquals(before,d.steps.size());
    }
}
