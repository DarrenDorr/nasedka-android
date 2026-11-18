package org.nasedka.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * User-controlled full, local-only JSON backup. Import is built in two staging
 * databases before switching files. A persistent marker and old DB copies enable
 * rollback on process death during the two-file swap.
 *
 * This is NOT a cloud backup. No third-party libraries, services or secrets.
 */
public final class BackupStore {
    private BackupStore() {}
    public static final String FORMAT="nasedka-offline-full";
    public static final int VERSION=1;
    public static final String[] INCUBATION={
            "batches","entries","done"};
    public static final String[] FARM={
            "flock","flock_moves","feeds","feed_moves","finances","nests",
            "nest_notes","egg_harvest","farm_tasks","incubator_transfers","flock_notes"};
    private static final int MAX_FILE=24*1024*1024;
    private static final String STAGE_INC="incuba_restore_stage.db";
    private static final String STAGE_FARM="farm_restore_stage.db";
    private static final String MARKER=".nasedka_restore_pending";

    private static File file(Context c,String name){return c.getDatabasePath(name);}
    private static void delete(File f) {if(f.exists()&&!f.delete())throw new IllegalStateException(
            "Не удалось удалить временный файл "+f.getName());}
    private static void rename(File from,File to) {
        if(!from.renameTo(to))throw new IllegalStateException("Не удалось переместить "+from.getName());
    }
    /**
     * After a successful WAL checkpoint and closed database handles, no nonempty
     * sidecar may be left beside a file that will be replaced. Otherwise a stale
     * WAL could be replayed against a restored database.
     */
    private static void cleanSidecars(File base) {
        File wal=new File(base.getPath()+"-wal");
        File journal=new File(base.getPath()+"-journal");
        if((wal.exists()&&wal.length()>0)||(journal.exists()&&journal.length()>0))
            throw new IllegalStateException("База ещё содержит журнал записи. Повторите операцию позже.");
        delete(wal);delete(new File(base.getPath()+"-shm"));delete(journal);
    }
    private static void checkpoint(SQLiteDatabase db) {
        try(Cursor c=db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null)){
            if(c.moveToFirst()&&c.getInt(0)!=0)
                throw new IllegalStateException("Данные базы сейчас используются. Повторите позже.");
        }
    }
    private static JSONArray data(SQLiteDatabase db,String table) throws Exception {
        JSONArray rows=new JSONArray();
        try(Cursor c=db.rawQuery("SELECT * FROM "+table,null)){
            String[] columns=c.getColumnNames();
            while(c.moveToNext()){
                JSONObject row=new JSONObject();
                for(int i=0;i<columns.length;i++){
                    switch(c.getType(i)){
                        case Cursor.FIELD_TYPE_NULL:row.put(columns[i],JSONObject.NULL);break;
                        case Cursor.FIELD_TYPE_INTEGER:row.put(columns[i],c.getLong(i));break;
                        case Cursor.FIELD_TYPE_FLOAT:row.put(columns[i],c.getDouble(i));break;
                        case Cursor.FIELD_TYPE_STRING:row.put(columns[i],c.getString(i));break;
                        default:throw new IllegalStateException("Неподдерживаемый тип записи.");
                    }
                }
                rows.put(row);
            }
        }
        return rows;
    }
    private static JSONObject all(SQLiteDatabase db,String[] tables) throws Exception {
        JSONObject out=new JSONObject();
        for(String table:tables)out.put(table,data(db,table));
        return out;
    }
    private static JSONObject settings(Context c) throws Exception {
        return new JSONObject().put("checks",Prefs.checks(c))
                .put("turns",Prefs.turns(c)).put("sprays",Prefs.sprays(c))
                .put("nest_checks",Prefs.nestChecks(c))
                .put("notifications",Prefs.notifications(c));
    }
    private static byte[] snapshot(Context c) throws Exception {
        JSONObject root=new JSONObject();
        root.put("format",FORMAT);root.put("version",VERSION);
        root.put("created",System.currentTimeMillis());
        try(Db inc=new Db(c);FarmDb farm=new FarmDb(c)){
            SQLiteDatabase i=inc.getReadableDatabase(),f=farm.getReadableDatabase();
            checkpoint(i);checkpoint(f);
            root.put("incuba_version",i.getVersion());root.put("farm_version",f.getVersion());
            root.put("incubation",all(i,INCUBATION));
            root.put("farm",all(f,FARM));
        }
        root.put("settings",settings(c));
        byte[] out=root.toString().getBytes(StandardCharsets.UTF_8);
        if(out.length>MAX_FILE)throw new IllegalStateException(
                "Резервная копия слишком большая для этого формата (лимит 24 МБ).");
        return out;
    }
    public static void export(Context c,Uri target) throws Exception {
        byte[] bytes=snapshot(c);
        try(OutputStream out=c.getContentResolver().openOutputStream(target,"wt")){
            if(out==null)throw new IllegalStateException("Не удалось открыть файл для записи.");
            out.write(bytes);out.flush();
        }
    }
    private static byte[] read(Context c,Uri source) throws Exception {
        try(InputStream in=c.getContentResolver().openInputStream(source)){
            if(in==null)throw new IllegalStateException("Не удалось открыть резервную копию.");
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] chunk=new byte[8192];int n;
            while((n=in.read(chunk))!=-1){
                if(out.size()+n>MAX_FILE)throw new IllegalArgumentException(
                        "Размер резервной копии превышает 24 МБ.");
                out.write(chunk,0,n);
            }
            return out.toByteArray();
        }
    }
    private static String string(JSONObject o,String key,int max) throws Exception {
        String s=o.getString(key);
        if(s==null||s.length()>max)throw new IllegalArgumentException("Некорректное поле "+key);
        return s;
    }
    private static void validate(JSONObject root) throws Exception {
        if(!FORMAT.equals(root.optString("format"))||root.optInt("version")!=VERSION
                ||root.optInt("incuba_version")!=2
                ||(root.optInt("farm_version")!=1&&root.optInt("farm_version")!=2))
            throw new IllegalArgumentException("Неподдерживаемая версия резервной копии.");
        JSONObject inc=root.getJSONObject("incubation"),farm=root.getJSONObject("farm");
        for(String t:INCUBATION)if(inc.getJSONArray(t).length()>100000)
            throw new IllegalArgumentException("Слишком много записей в "+t);
        for(String t:FARM)if((farm.optJSONArray(t)==null?0:farm.getJSONArray(t).length())>100000)
            throw new IllegalArgumentException("Слишком много записей в "+t);
        JSONObject prefs=root.getJSONObject("settings");
        Prefs.times(string(prefs,"checks",100));Prefs.times(string(prefs,"turns",100));
        Prefs.times(string(prefs,"sprays",100));
        Prefs.times(prefs.optString("nest_checks","09:00"));
        if(!prefs.has("notifications") || !(prefs.get("notifications") instanceof Boolean))
            throw new IllegalArgumentException("Неверные настройки уведомлений.");
    }
    private static Map<String,String> columns(SQLiteDatabase db,String table) {
        Map<String,String> out=new HashMap<>();
        try(Cursor c=db.rawQuery("PRAGMA table_info("+table+")",null)){
            while(c.moveToNext())
                out.put(c.getString(1),c.getString(2).toUpperCase(java.util.Locale.ROOT));
        }
        return out;
    }
    private static void importTable(SQLiteDatabase db,String table,JSONArray rows) throws Exception {
        Map<String,String> types=columns(db,table);
        if(types.isEmpty())throw new IllegalArgumentException("Неизвестная таблица "+table);
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);
            ContentValues values=new ContentValues();
            Iterator<String> keys=row.keys();
            while(keys.hasNext()){
                String k=keys.next(),type=types.get(k);
                if(type==null)throw new IllegalArgumentException("Лишнее поле "+table+"."+k);
                Object val=row.get(k);
                if(val==JSONObject.NULL)values.putNull(k);
                else if(type.contains("INT")){
                    if(!(val instanceof Number)||val.toString().contains("."))
                        throw new IllegalArgumentException("Неправильное целое число "+k);
                    values.put(k,((Number)val).longValue());
                }else if(type.contains("REAL")||type.contains("FLOA")||type.contains("DOUB")){
                    if(!(val instanceof Number)||!Double.isFinite(((Number)val).doubleValue()))
                        throw new IllegalArgumentException("Неправильное число "+k);
                    values.put(k,((Number)val).doubleValue());
                }else {
                    if(!(val instanceof String)||((String)val).length()>2000)
                        throw new IllegalArgumentException("Неправильный текст "+k);
                    values.put(k,(String)val);
                }
            }
            db.insertOrThrow(table,null,values);
        }
    }
    private static void populate(SQLiteDatabase db,JSONObject data,String[] tables,int version)
            throws Exception {
        db.beginTransaction();
        try {
            for(String t:tables) {
                JSONArray rows=data.optJSONArray(t);
                if(rows==null) {
                    if("flock_notes".equals(t))rows=new JSONArray();
                    else throw new IllegalArgumentException("Нет обязательной таблицы "+t);
                }
                importTable(db,t,rows);
            }
            db.setVersion(version);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        try(Cursor c=db.rawQuery("PRAGMA integrity_check",null)){
            if(!c.moveToFirst()||!"ok".equalsIgnoreCase(c.getString(0)))
                throw new IllegalStateException("Нарушена целостность базы резервной копии.");
        }
    }
    private static long count(SQLiteDatabase db,String query) {
        try(Cursor c=db.rawQuery(query,null)){c.moveToFirst();return c.getLong(0);}
    }
    private static void relations(SQLiteDatabase inc,SQLiteDatabase farm) {
        String[] checks={
            "SELECT COUNT(*) FROM entries WHERE batch_id NOT IN (SELECT id FROM batches)",
            "SELECT COUNT(*) FROM done WHERE batch_id NOT IN (SELECT id FROM batches)"};
        for(String query:checks)if(count(inc,query)>0)
            throw new IllegalArgumentException("В резервной копии повреждены связи инкубации.");
        String[] other={
            "SELECT COUNT(*) FROM flock_moves WHERE flock_id NOT IN (SELECT id FROM flock)",
            "SELECT COUNT(*) FROM flock_notes WHERE flock_id NOT IN (SELECT id FROM flock)",
            "SELECT COUNT(*) FROM feed_moves WHERE feed_id NOT IN (SELECT id FROM feeds)",
            "SELECT COUNT(*) FROM nest_notes WHERE nest_id NOT IN (SELECT id FROM nests)",
            "SELECT COUNT(*) FROM egg_harvest WHERE flock_id NOT IN (SELECT id FROM flock)",
            "SELECT COUNT(*) FROM finances WHERE feed_move_id IS NOT NULL AND feed_move_id NOT IN (SELECT id FROM feed_moves)",
            "SELECT COUNT(*) FROM flock_moves WHERE delta=0",
            "SELECT COUNT(*) FROM feeds WHERE name=''",
            "SELECT COUNT(*) FROM nests WHERE eggs<=0 OR hatched<0 OR hatched>eggs",
            "SELECT COUNT(*) FROM finances WHERE cents<=0",
            "SELECT COUNT(*) FROM flock WHERE id IN (SELECT flock_id FROM flock_moves GROUP BY flock_id HAVING SUM(delta)<0)",
            "SELECT COUNT(*) FROM feeds WHERE id IN (SELECT feed_id FROM feed_moves GROUP BY feed_id HAVING SUM(quantity)<-0.000001)"};
        for(String query:other)if(count(farm,query)>0)
            throw new IllegalArgumentException("В резервной копии повреждены связи учёта хозяйства.");
        try(Cursor c=farm.rawQuery("SELECT batch_id FROM incubator_transfers",null)){
            while(c.moveToNext()){
                try(Cursor b=inc.rawQuery("SELECT 1 FROM batches WHERE id=?",
                        new String[]{Long.toString(c.getLong(0))})){
                    if(!b.moveToFirst())
                        throw new IllegalArgumentException("Неверная связь вывода с поголовьем.");
                }
            }
        }
    }
    private static void prepareStage(Context c,String dbName,JSONObject data,String[] tables,
                                     boolean incubation,int version) throws Exception {
        File staged=file(c,dbName);delete(staged);
        try(SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(staged,null)) {
            if(incubation)new Db(c).onCreate(db);else new FarmDb(c).onCreate(db);
            populate(db,data,tables,version);
        }
    }
    /**
     * Called from Application.onCreate before any database helpers. On interrupted
     * restore rolls back from private copies. Does not silently create new databases.
     */
    public static synchronized void recover(Context c) {
        File marker=file(c,MARKER);
        if(!marker.exists())return;
        for(String name:new String[]{"incuba.db",FarmDb.NAME}) {
            File current=file(c,name),old=file(c,name+".pre_restore.bak");
            if(old.exists()){
                // The replacement database could have left sidecars, which must
                // never be reattached to the original recovered file.
                delete(new File(current.getPath()+"-wal"));
                delete(new File(current.getPath()+"-shm"));
                delete(new File(current.getPath()+"-journal"));
                delete(current);
                rename(old,current);
            }
        }
        delete(file(c,STAGE_INC));delete(file(c,STAGE_FARM));
        delete(marker);
    }
    private static void switchBoth(Context c) throws Exception {
        File inc=file(c,"incuba.db"),farm=file(c,FarmDb.NAME);
        File oldInc=file(c,"incuba.db.pre_restore.bak");
        File oldFarm=file(c,FarmDb.NAME+".pre_restore.bak");
        File pending=file(c,MARKER);
        if(pending.exists())throw new IllegalStateException("Незавершённое восстановление; перезапустите приложение.");
        delete(oldInc);delete(oldFarm);
        // The exporter ran checkpoint(TRUNCATE) and closed both helpers.
        // Refuse replacement if a concurrent receiver has written to either DB.
        cleanSidecars(inc);cleanSidecars(farm);
        try(FileOutputStream marker=new FileOutputStream(pending)) {
            marker.write("rollback_if_interrupted".getBytes(StandardCharsets.UTF_8));
            marker.getFD().sync();
        }
        try{
            if(!inc.exists()||!farm.exists())
                throw new IllegalStateException("Исходные базы не найдены.");
            rename(inc,oldInc);rename(farm,oldFarm);
            rename(file(c,STAGE_INC),inc);rename(file(c,STAGE_FARM),farm);
            // New databases are in place. Remove pending marker before deleting rollback copies.
            delete(pending);
            delete(oldInc);delete(oldFarm);
        }catch(Exception e){
            recover(c);
            throw e;
        }
    }
    public static synchronized void restore(Context c,Uri selected) throws Exception {
        if(file(c,MARKER).exists())
            throw new IllegalStateException("Незавершённое восстановление; перезапустите приложение.");
        byte[] bytes=read(c,selected);
        JSONObject root=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        validate(root);
        // Persist a private rollback snapshot before changing any database.
        byte[] previous=snapshot(c);
        File rollback=new File(c.getFilesDir(),"nasedka_before_last_restore.json");
        try(FileOutputStream out=new FileOutputStream(rollback)){
            out.write(previous);out.getFD().sync();
        }
        File stagedInc=file(c,STAGE_INC),stagedFarm=file(c,STAGE_FARM);
        try{
            prepareStage(c,STAGE_INC,root.getJSONObject("incubation"),INCUBATION,true,2);
            prepareStage(c,STAGE_FARM,root.getJSONObject("farm"),FARM,false,2);
            try(SQLiteDatabase i=SQLiteDatabase.openDatabase(stagedInc.getPath(),null,
                    SQLiteDatabase.OPEN_READONLY);
                SQLiteDatabase f=SQLiteDatabase.openDatabase(stagedFarm.getPath(),null,
                    SQLiteDatabase.OPEN_READONLY)){
                relations(i,f);
            }
            long enough=stagedInc.length()+stagedFarm.length()+1024*1024;
            if(stagedInc.getUsableSpace()<enough)
                throw new IllegalStateException("Недостаточно места для безопасного восстановления.");
            switchBoth(c);
            JSONObject p=root.getJSONObject("settings");
            Prefs.get(c).edit()
                    .putString("checks",p.getString("checks"))
                    .putString("turns",p.getString("turns"))
                    .putString("sprays",p.getString("sprays"))
                    .putString("nest_checks",p.optString("nest_checks","09:00"))
                    .putBoolean("notifications",p.getBoolean("notifications"))
                    .remove("next_alarm_at").remove("last_delivered_alarm_at")
                    .remove("farm_task_next").remove("farm_task_delivered_at")
                    .commit();
        }finally{
            // Never delete an interrupted restore marker; Application startup
            // will recover original copies before re-opening the databases.
            if(!file(c,MARKER).exists()){
                delete(stagedInc);delete(stagedFarm);
            }
        }
    }
}
