package org.nasedka.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Startup-only migration of the two known v1 schemas. External exclusive
 * coordinator, resolved restore evidence and closed handles are required.
 * Validate both inputs before any schema migration; each v1 -> v2 transaction
 * is independently atomic, so a mixed v1/v2 pair can resume at next startup.
 * No destructive corruption handler or CREATE flag on existing files.
 */
final class RestoreSchemaMigration {
    private RestoreSchemaMigration(){}
    private static final int FLAGS=SQLiteDatabase.NO_LOCALIZED_COLLATORS;
    private static Map<String,List<String>> columns(SQLiteDatabase db,String table)throws IOException{
        if(!table.matches("[a-z_]+"))throw new IOException("Unexpected table name");
        Map<String,List<String>> result=new TreeMap<>();
        try(Cursor c=db.rawQuery("PRAGMA table_info("+table+")",null)){
            while(c.moveToNext()){
                // Compare name/type/not-null/default/PK position, ignoring cid:
                // migrated columns are appended, fresh schemas order them differently.
                if(result.put(c.getString(1),Arrays.asList(c.getString(2),
                        c.getString(3),c.getString(4),c.getString(5)))!=null)
                    throw new IOException("Duplicate column");
            }
        }
        if(result.isEmpty())throw new IOException("Missing table");
        return result;
    }
    private static Map<String,Map<String,List<String>>> shape(SQLiteDatabase db)throws IOException{
        Map<String,Map<String,List<String>>> result=new TreeMap<>();
        List<String> tables=new ArrayList<>();
        try(Cursor c=db.rawQuery("SELECT type,name FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' AND name!='android_metadata' AND type!='index'",null)){
            while(c.moveToNext()){
                if(!"table".equals(c.getString(0)))throw new IOException("Unexpected SQLite object");
                tables.add(c.getString(1));
            }
        }
        for(String table:tables)result.put(table,columns(db,table));
        return result;
    }
    private static void createSchema(Context context,SQLiteDatabase db,boolean incubation){
        if(incubation){try(Db helper=new Db(context)){helper.onCreate(db);}}
        else{try(FarmDb helper=new FarmDb(context)){helper.onCreate(db);}}
    }
    private static void validate(Context context,SQLiteDatabase db,boolean incubation,int version)throws IOException{
        if(version!=1&&version!=2)throw new IOException("Unsupported SQLite schema version");
        if(db.getVersion()!=version)throw new IOException("SQLite version changed");
        try(Cursor c=db.rawQuery("PRAGMA integrity_check",null)){
            if(!c.moveToFirst()||!"ok".equalsIgnoreCase(c.getString(0))||c.moveToNext())
                throw new IOException("SQLite integrity check failed");
        }
        Map<String,Map<String,List<String>>> expected;
        try(SQLiteDatabase reference=SQLiteDatabase.create(null)){
            createSchema(context,reference,incubation);
            expected=shape(reference);
        }
        if(version==1){
            if(incubation)expected.get("batches").remove("profile_id");
            else{expected.get("flock").remove("hatched_at");expected.remove("flock_notes");}
        }
        if(!expected.equals(shape(db)))throw new IOException("Unexpected SQLite table or column structure");
    }
    static int inspect(Context context,Path path,boolean incubation)throws IOException{
        RestoreSqlitePreparation.prepareClosed(path);
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(path.toString(),null,
                SQLiteDatabase.OPEN_READONLY|FLAGS,RestoreSqlitePreparation.PRESERVE_CORRUPTION)){
            int version=db.getVersion();validate(context,db,incubation,version);return version;
        }catch(RuntimeException e){throw new IOException("SQLite schema inspection failed",e);}
    }
    static void migrate(Context context,Path path,boolean incubation,int expectedVersion)throws IOException{
        if(expectedVersion==2){
            if(inspect(context,path,incubation)!=2)throw new IOException("SQLite version changed");
            return;
        }
        if(expectedVersion!=1)throw new IOException("Unsupported migration source");
        RestoreFileStorage.regular(path);RestoreFileStorage.noSidecars(path);
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(path.toString(),null,
                SQLiteDatabase.OPEN_READWRITE|FLAGS,RestoreSqlitePreparation.PRESERVE_CORRUPTION)){
            // Revalidate inside transaction before changing user_version/schema.
            db.beginTransaction();
            try{
                validate(context,db,incubation,1);
                if(incubation){try(Db helper=new Db(context)){helper.onUpgrade(db,1,2);}}
                else{try(FarmDb helper=new FarmDb(context)){helper.onUpgrade(db,1,2);}}
                db.setVersion(2);validate(context,db,incubation,2);
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }catch(RuntimeException e){throw new IOException("SQLite migration failed",e);}
        if(inspect(context,path,incubation)!=2)throw new IOException("Migration verification failed");
    }
}
