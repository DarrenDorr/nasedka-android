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
}
