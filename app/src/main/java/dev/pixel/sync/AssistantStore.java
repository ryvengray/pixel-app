package dev.pixel.sync;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

/** The phone is the authoritative copy of messages and long-term memories. */
public final class AssistantStore extends SQLiteOpenHelper {
    public static final class Message { public final String role, content, groupId, status; public final long id, createdAt; Message(String r,String c,long t){this(0,r,c,t,"", "sent");} Message(long id,String r,String c,long t,String g,String s){this.id=id;role=r;content=c;createdAt=t;groupId=g;status=s;} }
    public static final class Memory { public final String id,category,fact,source,updatedAt; public final double confidence; public final int revision; public final boolean deleted;
        Memory(String id,String c,String f,double confidence,String source,int revision,boolean deleted,String updatedAt){this.id=id;category=c;fact=f;this.confidence=confidence;this.source=source;this.revision=revision;this.deleted=deleted;this.updatedAt=updatedAt;} }
    public AssistantStore(Context context) { super(context, "greta.db", null, 2); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE message(id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL, group_id TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'sent', external_id TEXT UNIQUE)");
        db.execSQL("CREATE TABLE memory(id TEXT PRIMARY KEY, category TEXT NOT NULL, fact TEXT NOT NULL, confidence REAL NOT NULL, source TEXT, revision INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        if(oldVersion<2){
            db.execSQL("ALTER TABLE message ADD COLUMN group_id TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE message ADD COLUMN status TEXT NOT NULL DEFAULT 'sent'");
            db.execSQL("ALTER TABLE message ADD COLUMN external_id TEXT");
            db.execSQL("CREATE UNIQUE INDEX message_external ON message(external_id)");
        }
    }
    public void addMessage(String role,String content){ addMessage(role,content,UUID.randomUUID().toString(),"sent",null,System.currentTimeMillis()); }
    public long addMessage(String role,String content,String group,String status,String external,long timestamp){
        ContentValues v=new ContentValues();v.put("role",role);v.put("content",content);v.put("created_at",timestamp);v.put("group_id",group);v.put("status",status);if(external!=null)v.put("external_id",external);
        return getWritableDatabase().insertWithOnConflict("message",null,v,SQLiteDatabase.CONFLICT_IGNORE);
    }
    public void setStatus(String group,String status){ContentValues v=new ContentValues();v.put("status",status);getWritableDatabase().update("message",v,"group_id=? AND role='user'",new String[]{group});}
    public List<Message> recentMessages(int count){return messagesBefore(Long.MAX_VALUE,count);}
    public List<Message> messagesBefore(long before,int count){
        ArrayList<Message> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,role,content,created_at,group_id,status FROM message WHERE id < ? ORDER BY id DESC LIMIT ?",new String[]{String.valueOf(before),String.valueOf(count)})){
            while(c.moveToNext())result.add(new Message(c.getLong(0),c.getString(1),c.getString(2),c.getLong(3),c.getString(4),c.getString(5)));
        } Collections.reverse(result);return result;
    }
    public List<Message> conversation(int count){ArrayList<Message> out=new ArrayList<>();for(Message m:recentMessages(count))if(m.role.equals("user")||m.role.equals("assistant"))out.add(m);return out;}
    public Message externalMessage(String external){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,role,content,created_at,group_id,status FROM message WHERE external_id=?",new String[]{external})){return c.moveToFirst()?new Message(c.getLong(0),c.getString(1),c.getString(2),c.getLong(3),c.getString(4),c.getString(5)):null;}
    }
    public List<Memory> memories(boolean includeDeleted){
        ArrayList<Memory> result=new ArrayList<>(); String where=includeDeleted?"":" WHERE deleted=0"; Cursor c=getReadableDatabase().rawQuery("SELECT id,category,fact,confidence,source,revision,deleted,updated_at FROM memory"+where+" ORDER BY updated_at DESC",null);
        while(c.moveToNext()) result.add(readMemory(c)); c.close(); return result;
    }
    private Memory readMemory(Cursor c){ return new Memory(c.getString(0),c.getString(1),c.getString(2),c.getDouble(3),c.getString(4),c.getInt(5),c.getInt(6)!=0,c.getString(7)); }
    public void upsertMemory(Memory m){
        ContentValues v=new ContentValues();v.put("id",m.id);v.put("category",m.category);v.put("fact",m.fact);v.put("confidence",m.confidence);v.put("source",m.source);v.put("revision",m.revision);v.put("deleted",m.deleted?1:0);v.put("updated_at",m.updatedAt);
        getWritableDatabase().insertWithOnConflict("memory",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    public Memory updateMemory(String id,String category,String fact,double confidence,String source,boolean deleted){
        Memory old=null; for(Memory value:memories(true))if(value.id.equals(id)){old=value;break;} int revision=old==null?1:old.revision+1;
        Memory m=new Memory(id,category,fact,confidence,source,revision,deleted,java.time.Instant.now().toString());upsertMemory(m);return m;
    }
}
