package org.nasedka.app;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Arrays;
import static org.nasedka.app.RestoreProtocol.Phase.*;
import static org.nasedka.app.RestoreProtocol.Outcome.*;

/** CI-only Linux tmpfs probe; no Android or app data. */
public final class RestoreEnospcProbe {
    static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static boolean noSpace(IOException e){return String.valueOf(e.getMessage()).contains("No space left on device");}
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args[0]).toAbsolutePath();
        FileStore fs=Files.getFileStore(root);
        require("tmpfs".equals(fs.type())&&fs.getTotalSpace()<=16L*1024*1024,"Refuse unbounded or non-tmpfs filesystem");
        Path work=root.resolve(RestoreFileStorage.WORK),fill=root.resolve("bounded-fill.bin");
        Path inc=root.resolve("incuba.db"),farm=root.resolve("nasedka_farm.db");
        String incHash=RestoreFileStorage.hash(inc),farmHash=RestoreFileStorage.hash(farm);
        byte[][] managed={new byte[]{1}};
        RestoreFileStorage.SettingsPort port=new RestoreFileStorage.SettingsPort(){
            public byte[] read(){return managed[0].clone();}
            public void write(byte[] bytes){managed[0]=bytes.clone();}
        };
        RestoreFileStorage s=new RestoreFileStorage(root,port,new byte[]{2});
        s.prepare();s.publish(NONE,PENDING);
        boolean full=false;
        try{
            try(FileChannel channel=FileChannel.open(fill,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
                ByteBuffer page=ByteBuffer.allocate(4096);
                long written=0;
                while(written<=fs.getTotalSpace()+4096){
                    page.clear();
                    while(page.hasRemaining())written+=channel.write(page);
                }
            }catch(IOException e){require(noSpace(e),"Unexpected fill failure: "+e);full=true;}
            require(full,"No actual ENOSPC from kernel");
            boolean failed=false;
            try{s.applyIncubation();}
            catch(IOException e){require(noSpace(e),"Unexpected apply failure: "+e);failed=true;}
            require(failed,"Restore write unexpectedly succeeded on full filesystem");
            require(s.readPhase()==PENDING,"Journal not pending");
            require(incHash.equals(RestoreFileStorage.hash(inc))&&farmHash.equals(RestoreFileStorage.hash(farm)),"Live files changed");
            require(Files.exists(work.resolve("old-inc.db"))&&Files.exists(work.resolve("old-farm.db")),"Rollback source lost");
            System.out.println("Actual kernel ENOSPC during restore temp write; PENDING and both live files intact");
        }finally{Files.deleteIfExists(fill);}
        require(new RestoreProtocol(new RestoreFileStorage(root,port,null)).recover()==OLD_GENERATION,"Recovery failed");
        require(s.readPhase()==NONE&&incHash.equals(RestoreFileStorage.hash(inc))&&farmHash.equals(RestoreFileStorage.hash(farm))&&Arrays.equals(managed[0],new byte[]{1}),"Old generation mismatch");
        System.out.println("R1 bounded Linux tmpfs ENOSPC: PASS");
    }
}
