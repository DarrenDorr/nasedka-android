package org.nasedka.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.nasedka.app.RestoreProtocol.Phase.*;
import static org.nasedka.app.RestoreProtocol.Outcome.*;

@RunWith(AndroidJUnit4.class)
public class RestoreFileStorageTest {
    private Context c;
    private Path root, work;
    private SharedPreferences prefs;
    private String prefsName;
    private RestoreSettingsPort port;
    private byte[] oldSettings,newSettings;
    @Before public void setup() throws Exception {
        c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        root=Files.createTempDirectory(c.getCacheDir().toPath(),"restore-test-");
        work=Files.createDirectory(root.resolve(RestoreFileStorage.WORK));
        prefsName="r1_test_"+UUID.randomUUID();
        prefs=c.getSharedPreferences(prefsName,Context.MODE_PRIVATE);
        assertTrue(prefs.edit().putString("checks","08:00").putLong("next_alarm_at",42)
                .putBoolean("notifications",true).putString("unrelated","keep").commit());
        port=new RestoreSettingsPort(prefs);
        oldSettings=port.read();
        Map<String,Object> next=new HashMap<>();next.put("checks","09:00");next.put("notifications",false);
        newSettings=RestoreSettingsPort.encode(next);
        db(root.resolve("incuba.db"),"old-inc");db(root.resolve("nasedka_farm.db"),"old-farm");
        db(work.resolve(RestoreFileStorage.INCOMING_INC),"new-inc");
        db(work.resolve(RestoreFileStorage.INCOMING_FARM),"new-farm");
    }
    @After public void cleanup() throws Exception {
        if(prefsName!=null)c.deleteSharedPreferences(prefsName);
        if(root!=null)try(java.util.stream.Stream<Path> paths=Files.walk(root)){
            for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(p);
        }
    }
    // Real SQLite files with synthetic data, not the application databases.
    private static void db(Path p,String value)throws IOException{
        try(SQLiteDatabase d=SQLiteDatabase.openOrCreateDatabase(p.toFile(),null)){
            d.execSQL("CREATE TABLE sample (value TEXT NOT NULL)");
            d.execSQL("INSERT INTO sample(value) VALUES(?)",new Object[]{value});
        }
        RestoreSqlitePreparation.prepareClosed(p);
    }
    private static String value(Path p){
        try(SQLiteDatabase d=SQLiteDatabase.openDatabase(p.toString(),null,SQLiteDatabase.OPEN_READONLY);
            Cursor row=d.rawQuery("SELECT value FROM sample",null)){
            assertTrue(row.moveToFirst());String v=row.getString(0);assertFalse(row.moveToNext());return v;
        }
    }
    private RestoreFileStorage storage(byte[] target) throws IOException {return new RestoreFileStorage(root,port,target);}
    private void assertGeneration(boolean next) throws Exception {
        assertEquals(next?"new-inc":"old-inc",value(root.resolve("incuba.db")));
        assertEquals(next?"new-farm":"old-farm",value(root.resolve("nasedka_farm.db")));
        assertArrayEquals(next?newSettings:oldSettings,port.read());
        assertEquals("keep",prefs.getString("unrelated",null));
    }
    interface Checked {void run() throws Exception;}
    private static void rejected(Checked call) throws Exception {
        try{call.run();fail("Expected IOException");}catch(IOException expected){}
    }

