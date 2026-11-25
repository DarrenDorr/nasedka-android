package org.nasedka.app;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Explicit SQLite handoff before file restore. Caller MUST hold exclusive
 * access, have closed all other SQLite handles and resolved any restore journal.
 * Never invoke on a mixed generation or while a v2/legacy restore is pending.
 * Not connected to the application yet.
 */
final class RestoreSqlitePreparation {
    private RestoreSqlitePreparation() {}

    // Do not allow Android's default corruption handler to delete a source DB.
    static final android.database.DatabaseErrorHandler PRESERVE_CORRUPTION =
            db -> { throw new SQLiteException("Database corruption; preserve files"); };

    static void prepareClosed(Path path) throws IOException {
        Path file=path.toAbsolutePath().normalize();
        if(!Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isRegularFile())
            throw new IOException("Not an existing SQLite file");
        // Check every existing sidecar before SQLite can open or modify it.
        for(String suffix:new String[]{"-wal","-shm","-journal"})regularIfPresent(side(file,suffix));
        // No CREATE_IF_NECESSARY: a missing database is an error, not a new one.
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.toString(),null,
                SQLiteDatabase.OPEN_READWRITE|SQLiteDatabase.NO_LOCALIZED_COLLATORS,PRESERVE_CORRUPTION)) {
            try(Cursor c=db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null)){
                if(!c.moveToFirst()||c.getInt(0)!=0||c.getInt(1)!=c.getInt(2))
                    throw new IOException("SQLite checkpoint incomplete");
            }
            db.disableWriteAheadLogging();
            try(Cursor c=db.rawQuery("PRAGMA journal_mode=DELETE",null)){
                if(!c.moveToFirst()||!"delete".equalsIgnoreCase(c.getString(0)))
                    throw new IOException("SQLite DELETE mode unavailable");
            }
            try(Cursor c=db.rawQuery("PRAGMA integrity_check",null)){
                if(!c.moveToFirst()||!"ok".equalsIgnoreCase(c.getString(0))||c.moveToNext())
                    throw new IOException("SQLite integrity check failed");
            }
        }catch(SQLiteException e){throw new IOException("SQLite preparation failed",e);}
        // Only after successful checkpoint, mode change, integrity and close:
        // empty WAL/rollback journal may be discarded; SHM is a rebuildable
        // WAL index only when no WAL content remains and no handles are open.
        Path wal=side(file,"-wal"), rollback=side(file,"-journal"), shm=side(file,"-shm");
        requireEmptyIfPresent(wal); requireEmptyIfPresent(rollback);
        regularIfPresent(shm);
        Files.deleteIfExists(wal); Files.deleteIfExists(rollback); Files.deleteIfExists(shm);
        try(FileChannel channel=FileChannel.open(file,StandardOpenOption.WRITE)){channel.force(true);}
        try(FileChannel channel=FileChannel.open(file.getParent(),StandardOpenOption.READ)){channel.force(true);}
        // Keep the adapter's independent strict guard (no sidecars at all).
        if(attributes(wal)!=null||attributes(rollback)!=null||attributes(shm)!=null)
            throw new IOException("SQLite sidecar remains after preparation");
    }

    private static Path side(Path p,String suffix){return p.resolveSibling(p.getFileName()+suffix);}
    private static BasicFileAttributes attributes(Path p)throws IOException{
        try{return Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
        catch(NoSuchFileException absent){return null;}
    }
    private static void regularIfPresent(Path p)throws IOException{
        BasicFileAttributes a=attributes(p);
        if(a!=null&&!a.isRegularFile())throw new IOException("Invalid SQLite sidecar");
    }
    private static void requireEmptyIfPresent(Path p)throws IOException{
        BasicFileAttributes a=attributes(p);
        if(a!=null&&(!a.isRegularFile()||a.size()!=0))
            throw new IOException("Nonempty SQLite journal after checkpoint; preserve files");
    }
}
