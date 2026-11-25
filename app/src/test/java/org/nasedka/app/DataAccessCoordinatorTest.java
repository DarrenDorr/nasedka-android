package org.nasedka.app;

import org.junit.Test;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.nasedka.app.DataAccessCoordinator.State.*;
import static org.nasedka.app.DataAccessCoordinator.Work.RECOVERY;

public class DataAccessCoordinatorTest {
    private static final UUID OLD=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NEW=UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final long TIMEOUT=5;
    interface Checked { void run() throws Exception; }
    private static void rejected(Checked c)throws Exception{
        try{c.run();fail("Expected illegal ownership");}catch(IllegalStateException expected){}
    }
    private static DataAccessCoordinator ready(){
        DataAccessCoordinator c=new DataAccessCoordinator();
        try(DataAccessCoordinator.Lease l=c.claim(c.tryReserve(RECOVERY))){
            assertNotNull(l);l.finishVerified(OLD);
        }
        assertEquals(READY,c.state());return c;
    }
    private static void await(CountDownLatch latch)throws InterruptedException{
        assertTrue("Latch timeout",latch.await(TIMEOUT,TimeUnit.SECONDS));
    }
    private static <T> T result(Future<T> f)throws Exception{return f.get(TIMEOUT,TimeUnit.SECONDS);}
    private static void stop(ExecutorService ex)throws InterruptedException{
        ex.shutdownNow();assertTrue(ex.awaitTermination(TIMEOUT,TimeUnit.SECONDS));
    }

    @Test public void startupAndUnresolvedRecoveryBlockOrdinaryAccess(){
        DataAccessCoordinator c=new DataAccessCoordinator();
        assertEquals(STARTING,c.state());assertNull(c.tryShort());
        assertNull(c.tryReserve(DataAccessCoordinator.Work.SNAPSHOT));assertNull(c.tryReserve(DataAccessCoordinator.Work.RESTORE));
        try(DataAccessCoordinator.Lease l=c.claim(c.tryReserve(RECOVERY))){
            assertEquals(DataAccessCoordinator.State.RESTORE,c.state());assertNull(c.tryShort());
        }
        assertEquals(RECOVERY_REQUIRED,c.state());assertNull(c.tryShort());
        assertNull(c.tryReserve(DataAccessCoordinator.Work.SNAPSHOT));assertNull(c.tryReserve(DataAccessCoordinator.Work.RESTORE));
        try(DataAccessCoordinator.Lease l=c.claim(c.tryReserve(RECOVERY))){l.finishVerified(OLD);}
        try(DataAccessCoordinator.Lease l=c.tryShort()){assertEquals(OLD,l.capture().generation());}
    }

    @Test public void concurrentShortRejectsOtherThreadWithoutWaiting()throws Exception{
        DataAccessCoordinator c=ready();ExecutorService ex=Executors.newFixedThreadPool(2);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try{
            Future<?> worker=ex.submit(()->{
                try(DataAccessCoordinator.Lease l=c.tryShort()){
                    assertNotNull(l);entered.countDown();await(release);
                }return null;
            });
            await(entered);
            result(ex.submit(()->{
                assertEquals(SHORT,c.state());assertNull(c.tryShort());assertNull(c.tryReserve(DataAccessCoordinator.Work.SNAPSHOT));
                assertNull(c.tryReserve(DataAccessCoordinator.Work.RESTORE));return null;
            }));
            release.countDown();result(worker);assertEquals(READY,c.state());
        }finally{release.countDown();stop(ex);}
    }

    @Test public void reservationRaceAdmitsExactlyOneHeavyOperation()throws Exception{
        DataAccessCoordinator c=ready();ExecutorService ex=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try{
            Future<DataAccessCoordinator.Ticket> a=ex.submit(()->{await(start);return c.tryReserve(DataAccessCoordinator.Work.SNAPSHOT);});
            Future<DataAccessCoordinator.Ticket> b=ex.submit(()->{await(start);return c.tryReserve(DataAccessCoordinator.Work.RESTORE);});
            start.countDown();DataAccessCoordinator.Ticket x=result(a),y=result(b);
            assertTrue((x==null) != (y==null));assertNull(c.tryShort());
            assertTrue(c.cancel(x==null?y:x));assertEquals(READY,c.state());
        }finally{start.countDown();stop(ex);}
    }