    @Test public void restoreTwoSqliteFilesAndSettings() throws Exception {
        assertEquals(NEW_GENERATION,new RestoreProtocol(storage(newSettings)).restore());
        assertGeneration(true);assertEquals(NONE,storage(null).readPhase());
        assertFalse(prefs.contains("next_alarm_at"));
        assertFalse(Files.exists(work.resolve("old-inc.db")));
    }
    @Test public void recoverAfterOneFileReplacement() throws Exception {
        RestoreFileStorage s=storage(newSettings);
        s.prepare();s.publish(NONE,PENDING);s.applyIncubation();
        assertEquals(PENDING,storage(null).readPhase());
        assertEquals(OLD_GENERATION,new RestoreProtocol(storage(null)).recover());
        assertGeneration(false);assertEquals(NONE,storage(null).readPhase());
    }
    @Test public void failedSettingsWriteAfterBothDatabasesRecovers() throws Exception {
        final boolean[] attempted={false};
        RestoreFileStorage.SettingsPort failing=new RestoreFileStorage.SettingsPort(){
            public byte[] read() throws IOException{return port.read();}
            public void write(byte[] bytes) throws IOException{attempted[0]=true;throw new IOException("Synthetic commit failure");}
        };
        RestoreFileStorage s=new RestoreFileStorage(root,failing,newSettings);
        rejected(()->new RestoreProtocol(s).restore());
        assertTrue("Must reach settings write, not fail earlier",attempted[0]);
        assertEquals(PENDING,storage(null).readPhase());
        assertEquals(OLD_GENERATION,new RestoreProtocol(storage(null)).recover());
        assertGeneration(false);
    }
    @Test public void corruptRollbackSourceKeepsJournalAndOtherCopies() throws Exception {
        RestoreFileStorage s=storage(newSettings);
        s.prepare();s.publish(NONE,PENDING);s.applyIncubation();
        Files.write(work.resolve("old-farm.db"),new byte[]{1,2,3});
        rejected(()->new RestoreProtocol(storage(null)).recover());
        assertEquals(PENDING,storage(null).readPhase());
        assertTrue(Files.exists(work.resolve("old-inc.db")));
        assertTrue(Files.exists(work.resolve("old-prefs.bin")));
        assertEquals("new-inc",value(root.resolve("incuba.db")));
    }
    @Test public void legacyMarkerAndSidecarsBlockBeforeMutation() throws Exception {
        Path marker=root.resolve(".nasedka_restore_pending");Files.write(marker,new byte[]{1});
        rejected(()->new RestoreProtocol(storage(newSettings)).restore());assertTrue(Files.exists(marker));
        Files.delete(marker);
        Path wal=root.resolve("incuba.db-wal");Files.write(wal,new byte[]{1});
        rejected(()->new RestoreProtocol(storage(newSettings)).restore());assertTrue(Files.exists(wal));
        Files.delete(wal);assertGeneration(false);assertEquals(NONE,storage(null).readPhase());
    }

    @Test public void preparedWalPreservesCommittedRows() throws Exception {
        Path p=work.resolve("wal-fixture.db");
        try(SQLiteDatabase d=SQLiteDatabase.openOrCreateDatabase(p.toFile(),null)){
            assertTrue(d.enableWriteAheadLogging());
            d.execSQL("CREATE TABLE sample (value TEXT NOT NULL)");
            d.execSQL("INSERT INTO sample(value) VALUES('committed-wal')");
            try(Cursor c=d.rawQuery("PRAGMA journal_mode",null)){
                assertTrue(c.moveToFirst());assertEquals("wal",c.getString(0).toLowerCase(Locale.ROOT));
            }
        }
        RestoreSqlitePreparation.prepareClosed(p);
        for(String suffix:new String[]{"-wal","-shm","-journal"})
            assertFalse(Files.exists(p.resolveSibling(p.getFileName()+suffix)));
        assertEquals("committed-wal",value(p));
    }
    @Test public void missingSqliteIsNotCreatedByPreparation() throws Exception {
        Path missing=work.resolve("missing.db");
        rejected(()->RestoreSqlitePreparation.prepareClosed(missing));
        assertFalse(Files.exists(missing));
    }
    @Test public void insufficientSpaceRejectsBeforeJournalOrLiveChanges() throws Exception {
        RestoreFileStorage s=new RestoreFileStorage(root,port,newSettings,path->0L);
        try{new RestoreProtocol(s).restore();fail("Expected insufficient space");}
        catch(IOException e){assertTrue(e.getMessage().contains("Insufficient space"));}
        assertEquals(NONE,storage(null).readPhase());
        assertFalse(Files.exists(work.resolve("old-inc.db")));
        assertGeneration(false);
    }
    @Test public void settingsFailureAfterWriteStillRollsBack() throws Exception {
        final boolean[] attempted={false};
        RestoreFileStorage.SettingsPort failing=new RestoreFileStorage.SettingsPort(){
            public byte[] read()throws IOException{return port.read();}
            public void write(byte[] bytes)throws IOException{
                port.write(bytes);attempted[0]=true;throw new IOException("Ambiguous settings write");
            }
        };
        rejected(()->new RestoreProtocol(new RestoreFileStorage(root,failing,newSettings)).restore());
        assertTrue(attempted[0]);assertArrayEquals(newSettings,port.read());
        assertEquals(PENDING,storage(null).readPhase());
        assertEquals(OLD_GENERATION,new RestoreProtocol(storage(null)).recover());
        assertGeneration(false);
    }
    @Test public void repeatedRecoveryAfterPartialRollback() throws Exception {
        RestoreFileStorage s=storage(newSettings);
        s.prepare();s.publish(NONE,PENDING);s.applyIncubation();s.applyFarm();s.applySettings();
        s.rollbackIncubation(); // simulate a restart during rollback, not real process kill
        assertEquals(PENDING,storage(null).readPhase());
        assertEquals(OLD_GENERATION,new RestoreProtocol(storage(null)).recover());
        assertGeneration(false);
    }
    @Test public void committedRecoveryAfterPartialCleanupRetainsNewData() throws Exception {
        RestoreFileStorage s=storage(newSettings);
        s.prepare();s.publish(NONE,PENDING);s.applyIncubation();s.applyFarm();s.applySettings();
        s.verifyNew();s.publish(PENDING,COMMITTED);
        Files.delete(work.resolve("old-inc.db")); // interrupted terminal cleanup
        assertEquals(NEW_GENERATION,new RestoreProtocol(storage(null)).recover());
        assertGeneration(true);assertEquals(NONE,storage(null).readPhase());
    }

