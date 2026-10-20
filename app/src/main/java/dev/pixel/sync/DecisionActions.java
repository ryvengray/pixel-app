package dev.pixel.sync;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.view.inputmethod.InputMethodManager;
import org.json.JSONObject;
import java.util.WeakHashMap;

/** Validated UI dispatch for both BGE and LLM tools. No sync/print side effects here. */
public final class DecisionActions {
    private static final class Pending {
        final JSONObject action;final long created=SystemClock.elapsedRealtime();
        Pending(JSONObject value){action=value;}
    }
    private static final WeakHashMap<Activity,Pending> pending=new WeakHashMap<>();
    public static void open(Activity activity,JSONObject action){
        if(action==null||activity.isFinishing()||activity.isDestroyed())return;
        if(!ClientTools.valid(action.optString("name"),action.optJSONObject("arguments")))return;
        String id=action.optString("id");if(id.isEmpty()||id.equals("null"))return;
        pending.put(activity,new Pending(action));onFocus(activity);
    }
    public static void cancelPending(Activity activity){pending.remove(activity);}
    public static void onFocus(Activity activity){
        Pending next=pending.get(activity);if(next==null)return;
        if(activity.isFinishing()||activity.isDestroyed()||SystemClock.elapsedRealtime()-next.created>120000){pending.remove(activity);return;}
        if(!activity.hasWindowFocus())return;
        JSONObject action=next.action;String id=action.optString("id"),name=action.optString("name");
        SharedPreferences seen=activity.getSharedPreferences("decision_actions",0);
        pending.remove(activity);if(seen.getBoolean(id,false))return;
        Intent intent=intent(activity,name,action.optJSONObject("arguments"));if(intent==null)return;
        android.view.View focus=activity.getCurrentFocus();
        if(focus!=null)((InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(),0);
        activity.startActivity(intent);seen.edit().putBoolean(id,true).apply();
    }
    /** Menu entries and agent replies share this fixed allowlist and presentation preference. */
    public static Intent intent(Activity activity,String name,JSONObject args){
        if(!ClientTools.valid(name,args))return null;
        Class<?> target;
        switch(name){
            case "open_sync":target=SyncActivity.class;break;
            case "open_print":target=PrintActivity.class;break;
            case "open_topics":target=TopicsActivity.class;break;
            default:return null;
        }
        Intent intent=new Intent(activity,target);
        int theme=activity.getSharedPreferences("home",0).getInt("theme",0);
        boolean dark=theme==2||(theme==0&&(activity.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);
        intent.putExtra("dark",dark);
        if(name.equals("open_print")&&args.has("text"))intent.putExtra(PrintActivity.EXTRA_DRAFT_TEXT,args.optString("text"));
        return intent;
    }
    private DecisionActions(){}
}
