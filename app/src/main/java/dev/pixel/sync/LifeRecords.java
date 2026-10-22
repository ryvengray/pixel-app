package dev.pixel.sync;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Authoritative phone records. Money uses decimal strings validated as integer minor units. */
public final class LifeRecords implements AutoCloseable {
    private final AssistantStore store;
    private final Context context;
    public LifeRecords(Context context){this(context,"greta.db");}
    LifeRecords(Context context,String database){this.context=context.getApplicationContext();store=new AssistantStore(context,database);}
    static void create(SQLiteDatabase db){db.execSQL("CREATE TABLE IF NOT EXISTS life_record(id TEXT PRIMARY KEY,day TEXT NOT NULL,timezone TEXT NOT NULL,payload TEXT NOT NULL,original TEXT NOT NULL,created INTEGER NOT NULL,revision INTEGER NOT NULL DEFAULT 1,deleted INTEGER NOT NULL DEFAULT 0,exported INTEGER NOT NULL DEFAULT 0,synced INTEGER NOT NULL DEFAULT 0,sync_target INTEGER NOT NULL DEFAULT 0,export_hash TEXT NOT NULL DEFAULT '')");createImages(db);}
    static void createImages(SQLiteDatabase db){db.execSQL("CREATE TABLE IF NOT EXISTS life_record_image(record_id TEXT NOT NULL,image_id TEXT NOT NULL UNIQUE,name TEXT NOT NULL,mime TEXT NOT NULL,sha256 TEXT NOT NULL,PRIMARY KEY(record_id,image_id))");}
    public static final class Record {
        public String id,day,timezone,original,hash;public JSONObject payload;
        public long created;public int revision,exported,synced;public boolean deleted;
        public final List<LifeImages.Image> images=new ArrayList<>();
        public String state(){return deleted?"已删除":exported<revision?"待导出":synced<revision?"待同步":"已同步";}
    }
    public static boolean valid(JSONObject args){
        try{
            if(args==null||args.length()<2||args.length()>3||!(args.opt("content") instanceof String)||args.getString("content").trim().isEmpty()||args.getString("content").length()>4000)return false;
            for(Iterator<String> it=args.keys();it.hasNext();)if(!Arrays.asList("content","date","items").contains(it.next()))return false;
            if(args.has("date")&&(!(args.opt("date") instanceof String)||!LocalDate.parse(args.getString("date")).toString().equals(args.getString("date"))))return false;
            JSONArray items=args.getJSONArray("items");if(items.length()>30)return false;
            for(int i=0;i<items.length();i++){
                JSONObject item=items.getJSONObject(i);if(item.length()!=4)return false;
                for(String key:Arrays.asList("amount","currency","kind","category"))if(!(item.opt(key) instanceof String))return false;
                String amount=item.getString("amount"),currency=item.getString("currency");
                if(!amount.matches("[0-9]{1,9}(\\.[0-9]{1,2})?")||!Arrays.asList("CNY","USD","EUR","JPY","HKD","GBP").contains(currency))return false;
                BigDecimal value=new BigDecimal(amount);if(value.signum()<=0)return false;
                value.movePointRight(currency.equals("JPY")?0:2).longValueExact();
                if(!Arrays.asList("expense","income").contains(item.getString("kind"))||item.getString("category").trim().isEmpty()||item.getString("category").length()>80)return false;
            }
            return true;
        }catch(Exception invalid){return false;}
    }
    public synchronized void save(String id,JSONObject args,String original)throws Exception{
        if(id==null||id.isEmpty()||id.length()>100||!valid(args))throw new IllegalArgumentException("记录内容、日期或金额不完整，请重新说明");
        JSONObject payload=new JSONObject(args.toString());String day=payload.optString("date",LocalDate.now().toString());payload.put("date",day);
        ContentValues v=new ContentValues();v.put("id",id);v.put("day",day);v.put("timezone",ZoneId.systemDefault().getId());v.put("payload",payload.toString());v.put("original",original);v.put("created",System.currentTimeMillis());
        // First committed result wins, including after edits/deletion and network retries.
        store.getWritableDatabase().insertWithOnConflict("life_record",null,v,SQLiteDatabase.CONFLICT_IGNORE);
    }
    public void update(String id,JSONObject payload,boolean deleted)throws Exception{
        if(!valid(payload))throw new IllegalArgumentException("请检查内容、日期和金额");
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();try{
            ContentValues v=new ContentValues();v.put("payload",payload.toString());v.put("day",payload.optString("date",LocalDate.now().toString()));v.put("deleted",deleted?1:0);
            if(db.update("life_record",v,"id=?",new String[]{id})!=1)throw new IllegalArgumentException("记录不存在");
            db.execSQL("UPDATE life_record SET revision=revision+1 WHERE id=?",new Object[]{id});db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    synchronized void saveWithImage(String id,JSONObject payload,String original,LifeImages.Image image)throws Exception{
        if(id==null||id.isEmpty()||id.length()>100||!valid(payload))throw new IllegalArgumentException("请检查记录内容和日期");
        if(find(id)!=null)return;
        LifeImages.retain(context,image);SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try{
            if(find(id)!=null){db.setTransactionSuccessful();return;}
            save(id,payload,original);ContentValues values=new ContentValues();values.put("record_id",id);values.put("image_id",image.fileName);values.put("name",image.name);values.put("mime",image.mime);values.put("sha256",image.hash);db.insertOrThrow("life_record_image",null,values);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public Record find(String id){
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT id,day,timezone,payload,original,created,revision,deleted,exported,synced,export_hash FROM life_record WHERE id=?",new String[]{id})){return c.moveToFirst()?readRecord(c):null;}
    }
    private Record readRecord(Cursor c){
        Record r=new Record();r.id=c.getString(0);r.day=c.getString(1);r.timezone=c.getString(2);try{r.payload=new JSONObject(c.getString(3));}catch(JSONException e){throw new IllegalStateException(e);}r.original=c.getString(4);r.created=c.getLong(5);r.revision=c.getInt(6);r.deleted=c.getInt(7)!=0;r.exported=c.getInt(8);r.synced=c.getInt(9);r.hash=c.getString(10);
        try(Cursor images=store.getReadableDatabase().rawQuery("SELECT image_id,name,mime,sha256 FROM life_record_image WHERE record_id=? ORDER BY image_id",new String[]{r.id})){while(images.moveToNext())r.images.add(new LifeImages.Image(images.getString(0),images.getString(1),images.getString(2),images.getString(3)));}return r;
    }
    public List<Record> all(boolean deleted){
        List<Record> rows=new ArrayList<>();try(Cursor c=store.getReadableDatabase().rawQuery("SELECT id,day,timezone,payload,original,created,revision,deleted,exported,synced,export_hash FROM life_record"+(deleted?"":" WHERE deleted=0")+" ORDER BY day DESC,created DESC",null)){
            while(c.moveToNext())rows.add(readRecord(c));
        }return rows;
    }
    public void exported(Record r,String hash){ContentValues v=new ContentValues();v.put("exported",r.revision);v.put("export_hash",hash);store.getWritableDatabase().update("life_record",v,"id=?",new String[]{r.id});}
    public boolean pendingExport(){for(Record r:all(true))if(r.exported<r.revision)return true;return false;}
    public boolean pendingSync(){for(Record r:all(true))if(r.synced<r.exported)return true;return false;}
    public void beginSync(){store.getWritableDatabase().execSQL("UPDATE life_record SET sync_target=exported");}
    public void endSync(boolean success){store.getWritableDatabase().execSQL(success?"UPDATE life_record SET synced=MAX(synced,sync_target),sync_target=0":"UPDATE life_record SET sync_target=0");}
    public static String summary(JSONObject args){
        StringBuilder text=new StringBuilder();JSONArray items=args.optJSONArray("items");if(items==null)return "";
        for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(item==null)continue;if(text.length()>0)text.append("，");text.append(item.optString("category")).append(" · ").append(item.optString("kind").equals("income")?"收入 ":"支出 ").append(item.optString("amount")).append(" ").append(item.optString("currency"));}return text.toString();
    }
    public static String markdown(Record r){
        String text="# "+r.day+"\n\n"+(r.deleted?"> 此记录已在 Greta 中删除。\n\n":r.payload.optString("content")+"\n\n");
        if(!r.deleted){JSONArray items=r.payload.optJSONArray("items");if(items!=null)for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);text+="- "+(item.optString("kind").equals("income")?"收入":"支出")+" · "+item.optString("category").replace("\n"," ")+" · "+item.optString("amount")+" "+item.optString("currency")+"\n";}}
        if(!r.deleted)for(LifeImages.Image image:r.images)text+="\n![](图片/"+image.fileName+")\n";
        return text+"\n<!-- Greta record "+r.id.replace("--","")+" revision "+r.revision+"; timezone "+r.timezone+" -->\n";
    }
    @Override public void close(){store.close();}
}
