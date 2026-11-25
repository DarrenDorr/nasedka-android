package org.nasedka.app;

import java.util.Objects;
import java.util.UUID;

/**
 * In-process admission for complete logical operations. No Android or storage
 * calls here. The monitor protects metadata only; never execute a callback,
 * wait for an operation, or transfer a thread-bound lease under it.
 *
 * Isolated R1.1b-3b component: not yet installed in Application or helpers.
 * Verification of durable journal absence and persistent generation is the
 * responsibility of the exclusive recovery/restore caller.
 */
final class DataAccessCoordinator {
    enum State { STARTING, READY, SHORT, SNAPSHOT, RESTORE, RECOVERY_REQUIRED }
    enum Work { SNAPSHOT, RESTORE, RECOVERY }

    private final Object mutex = new Object();
    private State state = State.STARTING;
    private UUID generation;
    // Identity rather than a wrapping numeric counter; invalidates old
    // in-memory views even if rollback restores the same persistent UUID.
    private Object viewEpoch;
    private Ticket pending;
    private Lease top;

    static final class Token {
        private final DataAccessCoordinator source;
        private final UUID generation;
        private final Object epoch;
        private Token(DataAccessCoordinator source, UUID generation, Object epoch) {
            this.source=source; this.generation=generation; this.epoch=epoch;
        }
        UUID generation(){ return generation; }
    }

    static final class Ticket {
        private final DataAccessCoordinator source;
        private final Work work;
        private final State prior;
        private Ticket(DataAccessCoordinator source, Work work, State prior) {
            this.source=source; this.work=work; this.prior=prior;
        }
    }

    static final class Lease implements AutoCloseable {
        private final DataAccessCoordinator source;
        private final Thread owner;
        private final Work work; // null is SHORT
        private final Lease parent;
        private boolean closed;
        private Lease(DataAccessCoordinator source, Work work, Lease parent) {
            this.source=source; this.owner=Thread.currentThread();
            this.work=work; this.parent=parent;
        }

        /** Only an admitted SHORT may capture a UI token. */
        Token capture(){return source.capture(this);}
        /** Assert ownership at future helper boundary; no storage calls. */
        void requireOwner(){source.requireOwner(this);}
        /**
         * Explicit assertion by trusted integration code: durable NONE and a
         * coherent persistent generation were independently verified. Must be
         * the last action after closing all handles. Consumes the root lease.
         * No auto-success on close or on a storage exception.
         */
        void finishVerified(UUID verifiedGeneration){source.finish(this,verifiedGeneration);}
        @Override public void close(){source.release(this);}
    }

    State state(){synchronized(mutex){return state;}}

    /** Immediate busy/blocked rejection; no waiting for active operation. */
    Lease tryShort(){return admitShort(null,false);}
    /** Versioned UI callback: token check and admission in the same section. */
    Lease tryShort(Token expected){
        if(expected==null)return null;
        return admitShort(expected,true);
    }
    private Lease admitShort(Token expected,boolean check){
        synchronized(mutex){
            if(state!=State.READY && !(state==State.SHORT && top!=null
                    && top.owner==Thread.currentThread()))return null;
            if(check && (expected.source!=this || !expected.generation.equals(generation)
                    || expected.epoch!=viewEpoch))return null;
            Lease lease=new Lease(this,null,top);
            top=lease;state=State.SHORT;
            return lease;
        }
    }

    /** Reserve before dispatch; immutable capability can be handed to worker. */
    Ticket tryReserve(Work work){
        Objects.requireNonNull(work,"work");
        synchronized(mutex){
            if(work==Work.RECOVERY){
                if(state!=State.STARTING && state!=State.RECOVERY_REQUIRED)return null;
            }else if(state!=State.READY)return null;
            if(top!=null || pending!=null)throw new IllegalStateException("Inconsistent admission");
            Ticket ticket=new Ticket(this,work,state);
            pending=ticket;state=work==Work.SNAPSHOT?State.SNAPSHOT:State.RESTORE;
            return ticket;
        }
    }

    /** Exactly one claimant; no transferable live lease. */
    Lease claim(Ticket ticket){
        synchronized(mutex){
            if(ticket==null || ticket.source!=this || pending!=ticket || top!=null)return null;
            Lease lease=new Lease(this,ticket.work,null);
            pending=null;top=lease;
            return lease;
        }
    }

    /** Cancel only an unclaimed ticket, e.g. executor rejected submission. */
    boolean cancel(Ticket ticket){
        synchronized(mutex){
            if(ticket==null || ticket.source!=this || pending!=ticket || top!=null)return false;
            state=ticket.prior;pending=null;
            return true;
        }
    }

    private void requireOwner(Lease lease){
        synchronized(mutex){checkTop(lease);}
    }
    private void checkTop(Lease lease){
        if(lease.source!=this || lease.owner!=Thread.currentThread()
                || lease.closed || top!=lease)
            throw new IllegalStateException("Not the current operation owner");
    }
    private Token capture(Lease lease){
        synchronized(mutex){
            checkTop(lease);
            if(state!=State.SHORT || generation==null || viewEpoch==null)
                throw new IllegalStateException("No admitted UI generation");
            return new Token(this,generation,viewEpoch);
        }
    }

    private void finish(Lease lease,UUID verifiedGeneration){
        Objects.requireNonNull(verifiedGeneration,"verifiedGeneration");
        synchronized(mutex){
            checkTop(lease);
            if(lease.parent!=null || state!=State.RESTORE ||
                    (lease.work!=Work.RESTORE && lease.work!=Work.RECOVERY))
                throw new IllegalStateException("Verified completion requires exclusive recovery/restore");
            // Allocate before releasing ownership; even rollback to the same
            // persistent UUID invalidates old process-local form tokens.
            Object nextEpoch=new Object();
            generation=verifiedGeneration;viewEpoch=nextEpoch;
            lease.closed=true;top=null;state=State.READY;
        }
    }

    private void release(Lease lease){
        synchronized(mutex){
            if(lease.source!=this || lease.owner!=Thread.currentThread())
                throw new IllegalStateException("Foreign lease release");
            // Same-owner close after finishVerified or prior close is harmless;
            // cannot release a different current lease.
            if(lease.closed)return;
            checkTop(lease);
            lease.closed=true;top=lease.parent;
            if(top!=null)return;
            if(lease.work==Work.RESTORE || lease.work==Work.RECOVERY){
                generation=null;viewEpoch=null;state=State.RECOVERY_REQUIRED;
            }else state=State.READY;
        }
    }
}
