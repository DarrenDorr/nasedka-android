package org.nasedka.app;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;

import org.nasedka.app.RestoreProtocol.Phase;

/**
 * Isolated adapter for two closed SQLite files and managed preference bytes.
 * NOT wired to BackupStore. Caller MUST hold the logical-operation barrier,
 * validate incoming SQLite schemas/relations and checkpoint/close all handles.
 * Never deletes SQLite sidecars or interprets a legacy journal.
 */
final class RestoreFileStorage implements RestoreProtocol.Storage {
    static final String WORK = "restore-v2";
    static final String INCOMING_INC = "incoming-inc.db";
    static final String INCOMING_FARM = "incoming-farm.db";
    private static final int MAX_PREFS = 16384;
    interface SettingsPort {
        byte[] read() throws IOException;
        void write(byte[] canonical) throws IOException;
    }

    private final Path root, work;
    private final Path[] live, old, staged;
    private final SettingsPort settings;
    private final RestoreJournal journal;
    private final byte[] targetSettings;
    private RestoreJournal.Entry prepared;

    // root is the private database directory; work must be reserved for this adapter.
    // Incoming DB files are supplied in work before restore. Recovery needs no target bytes.
    RestoreFileStorage(Path root, SettingsPort settings, byte[] targetSettings) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.work = this.root.resolve(WORK);
        directory(this.root); directory(work);
        this.settings = java.util.Objects.requireNonNull(settings);
        this.targetSettings = targetSettings == null ? null : targetSettings.clone();
        this.live = new Path[]{this.root.resolve("incuba.db"), this.root.resolve("nasedka_farm.db")};
        this.old = new Path[]{work.resolve("old-inc.db"),work.resolve("old-farm.db"),work.resolve("old-prefs.bin")};
        this.staged = new Path[]{work.resolve("new-inc.db"),work.resolve("new-farm.db"),work.resolve("new-prefs.bin")};
        this.journal = new RestoreJournal(work);
    }

    @Override public Phase readPhase() throws IOException {
        if (exists(root.resolve(".nasedka_restore_pending")))
            throw new IOException("Legacy recovery required before v2");
        RestoreJournal.Entry e = journal.read();
        return e == null ? Phase.NONE : e.phase;
    }

    @Override public void prepare() throws IOException {
        if (readPhase()!=Phase.NONE || targetSettings==null)
            throw new IOException("Not ready to prepare restore");
        byte[] previous = checkedPrefs(settings.read());
        byte[] next = checkedPrefs(targetSettings);
        noSidecars(live[0]); noSidecars(live[1]);
        Path[] incoming = {work.resolve(INCOMING_INC),work.resolve(INCOMING_FARM)};
        for (int i=0;i<2;i++) {
            noSidecars(incoming[i]);
            copyDurable(live[i],old[i]);
            copyDurable(incoming[i],staged[i]);
        }
        writeDurable(old[2],previous); writeDurable(staged[2],next);
        String[] hashes = new String[6];
        for (int i=0;i<3;i++) { hashes[i]=hash(old[i]); hashes[i+3]=hash(staged[i]); }
        prepared = new RestoreJournal.Entry(UUID.randomUUID(),Phase.PENDING,hashes);
        verify(prepared,false);
    }

    @Override public void publish(Phase expected, Phase next) throws IOException {
        if (readPhase()!=expected) throw new IOException("Unexpected journal phase");
        if (expected==Phase.NONE) {
            if (prepared==null || next!=Phase.PENDING) throw new IOException("No prepared generation");
            // Recheck every immutable source before publishing PENDING.
            for (int i=0;i<3;i++) { check(old[i],prepared.hashes()[i]); check(staged[i],prepared.hashes()[i+3]); }
            verify(prepared,false);
            journal.publish(expected,prepared);
        } else {
            RestoreJournal.Entry e=entry(expected);
            if(next==Phase.COMMITTED) verify(e,true);
            else if(next==Phase.ROLLED_BACK) verify(e,false);
            else throw new IOException("Invalid terminal phase");
            journal.publish(expected,e.withPhase(next));
        }
    }

    @Override public void applyIncubation() throws IOException { replaceDb(0,true); }
    @Override public void applyFarm() throws IOException { replaceDb(1,true); }
    @Override public void applySettings() throws IOException { replacePrefs(true); }
    @Override public void rollbackIncubation() throws IOException { replaceDb(0,false); }
    @Override public void rollbackFarm() throws IOException { replaceDb(1,false); }
    @Override public void rollbackSettings() throws IOException { replacePrefs(false); }
    @Override public void verifyNew() throws IOException { verify(entryForVerification(true),true); }
    @Override public void verifyOld() throws IOException { verify(entryForVerification(false),false); }

    private RestoreJournal.Entry entry(Phase expected) throws IOException {
        if (readPhase()!=expected) throw new IOException("Unexpected restore state");
        RestoreJournal.Entry e=journal.read();
        if(e==null || e.phase!=expected) throw new IOException("Missing restore journal");
        return e;
    }
    private RestoreJournal.Entry entryForVerification(boolean next) throws IOException {
        Phase phase=readPhase();
        if(phase!=Phase.PENDING && phase!=(next?Phase.COMMITTED:Phase.ROLLED_BACK))
            throw new IOException("Verification in wrong phase");
        return entry(phase);
    }
    private void replaceDb(int index,boolean next) throws IOException {
        RestoreJournal.Entry e=entry(Phase.PENDING);
        // Before any destructive rollback/apply, prove all three source copies.
        checkSources(e,next);
        Path source=(next?staged:old)[index];
        noSidecars(live[index]);
        copyDurable(source,live[index]);
        check(live[index],e.hashes()[index+(next?3:0)]);
    }
    private void replacePrefs(boolean next) throws IOException {
        RestoreJournal.Entry e=entry(Phase.PENDING);
        checkSources(e,next);
        Path source=(next?staged:old)[2];
        byte[] bytes=readPrefs(source);
        settings.write(bytes);
        if(!Arrays.equals(bytes,checkedPrefs(settings.read())))throw new IOException("Settings verification failed");
    }
    private void checkSources(RestoreJournal.Entry e,boolean next) throws IOException {
        for(int i=0;i<3;i++)check((next?staged:old)[i],e.hashes()[i+(next?3:0)]);
    }
    private void verify(RestoreJournal.Entry e,boolean next) throws IOException {
        int offset=next?3:0;
        for(int i=0;i<2;i++){noSidecars(live[i]);check(live[i],e.hashes()[i+offset]);}
        if(!digest(checkedPrefs(settings.read())).equals(e.hashes()[2+offset]))
            throw new IOException("Settings generation mismatch");
    }

    @Override public void cleanup(Phase terminal) throws IOException {
        if(terminal!=Phase.COMMITTED && terminal!=Phase.ROLLED_BACK)throw new IOException("Not terminal");
        RestoreJournal.Entry e=entry(terminal);verify(e,terminal==Phase.COMMITTED);
        for(Path path:old) removeOwned(path);
        for(Path path:staged) removeOwned(path);
        removeOwned(work.resolve(INCOMING_INC)); removeOwned(work.resolve(INCOMING_FARM));
        // Replacement temp files are reserved for this adapter.
        for(Path path:live) removeOwned(temp(path));
        sync(work);sync(root);
    }
    @Override public void clear(Phase terminal) throws IOException {
        RestoreJournal.Entry e=entry(terminal); verify(e,terminal==Phase.COMMITTED);
        journal.clear(e);
    }
    private static void removeOwned(Path p) throws IOException {
        removeRegular(p);removeRegular(temp(p));
    }
    private static Path temp(Path p){return p.resolveSibling(p.getFileName()+".r1tmp");}
    private static boolean exists(Path p) throws IOException {
        try { Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return true; }
        catch(NoSuchFileException absent){directory(p.getParent());return false;}
    }
    private static void directory(Path p) throws IOException {
        if(!Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isDirectory())
            throw new IOException("Invalid directory");
    }
    private static void regular(Path p) throws IOException {
        if(!Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isRegularFile())
            throw new IOException("Invalid restore file");
    }
    private static void removeRegular(Path p) throws IOException {
        if(exists(p)){regular(p);Files.delete(p);}
    }
    private static void noSidecars(Path db) throws IOException {
        for(String suffix:new String[]{"-wal","-shm","-journal"})
            if(exists(db.resolveSibling(db.getFileName()+suffix)))
                throw new IOException("SQLite sidecar requires checkpoint and close");
    }
    private static void sync(Path dir) throws IOException {
        try(FileChannel c=FileChannel.open(dir,StandardOpenOption.READ)){c.force(true);}
    }
    private static void publishFile(Path t,Path target) throws IOException {
        if(exists(target))regular(target);
        Files.move(t,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        sync(target.getParent());
    }
    private static void copyDurable(Path source,Path target) throws IOException {
        regular(source);
        Path t=temp(target);removeRegular(t);
        try(InputStream in=Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS);
            FileChannel out=FileChannel.open(t,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            byte[] bytes=new byte[65536]; int n;
            while((n=in.read(bytes))!=-1){ByteBuffer buf=ByteBuffer.wrap(bytes,0,n);while(buf.hasRemaining())out.write(buf);}
            out.force(true);
        }
        publishFile(t,target);
    }
    private static void writeDurable(Path target,byte[] bytes) throws IOException {
        Path t=temp(target);removeRegular(t);
        try(FileChannel out=FileChannel.open(t,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            ByteBuffer buf=ByteBuffer.wrap(bytes);while(buf.hasRemaining())out.write(buf);out.force(true);
        }
        publishFile(t,target);
    }
    private static byte[] checkedPrefs(byte[] bytes) throws IOException {
        if(bytes==null || bytes.length>MAX_PREFS)throw new IOException("Invalid settings snapshot");
        return bytes;
    }
    private static byte[] readPrefs(Path p) throws IOException {
        regular(p);
        if(Files.size(p)>MAX_PREFS)throw new IOException("Oversized settings snapshot");
        return checkedPrefs(Files.readAllBytes(p));
    }
    private static MessageDigest sha() {
        try{return MessageDigest.getInstance("SHA-256");}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static String hex(byte[] bytes) {
        char[] digits="0123456789abcdef".toCharArray();StringBuilder out=new StringBuilder(64);
        for(byte b:bytes){int n=b&255;out.append(digits[n>>>4]).append(digits[n&15]);}
        return out.toString();
    }
    private static String digest(byte[] bytes){return hex(sha().digest(bytes));}
    private static String hash(Path p) throws IOException {
        regular(p);MessageDigest d=sha();
        try(InputStream in=Files.newInputStream(p,LinkOption.NOFOLLOW_LINKS)){
            byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);
        }
        return hex(d.digest());
    }
    private static void check(Path p,String expected) throws IOException {
        if(!hash(p).equals(expected))throw new IOException("Restore file digest mismatch");
    }
}
