package org.nasedka.app;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import static org.nasedka.app.RestoreFileStorage.*;

/**
 * Concrete isolated startup backend. Root must be the caller's existing
 * private database directory; no live helpers opened by Application yet.
 * Caller owns the exclusive coordinator for the whole gate attempt.
 * No implicit deletion of ambiguous files, no upgrade of existing schemas.
 */
final class RestoreStartupFiles implements RestoreStartupGate.Backend {
    private static final String INC="incuba.db", FARM=FarmDb.NAME;
    private static final String MARKER=".nasedka_restore_pending";
    private static final String[] LEGACY={"incuba.db.pre_restore.bak","nasedka_farm.db.pre_restore.bak",
            "incuba_restore_stage.db","farm_restore_stage.db"};
    private final Context context;
    private final Path root,work;
    private final RestoreSettingsPort settings;

    RestoreStartupFiles(Context context,Path root,RestoreSettingsPort settings)throws IOException{
        this.context=java.util.Objects.requireNonNull(context);
        this.root=root.toAbsolutePath().normalize();directory(this.root);
        this.work=this.root.resolve(WORK);
        this.settings=java.util.Objects.requireNonNull(settings);
    }

    // No symlinks, no open of live SQLite while inspecting restore evidence.
    private boolean file(Path path)throws IOException{
        if(!exists(path))return false;
        regular(path);return true;
    }
    private boolean family(Path path)throws IOException{
        boolean present=file(path);
        for(String suffix:new String[]{"-wal","-shm","-journal",".r1tmp"})
            present=file(path.resolveSibling(path.getFileName()+suffix))||present;
        return present;
    }
    @Override public RestoreStartupGate.Inspection inspect()throws IOException{
        directory(root);
        boolean marker=file(root.resolve(MARKER)), remnants=false;
        for(String name:LEGACY)remnants=family(root.resolve(name))||remnants;
        // If initialization was interrupted, preserve its private staging file.
        // Do not guess that the surviving database is a fresh empty DB.
        for(String name:new String[]{INC,FARM})
            remnants=family(root.resolve(name+".startup-init"))||remnants;
        boolean inc=file(root.resolve(INC)),farm=file(root.resolve(FARM));
        // Missing live base with any sidecar is not a normal fresh install.
        if(!inc && family(root.resolve(INC)))remnants=true;
        if(!farm && family(root.resolve(FARM)))remnants=true;
        boolean artifacts=file(root.resolve(INC+".r1tmp"))|file(root.resolve(FARM+".r1tmp"));
        RestoreProtocol.Phase phase=RestoreProtocol.Phase.NONE;
        if(exists(work)){
            directory(work);
            RestoreJournal journal=new RestoreJournal(work);
            RestoreJournal.Entry entry=journal.read();
            if(entry==null)journal.confirmAbsent();
            else phase=entry.phase;
            // Work is reserved for this adapter. Unknown files are evidence,
            // never treated as permission to create/overwrite a live database.
            try(java.nio.file.DirectoryStream<Path> children=Files.newDirectoryStream(work)){
                for(Path child:children){
                    regular(child);artifacts=true;
                    String name=child.getFileName().toString();
                    if(!name.matches("(restore-v2\\.journal(\\.tmp)?|((old|new)-(inc|farm)\\.db|(old|new)-prefs\\.bin|incoming-(inc|farm)\\.db)(\\.r1tmp)?)"))
                        throw new IOException("Unrecognized restore work file");
                }
            }
        }
        sync(root);
        return new RestoreStartupGate.Inspection(phase,marker,remnants,artifacts,inc,farm);
    }

    @Override public void recoverLegacy()throws IOException{
        if(!RestoreLegacyRecovery.recover(root))throw new IOException("Legacy marker disappeared");
        // The existing recovery deliberately retains old/staged files.
        // inspect still reports these as unclassified; fail closed until a
        // durable classification/disposition protocol is implemented.
    }
    @Override public void recoverV2()throws IOException{
        new RestoreProtocol(new RestoreFileStorage(root,settings,null)).recover();
        if(new RestoreFileStorage(root,settings,null).readPhase()!=RestoreProtocol.Phase.NONE)
            throw new IOException("Unresolved v2 journal");
    }
    @Override public UUID readGeneration()throws IOException{
        settings.read(); // strict validation of all managed settings
        return settings.generation();
    }
    @Override public UUID initializeGeneration()throws IOException{return settings.initializeGeneration();}

    @Override public void initializeMissing(RestoreStartupGate.Inspection before)throws IOException{
        RestoreStartupGate.Inspection now=inspect();
        if(now.phase!=RestoreProtocol.Phase.NONE||now.legacyMarker||now.unclassifiedLegacyRemnants||
                now.v2Artifacts||now.incubation!=before.incubation||now.farm!=before.farm||readGeneration()!=null)
            throw new IOException("Initialization evidence changed");
        // Validate existing v2 files before creating the absent partner.
        if(now.incubation)validateSingle(root.resolve(INC),true);
        if(now.farm)validateSingle(root.resolve(FARM),false);
        if(!now.incubation)createMissing(INC,true);
        if(!now.farm)createMissing(FARM,false);
    }
    private void createMissing(String name,boolean incubation)throws IOException{
        Path live=root.resolve(name),stage=root.resolve(name+".startup-init");
        if(family(live)||family(stage))throw new IOException("Database initialization target occupied");
        // CREATE_NEW before SQLite; no default corruption handler, no opening
        // existing live helper, no replacement of an existing file.
        Files.createFile(stage);sync(root);
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(stage.toString(),null,
                SQLiteDatabase.OPEN_READWRITE|SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                RestoreSqlitePreparation.PRESERVE_CORRUPTION)){
            db.beginTransaction();
            try{
                if(incubation){try(Db helper=new Db(context)){helper.onCreate(db);}}
                else{try(FarmDb helper=new FarmDb(context)){helper.onCreate(db);}}
                db.setVersion(2);db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }catch(RuntimeException e){throw new IOException("Initial SQLite schema failed",e);}
        validateSingle(stage,incubation);
        if(family(live))throw new IOException("Database appeared during initialization");
        // Same private directory, exclusive access, no replacement requested.
        Files.move(stage,live,StandardCopyOption.ATOMIC_MOVE);sync(root);
    }
    private static void validateSingle(Path path,boolean incubation)throws IOException{
        RestoreSqlitePreparation.prepareClosed(path);
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(path.toString(),null,
                SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                RestoreSqlitePreparation.PRESERVE_CORRUPTION)){
            RestoreLegacyRecovery.validateDatabase(db,incubation?BackupStore.INCUBATION:BackupStore.FARM);
        }catch(RuntimeException e){throw new IOException("Existing SQLite validation failed",e);}
    }
    @Override public void validatePair()throws IOException{
        RestoreStartupGate.Inspection now=inspect();
        if(!now.pair()||now.phase!=RestoreProtocol.Phase.NONE||now.legacyMarker||now.unclassifiedLegacyRemnants)
            throw new IOException("Cannot validate unresolved pair");
        Path inc=root.resolve(INC),farm=root.resolve(FARM);
        validateSingle(inc,true);validateSingle(farm,false);
        RestoreLegacyRecovery.validatePair(inc,farm);
    }
}
