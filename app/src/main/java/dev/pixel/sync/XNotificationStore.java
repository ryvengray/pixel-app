package dev.pixel.sync;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.os.Bundle;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Device-local opt-in queue. No other application's notification payload is retained. */
public final class XNotificationStore extends SQLiteOpenHelper {
    static final String PREFS="x_notification_collection";
    static final String DEFAULT_AUTHORS="elonmusk|Elon Musk\nkarpathy|Andrej Karpathy";
    public XNotificationStore(Context c){super(c,"x-notifications.db",null,1);}
    @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE excerpt(id TEXT PRIMARY KEY,author TEXT,text TEXT,posted_at INTEGER,uploaded INTEGER DEFAULT 0)");}
    @Override public void onUpgrade(SQLiteDatabase db,int old,int next){}
    static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,0);}
    static boolean access(Context c){return c.getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(new ComponentName(c,XNotificationListener.class));}
    static boolean enabled(Context c){return prefs(c).getBoolean("enabled",false);}
    static synchronized void refreshPermission(Context c){
        SharedPreferences p=prefs(c);boolean granted=access(c);
        String zone=java.util.TimeZone.getDefault().getID();
        if(granted!=p.getBoolean("access",false)||!zone.equals(p.getString("zone", ""))){
            SharedPreferences.Editor edit=p.edit().putString("zone",zone).putBoolean("access",granted).putLong("revision",Math.max(System.currentTimeMillis(),p.getLong("revision",0)+1));
            if(granted&&!p.getBoolean("access",false))edit.putLong("enabled_since",System.currentTimeMillis());edit.commit();
        }
    }
    static LinkedHashMap<String,String> authors(String raw){
        LinkedHashMap<String,String> out=new LinkedHashMap<>();
        for(String line:raw.split("\n")){if(line.trim().isEmpty())continue;String[] parts=line.trim().split("\\|",2);String handle=parts[0].trim().replaceFirst("^@","").toLowerCase(Locale.ROOT);
            if(!handle.matches("[a-z0-9_]{1,15}")||out.size()>=30)throw new IllegalArgumentException("每行填写 用户名|显示名称，最多30个账号");
            String name=parts.length>1?parts[1].trim():handle;if(name.length()>100)throw new IllegalArgumentException("显示名称过长");out.put(handle,name);
        }
        if(out.isEmpty())throw new IllegalArgumentException("请至少填写一个关注账号");return out;
    }
    static synchronized void configure(Context c,boolean enabled,int hour,String raw,boolean purge){
        authors(raw);SharedPreferences p=prefs(c);
        if(enabled&&!p.getBoolean("enabled",false)){
            p.edit().putLong("enabled_since",System.currentTimeMillis())
                    .remove("last_seen").remove("last_match").remove("capture_state").commit();
        }
        p.edit().putBoolean("enabled",enabled).putInt("hour",hour).putString("authors",raw).putBoolean("purge",purge)
            .putLong("revision",Math.max(System.currentTimeMillis(),p.getLong("revision",0)+1)).commit();
        try(XNotificationStore db=new XNotificationStore(c)){if(!enabled||purge)db.getWritableDatabase().delete("excerpt",null,null);else db.pruneAuthors(authors(raw).keySet());}
        XNotificationSync.schedule(c,true);
    }
    private void pruneAuthors(Set<String> allowed){try(Cursor rows=getReadableDatabase().rawQuery("SELECT DISTINCT author FROM excerpt",null)){while(rows.moveToNext())if(!allowed.contains(rows.getString(0)))getWritableDatabase().delete("excerpt","author=?",new String[]{rows.getString(0)});}}
    static JSONObject extract(String packageName,Notification notification,long posted,Map<String,String> allowed)throws Exception{
        if(!"com.twitter.android".equals(packageName)||notification==null)return null;
        Bundle extra=notification.extras;if(extra==null)return null;
        boolean reply=false;if(notification.actions!=null)for(Notification.Action action:notification.actions)if(action.getRemoteInputs()!=null&&action.getRemoteInputs().length>0)reply=true;
        CharSequence title=extra.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence content=extra.getCharSequence(Notification.EXTRA_BIG_TEXT);if(content==null||content.length()==0)content=extra.getCharSequence(Notification.EXTRA_TEXT);
        String text=content==null?null:content.toString().trim();
        CharSequence expanded=extra.getCharSequence(Notification.EXTRA_TITLE_BIG);
        if(XNotificationRules.privateText(title)||XNotificationRules.privateText(expanded))return null;
        boolean summary=(notification.flags&Notification.FLAG_GROUP_SUMMARY)!=0;
        boolean messages=extra.containsKey(Notification.EXTRA_MESSAGES)||extra.containsKey(Notification.EXTRA_HISTORIC_MESSAGES);
        String author=XNotificationRules.accept(packageName,notification.category,summary,messages,reply,
                notification.getChannelId(),title==null?null:title.toString(),text,allowed);
        if(author==null&&expanded!=null){
            author=XNotificationRules.accept(packageName,notification.category,summary,messages,reply,
                    notification.getChannelId(),expanded.toString(),text,allowed);
        }
        if(author==null)return null;
        if(text.length()>2000)text=text.substring(0,2000);
        byte[] hash=MessageDigest.getInstance("SHA-256").digest((author+"\n"+text).getBytes(StandardCharsets.UTF_8));StringBuilder id=new StringBuilder();for(byte b:hash)id.append(String.format(Locale.ROOT,"%02x",b&255));
        return new JSONObject().put("id",id.toString()).put("author",author).put("text",text).put("posted_at",posted);
    }
    static synchronized boolean capture(Context c,String pkg,Notification n,long posted){
        if(!enabled(c)||!access(c)||posted<prefs(c).getLong("enabled_since",Long.MAX_VALUE)||posted>System.currentTimeMillis()+300000)return false;
        SharedPreferences.Editor status=prefs(c).edit().putLong("last_seen",System.currentTimeMillis());
        try{
            JSONObject item=extract(pkg,n,posted,authors(prefs(c).getString("authors",DEFAULT_AUTHORS)));
            if(item==null){
                status.putString("capture_state","已看到 X 通知，作者或通知类型未匹配").apply();
                return false;
            }
            try(XNotificationStore store=new XNotificationStore(c)){
                store.prune();
                ContentValues values=new ContentValues();
                values.put("id",item.getString("id"));values.put("author",item.getString("author"));
                values.put("text",item.getString("text"));values.put("posted_at",posted);
                boolean added=store.getWritableDatabase().insertWithOnConflict("excerpt",null,values,SQLiteDatabase.CONFLICT_IGNORE)!=-1;
                store.prune();
                status.putString("capture_state",added?"已收集一条发帖通知":"已识别重复的发帖通知")
                        .putLong("last_match",System.currentTimeMillis()).apply();
                return added;
            }
        }catch(Exception ignored){
            status.putString("capture_state","收到 X 通知，但本机保存失败").apply();
            return false;
        }
    }
    void prune(){getWritableDatabase().delete("excerpt","posted_at<?",new String[]{Long.toString(System.currentTimeMillis()-7L*86400000)});getWritableDatabase().execSQL("DELETE FROM excerpt WHERE id IN (SELECT id FROM excerpt ORDER BY posted_at DESC LIMIT -1 OFFSET 500)");}
    JSONArray pending()throws Exception{prune();JSONArray out=new JSONArray();try(Cursor rows=getReadableDatabase().rawQuery("SELECT id,author,text,posted_at FROM excerpt WHERE uploaded=0 ORDER BY posted_at LIMIT 50",null)){while(rows.moveToNext())out.put(new JSONObject().put("id",rows.getString(0)).put("author",rows.getString(1)).put("text",rows.getString(2)).put("posted_at",java.time.Instant.ofEpochMilli(rows.getLong(3)).toString()));}return out;}
    void acknowledge(JSONArray ids){for(int i=0;i<ids.length();i++){ContentValues v=new ContentValues();v.put("uploaded",1);getWritableDatabase().update("excerpt",v,"id=?",new String[]{ids.optString(i)});}}
    String preview(){prune();StringBuilder out=new StringBuilder();try(Cursor rows=getReadableDatabase().rawQuery("SELECT author,text,posted_at,uploaded FROM excerpt ORDER BY posted_at DESC LIMIT 30",null)){while(rows.moveToNext())out.append("@").append(rows.getString(0)).append(" · ").append(rows.getInt(3)==1?"已同步":"待同步").append("\n").append(rows.getString(1)).append("\n\n");}return out.length()==0?"尚未收集到符合条件的 X 发帖通知。":out.toString();}
}
