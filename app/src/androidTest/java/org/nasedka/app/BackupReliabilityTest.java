package org.nasedka.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real Android SQLite/JSON, isolated in-memory databases; never opens user files. */
@RunWith(AndroidJUnit4.class)
public class BackupReliabilityTest {
    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }
    private static JSONObject empty(String[] tables) throws JSONException {
        JSONObject data=new JSONObject();
        for(String t:tables)data.put(t,new JSONArray());
        return data;
    }
    private static JSONObject envelope(int farmVersion, JSONObject inc, JSONObject farm)
            throws JSONException {
        return new JSONObject().put("format",BackupStore.FORMAT).put("version",BackupStore.VERSION)
                .put("incuba_version",2).put("farm_version",farmVersion)
                .put("incubation",inc).put("farm",farm)
                .put("settings",new JSONObject().put("checks","08:00,20:00")
                        .put("turns","12:00").put("sprays","09:00")
                        .put("nest_checks","09:00").put("notifications",true));
    }
    private static JSONObject emptyEnvelope(int farmVersion) throws JSONException {
        return envelope(farmVersion,empty(BackupStore.INCUBATION),empty(BackupStore.FARM));
    }
    private interface Checked { void run() throws Exception; }
    private static void rejected(Checked action) throws Exception {
        try { action.run(); fail("Malformed data must be rejected"); }
        catch (JSONException | IllegalArgumentException | android.database.sqlite.SQLiteException expected) {
            // Validation or SQLite constraint rejection.
        }
    }
    private void createCurrent(SQLiteDatabase inc, SQLiteDatabase farm) {
        try(Db i=new Db(context()); FarmDb f=new FarmDb(context())) {
            i.onCreate(inc); f.onCreate(farm);
        }
    }
    private static void seedInc(SQLiteDatabase db) {
        db.execSQL("INSERT INTO batches(id,name,species,incubator_model,eggs,duration,started_at,manual_turn,spray,target_temp,active,hatched) VALUES(7,'Тест','chicken','test',10,21,1000,1,0,37.5,0,8)");
        db.execSQL("INSERT INTO entries(id,batch_id,time,temperature,humidity,text) VALUES(9,7,2000,37.5,NULL,'Измерение')");
        db.execSQL("INSERT INTO done(key,batch_id,label,completed_at) VALUES('test-key',7,'Проверка',2000)");
    }
    private static void seedFarmV1(SQLiteDatabase db) {
        db.execSQL("INSERT INTO flock(id,name,species,breed,place,since) VALUES(3,'Группа','chicken','test','test',1000)");
        db.execSQL("INSERT INTO flock_moves(id,flock_id,at,kind,delta) VALUES(4,3,2000,'Вывод',8)");
        db.execSQL("INSERT INTO feeds(id,name,unit) VALUES(5,'Корм','kg')");
        db.execSQL("INSERT INTO feed_moves(id,feed_id,at,kind,quantity) VALUES(6,5,2000,'Покупка',20)");
        db.execSQL("INSERT INTO finances(id,at,type,category,cents,title,feed_move_id) VALUES(7,2000,'expense','feed',80000,'Покупка',6)");
        db.execSQL("INSERT INTO nests(id,name,hen,species,eggs,started_at,days) VALUES(8,'Гнездо','Курица','chicken',6,1000,21)");
        db.execSQL("INSERT INTO nest_notes(id,nest_id,at,kind,text) VALUES(9,8,2000,'check','Осмотр')");
        db.execSQL("INSERT INTO egg_harvest(id,flock_id,at,quantity) VALUES(10,3,2000,4)");
        db.execSQL("INSERT INTO farm_tasks(id,title,due_at) VALUES(11,'Задача',3000)");
        db.execSQL("INSERT INTO incubator_transfers(batch_id,flock_id,quantity,at) VALUES(7,3,8,2000)");
    }
    private static long scalar(SQLiteDatabase db,String query) {
        try(Cursor c=db.rawQuery(query,null)) { assertTrue(c.moveToFirst()); return c.getLong(0); }
    }
    private static String value(SQLiteDatabase db,String query) {
        try(Cursor c=db.rawQuery(query,null)) { assertTrue(c.moveToFirst()); return c.getString(0); }
    }

    @Test public void currentRoundTripAllFourteenTables() throws Exception {
        try(SQLiteDatabase inc=SQLiteDatabase.create(null); SQLiteDatabase farm=SQLiteDatabase.create(null);
            SQLiteDatabase outInc=SQLiteDatabase.create(null); SQLiteDatabase outFarm=SQLiteDatabase.create(null)) {
            createCurrent(inc,farm); createCurrent(outInc,outFarm);
            seedInc(inc); seedFarmV1(farm);
            farm.execSQL("INSERT INTO flock_notes(id,flock_id,at,kind,text) VALUES(12,3,2000,'care','Уход')");
            JSONObject root=envelope(2,BackupStore.all(inc,BackupStore.INCUBATION),BackupStore.all(farm,BackupStore.FARM));
            root=new JSONObject(root.toString());
            BackupStore.validate(root);
            BackupStore.populate(outInc,root.getJSONObject("incubation"),BackupStore.INCUBATION,2);
            BackupStore.populate(outFarm,root.getJSONObject("farm"),BackupStore.FARM,2);
            BackupStore.relations(outInc,outFarm);
            for(String table:BackupStore.INCUBATION)
                assertEquals(table,root.getJSONObject("incubation").getJSONArray(table).toString(),
                        BackupStore.all(outInc,new String[]{table}).getJSONArray(table).toString());
            for(String table:BackupStore.FARM)
                assertEquals(table,root.getJSONObject("farm").getJSONArray(table).toString(),
                        BackupStore.all(outFarm,new String[]{table}).getJSONArray(table).toString());
            assertEquals(2,outInc.getVersion()); assertEquals(2,outFarm.getVersion());
        }
    }
    @Test public void v2MissingAnyTableIsRejected() throws Exception {
        for(String table:BackupStore.FARM) {
            JSONObject root=emptyEnvelope(2); root.getJSONObject("farm").remove(table);
            rejected(()->BackupStore.validate(root));
        }
        for(String table:BackupStore.INCUBATION) {
            JSONObject root=emptyEnvelope(2); root.getJSONObject("incubation").remove(table);
            rejected(()->BackupStore.validate(root));
        }
    }
    @Test public void v1OnlyAbsentNotesAllowed() throws Exception {
        JSONObject root=emptyEnvelope(1); root.getJSONObject("farm").remove("flock_notes");
        BackupStore.validate(root);
        assertEquals(0,root.getJSONObject("farm").getJSONArray("flock_notes").length());
        for(Object invalid:new Object[]{JSONObject.NULL,"bad",new JSONObject()}) {
            JSONObject bad=emptyEnvelope(1); bad.getJSONObject("farm").put("flock_notes",invalid);
            rejected(()->BackupStore.validate(bad));
        }
        JSONObject missing=emptyEnvelope(1); missing.getJSONObject("farm").remove("flock");
        rejected(()->BackupStore.validate(missing));
    }
    @Test public void brokenTransferReferencesAndQuantityRejected() throws Exception {
        try(SQLiteDatabase inc=SQLiteDatabase.create(null); SQLiteDatabase farm=SQLiteDatabase.create(null)) {
            createCurrent(inc,farm); seedInc(inc); seedFarmV1(farm);
            BackupStore.relations(inc,farm);
            farm.execSQL("UPDATE incubator_transfers SET flock_id=999");
            rejected(()->BackupStore.relations(inc,farm));
            farm.execSQL("UPDATE incubator_transfers SET flock_id=3,batch_id=999");
            rejected(()->BackupStore.relations(inc,farm));
            farm.execSQL("UPDATE incubator_transfers SET batch_id=7,quantity=0");
            rejected(()->BackupStore.relations(inc,farm));
        }
    }
    // Exact schema from FarmDb.java at 535417a2be877491d40b7b3cfaa6915c8eaf0374.
    private static void createFarmV1(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE flock (id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,species TEXT NOT NULL,breed TEXT NOT NULL,place TEXT NOT NULL,since INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE flock_moves (id INTEGER PRIMARY KEY AUTOINCREMENT,flock_id INTEGER NOT NULL,at INTEGER NOT NULL,kind TEXT NOT NULL,delta INTEGER NOT NULL,note TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX fm_flock ON flock_moves(flock_id,at DESC)");
        db.execSQL("CREATE TABLE feeds (id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,unit TEXT NOT NULL)");
        db.execSQL("CREATE TABLE feed_moves (id INTEGER PRIMARY KEY AUTOINCREMENT,feed_id INTEGER NOT NULL,at INTEGER NOT NULL,kind TEXT NOT NULL,quantity REAL NOT NULL,note TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX fd_feed ON feed_moves(feed_id,at DESC)");
        db.execSQL("CREATE TABLE finances (id INTEGER PRIMARY KEY AUTOINCREMENT,at INTEGER NOT NULL,type TEXT NOT NULL,category TEXT NOT NULL,cents INTEGER NOT NULL,title TEXT NOT NULL,note TEXT NOT NULL DEFAULT '',feed_move_id INTEGER UNIQUE)");
        db.execSQL("CREATE INDEX fin_at ON finances(at DESC)");
        db.execSQL("CREATE TABLE nests (id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,hen TEXT NOT NULL,species TEXT NOT NULL,eggs INTEGER NOT NULL,started_at INTEGER NOT NULL,days INTEGER NOT NULL,active INTEGER NOT NULL DEFAULT 1,hatched INTEGER NOT NULL DEFAULT 0,note TEXT NOT NULL DEFAULT '',transferred INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE nest_notes (id INTEGER PRIMARY KEY AUTOINCREMENT,nest_id INTEGER NOT NULL,at INTEGER NOT NULL,kind TEXT NOT NULL,text TEXT NOT NULL)");
        db.execSQL("CREATE INDEX nn_nest ON nest_notes(nest_id,at DESC)");
        db.execSQL("CREATE TABLE egg_harvest (id INTEGER PRIMARY KEY AUTOINCREMENT,flock_id INTEGER NOT NULL,at INTEGER NOT NULL,quantity INTEGER NOT NULL,note TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX egg_at ON egg_harvest(at DESC)");
        db.execSQL("CREATE TABLE farm_tasks (id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,notes TEXT NOT NULL DEFAULT '',due_at INTEGER NOT NULL,done_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX tasks_due ON farm_tasks(done_at,due_at)");
        db.execSQL("CREATE TABLE incubator_transfers (batch_id INTEGER PRIMARY KEY,flock_id INTEGER NOT NULL,quantity INTEGER NOT NULL,at INTEGER NOT NULL)");
        db.setVersion(1);
    }
    // Reconstructed by reversing the documented ALTER TABLE in Db.onUpgrade.
    // The earliest repository revision already has v2; not an archival v1 APK.
    private static void createIncV1(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE batches (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, species TEXT NOT NULL, incubator_model TEXT NOT NULL, eggs INTEGER NOT NULL, duration INTEGER NOT NULL, started_at INTEGER NOT NULL, manual_turn INTEGER NOT NULL, spray INTEGER NOT NULL, target_temp REAL NOT NULL, active INTEGER NOT NULL DEFAULT 1, hatched INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY AUTOINCREMENT, batch_id INTEGER NOT NULL, time INTEGER NOT NULL, temperature REAL, humidity REAL, text TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX entries_by_batch ON entries(batch_id, time DESC)");
        db.execSQL("CREATE TABLE done (key TEXT PRIMARY KEY, batch_id INTEGER NOT NULL, label TEXT NOT NULL, completed_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX done_by_batch ON done(batch_id)");
        db.setVersion(1);
    }
    @Test public void farmV1MigrationPreservesRowsAndIds() throws Exception {
        try(SQLiteDatabase db=SQLiteDatabase.create(null); FarmDb helper=new FarmDb(context())) {
            createFarmV1(db); seedFarmV1(db);
            JSONObject before=BackupStore.all(db,new String[]{"flock_moves","feed_moves","finances","incubator_transfers","egg_harvest","farm_tasks","nests","nest_notes","feeds"});
            db.beginTransaction();
            try { helper.onUpgrade(db,1,2); db.setVersion(2); db.setTransactionSuccessful(); }
            finally { db.endTransaction(); }
            for(java.util.Iterator<String> it=before.keys();it.hasNext();) {
                String t=it.next();
                assertEquals(t,before.getJSONArray(t).toString(),BackupStore.all(db,new String[]{t}).getJSONArray(t).toString());
            }
            assertEquals("Группа",value(db,"SELECT name FROM flock WHERE id=3"));
            assertEquals(0,scalar(db,"SELECT hatched_at FROM flock WHERE id=3"));
            assertEquals(0,scalar(db,"SELECT COUNT(*) FROM flock_notes"));
            assertEquals(1,scalar(db,"SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='fn_group'"));
            assertEquals("ok",value(db,"PRAGMA integrity_check"));
        }
    }
    @Test public void incV1ReconstructedMigrationPreservesRows() throws Exception {
        try(SQLiteDatabase db=SQLiteDatabase.create(null); Db helper=new Db(context())) {
            createIncV1(db); seedInc(db);
            JSONObject before=BackupStore.all(db,new String[]{"entries","done"});
            db.beginTransaction();
            try { helper.onUpgrade(db,1,2); db.setVersion(2); db.setTransactionSuccessful(); }
            finally { db.endTransaction(); }
            for(String t:new String[]{"entries","done"})
                assertEquals(t,before.getJSONArray(t).toString(),BackupStore.all(db,new String[]{t}).getJSONArray(t).toString());
            assertEquals("nesushka",value(db,"SELECT profile_id FROM batches WHERE id=7"));
            assertEquals(8,scalar(db,"SELECT hatched FROM batches WHERE id=7"));
            assertEquals("ok",value(db,"PRAGMA integrity_check"));
        }
    }
    @Test public void farmV1BackupMigratesWithoutNotes() throws Exception {
        try(SQLiteDatabase inc=SQLiteDatabase.create(null); SQLiteDatabase farm=SQLiteDatabase.create(null);
            SQLiteDatabase dstInc=SQLiteDatabase.create(null); SQLiteDatabase dstFarm=SQLiteDatabase.create(null)) {
            createCurrent(inc,dstFarm); // use a separate farm v1 source
            seedInc(inc); createFarmV1(farm); seedFarmV1(farm);
            try(Db helper=new Db(context())) { helper.onCreate(dstInc); }
            String[] v1Tables=java.util.Arrays.stream(BackupStore.FARM).filter(t->!t.equals("flock_notes")).toArray(String[]::new);
            JSONObject root=envelope(1,BackupStore.all(inc,BackupStore.INCUBATION),BackupStore.all(farm,v1Tables));
            BackupStore.validate(root);
            BackupStore.populate(dstInc,root.getJSONObject("incubation"),BackupStore.INCUBATION,2);
            BackupStore.populate(dstFarm,root.getJSONObject("farm"),BackupStore.FARM,2);
            BackupStore.relations(dstInc,dstFarm);
            assertEquals(0,scalar(dstFarm,"SELECT hatched_at FROM flock WHERE id=3"));
            assertEquals(0,scalar(dstFarm,"SELECT COUNT(*) FROM flock_notes"));
            assertEquals(8,scalar(dstFarm,"SELECT quantity FROM incubator_transfers WHERE batch_id=7"));
        }
    }
}
