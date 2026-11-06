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
    public AssistantStore(Context context) { this(context,"greta.db"); }
    AssistantStore(Context context,String name){super(context,name,null,7);}
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE message(id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL, group_id TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'sent', external_id TEXT UNIQUE)");
        db.execSQL("CREATE TABLE memory(id TEXT PRIMARY KEY, category TEXT NOT NULL, fact TEXT NOT NULL, confidence REAL NOT NULL, source TEXT, revision INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL)");
        createTopics(db);
        createNotifications(db);
        LifeRecords.create(db);
    }
    private void createNotifications(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS notification(id TEXT PRIMARY KEY, title TEXT NOT NULL, body TEXT NOT NULL, created_at INTEGER NOT NULL, read INTEGER NOT NULL DEFAULT 0)");
    }
    private void createTopics(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS topic(id TEXT PRIMARY KEY,payload TEXT NOT NULL,revision INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS topic_outbox(sequence INTEGER PRIMARY KEY AUTOINCREMENT,operation_id TEXT NOT NULL UNIQUE,topic_id TEXT NOT NULL,payload TEXT NOT NULL,error TEXT)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        if(oldVersion<2){
            db.execSQL("ALTER TABLE message ADD COLUMN group_id TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE message ADD COLUMN status TEXT NOT NULL DEFAULT 'sent'");
            db.execSQL("ALTER TABLE message ADD COLUMN external_id TEXT");
            db.execSQL("CREATE UNIQUE INDEX message_external ON message(external_id)");
        }
        if(oldVersion<3)createTopics(db);
        if(oldVersion<4)LifeRecords.create(db);
        if(oldVersion<5)LifeRecords.createImages(db);
        if(oldVersion>=4&&oldVersion<6){
            boolean hasLocation=false;try(Cursor columns=db.rawQuery("PRAGMA table_info(life_record)",null)){while(columns.moveToNext())if("location".equals(columns.getString(1)))hasLocation=true;}
            if(!hasLocation)db.execSQL("ALTER TABLE life_record ADD COLUMN location TEXT");
        }
        if(oldVersion<7){
            createNotifications(db);
            // Old clients wrote each inbox item as an assistant message and a tool card.
            db.execSQL("INSERT OR IGNORE INTO notification(id,title,body,created_at) "
                    + "SELECT substr(m.external_id,7), "
                    + "COALESCE(NULLIF(substr(card.content,1,instr(card.content,char(10))-1),''),'Greta 提醒你'), "
                    + "m.content,m.created_at FROM message m LEFT JOIN message card "
                    + "ON card.external_id='inbox-card:' || substr(m.external_id,7) "
                    + "WHERE m.external_id GLOB 'inbox:*'");
        }
    }
    public void addMessage(String role,String content){ addMessage(role,content,UUID.randomUUID().toString(),"sent",null,System.currentTimeMillis()); }
    public long addMessage(String role,String content,String group,String status,String external,long timestamp){
        ContentValues v=new ContentValues();v.put("role",role);v.put("content",content);v.put("created_at",timestamp);v.put("group_id",group);v.put("status",status);if(external!=null)v.put("external_id",external);
        return getWritableDatabase().insertWithOnConflict("message",null,v,SQLiteDatabase.CONFLICT_IGNORE);
    }
    public void setStatus(String group,String status){ContentValues v=new ContentValues();v.put("status",status);getWritableDatabase().update("message",v,"group_id=? AND role='user'",new String[]{group});}
    public List<Message> recentMessages(int count){return messagesBefore(Long.MAX_VALUE,count);}
    private static final String CHAT_MESSAGES =
            " AND group_id NOT GLOB 'inbox:*'"
            + " AND (external_id IS NULL OR (external_id NOT GLOB 'inbox:*' AND external_id NOT GLOB 'inbox-card:*'))"
            + " AND (role IN ('user','assistant') OR (role='tool' AND"
            + " (group_id GLOB 'reply:*' OR content NOT IN ('提醒已完成','已延后 15 分钟'))))";

    public List<Message> messagesBefore(long before,int count){return messagesBefore(before,count,"");}
    public List<Message> chatBefore(long before,int count){return messagesBefore(before,count,CHAT_MESSAGES);}
    private List<Message> messagesBefore(long before,int count,String filter){
        List<Message> result=readMessages("id < ?"+filter+" ORDER BY id DESC LIMIT ?",
                String.valueOf(before),String.valueOf(count));
        Collections.reverse(result);
        return result;
    }
    List<Message> chatAfter(long after){
        return readMessages("id > ?"+CHAT_MESSAGES+" ORDER BY id",String.valueOf(after));
    }
    Message latestChatMessage(String role){
        List<Message> result=readMessages("role=?"+CHAT_MESSAGES+" ORDER BY id DESC LIMIT 1",role);
        return result.isEmpty()?null:result.get(0);
    }
    private List<Message> readMessages(String selection,String... args){
        List<Message> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT id,role,content,created_at,group_id,status FROM message WHERE "+selection,args)){
            while(c.moveToNext())result.add(new Message(c.getLong(0),c.getString(1),c.getString(2),c.getLong(3),c.getString(4),c.getString(5)));
        }
        return result;
    }
    public List<Message> conversation(int count){
        return messagesBefore(Long.MAX_VALUE,count,CHAT_MESSAGES+" AND role IN ('user','assistant')");
    }

    static final class InboxItem {
        final String id,title,body;
        final long createdAt;
        final boolean read;

        InboxItem(String id,String title,String body,long createdAt,boolean read){
            this.id=id;this.title=title;this.body=body;this.createdAt=createdAt;this.read=read;
        }
    }
    void saveNotifications(List<InboxItem> items){
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            for(InboxItem item:items){
                ContentValues values=new ContentValues();
                values.put("title",item.title);values.put("body",item.body);values.put("created_at",item.createdAt);
                if(db.update("notification",values,"id=?",new String[]{item.id})==0){
                    values.put("id",item.id);
                    db.insertOrThrow("notification",null,values);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
    List<InboxItem> notifications(){
        List<InboxItem> items=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,title,body,created_at,read FROM notification ORDER BY created_at DESC,rowid DESC",null)){
            while(c.moveToNext())items.add(new InboxItem(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getInt(4)!=0));
        }
        return items;
    }
    void readNotification(String id){
        ContentValues values=new ContentValues();values.put("read",1);
        getWritableDatabase().update("notification",values,"id=?",new String[]{id});
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
