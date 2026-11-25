package org.nasedka.app;

import android.content.SharedPreferences;
import java.io.*;
import java.util.Arrays;
import java.util.Map;

/** Canonical encoding of managed fields only; unrelated preferences are untouched.
 * No implicit defaults: absent is distinct from present. Not wired to production. */
final class RestoreSettingsPort implements RestoreFileStorage.SettingsPort {
    private static final String[] KEYS={"checks","turns","sprays","nest_checks","notifications",
            "next_alarm_at","last_delivered_alarm_at","farm_task_next","farm_task_delivered_at"};
    private static final int MAGIC=0x4e535032;
    private final SharedPreferences prefs;
    RestoreSettingsPort(SharedPreferences prefs){this.prefs=java.util.Objects.requireNonNull(prefs);}
    @Override public byte[] read() throws IOException {return encode(prefs.getAll());}

    static byte[] encode(Map<String,?> values) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(bytes)){
            out.writeInt(MAGIC);
            for(int i=0;i<KEYS.length;i++){
                Object value=values.get(KEYS[i]);
                if(!values.containsKey(KEYS[i])){out.writeBoolean(false);continue;}
                out.writeBoolean(true);
                if(i<4){
                    if(!(value instanceof String)||((String)value).length()>100)throw new IOException("Invalid schedule");
                    out.writeUTF((String)value);
                }else if(i==4){
                    if(!(value instanceof Boolean))throw new IOException("Invalid notification flag");
                    out.writeBoolean((Boolean)value);
                }else{
                    if(!(value instanceof Long))throw new IOException("Invalid scheduler timestamp");
                    out.writeLong((Long)value);
                }
            }
        }
        return bytes.toByteArray();
    }
    @Override public void write(byte[] bytes) throws IOException {
        if(bytes==null||bytes.length>16384)throw new IOException("Invalid settings snapshot");
        java.util.Map<String,Object> values=new java.util.HashMap<>();
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))){
            if(in.readInt()!=MAGIC)throw new IOException("Invalid settings version");
            for(int i=0;i<KEYS.length;i++)if(in.readBoolean()){
                Object value;
                if(i<4)value=in.readUTF();
                else if(i==4)value=in.readBoolean();
                else value=in.readLong();
                values.put(KEYS[i],value);
            }
            if(in.read()!=-1||!Arrays.equals(bytes,encode(values)))throw new IOException("Noncanonical settings snapshot");
        }
        SharedPreferences.Editor edit=prefs.edit();
        for(int i=0;i<KEYS.length;i++){
            String key=KEYS[i];Object value=values.get(key);
            if(!values.containsKey(key))edit.remove(key);
            else if(i<4)edit.putString(key,(String)value);
            else if(i==4)edit.putBoolean(key,(Boolean)value);
            else edit.putLong(key,(Long)value);
        }
        if(!edit.commit())throw new IOException("Settings commit failed");
    }
}