    @Test public void nestedShortIsLifoAndCannotUpgrade()throws Exception{
        DataAccessCoordinator c=ready();
        DataAccessCoordinator.Lease outer=c.tryShort();assertNotNull(outer);
        try{
            DataAccessCoordinator.Lease inner=c.tryShort(outer.capture());assertNotNull(inner);
            try{
                rejected(outer::close);rejected(outer::requireOwner);
                rejected(()->inner.finishVerified(NEW));assertNull(c.tryReserve(DataAccessCoordinator.Work.RESTORE));
                assertNull(c.tryReserve(DataAccessCoordinator.Work.SNAPSHOT));assertEquals(SHORT,c.state());inner.requireOwner();
            }finally{inner.close();}
            outer.requireOwner();assertEquals(SHORT,c.state());
        }finally{outer.close();}
        assertEquals(READY,c.state());
    }

    @Test public void foreignThreadCannotReleaseOrUseLease()throws Exception{
        DataAccessCoordinator c=ready();ExecutorService ex=Executors.newSingleThreadExecutor();
        try(DataAccessCoordinator.Lease l=c.tryShort()){
            result(ex.submit(()->{
                rejected(l::close);rejected(l::requireOwner);rejected(l::capture);
                rejected(()->l.finishVerified(NEW));return null;
            }));
            l.requireOwner();assertEquals(SHORT,c.state());
        }finally{stop(ex);}
        assertEquals(READY,c.state());
    }

    @Test public void ticketTransferredAndClaimedOnlyOnce()throws Exception{
        DataAccessCoordinator c=ready(),foreign=ready();
        DataAccessCoordinator.Ticket t=c.tryReserve(DataAccessCoordinator.Work.SNAPSHOT);assertNotNull(t);
        assertEquals(DataAccessCoordinator.State.SNAPSHOT,c.state());assertNull(c.tryShort());
        assertNull(foreign.claim(t));assertFalse(foreign.cancel(t));
        ExecutorService ex=Executors.newSingleThreadExecutor();
        try{
            result(ex.submit(()->{
                try(DataAccessCoordinator.Lease l=c.claim(t)){
                    assertNotNull(l);l.requireOwner();assertNull(c.claim(t));assertFalse(c.cancel(t));
                    assertNull(c.tryShort());rejected(()->l.finishVerified(NEW));
                }return null;
            }));
            assertNull(c.claim(t));assertEquals(READY,c.state());
        }finally{stop(ex);}
    }

    @Test public void cancellationRestoresOnlyOriginalUnclaimedState(){
        DataAccessCoordinator c=new DataAccessCoordinator();
        DataAccessCoordinator.Ticket startup=c.tryReserve(RECOVERY);
        assertTrue(c.cancel(startup));assertEquals(STARTING,c.state());
        assertFalse(c.cancel(startup));assertNull(c.claim(startup));
        try(DataAccessCoordinator.Lease l=c.claim(c.tryReserve(RECOVERY))){}
        DataAccessCoordinator.Ticket retry=c.tryReserve(RECOVERY);
        assertTrue(c.cancel(retry));assertEquals(RECOVERY_REQUIRED,c.state());
        c=ready();DataAccessCoordinator.Ticket restore=c.tryReserve(DataAccessCoordinator.Work.RESTORE);
        assertTrue(c.cancel(restore));assertEquals(READY,c.state());
        assertFalse(c.cancel(restore));assertNull(c.claim(restore));
    }

    @Test public void cancellationAndClaimRaceCannotReleaseActiveWork()throws Exception{
        DataAccessCoordinator c=ready();ExecutorService ex=Executors.newFixedThreadPool(2);
        DataAccessCoordinator.Ticket t=c.tryReserve(DataAccessCoordinator.Work.RESTORE);
        CountDownLatch start=new CountDownLatch(1),decided=new CountDownLatch(2),release=new CountDownLatch(1);
        AtomicInteger winners=new AtomicInteger();
        try{
            Future<?> claim=ex.submit(()->{
                await(start);
                try(DataAccessCoordinator.Lease l=c.claim(t)){
                    if(l!=null){winners.incrementAndGet();decided.countDown();await(release);l.finishVerified(NEW);}
                    else decided.countDown();
                }return null;
            });
            Future<?> cancel=ex.submit(()->{await(start);if(c.cancel(t))winners.incrementAndGet();decided.countDown();return null;});
            start.countDown();await(decided);assertEquals(1,winners.get());
            assertFalse(c.cancel(t));release.countDown();result(claim);result(cancel);assertEquals(READY,c.state());
        }finally{start.countDown();release.countDown();stop(ex);}
    }

