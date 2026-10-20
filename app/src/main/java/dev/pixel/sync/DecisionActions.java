package dev.pixel.sync;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.Iterator;

/** Fixed local allowlist: never accept a remote class, URI, shell command or print job. */
public final class DecisionActions {
    public static void open(Activity activity, JSONObject action) {
        if(action==null||activity.isFinishing()||activity.isDestroyed()||!activity.hasWindowFocus())return;
        if(!activity.getSharedPreferences("gray_settings",0).getBoolean("decision_enabled",false))return;
        String name=action.optString("name",""),id=action.optString("id","");
        if(id.isEmpty()||id.equals("null"))return;
        JSONObject args=action.optJSONObject("arguments");if(args==null)return;
        Class<?> target;
        switch(name){
            case "open_sync":target=SyncActivity.class;break;
            case "open_print":target=PrintActivity.class;break;
            case "open_topics":target=TopicsActivity.class;break;
            default:return;
        }
        for(Iterator<String> keys=args.keys();keys.hasNext();){
            String key=keys.next();if(!name.equals("open_print")||!key.equals("text"))return;
            Object value=args.opt(key);if(!(value instanceof String)||((String)value).isEmpty()||((String)value).length()>4000)return;
        }
        SharedPreferences seen=activity.getSharedPreferences("decision_actions",0);
        if(seen.getBoolean(id,false))return;
        Intent intent=new Intent(activity,target);
        if(name.equals("open_print")&&args.has("text"))intent.putExtra(PrintActivity.EXTRA_DRAFT_TEXT,args.optString("text"));
        activity.startActivity(intent);
        seen.edit().putBoolean(id,true).apply();
    }
    private DecisionActions(){}
}
