package org.nasedka.app;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * Isolated startup ordering policy. No Android calls; Backend must inspect
 * real private files without creating helpers, perform non-destructive
 * recovery/validation and own the same process-scoped settings port.
 * Not wired to Application. Only exclusive recovery can publish READY.
 */
final class RestoreStartupGate {
    static final class Inspection {
        final RestoreProtocol.Phase phase;
        final boolean legacyMarker, unclassifiedLegacyRemnants, v2Artifacts, incubation, farm;
        Inspection(RestoreProtocol.Phase phase,boolean legacyMarker,boolean unclassifiedLegacyRemnants,
                   boolean v2Artifacts,boolean incubation,boolean farm){
            this.phase=Objects.requireNonNull(phase);
            this.legacyMarker=legacyMarker;this.unclassifiedLegacyRemnants=unclassifiedLegacyRemnants;
            this.v2Artifacts=v2Artifacts;this.incubation=incubation;this.farm=farm;
        }
        boolean pair(){return incubation&&farm;}
    }
    interface Backend {
        // Must throw on unreadable/ambiguous metadata; NONE must be durable.
        Inspection inspect()throws IOException;
        void recoverLegacy()throws IOException;
        void recoverV2()throws IOException;
        UUID readGeneration()throws IOException;
        // Only absent files may be initialized; never replace existing DBs.
        void initializeMissing(Inspection before)throws IOException;
        // Validate/migrate established pair without destructive corruption handler.
        void validatePair()throws IOException;
        UUID initializeGeneration()throws IOException;
    }

    private RestoreStartupGate(){}

    /** False means another operation owns admission. Exceptions leave recovery required. */
    static boolean attempt(DataAccessCoordinator coordinator,Backend backend)throws IOException{
        Objects.requireNonNull(coordinator);Objects.requireNonNull(backend);
        DataAccessCoordinator.Ticket ticket=coordinator.tryReserve(DataAccessCoordinator.Work.RECOVERY);
        if(ticket==null)return false;
        try(DataAccessCoordinator.Lease lease=coordinator.claim(ticket)){
            if(lease==null)throw new IllegalStateException("Lost recovery reservation");
            Inspection before=backend.inspect();
            boolean recovered=false;
            if(before.legacyMarker){
                if(before.phase!=RestoreProtocol.Phase.NONE||before.v2Artifacts)
                    throw new IOException("Conflicting restore evidence");
                backend.recoverLegacy();recovered=true;
            }else if(before.phase!=RestoreProtocol.Phase.NONE){
                if(before.unclassifiedLegacyRemnants)throw new IOException("Conflicting restore evidence");
                backend.recoverV2();recovered=true;
            }
            Inspection current=backend.inspect();
            requireResolved(current);
            // Do not read generation until recovery has restored its canonical
            // value. Missing generation on historical snapshots is legitimate.
            UUID previous=backend.readGeneration();
            if(!current.pair()){
                if(recovered||previous!=null||current.v2Artifacts)
                    throw new IOException("Incomplete established database pair");
                backend.initializeMissing(current);
                current=backend.inspect();
                requireResolved(current);
                if(!current.pair()||current.v2Artifacts)
                    throw new IOException("Incomplete initialization");
            }
            backend.validatePair();
            UUID generation=previous==null?backend.initializeGeneration():previous;
            if(generation==null||!generation.equals(backend.readGeneration()))
                throw new IOException("Unverified startup generation");
            Inspection last=backend.inspect();
            requireResolved(last);
            if(!last.pair())throw new IOException("Database pair disappeared");
            lease.finishVerified(generation);
            return true;
        }
    }

    private static void requireResolved(Inspection current)throws IOException{
        if(current.legacyMarker||current.phase!=RestoreProtocol.Phase.NONE||current.unclassifiedLegacyRemnants)
            throw new IOException("Unresolved restore evidence");
    }
}
