package dev.pixel.sync;

import android.appwidget.*;
import android.content.*;
import org.json.*;

/** Agent configuration addresses a specific existing widget, never arbitrary launcher placement. */
final class CalendarWidgetActions {
    private static boolean owned(Context c,int id){AppWidgetProviderInfo info=AppWidgetManager.getInstance(c).getAppWidgetInfo(id);if(info==null||!c.getPackageName().equals(info.provider.getPackageName()))return false;String cls=info.provider.getClassName();return cls.equals(CalendarWidget.class.getName())||cls.equals(CalendarWidget.Agenda.class.getName())||cls.equals(CalendarWidget.Countdown.class.getName());}
    private static JSONObject snapshot(Context c,int id)throws Exception {SharedPreferences p=CalendarWidget.prefs(c,id);return new JSONObject().put("widget_id",id).put("kind",CalendarWidget.mode(c,id)).put("revision",p.getInt("revision",0)).put("theme",p.getInt("theme",0)).put("show_cycle",p.getBoolean("cycle",false)).put("event_id",p.getString("event",""));}
    static JSONArray list(Context c)throws Exception {
        JSONArray result=new JSONArray();for(Class<?> cls:new Class<?>[]{CalendarWidget.class,CalendarWidget.Agenda.class,CalendarWidget.Countdown.class})for(int id:AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c,cls))){JSONObject item=snapshot(c,id);JSONArray ops=new JSONArray();for(java.util.Map.Entry<String,?> e:CalendarWidget.prefs(c,id).getAll().entrySet())if(e.getKey().startsWith("operation:")){JSONObject op=new JSONObject(String.valueOf(e.getValue()));ops.put(new JSONObject().put("operation_id",op.getString("operation_id")).put("at",op.optLong("at")).put("revision",op.getJSONObject("after").getInt("revision")));}item.put("operations",ops);result.put(item);}return result;
    }
    static synchronized JSONObject configure(Context c,JSONObject args,String operation)throws Exception {
        int id=args.getInt("widget_id");if(!owned(c,id))throw new IllegalArgumentException("找不到这张桌面组件，请重新查询");SharedPreferences p=CalendarWidget.prefs(c,id);String cached=p.getString("operation:"+operation,null);if(cached!=null)return new JSONObject(cached);
        if(args.optBoolean("show_cycle")&&"countdown".equals(CalendarWidget.mode(c,id)))throw new IllegalArgumentException("单个纪念日组件只展示重要日期，请选择大月历或近期日期组件");if(args.has("event_id")&&!"countdown".equals(CalendarWidget.mode(c,id)))throw new IllegalArgumentException("只有单个纪念日组件可以选择专属日期；月历和近期日期自动展示列表");JSONObject before=snapshot(c,id);if(before.getInt("revision")!=args.getInt("expected_revision"))throw new IllegalArgumentException("组件配置已改变，请重新查询");JSONObject change=args;
        if(args.has("restore_operation_id")){String saved=p.getString("operation:"+args.getString("restore_operation_id"),null);if(saved==null)throw new IllegalArgumentException("找不到这次组件修改");JSONObject prior=new JSONObject(saved);if(prior.getJSONObject("after").getInt("revision")!=before.getInt("revision"))throw new IllegalArgumentException("组件已有后续修改，无法直接撤销");change=prior.getJSONObject("before");}
        int theme=change.optInt("theme",before.getInt("theme"));if(theme<0||theme>2)throw new IllegalArgumentException("主题无效");String event=change.optString("event_id",before.getString("event_id"));
        if(!event.isEmpty())try(CalendarStore store=new CalendarStore(c)){JSONObject item=store.get(event);if(item==null||item.optBoolean("deleted")||!"event".equals(item.optString("kind")))throw new IllegalArgumentException("请先查询有效的重要日期ID");}
        JSONObject after=new JSONObject(before.toString()).put("revision",before.getInt("revision")+1).put("theme",theme).put("show_cycle",change.optBoolean("show_cycle",before.getBoolean("show_cycle"))).put("event_id",event);
        JSONObject result=new JSONObject().put("ok",true).put("operation_id",operation).put("before",before).put("after",after).put("at",System.currentTimeMillis()).put("summary","已更新桌面组件");
        if(!p.edit().putInt("revision",after.getInt("revision")).putInt("theme",theme).putBoolean("cycle",after.getBoolean("show_cycle")).putString("event",event).putString("operation:"+operation,result.toString()).commit())throw new IllegalStateException("组件设置未能保存");CalendarWidget.update(c,id);return result;
    }
    private CalendarWidgetActions(){}
}