    private Path legacyMarker()throws IOException {
        Path p=root.resolve(".nasedka_restore_pending");
        Files.write(p,"rollback_if_interrupted".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return p;
    }
    private void legacyStage()throws IOException {
        Files.copy(work.resolve(RestoreFileStorage.INCOMING_INC),root.resolve("incuba_restore_stage.db"));
        Files.copy(work.resolve(RestoreFileStorage.INCOMING_FARM),root.resolve("farm_restore_stage.db"));
    }
    private Path legacyOld(String name){return root.resolve(name+".pre_restore.bak");}
    private void moveLegacyPrefix(int count)throws IOException {
        Path inc=root.resolve("incuba.db"),farm=root.resolve("nasedka_farm.db");
        if(count>=1)Files.move(inc,legacyOld("incuba.db"));
        if(count>=2)Files.move(farm,legacyOld("nasedka_farm.db"));
        if(count>=3)Files.move(root.resolve("incuba_restore_stage.db"),inc);
        if(count>=4)Files.move(root.resolve("farm_restore_stage.db"),farm);
    }
    private void verifyLegacySample(Path inc,Path farm)throws IOException {
        if(!"old-inc".equals(value(inc))||!"old-farm".equals(value(farm)))
            throw new IOException("Wrong legacy generation");
    }
    @Test public void legacyRecoveryAcrossAllFiveRenamePrefixes() throws Exception {
        // Independent fixture for every prefix of historical four renames.
        Path initialRoot=root;
        try {
            for(int prefix=0;prefix<=4;prefix++){
                root=Files.createDirectory(initialRoot.resolve("legacy-"+prefix));
                Files.copy(initialRoot.resolve("incuba.db"),root.resolve("incuba.db"));
                Files.copy(initialRoot.resolve("nasedka_farm.db"),root.resolve("nasedka_farm.db"));
                legacyStage();Path marker=legacyMarker();moveLegacyPrefix(prefix);
                assertTrue(RestoreLegacyRecovery.recover(root,this::verifyLegacySample));
                assertFalse(Files.exists(marker));assertGeneration(false);
                if(prefix>=1)assertTrue(Files.exists(legacyOld("incuba.db")));
                if(prefix>=2)assertTrue(Files.exists(legacyOld("nasedka_farm.db")));
                assertFalse(RestoreLegacyRecovery.recover(root,this::verifyLegacySample));
            }
        }finally{root=initialRoot;}
    }
    @Test public void legacyRecoveryRepeatsAfterFirstCopy() throws Exception {
        legacyStage();Path marker=legacyMarker();moveLegacyPrefix(4);
        RestoreFileStorage.copyDurable(legacyOld("incuba.db"),root.resolve("incuba.db"));
        assertTrue(Files.exists(marker));
        assertTrue(RestoreLegacyRecovery.recover(root,this::verifyLegacySample));
        assertGeneration(false);assertFalse(Files.exists(marker));
        assertTrue(Files.exists(legacyOld("incuba.db")));assertTrue(Files.exists(legacyOld("nasedka_farm.db")));
    }
    @Test public void legacyAmbiguousOrMalformedMarkerPreservesFiles() throws Exception {
        legacyStage();Path marker=legacyMarker();moveLegacyPrefix(4);
        // Historical recovery may have consumed one old copy. Never guess.
        Files.delete(legacyOld("incuba.db"));
        byte[] inc=Files.readAllBytes(root.resolve("incuba.db"));
        byte[] farm=Files.readAllBytes(root.resolve("nasedka_farm.db"));
        rejected(()->RestoreLegacyRecovery.recover(root,this::verifyLegacySample));
        assertTrue(Files.exists(marker));assertTrue(Files.exists(legacyOld("nasedka_farm.db")));
        assertArrayEquals(inc,Files.readAllBytes(root.resolve("incuba.db")));
        assertArrayEquals(farm,Files.readAllBytes(root.resolve("nasedka_farm.db")));
        Files.write(marker,new byte[]{1});
        rejected(()->RestoreLegacyRecovery.recover(root,this::verifyLegacySample));
        assertArrayEquals(new byte[]{1},Files.readAllBytes(marker));
    }
    @Test public void legacyConflictingJournalOrFailedValidationBlocksReplacement() throws Exception {
        legacyStage();
        RestoreFileStorage s=storage(newSettings);s.prepare();s.publish(NONE,PENDING);
        Path marker=legacyMarker();
        rejected(()->RestoreLegacyRecovery.recover(root,this::verifyLegacySample));
        assertTrue(Files.exists(marker));Files.delete(marker);
        assertEquals(OLD_GENERATION,new RestoreProtocol(storage(null)).recover());
        // Recovery cleaned the v2 incoming files; recreate only test fixtures.
        db(work.resolve(RestoreFileStorage.INCOMING_INC),"new-inc");
        db(work.resolve(RestoreFileStorage.INCOMING_FARM),"new-farm");
        // Legacy stage files from before v2 remain distinct.
        legacyMarker();moveLegacyPrefix(4);
        byte[] before=Files.readAllBytes(root.resolve("incuba.db"));
        rejected(()->RestoreLegacyRecovery.recover(root,(i,f)->{throw new IOException("Invalid pair");}));
        assertArrayEquals(before,Files.readAllBytes(root.resolve("incuba.db")));
        assertTrue(Files.exists(marker));assertTrue(Files.exists(legacyOld("incuba.db")));
    }
    @Test public void legacyRecoveryValidatesRealVersionTwoSchemas() throws Exception {
        Path initialRoot=root;
        try{
            root=Files.createDirectory(initialRoot.resolve("legacy-schema"));
            Path inc=root.resolve("incuba.db"),farm=root.resolve("nasedka_farm.db");
            try(Db ih=new Db(c);FarmDb fh=new FarmDb(c);
                SQLiteDatabase i=SQLiteDatabase.openOrCreateDatabase(inc.toFile(),null);
                SQLiteDatabase f=SQLiteDatabase.openOrCreateDatabase(farm.toFile(),null)){
                ih.onCreate(i);fh.onCreate(f);i.setVersion(2);f.setVersion(2);
                i.execSQL("INSERT INTO batches(id,name,species,incubator_model,eggs,duration,started_at,manual_turn,spray,target_temp) VALUES(7,'old','chicken','test',4,21,1,0,0,37.5)");
                f.execSQL("INSERT INTO flock(id,name,species,breed,place,since) VALUES(3,'old','chicken','test','test',1)");
                f.execSQL("INSERT INTO incubator_transfers(batch_id,flock_id,quantity,at) VALUES(7,3,4,1)");
            }
            RestoreSqlitePreparation.prepareClosed(inc);RestoreSqlitePreparation.prepareClosed(farm);
            legacyStage();Path marker=legacyMarker();moveLegacyPrefix(4);
            assertTrue(RestoreLegacyRecovery.recover(root));assertFalse(Files.exists(marker));
            try(SQLiteDatabase i=SQLiteDatabase.openDatabase(inc.toString(),null,SQLiteDatabase.OPEN_READONLY);
                SQLiteDatabase f=SQLiteDatabase.openDatabase(farm.toString(),null,SQLiteDatabase.OPEN_READONLY);
                Cursor row=f.rawQuery("SELECT batch_id,quantity FROM incubator_transfers",null)){
                BackupStore.relations(i,f);assertTrue(row.moveToFirst());assertEquals(7,row.getInt(0));assertEquals(4,row.getInt(1));
            }
            assertArrayEquals(oldSettings,port.read());
        }finally{root=initialRoot;}
    }

    private void assertRealFileAccessFailure(Checked action)throws Exception {
        try{action.run();fail("Expected real filesystem access failure");}
        catch(java.nio.file.FileSystemException e){
            assertTrue("Expected permission denial: "+e,e instanceof java.nio.file.AccessDeniedException
                    ||String.valueOf(e.getReason()).toLowerCase(Locale.ROOT).contains("permission denied"));
        }
    }
    @Test public void realWriteDenialDuringApplyRetainsPending()throws Exception {
        RestoreFileStorage s=storage(newSettings);s.prepare();s.publish(NONE,PENDING);
        android.system.Os.chmod(root.toString(),0500);
        try{assertRealFileAccessFailure(s::applyIncubation);}
        finally{android.system.Os.chmod(root.toString(),0700);}
        assertEquals(PENDING,storage(null).readPhase());
        assertTrue(Files.exists(work.resolve("old-inc.db")));
        assertEquals(OLD_GENERATION,new RestoreProtocol(storage(null)).recover());assertGeneration(false);
    }
    @Test public void realWriteDenialDuringCommitPublicationRollsBack()throws Exception {
        RestoreFileStorage s=storage(newSettings);s.prepare();s.publish(NONE,PENDING);
        s.applyIncubation();s.applyFarm();s.applySettings();
        android.system.Os.chmod(work.toString(),0500);
        try{assertRealFileAccessFailure(()->s.publish(PENDING,COMMITTED));}
        finally{android.system.Os.chmod(work.toString(),0700);}
        assertEquals(PENDING,storage(null).readPhase());
        assertEquals(OLD_GENERATION,new RestoreProtocol(storage(null)).recover());assertGeneration(false);
    }
    @Test public void realWriteDenialDuringRollbackPreservesSources()throws Exception {
        RestoreFileStorage s=storage(newSettings);s.prepare();s.publish(NONE,PENDING);s.applyIncubation();
        android.system.Os.chmod(root.toString(),0500);
        try{assertRealFileAccessFailure(()->new RestoreProtocol(storage(null)).recover());}
        finally{android.system.Os.chmod(root.toString(),0700);}
        assertEquals(PENDING,storage(null).readPhase());
        assertTrue(Files.exists(work.resolve("old-inc.db")));assertTrue(Files.exists(work.resolve("old-farm.db")));
        assertEquals(OLD_GENERATION,new RestoreProtocol(storage(null)).recover());assertGeneration(false);
    }

    // Inject failure of the directory-force call, not a physical disk fault.
    // Every non-failing invocation performs the original real force.
    private static final class JournalForceFault implements RestoreJournal.DirectorySync {
        boolean armed;
        int failures;
        @Override public void force(Path directory)throws IOException {
            if(armed){armed=false;failures++;throw new IOException("Injected journal directory force failure");}
            RestoreFileStorage.sync(directory);
        }
    }
    private RestoreFileStorage faultStorage(byte[] target,JournalForceFault fault)throws IOException{
        return new RestoreFileStorage(root,port,target,p->p.toFile().getUsableSpace(),fault);
    }
    private void assertRollbackCopiesPresent(){
        for(String name:new String[]{"old-inc.db","old-farm.db","old-prefs.bin","new-inc.db","new-farm.db","new-prefs.bin"})
            assertTrue("Missing source "+name,Files.isRegularFile(work.resolve(name)));
    }
    private static void forceRejected(Checked action,JournalForceFault fault)throws Exception{
        int before=fault.failures;fault.armed=true;
        try{action.run();fail("Expected directory force failure");}
        catch(IOException e){assertEquals("Injected journal directory force failure",e.getMessage());}
        assertEquals(before+1,fault.failures);assertFalse(fault.armed);
    }

    @Test public void ambiguousCommittedForceRetainsCopiesUntilResynced()throws Exception{
        JournalForceFault fault=new JournalForceFault();
        RestoreFileStorage s=faultStorage(newSettings,fault);
        s.prepare();s.publish(NONE,PENDING);s.applyIncubation();s.applyFarm();s.applySettings();
        forceRejected(()->s.publish(PENDING,COMMITTED),fault);
        assertEquals(COMMITTED,s.readPhase());assertRollbackCopiesPresent();
        RestoreFileStorage restarted=faultStorage(null,fault);
        forceRejected(()->new RestoreProtocol(restarted).recover(),fault);
        assertEquals(COMMITTED,restarted.readPhase());assertRollbackCopiesPresent();
        assertEquals(NEW_GENERATION,new RestoreProtocol(faultStorage(null,fault)).recover());
        assertEquals(NONE,storage(null).readPhase());assertGeneration(true);
    }

    @Test public void ambiguousRolledBackForceRetainsCopiesUntilResynced()throws Exception{
        JournalForceFault fault=new JournalForceFault();
        RestoreFileStorage s=faultStorage(newSettings,fault);
        s.prepare();s.publish(NONE,PENDING);s.applyIncubation();s.applyFarm();s.applySettings();
        s.rollbackIncubation();s.rollbackFarm();s.rollbackSettings();
        forceRejected(()->s.publish(PENDING,ROLLED_BACK),fault);
        assertEquals(ROLLED_BACK,s.readPhase());assertRollbackCopiesPresent();
        forceRejected(()->new RestoreProtocol(faultStorage(null,fault)).recover(),fault);
        assertRollbackCopiesPresent();
        assertEquals(OLD_GENERATION,new RestoreProtocol(faultStorage(null,fault)).recover());
        assertEquals(NONE,storage(null).readPhase());assertGeneration(false);
    }

    @Test public void ambiguousJournalRemovalRequiresDurableAbsence()throws Exception{
        JournalForceFault fault=new JournalForceFault();
        RestoreFileStorage s=faultStorage(newSettings,fault);
        s.prepare();s.publish(NONE,PENDING);s.applyIncubation();s.applyFarm();s.applySettings();
        s.publish(PENDING,COMMITTED);s.cleanup(COMMITTED);
        forceRejected(()->s.clear(COMMITTED),fault);
        assertFalse(Files.exists(work.resolve("restore-v2.journal")));
        forceRejected(()->new RestoreProtocol(faultStorage(null,fault)).recover(),fault);
        assertEquals(NO_PENDING,new RestoreProtocol(faultStorage(null,fault)).recover());
        assertGeneration(true);
    }

    private static final UUID GEN_OLD=UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID GEN_NEW=UUID.fromString("00000000-0000-4000-8000-000000000002");
    private void installGenerationFixtures()throws IOException {
        assertTrue(prefs.edit().putString(RestoreSettingsPort.GENERATION,GEN_OLD.toString()).commit());
        oldSettings=port.read();
        Map<String,Object> next=new HashMap<>();
        next.put("checks","09:00");next.put("notifications",false);
        next.put(RestoreSettingsPort.GENERATION,GEN_NEW.toString());
        newSettings=RestoreSettingsPort.encode(next);
    }

    @Test public void generationCommitAndRollbackPreserveCorrectUuid()throws Exception {
        installGenerationFixtures();
        RestoreFileStorage s=storage(newSettings);s.prepare();s.publish(NONE,PENDING);
        s.applyIncubation();s.applyFarm();s.applySettings();
        assertEquals(GEN_NEW,port.generation());
        assertEquals(OLD_GENERATION,new RestoreProtocol(storage(null)).recover());
        assertEquals(GEN_OLD,new RestoreSettingsPort(prefs).generation());assertGeneration(false);
        // A terminal rollback intentionally consumes the staging inputs.
        // A second independent restore must supply its own prepared SQLite pair.
        assertEquals(NONE,storage(null).readPhase());
        assertFalse(Files.exists(work.resolve(RestoreFileStorage.INCOMING_INC)));
        assertFalse(Files.exists(work.resolve(RestoreFileStorage.INCOMING_FARM)));
        db(work.resolve(RestoreFileStorage.INCOMING_INC),"new-inc");
        db(work.resolve(RestoreFileStorage.INCOMING_FARM),"new-farm");
        assertEquals(NEW_GENERATION,new RestoreProtocol(storage(newSettings)).restore());
        assertEquals(GEN_NEW,new RestoreSettingsPort(prefs).generation());assertGeneration(true);
        assertEquals(NONE,storage(null).readPhase());
        assertFalse(Files.exists(work.resolve(RestoreFileStorage.INCOMING_INC)));
        assertFalse(Files.exists(work.resolve(RestoreFileStorage.INCOMING_FARM)));
    }

    @Test public void legacySettingsEncodingRemainsByteIdenticalAndRemovesGeneration()throws Exception {
        byte[] legacy=oldSettings.clone();
        java.io.ByteArrayOutputStream buf=new java.io.ByteArrayOutputStream();
        try(java.io.DataOutputStream out=new java.io.DataOutputStream(buf)){
            out.writeInt(0x4e535032);out.writeBoolean(true);out.writeUTF("08:00");
            out.writeBoolean(false);out.writeBoolean(false);out.writeBoolean(false);
            out.writeBoolean(true);out.writeBoolean(true);
            out.writeBoolean(true);out.writeLong(42L);
            out.writeBoolean(false);out.writeBoolean(false);out.writeBoolean(false);
        }
        assertArrayEquals(buf.toByteArray(),legacy);
        installGenerationFixtures();
        port.write(legacy);
        assertNull(port.generation());assertArrayEquals(legacy,port.read());
        assertEquals("keep",prefs.getString("unrelated",null));
    }

    @Test public void malformedGenerationAndUnknownVersionRejectWithoutMutation()throws Exception {
        installGenerationFixtures();byte[] before=port.read();
        Map<String,Object> invalid=new HashMap<>();
        for(Object bad:new Object[]{"1-1-1-1-1","00000000-0000-4000-8000-00000000000A",42L,"",null}){
            invalid.put(RestoreSettingsPort.GENERATION,bad);
            rejected(()->RestoreSettingsPort.encode(invalid));
        }
        byte[] unknown=before.clone();unknown[3]=99;
        rejected(()->port.write(unknown));
        byte[] truncated=Arrays.copyOf(before,before.length-1);
        rejected(()->port.write(truncated));
        assertArrayEquals(before,port.read());assertEquals(GEN_OLD,port.generation());
    }

    @Test public void targetGenerationIgnoresImportedUuidAndSchedulerState()throws Exception {
        installGenerationFixtures();
        Map<String,Object> incoming=new HashMap<>();
        for(String key:new String[]{"checks","turns","sprays","nest_checks"})incoming.put(key,"09:00");
        incoming.put("notifications",true);incoming.put(RestoreSettingsPort.GENERATION,GEN_NEW.toString());
        incoming.put("next_alarm_at",999L);incoming.put("unrelated","overwrite");
        rejected(()->RestoreSettingsPort.target(incoming,null));
        byte[] target=RestoreSettingsPort.target(incoming,GEN_OLD);
        port.write(target);
        UUID issued=port.generation();assertNotNull(issued);assertNotEquals(GEN_OLD,issued);
        assertNotEquals(GEN_NEW,issued);assertFalse(prefs.contains("next_alarm_at"));
        assertEquals("keep",prefs.getString("unrelated",null));assertArrayEquals(target,port.read());
    }

    @Test public void generationTokenChangesOnlyAfterVerifiedRestoreOutcome()throws Exception {
        installGenerationFixtures();
        DataAccessCoordinator coordinator=new DataAccessCoordinator();
        try(DataAccessCoordinator.Lease lease=coordinator.claim(coordinator.tryReserve(DataAccessCoordinator.Work.RECOVERY))){
            assertEquals(NONE,storage(null).readPhase());lease.finishVerified(port.generation());
        }
        DataAccessCoordinator.Token token;
        try(DataAccessCoordinator.Lease lease=coordinator.tryShort()){token=lease.capture();}
        try(DataAccessCoordinator.Lease lease=coordinator.claim(coordinator.tryReserve(DataAccessCoordinator.Work.RESTORE))){
            RestoreFileStorage s=storage(newSettings);s.prepare();s.publish(NONE,PENDING);
            s.applyIncubation();s.applyFarm();s.applySettings();
            assertNull(coordinator.tryShort(token));assertEquals(GEN_NEW,port.generation());
        }
        assertEquals(DataAccessCoordinator.State.RECOVERY_REQUIRED,coordinator.state());
        try(DataAccessCoordinator.Lease lease=coordinator.claim(coordinator.tryReserve(DataAccessCoordinator.Work.RECOVERY))){
            assertEquals(OLD_GENERATION,new RestoreProtocol(storage(null)).recover());
            assertEquals(NONE,storage(null).readPhase());lease.finishVerified(port.generation());
        }
        assertNull(coordinator.tryShort(token));
        try(DataAccessCoordinator.Lease lease=coordinator.tryShort()){assertEquals(GEN_OLD,lease.capture().generation());}
        assertGeneration(false);
    }

    @Test public void startupGenerationInitializationIsStableAndPreservesSettings()throws Exception {
        assertNull(port.generation());byte[] previous=port.read();
        UUID issued=port.initializeGeneration();assertNotNull(issued);
        assertEquals(issued,new RestoreSettingsPort(prefs).initializeGeneration());
        assertEquals(issued,port.generation());
        assertEquals("08:00",prefs.getString("checks",null));
        assertEquals(42L,prefs.getLong("next_alarm_at",0));
        assertEquals("keep",prefs.getString("unrelated",null));
        assertFalse(Arrays.equals(previous,port.read()));
    }

    // Simulate the Android contract where commit reports failure even though
    // the in-memory map changed. Delegate edits to actual isolated prefs.
    // This is NOT a physical I/O failure or proof of disk state.
    private static SharedPreferences falseCommit(SharedPreferences backing){
        return (SharedPreferences)java.lang.reflect.Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},
                (proxy,method,args)->{
                    if(!"edit".equals(method.getName()))return invokeDelegate(backing,method,args);
                    SharedPreferences.Editor editor=backing.edit();
                    return java.lang.reflect.Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class},(e,m,a)->{
                                if("commit".equals(m.getName())){editor.apply();return false;}
                                Object value=invokeDelegate(editor,m,a);
                                return value==editor?e:value;
                            });
                });
    }
    private static Object invokeDelegate(Object target,java.lang.reflect.Method method,Object[] args)throws Throwable{
        try{return method.invoke(target,args);}
        catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
    }

    @Test public void falseCommitQuarantinesCachedGenerationAcrossAdapterInstances()throws Exception {
        SharedPreferences failing=falseCommit(prefs);
        RestoreSettingsPort uncertain=new RestoreSettingsPort(failing);
        rejected(uncertain::initializeGeneration);
        assertNotNull(prefs.getString(RestoreSettingsPort.GENERATION,null));
        rejected(uncertain::generation);rejected(uncertain::read);
        RestoreSettingsPort reopened=new RestoreSettingsPort(failing);
        rejected(reopened::initializeGeneration);rejected(()->reopened.write(oldSettings));
        rejected(reopened::read);
    }

    @Test public void falseCommitDuringRestoreRetainsPendingAndBlocksSameProcessRecovery()throws Exception {
        installGenerationFixtures();
        SharedPreferences failing=falseCommit(prefs);
        RestoreSettingsPort uncertain=new RestoreSettingsPort(failing);
        RestoreFileStorage s=new RestoreFileStorage(root,uncertain,newSettings);
        rejected(()->new RestoreProtocol(s).restore());
        assertEquals(PENDING,s.readPhase());
        assertEquals(GEN_NEW.toString(),prefs.getString(RestoreSettingsPort.GENERATION,null));
        assertTrue(Files.exists(work.resolve("old-inc.db")));assertTrue(Files.exists(work.resolve("old-farm.db")));
        rejected(()->new RestoreProtocol(new RestoreFileStorage(root,new RestoreSettingsPort(failing),null)).recover());
        assertEquals(PENDING,s.readPhase());assertTrue(Files.exists(work.resolve("old-prefs.bin")));
        // Intentionally no READY claim or same-process bypass through backing.
    }
}