    @Test public void shortAndSnapshotExceptionsReleaseButRestoreFailsClosed(){
        DataAccessCoordinator c=ready();
        try(DataAccessCoordinator.Lease l=c.tryShort()){throw new IllegalArgumentException("short");}
        catch(IllegalArgumentException expected){}
        assertEquals(READY,c.state());
        try(DataAccessCoordinator.Lease l=c.claim(c.tryReserve(DataAccessCoordinator.Work.SNAPSHOT))){throw new IllegalArgumentException("snapshot");}
        catch(IllegalArgumentException expected){}
        assertEquals(READY,c.state());
        try(DataAccessCoordinator.Lease l=c.claim(c.tryReserve(DataAccessCoordinator.Work.RESTORE))){throw new IllegalArgumentException("restore");}
        catch(IllegalArgumentException expected){}
        assertEquals(RECOVERY_REQUIRED,c.state());assertNull(c.tryShort());
        try(DataAccessCoordinator.Lease l=c.claim(c.tryReserve(RECOVERY))){l.finishVerified(OLD);}
        assertEquals(READY,c.state());
    }

    @Test public void workerOwnershipSurvivesInitiatingThreadExit()throws Exception{
        DataAccessCoordinator c=ready();ExecutorService initiator=Executors.newSingleThreadExecutor(),worker=Executors.newSingleThreadExecutor();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try{
            DataAccessCoordinator.Ticket t=result(initiator.submit(()->c.tryReserve(DataAccessCoordinator.Work.RESTORE)));
            stop(initiator);
            Future<?> f=worker.submit(()->{
                try(DataAccessCoordinator.Lease l=c.claim(t)){assertNotNull(l);entered.countDown();await(release);l.finishVerified(NEW);}
                return null;
            });
            await(entered);assertNull(c.tryShort());assertFalse(c.cancel(t));assertEquals(DataAccessCoordinator.State.RESTORE,c.state());
            release.countDown();result(f);
            try(DataAccessCoordinator.Lease l=c.tryShort()){assertEquals(NEW,l.capture().generation());}
        }finally{release.countDown();stop(worker);if(!initiator.isTerminated())stop(initiator);}
    }

    @Test public void oldLeaseDoubleCloseCannotReleaseNewOperation(){
        DataAccessCoordinator c=ready();DataAccessCoordinator.Lease old=c.tryShort();old.close();
        try(DataAccessCoordinator.Lease current=c.tryShort()){
            old.close();current.requireOwner();assertEquals(SHORT,c.state());
        }
        assertEquals(READY,c.state());
    }

    @Test public void staleTokenRejectedAfterRestoreAndRollbackEvenWithSameUuid(){
        DataAccessCoordinator c=ready();DataAccessCoordinator.Token old;
        try(DataAccessCoordinator.Lease l=c.tryShort()){old=l.capture();}
        try(DataAccessCoordinator.Lease l=c.claim(c.tryReserve(DataAccessCoordinator.Work.RESTORE))){l.finishVerified(NEW);}
        assertNull(c.tryShort(old));DataAccessCoordinator.Token next;
        try(DataAccessCoordinator.Lease l=c.tryShort()){next=l.capture();}
        try(DataAccessCoordinator.Lease l=c.claim(c.tryReserve(DataAccessCoordinator.Work.RESTORE))){}
        assertNull(c.tryShort(next));
        try(DataAccessCoordinator.Lease l=c.claim(c.tryReserve(RECOVERY))){l.finishVerified(NEW);}
        assertNull(c.tryShort(next));
        try(DataAccessCoordinator.Lease l=c.tryShort()){assertEquals(NEW,l.capture().generation());}
    }

    @Test public void tokenAdmissionIsAtomicAgainstRestoreReservation()throws Exception{
        DataAccessCoordinator c=ready(),other=ready();DataAccessCoordinator.Token token;
        try(DataAccessCoordinator.Lease l=c.tryShort()){token=l.capture();}
        assertNull(other.tryShort(token));assertNull(c.tryShort((DataAccessCoordinator.Token)null));
        try(DataAccessCoordinator.Lease l=c.tryShort(token)){
            assertNotNull(l);assertNull(c.tryReserve(DataAccessCoordinator.Work.RESTORE));
        }
        DataAccessCoordinator.Ticket t=c.tryReserve(DataAccessCoordinator.Work.RESTORE);
        assertNull(c.tryShort(token));assertTrue(c.cancel(t));
        try(DataAccessCoordinator.Lease l=c.tryShort(token)){assertNotNull(l);}
    }
}
