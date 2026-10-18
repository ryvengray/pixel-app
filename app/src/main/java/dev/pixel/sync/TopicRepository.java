package dev.pixel.sync;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.io.IOException;
import java.util.UUID;

/** Topic projection and ordered edits share the phone database. Edits survive process death. */
public final class TopicRepository implements AutoCloseable {
    private final AssistantStore store;
    private final Context context;
    private static final Object SYNC_LOCK=new Object();
    public TopicRepository(Context context){this.context=context.getApplicationContext();store=new AssistantStore(this.context);}
    public JSONArray all()throws JSONException{
        JSONArray rows=new JSONArray();try(Cursor c=store.getReadableDatabase().rawQuery("SELECT payload FROM topic ORDER BY id",null)){while(c.moveToNext())rows.put(new JSONObject(c.getString(0)));}return rows;
    }
    public JSONArray suppressed()throws JSONException{JSONArray ids=new JSONArray();JSONArray rows=all();for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!"open".equals(row.optString("state")))ids.put(row.getString("id"));}return ids;}
    private JSONObject find(String id)throws JSONException{
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT payload FROM topic WHERE id=?",new String[]{id})){if(c.moveToFirst())return new JSONObject(c.getString(0));}return null;
    }
    private void put(SQLiteDatabase db,JSONObject value)throws JSONException{
        ContentValues v=new ContentValues();v.put("id",value.getString("id"));v.put("payload",value.toString());v.put("revision",value.getInt("revision"));db.insertWithOnConflict("topic",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    private boolean dirty(SQLiteDatabase db,String id){try(Cursor c=db.rawQuery("SELECT 1 FROM topic_outbox WHERE topic_id=? LIMIT 1",new String[]{id})){return c.moveToFirst();}}
    public void edit(String id,JSONObject fields)throws JSONException{
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();try{
            JSONObject current=find(id);if(current==null)throw new JSONException("话题不存在");
            JSONObject change=new JSONObject(fields.toString());change.put("operation_id",UUID.randomUUID().toString());change.put("base_revision",current.getInt("revision"));
            java.util.Iterator<String> keys=fields.keys();while(keys.hasNext()){String name=keys.next();current.put(name,fields.get(name));}
            current.put("revision",current.getInt("revision")+1);
            if(fields.has("followup_at")&&"allowed".equals(current.optString("consent")))current.put("followup_status","pending");
            if(!"allowed".equals(current.optString("consent"))||!"open".equals(current.optString("state"))){current.put("followup_status","none");current.put("followup_at",JSONObject.NULL);current.put("notify",false);}
            if("forgotten".equals(current.optString("state"))){current.put("title","已忘记的话题");current.put("source_quote","");current.put("source_request_id","");current.put("consent","declined");}
            put(db,current);ContentValues job=new ContentValues();job.put("operation_id",change.getString("operation_id"));job.put("topic_id",id);job.put("payload",change.toString());db.insertOrThrow("topic_outbox",null,job);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public boolean blocksFollowup(String id)throws JSONException{JSONObject row=find(id);return row!=null&&(!"open".equals(row.optString("state"))||"declined".equals(row.optString("consent")));}
    public static boolean blocksIncoming(Context context,String messageId){if(!messageId.startsWith("topic:"))return false;String[] parts=messageId.split(":");if(parts.length<3)return false;try(TopicRepository repository=new TopicRepository(context)){return repository.blocksFollowup(parts[1]);}catch(Exception e){return false;}}
    public boolean pending(String id){return dirty(store.getReadableDatabase(),id);}
    public boolean conflict(String id){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT 1 FROM topic_outbox WHERE topic_id=? AND error IS NOT NULL LIMIT 1",new String[]{id})){return c.moveToFirst();}}
    private void pull()throws Exception{
        JSONArray remote=AssistantApi.topics(context);SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();try{
            for(int i=0;i<remote.length();i++){JSONObject row=remote.getJSONObject(i);String id=row.getString("id");if(dirty(db,id))continue;JSONObject old=find(id);if(old==null||row.getInt("revision")>=old.getInt("revision"))put(db,row);}
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public void sync()throws Exception{
        synchronized(SYNC_LOCK){
            boolean conflict=false;
            while(true){String operation,id,payload;boolean blocked;
                try(Cursor c=store.getReadableDatabase().rawQuery("SELECT operation_id,topic_id,payload,error FROM topic_outbox WHERE error IS NULL AND topic_id NOT IN (SELECT topic_id FROM topic_outbox WHERE error IS NOT NULL) ORDER BY sequence LIMIT 1",null)){
                    if(!c.moveToFirst())break;operation=c.getString(0);id=c.getString(1);payload=c.getString(2);blocked=!c.isNull(3);
                }
                if(blocked){conflict=true;break;}
                JSONObject remote;
                try{remote=AssistantApi.updateTopic(context,id,new JSONObject(payload));}
                catch(AssistantApi.ApiException e){if(e.status==409||e.status==410||e.status==422){ContentValues v=new ContentValues();v.put("error","conflict");store.getWritableDatabase().update("topic_outbox",v,"operation_id=?",new String[]{operation});conflict=true;continue;}throw e;}
                SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();try{
                    db.delete("topic_outbox","operation_id=?",new String[]{operation});if(!dirty(db,id))put(db,remote);db.setTransactionSuccessful();
                }finally{db.endTransaction();}
            }
            try(Cursor c=store.getReadableDatabase().rawQuery("SELECT 1 FROM topic_outbox WHERE error IS NOT NULL LIMIT 1",null)){conflict=conflict||c.moveToFirst();}
            pull();if(conflict)throw new IOException("有话题同步冲突，请选择保留哪个版本");
        }
    }
    public void resolve(String id,boolean keepLocal)throws Exception{
        synchronized(SYNC_LOCK){JSONObject local=find(id),remote=null;JSONArray rows=AssistantApi.topics(context);for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).optString("id")))remote=rows.getJSONObject(i);
            if(remote==null)throw new IOException("服务端话题不存在");
            if(keepLocal&&"forgotten".equals(remote.optString("state")))throw new IOException("这件事已经被忘记，请使用服务端版本");
            SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();try{db.delete("topic_outbox","topic_id=?",new String[]{id});put(db,remote);
                if(keepLocal&&local!=null){JSONObject changes=new JSONObject();for(String field:new String[]{"title","state","consent","followup_at","notify"})if(local.has(field))changes.put(field,local.get(field));edit(id,changes);}db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }
    }
    public static void synchronize(Context context)throws Exception{try(TopicRepository repository=new TopicRepository(context)){repository.sync();}}
    public void close(){store.close();}
}
