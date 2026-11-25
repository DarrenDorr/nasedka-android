package org.nasedka.app;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import static org.nasedka.app.RestoreFileStorage.*;

/**
 * Conservative recovery for the exact historical rollback_if_interrupted marker.
 * NOT invoked by BackupStore or Application yet. Exclusive access and closed
 * database handles are mandatory. Old prefs were not written until the old
 * marker was removed; no settings are changed here. Cannot prove prior
 * completion if marker is absent, or recover an ambiguous/corrupt history.
 */
final class RestoreLegacyRecovery {
    private static final byte[] MARKER="rollback_if_interrupted".getBytes(StandardCharsets.US_ASCII);
    interface PairValidator {void verify(Path inc,Path farm)throws IOException;}
    private RestoreLegacyRecovery(){}

    static boolean recover(Path root)throws IOException {
        return recover(root,RestoreLegacyRecovery::validatePair);
    }

    // Package-private seam for isolated synthetic fixtures.
    static boolean recover(Path root,PairValidator validator)throws IOException {
        root=root.toAbsolutePath().normalize();
        directory(root);
        Path marker=root.resolve(".nasedka_restore_pending");
        if(!exists(marker))return false;
        regular(marker);
        if(Files.size(marker)!=MARKER.length||!Arrays.equals(MARKER,Files.readAllBytes(marker)))
            throw new IOException("Unrecognized legacy restore marker");
        Path work=root.resolve(WORK);
        if(exists(work)&&new RestoreJournal(work).read()!=null)
            throw new IOException("Conflicting restore journals");
        Path[] live={root.resolve("incuba.db"),root.resolve(FarmDb.NAME)};
        Path[] stage={root.resolve("incuba_restore_stage.db"),root.resolve("farm_restore_stage.db")};
        Path[] source=new Path[2];
        String[] hashes=new String[2];
        for(int i=0;i<2;i++){
            Path old=live[i].resolveSibling(live[i].getFileName()+".pre_restore.bak");
            noSidecars(live[i]);
            if(exists(live[i]))regular(live[i]);
            if(exists(old)){
                regular(old); source[i]=old;
            }else{
                // A still-present stage file proves its historical replacement
                // rename has not happened (under the original ordered protocol).
                // No old copy + consumed stage is ambiguous: preserve everything.
                if(!exists(stage[i])||!exists(live[i]))
                    throw new IOException("Ambiguous legacy rollback source");
                regular(stage[i]); source[i]=live[i];
            }
            noSidecars(source[i]); hashes[i]=hash(source[i]);
        }
        // Validate both candidates before the first replacement.
        validator.verify(source[0],source[1]);
        for(int i=0;i<2;i++){
            noSidecars(source[i]);check(source[i],hashes[i]);
        }
        // Copy, never consume the last old copy. Restart while marker persists
        // repeats the same deterministic selection. No stage/backup cleanup.
        for(int i=0;i<2;i++){
            if(!source[i].equals(live[i]))copyDurable(source[i],live[i]);
            noSidecars(live[i]);check(live[i],hashes[i]);
        }
        validator.verify(live[0],live[1]);
        for(int i=0;i<2;i++){
            noSidecars(live[i]);check(live[i],hashes[i]);
        }
        sync(root);
        Files.delete(marker);sync(root);
        return true;
    }

    static void validatePair(Path inc,Path farm)throws IOException {
        regular(inc);regular(farm);noSidecars(inc);noSidecars(farm);
        int flags=SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS;
        try(SQLiteDatabase i=SQLiteDatabase.openDatabase(inc.toString(),null,flags,RestoreSqlitePreparation.PRESERVE_CORRUPTION);
            SQLiteDatabase f=SQLiteDatabase.openDatabase(farm.toString(),null,flags,RestoreSqlitePreparation.PRESERVE_CORRUPTION)){
            validateDatabase(i,BackupStore.INCUBATION);
            validateDatabase(f,BackupStore.FARM);
            BackupStore.relations(i,f);
        }catch(RuntimeException invalid){throw new IOException("Legacy database pair failed validation",invalid);}
    }
    static void validateDatabase(SQLiteDatabase db,String[] tables)throws IOException {
        if(db.getVersion()!=2)throw new IOException("Unsupported legacy schema");
        try(Cursor c=db.rawQuery("PRAGMA integrity_check",null)){
            if(!c.moveToFirst()||!"ok".equalsIgnoreCase(c.getString(0))||c.moveToNext())
                throw new IOException("Legacy SQLite integrity check failed");
        }
        for(String table:tables){
            try(Cursor c=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",new String[]{table})){
                if(!c.moveToFirst())throw new IOException("Missing legacy table");
            }
        }
    }
}
