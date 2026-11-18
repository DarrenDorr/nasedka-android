package org.nasedka.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Offline-only storage. All cursor resources are closed explicitly. */
public final class Db extends SQLiteOpenHelper {
    public static final class Entry {
        public long id;
        public long batchId;
        public long time;
        public Double temperature;
        public Double humidity;
        public String text;
    }

    public Db(Context c) { super(c, "incuba.db", null, 2); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE batches (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, species TEXT NOT NULL, incubator_model TEXT NOT NULL, profile_id TEXT NOT NULL DEFAULT 'nesushka', eggs INTEGER NOT NULL, duration INTEGER NOT NULL, started_at INTEGER NOT NULL, manual_turn INTEGER NOT NULL, spray INTEGER NOT NULL, target_temp REAL NOT NULL, active INTEGER NOT NULL DEFAULT 1, hatched INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY AUTOINCREMENT, batch_id INTEGER NOT NULL, time INTEGER NOT NULL, temperature REAL, humidity REAL, text TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX entries_by_batch ON entries(batch_id, time DESC)");
        db.execSQL("CREATE TABLE done (key TEXT PRIMARY KEY, batch_id INTEGER NOT NULL, label TEXT NOT NULL, completed_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX done_by_batch ON done(batch_id)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion >= 2) {
            db.execSQL("ALTER TABLE batches ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'nesushka'");
            return;
        }
        throw new IllegalStateException("Unsupported database version " + oldVersion);
    }

    private static ContentValues values(Batch b) {
        ContentValues v = new ContentValues();
        v.put("name", b.name); v.put("species", b.species); v.put("incubator_model", b.incubatorModel); v.put("profile_id", b.profileId); v.put("eggs", b.eggs);
        v.put("duration", b.duration); v.put("started_at", b.startedAt);
        v.put("manual_turn", b.manualTurn ? 1 : 0); v.put("spray", b.spray ? 1 : 0);
        v.put("target_temp", b.targetTemp); v.put("active", b.active ? 1 : 0);
        v.put("hatched", b.hatched);
        return v;
    }

    public long saveBatch(Batch b) {
        if (b.id == 0) { b.id = getWritableDatabase().insertOrThrow("batches", null, values(b)); }
        else getWritableDatabase().update("batches", values(b), "id=?", new String[]{String.valueOf(b.id)});
        return b.id;
    }

    /**
     * Atomic creation of a batch plus its user-reported preparation log.
     * Existing batches and the database schema are not modified by the preparation wizard.
     */
    public long savePreparedBatch(Batch b, String preparationNote) {
        if (b.id != 0) throw new IllegalArgumentException("Подготовка записывается только при создании.");
        if (preparationNote == null || preparationNote.trim().isEmpty())
            throw new IllegalArgumentException("Отсутствует запись подготовки.");
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            long id = database.insertOrThrow("batches", null, values(b));
            ContentValues log = new ContentValues();
            log.put("batch_id", id);
            log.put("time", System.currentTimeMillis());
            log.put("text", preparationNote.trim());
            database.insertOrThrow("entries", null, log);
            database.setTransactionSuccessful();
            b.id = id;
            return id;
        } finally {
            database.endTransaction();
        }
    }

    private static Batch fromCursor(Cursor c) {
        Batch b = new Batch();
        b.id = c.getLong(c.getColumnIndexOrThrow("id"));
        b.name = c.getString(c.getColumnIndexOrThrow("name"));
        b.species = c.getString(c.getColumnIndexOrThrow("species"));
        b.incubatorModel = c.getString(c.getColumnIndexOrThrow("incubator_model"));
        b.profileId = c.getString(c.getColumnIndexOrThrow("profile_id"));
        b.eggs = c.getInt(c.getColumnIndexOrThrow("eggs"));
        b.duration = c.getInt(c.getColumnIndexOrThrow("duration"));
        b.startedAt = c.getLong(c.getColumnIndexOrThrow("started_at"));
        b.manualTurn = c.getInt(c.getColumnIndexOrThrow("manual_turn")) == 1;
        b.spray = c.getInt(c.getColumnIndexOrThrow("spray")) == 1;
        b.targetTemp = c.getDouble(c.getColumnIndexOrThrow("target_temp"));
        b.active = c.getInt(c.getColumnIndexOrThrow("active")) == 1;
        b.hatched = c.getInt(c.getColumnIndexOrThrow("hatched"));
        return b;
    }

