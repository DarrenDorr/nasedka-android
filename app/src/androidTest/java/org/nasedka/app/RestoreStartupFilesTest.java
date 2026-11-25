package org.nasedka.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.database.Cursor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RestoreStartupFilesTest {
    private Context context;
    private Path root,inc,farm;
    private String name;
    private SharedPreferences prefs;
    private RestoreSettingsPort settings;
    private DataAccessCoordinator coordinator;
    private RestoreStartupFiles backend;
    @Before public void setup()throws Exception{
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        root=Files.createTempDirectory(context.getCacheDir().toPath(),"startup-files-");
        inc=root.resolve("incuba.db");farm=root.resolve(FarmDb.NAME);
        name="startup_"+UUID.randomUUID();prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE);
        settings=new RestoreSettingsPort(prefs);
        reset();
    }
    private void reset()throws IOException{
        coordinator=new DataAccessCoordinator();backend=new RestoreStartupFiles(context,root,settings);
    }
    @After public void cleanup()throws Exception{
        if(name!=null)context.deleteSharedPreferences(name);
        if(root!=null)try(java.util.stream.Stream<Path> paths=Files.walk(root)){
            for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(p);
        }
    }
    private void ready()throws Exception{
        assertTrue(RestoreStartupGate.attempt(coordinator,backend));
        assertEquals(DataAccessCoordinator.State.READY,coordinator.state());
        try(DataAccessCoordinator.Lease l=coordinator.tryShort()){
            assertNotNull(l);assertEquals(settings.generation(),l.capture().generation());
        }
    }
    private void blocked()throws Exception{
        try{RestoreStartupGate.attempt(coordinator,backend);fail("Expected blocked startup");}
        catch(IOException expected){}
        assertEquals(DataAccessCoordinator.State.RECOVERY_REQUIRED,coordinator.state());
        assertNull(coordinator.tryShort());
    }
    private void schema(Path path,boolean incubation)throws Exception{
        try(SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(path.toFile(),null)){
            if(incubation){try(Db h=new Db(context)){h.onCreate(db);}}
            else{try(FarmDb h=new FarmDb(context)){h.onCreate(db);}}
            db.setVersion(2);
        }
        RestoreSqlitePreparation.prepareClosed(path);
    }
    private void pair()throws Exception{schema(inc,true);schema(farm,false);}
    private void insertBatch(Path path,String label)throws Exception{
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(path.toString(),null,SQLiteDatabase.OPEN_READWRITE)){
            db.execSQL("INSERT INTO batches(id,name,species,incubator_model,eggs,duration,started_at,manual_turn,spray,target_temp) VALUES(7,?,'chicken','test',4,21,1,0,0,37.5)",new Object[]{label});
        }
        RestoreSqlitePreparation.prepareClosed(path);
    }
    private String batch(Path path)throws Exception{
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(path.toString(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            Cursor c=db.rawQuery("SELECT name FROM batches WHERE id=7",null)){
            assertTrue(c.moveToFirst());return c.getString(0);
        }
    }
    @Test public void freshStartupCreatesActualSchemasAndStableGeneration()throws Exception{
        ready();UUID first=settings.generation();assertNotNull(first);
        RestoreLegacyRecovery.validatePair(inc,farm);
        reset();ready();assertEquals(first,settings.generation());
    }
    @Test public void historicalPartialPairPreservesExistingRows()throws Exception{
        schema(inc,true);insertBatch(inc,"preserve");ready();assertEquals("preserve",batch(inc));
        assertTrue(Files.isRegularFile(farm));assertNotNull(settings.generation());
    }
    @Test public void establishedMissingDatabaseIsNotRecreated()throws Exception{
        schema(inc,true);insertBatch(inc,"preserve");settings.initializeGeneration();
        blocked();assertFalse(Files.exists(farm));assertEquals("preserve",batch(inc));
    }
    @Test public void orphanSidecarOrInitializationStageBlocksCreation()throws Exception{
        Path wal=root.resolve(FarmDb.NAME+"-wal");byte[] bytes={1,2,3};Files.write(wal,bytes);
        blocked();assertFalse(Files.exists(farm));assertArrayEquals(bytes,Files.readAllBytes(wal));
        Files.delete(wal);Path stage=root.resolve("incuba.db.startup-init");Files.write(stage,bytes);
        reset();blocked();assertFalse(Files.exists(inc));assertArrayEquals(bytes,Files.readAllBytes(stage));
    }
    @Test public void malformedJournalAndLegacyConflictPreserveEvidence()throws Exception{
        Path work=Files.createDirectory(root.resolve(RestoreFileStorage.WORK));
        Path journal=work.resolve("restore-v2.journal");byte[] bytes={1,2,3};Files.write(journal,bytes);
        blocked();assertArrayEquals(bytes,Files.readAllBytes(journal));assertFalse(Files.exists(inc));
        Files.delete(journal);Path marker=root.resolve(".nasedka_restore_pending");
        Files.write(marker,"rollback_if_interrupted".getBytes(StandardCharsets.US_ASCII));
        Path incoming=work.resolve(RestoreFileStorage.INCOMING_INC);Files.write(incoming,bytes);
        reset();blocked();assertTrue(Files.exists(marker));assertArrayEquals(bytes,Files.readAllBytes(incoming));
    }
    @Test public void v2PendingRecoveredBeforeOpeningActualPair()throws Exception{
        pair();insertBatch(inc,"old");UUID old=settings.initializeGeneration();
        Path work=Files.createDirectory(root.resolve(RestoreFileStorage.WORK));
        Path nextInc=work.resolve(RestoreFileStorage.INCOMING_INC);
        schema(nextInc,true);insertBatch(nextInc,"new");schema(work.resolve(RestoreFileStorage.INCOMING_FARM),false);
        Map<String,Object> target=new HashMap<>();target.put(RestoreSettingsPort.GENERATION,UUID.randomUUID().toString());
        RestoreFileStorage s=new RestoreFileStorage(root,settings,RestoreSettingsPort.encode(target));
        s.prepare();s.publish(RestoreProtocol.Phase.NONE,RestoreProtocol.Phase.PENDING);s.applyIncubation();s.applyFarm();s.applySettings();
        ready();assertEquals(old,settings.generation());assertEquals("old",batch(inc));
        assertEquals(RestoreProtocol.Phase.NONE,new RestoreFileStorage(root,settings,null).readPhase());
    }
    @Test public void corruptDatabaseIsPreservedAndPartnerNotCreated()throws Exception{
        byte[] bad=new byte[4096];Arrays.fill(bad,(byte)0x5a);Files.write(inc,bad);
        blocked();assertArrayEquals(bad,Files.readAllBytes(inc));assertFalse(Files.exists(farm));
        assertNull(settings.generation());
    }
    @Test public void legacyRemnantsWithoutMarkerRemainBlockedAndUntouched()throws Exception{
        pair();Path remnant=root.resolve("incuba.db.pre_restore.bak");
        Files.copy(inc,remnant);String hash=RestoreFileStorage.hash(remnant);
        blocked();assertEquals(hash,RestoreFileStorage.hash(remnant));assertNull(settings.generation());
    }
}
