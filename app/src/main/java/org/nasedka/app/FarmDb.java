package org.nasedka.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

/**
 * Local farm ledger, isolated from incuba.db v2 to prevent migration damage to
 * installed incubation data. Physical movements and money are separate.
 * All quantity-changing writes use transactions and reject negative stock.
 */
public final class FarmDb extends SQLiteOpenHelper {
    public static final String NAME="nasedka_farm.db";
    public static final class Flock {
        public long id, since;
        public String name,species,breed,location;
        public long hatchedAt;
        public int count;
    }
    public static final class Feed {
        public long id;
        public String name,unit;
        public double stock;
    }
    public static final class Money {
        public long id, at, cents;
        public String type,category,title,note;
    }
    public static final class Nest {
        public long id, startedAt;
        public String name,hen,species,note;
        public int eggs,days,hatched;
        public boolean active,transferred;
    }
    public static final class Note {
        public long at;
        public String kind,text;
    }
    public static final class Task {
        public long id,dueAt,doneAt;
        public String title,notes;
    }
    public static final class Movement {
        public long at;
        public double delta;
        public String kind,note;
    }
    public FarmDb(Context context) { super(context,NAME,null,2); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE flock (id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,species TEXT NOT NULL,breed TEXT NOT NULL,place TEXT NOT NULL,since INTEGER NOT NULL,hatched_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE flock_moves (id INTEGER PRIMARY KEY AUTOINCREMENT,flock_id INTEGER NOT NULL,at INTEGER NOT NULL,kind TEXT NOT NULL,delta INTEGER NOT NULL,note TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX fm_flock ON flock_moves(flock_id,at DESC)");
        db.execSQL("CREATE TABLE flock_notes (id INTEGER PRIMARY KEY AUTOINCREMENT,flock_id INTEGER NOT NULL,at INTEGER NOT NULL,kind TEXT NOT NULL,text TEXT NOT NULL)");
        db.execSQL("CREATE INDEX fn_group ON flock_notes(flock_id,at DESC)");
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
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        if(oldVersion==1&&newVersion>=2){
            db.execSQL("ALTER TABLE flock ADD COLUMN hatched_at INTEGER NOT NULL DEFAULT 0");
            db.execSQL("CREATE TABLE flock_notes (id INTEGER PRIMARY KEY AUTOINCREMENT,flock_id INTEGER NOT NULL,at INTEGER NOT NULL,kind TEXT NOT NULL,text TEXT NOT NULL)");
            db.execSQL("CREATE INDEX fn_group ON flock_notes(flock_id,at DESC)");
            return;
        }
        throw new IllegalStateException("Unsupported farm version "+oldVersion+"→"+newVersion);
    }

    public List<Flock> flocks() {
        List<Flock> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT f.id,f.name,f.species,f.breed,f.place,f.since,f.hatched_at,COALESCE((SELECT SUM(m.delta) FROM flock_moves m WHERE m.flock_id=f.id),0) AS balance FROM flock f ORDER BY f.name COLLATE NOCASE",null)) {
            while(c.moveToNext()) {
                Flock f=new Flock();f.id=c.getLong(0);f.name=c.getString(1);
                f.species=c.getString(2);f.breed=c.getString(3);f.location=c.getString(4);
                f.since=c.getLong(5);f.hatchedAt=c.getLong(6);
                f.count=c.getInt(7);out.add(f);
            }
        }
        return out;
    }
    private int heads(SQLiteDatabase db,long id) {
        try(Cursor c=db.rawQuery("SELECT COALESCE(SUM(delta),0) FROM flock_moves WHERE flock_id=?",
                new String[]{Long.toString(id)})){c.moveToFirst();return c.getInt(0);}
    }
    private boolean exists(SQLiteDatabase db,String table,long id) {
        try(Cursor c=db.rawQuery("SELECT 1 FROM "+table+" WHERE id=?",
                new String[]{Long.toString(id)})){return c.moveToFirst();}
    }
    private static ContentValues move(long groupId,int delta,String kind,String note) {
        ContentValues v=new ContentValues();v.put("flock_id",groupId);v.put("at",System.currentTimeMillis());
        v.put("kind",kind);v.put("delta",delta);v.put("note",note==null?"":note.trim());return v;
    }
    public long addFlock(String name,String species,String breed,String place,int initial) {
        return addFlock(name,species,breed,place,initial,0);
    }
    public long addFlock(String name,String species,String breed,String place,
                         int initial,long hatchedAt) {
        name=FarmLogic.required(name,"Название группы",80);
        species=FarmLogic.required(species,"Вид птицы",70);
        breed=breed==null?"":breed.trim();place=place==null?"":place.trim();
        FarmLogic.count(initial,10000000);
        if(hatchedAt<0||hatchedAt>System.currentTimeMillis()+86400000L)
            throw new IllegalArgumentException("Дата появления не может быть в будущем.");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            ContentValues v=new ContentValues();v.put("name",name);v.put("species",species);
            v.put("breed",breed);v.put("place",place);
            v.put("since",System.currentTimeMillis());v.put("hatched_at",hatchedAt);
            long id=db.insertOrThrow("flock",null,v);
            if(initial>0)db.insertOrThrow("flock_moves",null,move(id,initial,"Начальный остаток",""));
            db.setTransactionSuccessful();return id;
        }finally{db.endTransaction();}
    }
    public void moveFlock(long id,int delta,String kind,String note) {
        if(delta==0||Math.abs((long)delta)>10000000)throw new IllegalArgumentException("Укажите ненулевое количество птицы.");
        kind=FarmLogic.required(kind,"Причина",60);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            if(!exists(db,"flock",id))throw new IllegalArgumentException("Группа птицы не найдена.");
            FarmLogic.newHeadcount(heads(db,id),delta);
            db.insertOrThrow("flock_moves",null,move(id,delta,kind,note));
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    public void updateFlock(long id,String name,String breed,String place,long hatchedAt) {
        if(hatchedAt<0 || hatchedAt>System.currentTimeMillis()+86400000L)
            throw new IllegalArgumentException("Неверная дата появления птицы.");
        ContentValues v=new ContentValues();
        v.put("name",FarmLogic.required(name,"Название",80));
        v.put("breed",breed==null?"":breed.trim());
        v.put("place",place==null?"":place.trim());v.put("hatched_at",hatchedAt);
        if(getWritableDatabase().update("flock",v,"id=?",new String[]{Long.toString(id)})!=1)
            throw new IllegalArgumentException("Группа не найдена.");
    }
    /** Atomic transfer, with no change to overall headcount. */
    public void moveBetweenFlocks(long from,long to,int quantity,String note) {
        if(from==to||quantity<=0||quantity>10000000)
            throw new IllegalArgumentException("Неверные группы или количество.");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            Flock source=null,target=null;
            for(Flock f:flocks()) {
                if(f.id==from)source=f;
                if(f.id==to)target=f;
            }
            if(source==null||target==null||!source.species.equals(target.species))
                throw new IllegalArgumentException("Перевод допустим только между группами одного вида.");
            FarmLogic.newHeadcount(heads(db,from),-quantity);
            FarmLogic.newHeadcount(heads(db,to),quantity);
            db.insertOrThrow("flock_moves",null,move(from,-quantity,"Перевод в группу "+target.name,note));
            db.insertOrThrow("flock_moves",null,move(to,quantity,"Перевод из группы "+source.name,note));
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public void addFlockNote(long groupId,String kind,String text) {
        if(!exists(getReadableDatabase(),"flock",groupId))
            throw new IllegalArgumentException("Группа не найдена.");
        ContentValues v=new ContentValues();v.put("flock_id",groupId);
        v.put("at",System.currentTimeMillis());
        v.put("kind",FarmLogic.required(kind,"Тип записи",50));
        v.put("text",FarmLogic.required(text,"Текст записи",600));
        getWritableDatabase().insertOrThrow("flock_notes",null,v);
    }
    public List<Note> flockNotes(long groupId) {
        List<Note> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT at,kind,text FROM flock_notes WHERE flock_id=? ORDER BY at DESC,id DESC LIMIT 500",
                new String[]{Long.toString(groupId)})){
            while(c.moveToNext()){
                Note n=new Note();n.at=c.getLong(0);
                n.kind=c.getString(1);n.text=c.getString(2);out.add(n);
            }
        }
        return out;
    }

