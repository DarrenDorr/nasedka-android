package org.nasedka.app;

import java.io.IOException;

/**
 * R1.1b-1: storage-independent restore protocol. Not wired to BackupStore yet.
 * A caller must hold the exclusive logical-operation barrier for the entire call.
 * Storage methods must implement durable, idempotent semantics; this class alone
 * does not supply file durability, concurrency protection or Android recovery.
 */
final class RestoreProtocol {
    enum Phase { NONE, PENDING, COMMITTED, ROLLED_BACK }
    enum Outcome { NO_PENDING, NEW_GENERATION, OLD_GENERATION }

    interface Storage {
        // Return NONE only for a definitely absent journal; corrupt/unreadable -> throw.
        Phase readPhase() throws IOException;
        // Stage and verify new data; preserve immutable old data/prefs. No live writes.
        // Handle pre-PENDING orphan files safely before preparing another operation.
        void prepare() throws IOException;
        // Durable atomic phase publication. On ambiguous failure, recovery rereads disk.
        // Expected previous phase must match; journal retains verification metadata.
        void publish(Phase expected, Phase next) throws IOException;
        void applyIncubation() throws IOException;
        void applyFarm() throws IOException;
        // Must throw if preferences commit returns false.
        void applySettings() throws IOException;
        void verifyNew() throws IOException;
        // Restore from immutable old copies; operations must be safe to repeat.
        void rollbackIncubation() throws IOException;
        void rollbackFarm() throws IOException;
        void rollbackSettings() throws IOException;
        void verifyOld() throws IOException;
        // May be interrupted; never remove journal or its verification metadata here.
        void cleanup(Phase terminal) throws IOException;
        // Atomic durable removal, only after cleanup; check expected phase.
        void clear(Phase terminal) throws IOException;
    }

    private final Storage storage;
    RestoreProtocol(Storage storage) {
        this.storage = java.util.Objects.requireNonNull(storage);
    }

    private Phase phase() throws IOException {
        Phase p = storage.readPhase();
        if (p == null) throw new IOException("Unknown restore journal state");
        return p;
    }

    /**
     * Any exception is an unresolved outcome, not a declaration that rollback
     * happened. Caller must retain exclusive access and recover from durable state
     * before opening databases. Do not infer phase from the failing method name.
     */
    Outcome restore() throws IOException {
        if (phase() != Phase.NONE) throw new IOException("Recovery required before restore");
        storage.prepare();
        storage.publish(Phase.NONE, Phase.PENDING);
        storage.applyIncubation();
        storage.applyFarm();
        storage.applySettings();
        storage.verifyNew();
        storage.publish(Phase.PENDING, Phase.COMMITTED);
        return finish(Phase.COMMITTED);
    }

    Outcome recover() throws IOException {
        switch (phase()) {
            case NONE:
                // Possible orphan staging files; live generation is not mutated.
                // No claim about outcome of a prior completed restore.
                return Outcome.NO_PENDING;
            case PENDING:
                storage.rollbackIncubation();
                storage.rollbackFarm();
                storage.rollbackSettings();
                storage.verifyOld();
                // Keep original copies until rollback itself is durably confirmed.
                storage.publish(Phase.PENDING, Phase.ROLLED_BACK);
                return finish(Phase.ROLLED_BACK);
            case COMMITTED:
                return finish(Phase.COMMITTED);
            case ROLLED_BACK:
                return finish(Phase.ROLLED_BACK);
            default:
                throw new IOException("Unknown restore journal state");
        }
    }

    private Outcome finish(Phase terminal) throws IOException {
        if (phase() != terminal) throw new IOException("Unexpected terminal state");
        if (terminal == Phase.COMMITTED) storage.verifyNew();
        else if (terminal == Phase.ROLLED_BACK) storage.verifyOld();
        else throw new IOException("Not a terminal restore state");
        storage.cleanup(terminal);
        storage.clear(terminal);
        return terminal == Phase.COMMITTED ? Outcome.NEW_GENERATION : Outcome.OLD_GENERATION;
    }
}
