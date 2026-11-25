package org.nasedka.app;

import android.content.SharedPreferences;
import java.io.*;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

/** Canonical encoding of managed fields only; unrelated preferences are untouched.
 * No implicit defaults: absent is distinct from present. Not wired to production. */
final class RestoreSettingsPort implements RestoreFileStorage.SettingsPort {
    private static final String[] KEYS={"checks","turns","sprays","nest_checks","notifications",
            "next_alarm_at","last_delivered_alarm_at","farm_task_next","farm_task_delivered_at"};
    private static final int MAGIC=0x4e535032;
    private static final int GENERATION_MAGIC=0x4e535033;
    static final String GENERATION="data_generation";
    // Process-local quarantine. Strong identity references are retained only
    // for ambiguous writes; no same-process retry by recreating the adapter.
    // Production must use the one application-scoped preferences instance
    // under the coordinator; different instances/processes are not covered.
    private static final java.util.Set<SharedPreferences> UNCERTAIN=
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final SharedPreferences prefs;
    private void requireCertain()throws IOException {
        synchronized(UNCERTAIN){
            if(UNCERTAIN.contains(prefs))throw new IOException("Settings outcome uncertain; restart required");
        }
    }
    private void markUncertain()throws IOException {
        synchronized(UNCERTAIN){
            if(!UNCERTAIN.add(prefs))throw new IOException("Settings outcome uncertain; restart required");
        }
    }
    private void confirmWrite(){
        synchronized(UNCERTAIN){UNCERTAIN.remove(prefs);}
    }
    RestoreSettingsPort(SharedPreferences prefs){this.prefs=java.util.Objects.requireNonNull(prefs);}
    @Override public byte[] read() throws IOException {requireCertain();return encode(prefs.getAll());}

    static UUID generation(Map<String,?> values)throws IOException {
        if(!values.containsKey(GENERATION))return null;
        Object raw=values.get(GENERATION);
        if(!(raw instanceof String))throw new IOException("Invalid data generation");
        try{
            UUID uuid=UUID.fromString((String)raw);
            if(!uuid.toString().equals(raw))throw new IllegalArgumentException("Noncanonical UUID");
            return uuid;
        }catch(IllegalArgumentException e){throw new IOException("Invalid data generation",e);}
    }
    UUID generation()throws IOException{requireCertain();return generation(prefs.getAll());}

    /** Called only after startup has resolved journals and verified the pair. */
    UUID initializeGeneration()throws IOException {
        requireCertain();
        Map<String,Object> values=new HashMap<>(prefs.getAll());
        encode(values); // validate all existing managed values before editing
        UUID existing=generation(values);
        if(existing!=null)return existing;
        UUID issued=UUID.randomUUID();
        values.put(GENERATION,issued.toString());
        write(encode(values));
        return issued;
    }

    /** Caller must first validate the public backup; never import its generation. */
    static byte[] target(Map<String,?> validatedPublicSettings, UUID previous)throws IOException {
        if(previous==null)throw new IOException("Persistent generation not initialized");
        Map<String,Object> next=new HashMap<>();
        for(int i=0;i<5;i++){
            String key=KEYS[i];
            if(!validatedPublicSettings.containsKey(key))throw new IOException("Missing validated setting");
            next.put(key,validatedPublicSettings.get(key));
        }
        UUID fresh;
        do{fresh=UUID.randomUUID();}while(fresh.equals(previous));
        next.put(GENERATION,fresh.toString());
        // Managed scheduler timestamps absent by design; no external UUID accepted.
        return encode(next);
    }

    static byte[] encode(Map<String,?> values) throws IOException {
        UUID generation=generation(values);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(bytes)){
            out.writeInt(generation==null?MAGIC:GENERATION_MAGIC);
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
            if(generation!=null)out.writeUTF(generation.toString());
        }
        return bytes.toByteArray();
    }
    @Override public void write(byte[] bytes) throws IOException {
        requireCertain();
        if(bytes==null||bytes.length>16384)throw new IOException("Invalid settings snapshot");
        java.util.Map<String,Object> values=new java.util.HashMap<>();
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))){
            int magic=in.readInt();
            if(magic!=MAGIC && magic!=GENERATION_MAGIC)throw new IOException("Invalid settings version");
            for(int i=0;i<KEYS.length;i++)if(in.readBoolean()){
                Object value;
                if(i<4)value=in.readUTF();
                else if(i==4)value=in.readBoolean();
                else value=in.readLong();
                values.put(KEYS[i],value);
            }
            if(magic==GENERATION_MAGIC)values.put(GENERATION,in.readUTF());
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
        if(values.containsKey(GENERATION))edit.putString(GENERATION,(String)values.get(GENERATION));
        else edit.remove(GENERATION); // historical snapshot has explicitly absent generation
        // commit(false) may already have changed the in-memory map. Never
        // treat that map, or a later no-op commit, as a verified disk outcome.
        // Any exception/false/mismatch leaves the quarantine set until restart.
        markUncertain();
        if(!edit.commit())throw new IOException("Settings commit failed");
        if(!Arrays.equals(bytes,encode(prefs.getAll())))throw new IOException("Settings verification failed");
        confirmWrite();
    }
}