    public List<Movement> flockMoves(long id,int limit) {
        return movements("flock_moves","flock_id","delta",id,limit);
    }

    public List<Feed> feeds() {
        List<Feed> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT f.*,COALESCE((SELECT SUM(m.quantity) FROM feed_moves m WHERE m.feed_id=f.id),0) AS balance FROM feeds f ORDER BY f.name COLLATE NOCASE",null)){
            while(c.moveToNext()){
                Feed f=new Feed();f.id=c.getLong(0);f.name=c.getString(1);
                f.unit=c.getString(2);f.stock=c.getDouble(3);out.add(f);
            }
        }
        return out;
    }
    public long addFeed(String name,String unit) {
        ContentValues v=new ContentValues();
        v.put("name",FarmLogic.required(name,"Название корма",80));
        v.put("unit",FarmLogic.required(unit,"Единица",20));
        return getWritableDatabase().insertOrThrow("feeds",null,v);
    }
    private double stock(SQLiteDatabase db,long id) {
        try(Cursor c=db.rawQuery("SELECT COALESCE(SUM(quantity),0) FROM feed_moves WHERE feed_id=?",
                new String[]{Long.toString(id)})){c.moveToFirst();return c.getDouble(0);}
    }
    /**
     * delta: positive = intake, negative = consumption.
     * Purchase amount, when supplied, is a money expense linked atomically
     * to the feed intake. Consumption does not duplicate expense.
     */
    public void moveFeed(long id,double delta,String kind,long totalPurchaseCents,String note) {
        if(!Double.isFinite(delta)||delta==0||Math.abs(delta)>10000000)
            throw new IllegalArgumentException("Некорректное количество корма.");
        if(totalPurchaseCents<0 || (totalPurchaseCents>0 && delta<=0))
            throw new IllegalArgumentException("Стоимость покупки допустима только при поступлении.");
        kind=FarmLogic.required(kind,"Операция",40);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            if(!exists(db,"feeds",id))throw new IllegalArgumentException("Корм не найден.");
            FarmLogic.newBalance(stock(db,id),delta);
            ContentValues v=new ContentValues();v.put("feed_id",id);v.put("at",System.currentTimeMillis());
            v.put("kind",kind);v.put("quantity",delta);
            v.put("note",note==null?"":note.trim());
            long movementId=db.insertOrThrow("feed_moves",null,v);
            if(totalPurchaseCents>0){
                ContentValues fin=new ContentValues();fin.put("at",System.currentTimeMillis());
                fin.put("type","Расход");fin.put("category","Корма");fin.put("cents",totalPurchaseCents);
                fin.put("title","Покупка корма");fin.put("note",note==null?"":note.trim());
                fin.put("feed_move_id",movementId);db.insertOrThrow("finances",null,fin);
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public List<Movement> feedMoves(long id,int limit) {
        return movements("feed_moves","feed_id","quantity",id,limit);
    }
    private List<Movement> movements(String table,String parent,String field,long id,int limit) {
        List<Movement> out=new ArrayList<>();
        String sql="SELECT at,kind,"+field+",note FROM "+table+" WHERE "+parent+"=? ORDER BY at DESC,id DESC LIMIT "+
                Math.max(1,Math.min(1000,limit));
        try(Cursor c=getReadableDatabase().rawQuery(sql,new String[]{Long.toString(id)})){
            while(c.moveToNext()){
                Movement m=new Movement();m.at=c.getLong(0);m.kind=c.getString(1);
                m.delta=c.getDouble(2);m.note=c.getString(3);out.add(m);
            }
        }
        return out;
    }

    public void addMoney(String type,String category,long cents,String title,String note) {
        if(!"Доход".equals(type)&&!"Расход".equals(type))
            throw new IllegalArgumentException("Неверный вид операции.");
        if(cents<=0||cents>999999999999L)throw new IllegalArgumentException("Неверная сумма.");
        ContentValues v=new ContentValues();v.put("at",System.currentTimeMillis());
        v.put("type",type);v.put("category",FarmLogic.required(category,"Категория",60));
        v.put("cents",cents);v.put("title",FarmLogic.required(title,"Назначение",100));
        v.put("note",note==null?"":note.trim());getWritableDatabase().insertOrThrow("finances",null,v);
    }
    public List<Money> money(int limit) {
        List<Money> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT id,at,type,category,cents,title,note FROM finances ORDER BY at DESC,id DESC LIMIT "+
                Math.max(1,Math.min(1000,limit)),null)) {
            while(c.moveToNext()){
                Money m=new Money();m.id=c.getLong(0);m.at=c.getLong(1);m.type=c.getString(2);
                m.category=c.getString(3);m.cents=c.getLong(4);m.title=c.getString(5);
                m.note=c.getString(6);out.add(m);
            }
        }
        return out;
    }
    public long[] totals(long from,long to) {
        long[] out={0,0};
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT type,COALESCE(SUM(cents),0) FROM finances WHERE at>=? AND at<? GROUP BY type",
                new String[]{Long.toString(from),Long.toString(to)})) {
            while(c.moveToNext()){
                if("Доход".equals(c.getString(0)))out[0]=c.getLong(1);
                else if("Расход".equals(c.getString(0)))out[1]=c.getLong(1);
            }
        }
        return out;
    }

    public long addNest(String name,String hen,String species,int eggs,long started,int days,String note) {
        FarmLogic.count(eggs,5000);if(eggs<1)throw new IllegalArgumentException("Укажите количество яиц.");
        FarmLogic.dueDate(started,days,java.time.ZoneId.systemDefault());
        ContentValues v=new ContentValues();v.put("name",FarmLogic.required(name,"Название закладки",80));
        v.put("hen",FarmLogic.required(hen,"Наседка или гнездо",80));
        v.put("species",FarmLogic.required(species,"Вид птицы",60));
        v.put("eggs",eggs);v.put("started_at",started);v.put("days",days);
        v.put("note",note==null?"":note.trim());return getWritableDatabase().insertOrThrow("nests",null,v);
    }
    public List<Nest> nests() {
        List<Nest> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT id,name,hen,species,eggs,started_at,days,active,hatched,note,transferred FROM nests ORDER BY active DESC,started_at DESC",null)) {
            while(c.moveToNext()){out.add(nestFrom(c));}
        }
        return out;
    }
    private static Nest nestFrom(Cursor c) {
        Nest n=new Nest();n.id=c.getLong(0);n.name=c.getString(1);n.hen=c.getString(2);
        n.species=c.getString(3);n.eggs=c.getInt(4);n.startedAt=c.getLong(5);
        n.days=c.getInt(6);n.active=c.getInt(7)==1;n.hatched=c.getInt(8);
        n.note=c.getString(9);n.transferred=c.getInt(10)==1;return n;
    }
    public Nest nest(long id) {
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT id,name,hen,species,eggs,started_at,days,active,hatched,note,transferred FROM nests WHERE id=?",
                new String[]{Long.toString(id)})){return c.moveToFirst()?nestFrom(c):null;}
    }
    public void nestObservation(long id,String kind,String text) {
        if(nest(id)==null)throw new IllegalArgumentException("Закладка не найдена.");
        ContentValues v=new ContentValues();v.put("nest_id",id);v.put("at",System.currentTimeMillis());
        v.put("kind",FarmLogic.required(kind,"Тип записи",50));
        v.put("text",FarmLogic.required(text,"Наблюдение",600));
        getWritableDatabase().insertOrThrow("nest_notes",null,v);
    }
    public List<Note> nestNotes(long id) {
        List<Note> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT at,kind,text FROM nest_notes WHERE nest_id=? ORDER BY at DESC,id DESC",
                new String[]{Long.toString(id)})) {
            while(c.moveToNext()){Note n=new Note();n.at=c.getLong(0);n.kind=c.getString(1);
                n.text=c.getString(2);out.add(n);}
        }
        return out;
    }
    public void finishNest(long id,int hatched) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            Nest n=nest(id);if(n==null||!n.active)throw new IllegalArgumentException("Закладка уже завершена.");
            if(hatched<0||hatched>n.eggs)throw new IllegalArgumentException("Неверное количество птенцов.");
            ContentValues v=new ContentValues();v.put("hatched",hatched);v.put("active",0);
            db.update("nests",v,"id=?",new String[]{Long.toString(id)});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public void transferNest(long nestId,long flockId) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            Nest n=nest(nestId);
            if(n==null||n.active||n.transferred||n.hatched<1)
                throw new IllegalArgumentException("Перенос невозможен или уже выполнен.");
            Flock group=null;for(Flock f:flocks())if(f.id==flockId){group=f;break;}
            if(group==null || !group.species.equals(n.species))
                throw new IllegalArgumentException("Выберите группу того же вида птицы.");
            FarmLogic.newHeadcount(heads(db,flockId),n.hatched);
            db.insertOrThrow("flock_moves",null,move(flockId,n.hatched,"Вывод под наседкой",n.name));
            ContentValues v=new ContentValues();v.put("transferred",1);
            db.update("nests",v,"id=?",new String[]{Long.toString(nestId)});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public void transferIncubator(Batch batch,long flockId) {
        if(batch==null||batch.active||batch.hatched<1||batch.hatched>batch.eggs)
            throw new IllegalArgumentException("Сначала завершите вывод с правильным количеством.");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            if(incubatorTransferred(batch.id))throw new IllegalArgumentException("Эта закладка уже перенесена.");
            Flock group=null;for(Flock f:flocks())if(f.id==flockId){group=f;break;}
            if(group==null||!group.species.equals(batch.species))
                throw new IllegalArgumentException("Вид выбранной группы не совпадает.");
            FarmLogic.newHeadcount(heads(db,flockId),batch.hatched);
            db.insertOrThrow("flock_moves",null,move(flockId,batch.hatched,"Вывод в инкубаторе",batch.name));
            ContentValues v=new ContentValues();v.put("batch_id",batch.id);
            v.put("flock_id",flockId);v.put("quantity",batch.hatched);
            v.put("at",System.currentTimeMillis());
            db.insertOrThrow("incubator_transfers",null,v);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public boolean incubatorTransferred(long batchId) {
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT 1 FROM incubator_transfers WHERE batch_id=?",
                new String[]{Long.toString(batchId)})){return c.moveToFirst();}
    }

    public void collectEggs(long groupId,int quantity,String note) {
        if(quantity<1||quantity>1000000)throw new IllegalArgumentException("Введите количество от 1 до 1 000 000.");
        if(!exists(getReadableDatabase(),"flock",groupId))
            throw new IllegalArgumentException("Группа не найдена.");
        ContentValues v=new ContentValues();v.put("flock_id",groupId);
        v.put("at",System.currentTimeMillis());v.put("quantity",quantity);
        v.put("note",note==null?"":note.trim());
        getWritableDatabase().insertOrThrow("egg_harvest",null,v);
    }
    public int eggsCollected(long from,long to) {
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT COALESCE(SUM(quantity),0) FROM egg_harvest WHERE at>=? AND at<?",
                new String[]{Long.toString(from),Long.toString(to)})) {
            c.moveToFirst();return c.getInt(0);
        }
    }
    public List<String[]> latestEggs(int limit) {
        List<String[]> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT e.at,e.quantity,f.name,e.note FROM egg_harvest e JOIN flock f ON e.flock_id=f.id ORDER BY e.at DESC,e.id DESC LIMIT "+
                Math.max(1,Math.min(200,limit)),null)) {
            while(c.moveToNext())out.add(new String[]{Long.toString(c.getLong(0)),
                    Integer.toString(c.getInt(1)),c.getString(2),c.getString(3)});
        }
        return out;
    }

    public long addTask(String title,String notes,long dueAt) {
        if(dueAt<=0)throw new IllegalArgumentException("Неверная дата задачи.");
        ContentValues v=new ContentValues();v.put("title",FarmLogic.required(title,"Задача",120));
        v.put("notes",notes==null?"":notes.trim());v.put("due_at",dueAt);
        return getWritableDatabase().insertOrThrow("farm_tasks",null,v);
    }
    public List<Task> tasks(boolean onlyOpen) {
        List<Task> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT id,title,notes,due_at,done_at FROM farm_tasks"+
                (onlyOpen?" WHERE done_at=0":"")+" ORDER BY done_at ASC,due_at ASC,id ASC LIMIT 400",null)) {
            while(c.moveToNext()){
                Task t=new Task();t.id=c.getLong(0);t.title=c.getString(1);
                t.notes=c.getString(2);t.dueAt=c.getLong(3);t.doneAt=c.getLong(4);
                out.add(t);
            }
        }
        return out;
    }
    public void completeTask(long id) {
        ContentValues v=new ContentValues();v.put("done_at",System.currentTimeMillis());
        getWritableDatabase().update("farm_tasks",v,"id=? AND done_at=0",new String[]{Long.toString(id)});
    }
    public int totalBirds() {
        int n=0;for(Flock f:flocks())n+=f.count;return n;
    }
    public int openNests() {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM nests WHERE active=1",null)){
            c.moveToFirst();return c.getInt(0);
        }
    }
}
