package org.nasedka.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Process;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.nasedka.app.RestoreProtocol.Phase.*;
import static org.nasedka.app.RestoreProtocol.Outcome.*;

/** Test APK only. Run two distinct instrumentation invocations, never in normal suite. */
@RunWith(AndroidJUnit4.class)
public class RestoreProcessDeathTest {
    private static final Set<String> CASES=new HashSet<>(Arrays.asList(
            "prepared","first-db","settings","committed","rollback","rolled-back-cleanup"));
    private String scenario(){
        String s=InstrumentationRegistry.getArguments().getString("r1_case");
        assertTrue("Explicit supported crash case required",CASES.contains(s));return s;
    }
    private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    private Path root(String s){return context().getFilesDir().toPath().resolve("r1-crash-fixtures").resolve(s);}
    private SharedPreferences prefs(String s){return context().getSharedPreferences("r1_crash_"+s,Context.MODE_PRIVATE);}
    private static byte[] settings(boolean next)throws IOException{
        Map<String,Object> m=new HashMap<>();m.put("checks",next?"09:00":"08:00");m.put("notifications",!next);
        if(!next)m.put("next_alarm_at",42L);return RestoreSettingsPort.encode(m);
    }
    private static void db(Path p,String value)throws IOException{
        try(SQLiteDatabase d=SQLiteDatabase.openOrCreateDatabase(p.toFile(),null)){
            d.execSQL("CREATE TABLE sample(value TEXT NOT NULL)");
            d.execSQL("INSERT INTO sample(value) VALUES(?)",new Object[]{value});
        }
        RestoreSqlitePreparation.prepareClosed(p);
    }
    private static String value(Path p){
        try(SQLiteDatabase d=SQLiteDatabase.openDatabase(p.toString(),null,SQLiteDatabase.OPEN_READONLY);
            Cursor row=d.rawQuery("SELECT value FROM sample",null)){
            assertTrue(row.moveToFirst());String v=row.getString(0);assertFalse(row.moveToNext());return v;
        }
    }
    private static void witness(Path p,String line)throws IOException{
        try(FileChannel channel=FileChannel.open(p,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            ByteBuffer buf=ByteBuffer.wrap((line+"\n").getBytes(StandardCharsets.US_ASCII));
            while(buf.hasRemaining())channel.write(buf);channel.force(true);
        }
        RestoreFileStorage.sync(p.getParent());
    }
    private static RestoreProtocol.Phase expectedPhase(String s){
        if("prepared".equals(s))return NONE;
        if("committed".equals(s))return COMMITTED;
        if("rolled-back-cleanup".equals(s))return ROLLED_BACK;
        return PENDING;
    }
    @Test public void interruptAtBoundary()throws Exception{
        String name=scenario();Path root=root(name);
        Files.createDirectories(root.getParent());Files.createDirectory(root);
        Path work=Files.createDirectory(root.resolve(RestoreFileStorage.WORK));
        SharedPreferences prefs=prefs(name);
        assertTrue(prefs.edit().clear().putString("unrelated","keep").commit());
        RestoreSettingsPort port=new RestoreSettingsPort(prefs);port.write(settings(false));
        db(root.resolve("incuba.db"),"old-inc");db(root.resolve("nasedka_farm.db"),"old-farm");
        db(work.resolve(RestoreFileStorage.INCOMING_INC),"new-inc");
        db(work.resolve(RestoreFileStorage.INCOMING_FARM),"new-farm");
        RestoreFileStorage s=new RestoreFileStorage(root,port,settings(true));
        s.prepare();
        if(!"prepared".equals(name)){
            s.publish(NONE,PENDING);s.applyIncubation();
            if(!"first-db".equals(name)){
                s.applyFarm();s.applySettings();
                if("committed".equals(name)){
                    s.verifyNew();s.publish(PENDING,COMMITTED);
                    Files.delete(work.resolve("old-inc.db"));RestoreFileStorage.sync(work);
                }else if("rollback".equals(name)||"rolled-back-cleanup".equals(name)){
                    s.rollbackIncubation();
                    if("rolled-back-cleanup".equals(name)){
                        s.rollbackFarm();s.rollbackSettings();s.verifyOld();s.publish(PENDING,ROLLED_BACK);
                        Files.delete(work.resolve("old-inc.db"));RestoreFileStorage.sync(work);
                    }
                }
            }
        }
        RestoreProtocol.Phase phase=s.readPhase();assertEquals(expectedPhase(name),phase);
        witness(root.resolve("ready.txt"),name+" "+Process.myPid()+" "+phase.name());
        // Actual abrupt Linux process death, no finally/close of the harness.
        Process.killProcess(Process.myPid());
        fail("Process kill returned");
    }
    @Test public void recoverInIndependentProcess()throws Exception{
        String name=scenario();Path root=root(name);
        String[] before=new String(Files.readAllBytes(root.resolve("ready.txt")),StandardCharsets.US_ASCII).trim().split(" ");
        assertEquals(3,before.length);assertEquals(name,before[0]);
        int oldPid=Integer.parseInt(before[1]);assertNotEquals(oldPid,Process.myPid());
        RestoreSettingsPort port=new RestoreSettingsPort(prefs(name));
        RestoreFileStorage s=new RestoreFileStorage(root,port,null);
        assertEquals(expectedPhase(name),s.readPhase());assertEquals(expectedPhase(name).name(),before[2]);
        boolean next="committed".equals(name);
        RestoreProtocol.Outcome outcome=new RestoreProtocol(s).recover();
        assertEquals(next?NEW_GENERATION:"prepared".equals(name)?NO_PENDING:OLD_GENERATION,outcome);
        assertEquals(NONE,s.readPhase());
        assertEquals(next?"new-inc":"old-inc",value(root.resolve("incuba.db")));
        assertEquals(next?"new-farm":"old-farm",value(root.resolve("nasedka_farm.db")));
        assertArrayEquals(settings(next),port.read());
        assertEquals("keep",prefs(name).getString("unrelated",null));
        witness(root.resolve("verified.txt"),name+" "+Process.myPid()+" "+outcome.name());
    }

    private static boolean actualNoSpace(IOException e){
        for(Throwable t=e;t!=null;t=t.getCause()){
            if(t instanceof android.system.ErrnoException &&
                    ((android.system.ErrnoException)t).errno==android.system.OsConstants.ENOSPC)return true;
            String message=String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
            if(message.contains("enospc")||message.contains("no space left on device"))return true;
        }
        return false;
    }

    @Test public void boundedAndroidEnospc()throws Exception{
        assertEquals("1",InstrumentationRegistry.getArguments().getString("r1_enospc"));
        assertTrue("Disposable emulator only",android.os.Build.FINGERPRINT.contains("generic")
                ||android.os.Build.HARDWARE.contains("ranchu"));
        Path root=context().getFilesDir().toPath().resolve("r1-bounded-enospc");
        // Refuse any unbounded/shared filesystem before writing fixtures.
        RestoreFileStorage.directory(root);
        assertNotEquals("Separate device required",android.system.Os.stat(root.getParent().toString()).st_dev,
                android.system.Os.stat(root.toString()).st_dev);
        android.os.StatFs fs=new android.os.StatFs(root.toString());
        long capacity=fs.getTotalBytes();
        assertTrue("Expected 8 MiB disposable mount",capacity>=4L*1024*1024&&capacity<=16L*1024*1024);
        try(java.util.stream.Stream<Path> files=Files.list(root)){
            assertEquals("Mount must be empty",0L,files.count());
        }
        Path work=Files.createDirectory(root.resolve(RestoreFileStorage.WORK));
        Path inc=root.resolve("incuba.db"),farm=root.resolve("nasedka_farm.db");
        Path fill=root.resolve("bounded-fill.bin");
        SharedPreferences prefs=context().getSharedPreferences("r1_bounded_enospc",Context.MODE_PRIVATE);
        assertTrue(prefs.edit().clear().putString("unrelated","keep").commit());
        RestoreSettingsPort port=new RestoreSettingsPort(prefs);
        port.write(settings(false));
        db(inc,"old-inc");db(farm,"old-farm");
        db(work.resolve(RestoreFileStorage.INCOMING_INC),"new-inc");
        db(work.resolve(RestoreFileStorage.INCOMING_FARM),"new-farm");
        String incHash=RestoreFileStorage.hash(inc),farmHash=RestoreFileStorage.hash(farm);
        RestoreFileStorage s=new RestoreFileStorage(root,port,settings(true));
        s.prepare();s.publish(NONE,PENDING);
        try{
            boolean full=false;
            try(FileChannel channel=FileChannel.open(fill,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
                ByteBuffer page=ByteBuffer.allocate(4096);long written=0;
                // Hard bound independent of free-space estimates.
                while(written<=capacity+4096){
                    page.clear();
                    while(page.hasRemaining()){
                        int n=channel.write(page);
                        if(n<=0)throw new IOException("No progress filling isolated mount");
                        written+=n;
                    }
                }
            }catch(IOException e){
                assertTrue("Expected kernel ENOSPC, got "+e,actualNoSpace(e));full=true;
            }
            assertTrue("Kernel ENOSPC not reached",full);
            boolean refused=false;
            try{s.applyIncubation();}
            catch(IOException e){
                assertTrue("Expected actual restore ENOSPC, got "+e,actualNoSpace(e));refused=true;
            }
            assertTrue("Restore unexpectedly succeeded on full mount",refused);
            assertEquals(PENDING,s.readPhase());
            assertEquals(incHash,RestoreFileStorage.hash(inc));
            assertEquals(farmHash,RestoreFileStorage.hash(farm));
            assertTrue(Files.exists(work.resolve("old-inc.db")));
            assertTrue(Files.exists(work.resolve("old-farm.db")));
            assertArrayEquals(settings(false),port.read());
        }finally{Files.deleteIfExists(fill);}
        assertEquals(OLD_GENERATION,new RestoreProtocol(new RestoreFileStorage(root,port,null)).recover());
        assertEquals(NONE,s.readPhase());
        assertEquals(incHash,RestoreFileStorage.hash(inc));assertEquals(farmHash,RestoreFileStorage.hash(farm));
        assertEquals("old-inc",value(inc));assertEquals("old-farm",value(farm));
        assertArrayEquals(settings(false),port.read());assertEquals("keep",prefs.getString("unrelated",null));
        witness(root.resolve("enospc-verified.txt"),"ANDROID_ENOSPC_PASS "+capacity+" "+Process.myPid());
    }
}