    public List<Batch> allBatches() {
        ArrayList<Batch> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM batches ORDER BY active DESC, started_at DESC", null)) {
            while (c.moveToNext()) list.add(fromCursor(c));
        }
        return list;
    }

    public Batch batch(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM batches WHERE id=?", new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? fromCursor(c) : null;
        }
    }

    /**
     * One transaction for the final result and its auditable journal record.
     * Preserves the existing v2 schema.
     */
    public void finishWithReport(Batch batch,String report) {
        if(batch==null||batch.id<1||batch.active||batch.hatched<0||
                batch.hatched>batch.eggs || report==null||report.trim().isEmpty())
            throw new IllegalArgumentException("Неверные данные завершения.");
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try{
            if(db.update("batches",values(batch),"id=? AND active=1",
                    new String[]{Long.toString(batch.id)})!=1)
                throw new IllegalArgumentException("Партия уже завершена или не найдена.");
            ContentValues log=new ContentValues();
            log.put("batch_id",batch.id);log.put("time",System.currentTimeMillis());
            log.put("text",report.trim());
            db.insertOrThrow("entries",null,log);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    public void record(long batchId, Double temperature, Double humidity, String text) {
        ContentValues v = new ContentValues();
        v.put("batch_id", batchId); v.put("time", System.currentTimeMillis());
        if (temperature != null) v.put("temperature", temperature);
        if (humidity != null) v.put("humidity", humidity);
        v.put("text", text == null ? "" : text.trim());
        getWritableDatabase().insertOrThrow("entries", null, v);
    }

    public List<Entry> entries(long batchId, int limit) {
        ArrayList<Entry> list = new ArrayList<>();
        String where = batchId > 0 ? " WHERE batch_id=" + batchId : "";
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM entries" + where + " ORDER BY time DESC LIMIT " + Math.max(1, Math.min(100000, limit)), null)) {
            while (c.moveToNext()) {
                Entry e = new Entry();
                e.id = c.getLong(c.getColumnIndexOrThrow("id"));
                e.batchId = c.getLong(c.getColumnIndexOrThrow("batch_id"));
                e.time = c.getLong(c.getColumnIndexOrThrow("time"));
                int t = c.getColumnIndexOrThrow("temperature"), h = c.getColumnIndexOrThrow("humidity");
                e.temperature = c.isNull(t) ? null : c.getDouble(t);
                e.humidity = c.isNull(h) ? null : c.getDouble(h);
                e.text = c.getString(c.getColumnIndexOrThrow("text"));
                list.add(e);
            }
        }
        return list;
    }

    public void complete(Reminder r) { complete(r.key, r.batchId, r.title); }
    public void complete(String key, long batchId, String label) {
        ContentValues v = new ContentValues();
        v.put("key", key); v.put("batch_id", batchId); v.put("label", label);
        v.put("completed_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("done", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public Set<String> doneKeys(long batchId) {
        Set<String> values = new HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT key FROM done WHERE batch_id=?", new String[]{String.valueOf(batchId)})) {
            while (c.moveToNext()) values.add(c.getString(0));
        }
        return values;
    }

    public void deleteBatch(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String[] args = {String.valueOf(id)};
            db.delete("done", "batch_id=?", args);
            db.delete("entries", "batch_id=?", args);
            db.delete("batches", "id=?", args);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public List<String[]> finishedActions(long batchId) {
        ArrayList<String[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT key,label,completed_at FROM done WHERE batch_id=? ORDER BY completed_at ASC", new String[]{String.valueOf(batchId)})) {
            while (c.moveToNext()) out.add(new String[]{c.getString(0), c.getString(1), Long.toString(c.getLong(2))});
        }
        return out;
    }
}
