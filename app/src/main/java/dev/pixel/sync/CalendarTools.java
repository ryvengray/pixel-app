package dev.pixel.sync;

import android.content.Context;
import org.json.*;
import java.time.*;
import java.util.*;

/** Agent and local UI use the same validated, revisioned executor. */
final class CalendarTools {
    static final Set<String> NAMES=new HashSet<>(Arrays.asList("open_calendar","query_calendar","save_special_date","set_calendar_todo_completed","delete_special_date","get_cycle_summary","save_cycle_record","delete_cycle_record","update_cycle_settings","update_calendar_preferences","undo_calendar_operation","list_calendar_widgets","configure_calendar_widget"));
    static boolean isTool(String name){return NAMES.contains(name);}
    static boolean isLocal(String name){return isTool(name)&&!name.equals("open_calendar");}
    static boolean valid(String name,JSONObject args){
        if(!isTool(name)||args==null||args.toString().length()>10000)return false;
        String fields;
        switch(name){
            case "list_calendar_widgets":fields="";break;
            case "configure_calendar_widget":fields="widget_id expected_revision theme event_id show_cycle restore_operation_id";break;
            case "open_calendar":fields="date item show_cycle";break;
            case "query_calendar":fields="from to query include_archived";break;
            case "get_cycle_summary":fields="from to";break;
            case "set_calendar_todo_completed":fields="id expected_revision occurrence completed";break;
            case "save_special_date":fields="event_type repeat id expected_revision title date month day year_known calendar lunar_month lunar_day lunar_leap yearly count_up milestone leap_day missing_day remind remind_days remind_time timezone follow_timezone note";break;
            case "save_cycle_record":fields="id expected_revision start end note";break;
            case "update_cycle_settings":fields="expected_revision cycle_days duration enabled adaptive remind remind_days remind_time timezone follow_timezone ai_access";break;
            case "update_calendar_preferences":fields="expected_revision show_cycle private_notifications";break;
            case "undo_calendar_operation":fields="operation_id";break;
            default:fields="id expected_revision";
        }
        Set<String> allowed=new HashSet<>(Arrays.asList(fields.split(" ")));
        Set<String> bools=new HashSet<>(Arrays.asList("completed","show_cycle","include_archived","year_known","lunar_leap","yearly","count_up","remind","enabled","adaptive","ai_access","private_notifications","follow_timezone"));
        Set<String> nums=new HashSet<>(Arrays.asList("expected_revision","widget_id","theme","month","day","lunar_month","lunar_day","milestone","remind_days","cycle_days","duration"));
        try{
            for(Iterator<String> keys=args.keys();keys.hasNext();){String k=keys.next();if(!allowed.contains(k))return false;Object value=args.get(k);if(args.isNull(k)){if(!k.equals("end")&&!k.equals("milestone"))return false;continue;}
                if(bools.contains(k)){if(!(value instanceof Boolean))return false;}else if(nums.contains(k)){if(!(value instanceof Number)||((Number)value).doubleValue()!=((Number)value).intValue())return false;}else if(!(value instanceof String)||((String)value).length()>2000)return false;
                if(Arrays.asList("occurrence","date","start","end","from","to").contains(k))LocalDate.parse(args.getString(k));
            }
            if(name.equals("set_calendar_todo_completed")&&(!args.has("id")||!args.has("expected_revision")||!args.has("occurrence")||!args.has("completed")))return false;
            if(name.equals("configure_calendar_widget")&&(!args.has("widget_id")||!args.has("expected_revision")))return false;
            if(name.startsWith("delete_")&&(!args.has("id")||!args.has("expected_revision")))return false;
            return true;
        }catch(Exception invalid){return false;}
    }
    static JSONObject execute(Context c,String name,JSONObject args,String operation){
        try(CalendarStore store=new CalendarStore(c)){
            if(!valid(name,args)||!isLocal(name)||operation==null||operation.isEmpty())throw new IllegalArgumentException("日历工具参数无效");
            boolean sensitive=name.contains("cycle");if(sensitive&&!store.settings().optBoolean("ai_access",true))throw new IllegalArgumentException("大姨妈 AI 访问已关闭，请在日历设置中开启");
            switch(name){
                case "list_calendar_widgets":return new JSONObject().put("ok",true).put("widgets",CalendarWidgetActions.list(c));
                case "configure_calendar_widget":return CalendarWidgetActions.configure(c,args,operation);
                case "query_calendar":{
                    LocalDate from=LocalDate.parse(args.optString("from",LocalDate.now().toString())),to=LocalDate.parse(args.optString("to",from.plusMonths(1).toString()));if(to.isBefore(from)||CalendarDates.days(from,to)>732)throw new IllegalArgumentException("每次查询最多两年");JSONArray events=store.events(from,to),filtered=new JSONArray();String query=args.optString("query","");for(int i=0;i<events.length();i++){JSONObject e=events.getJSONObject(i);if(e.optString("title").contains(query)&&filtered.length()<50)filtered.put(e);}
                    JSONArray all=new JSONArray();if(args.optBoolean("include_archived"))for(JSONObject e:store.items("event"))if(e.optString("title").contains(query)&&all.length()<50){JSONObject copy=new JSONObject(e.toString());if(e.optBoolean("year_known",true))copy.put("elapsed_days",CalendarDates.days(LocalDate.parse(e.getString("date")),LocalDate.now()));all.put(copy);}
                    return new JSONObject().put("ok",true).put("events",filtered).put("all_records",all).put("limit",50).put("narrow_query_if_full",filtered.length()==50||all.length()==50).put("recent_operations",store.recentOperations(false)).put("notifications_allowed",store.notificationsAllowed()).put("preferences",store.preferences()).put("today",LocalDate.now().toString()).put("summary","已读取重要日期");
                }
                case "get_cycle_summary":{
                    LocalDate from=LocalDate.parse(args.optString("from",LocalDate.now().minusMonths(6).toString())),to=LocalDate.parse(args.optString("to",LocalDate.now().toString()));if(to.isBefore(from)||CalendarDates.days(from,to)>732)throw new IllegalArgumentException("每次查询最多两年");JSONArray records=new JSONArray();for(JSONObject e:store.cycles()){LocalDate date=LocalDate.parse(e.getString("start"));if(!date.isBefore(from)&&!date.isAfter(to)){records.put(e);if(records.length()>50)records.remove(0);}}return new JSONObject().put("ok",true).put("records",records).put("notifications_allowed",store.notificationsAllowed()).put("settings",store.settings()).put("recent_operations",store.recentOperations(true)).put("prediction",store.prediction()).put("summary","已读取大姨妈实际记录与预测，预测不能作为避孕依据");
                }
                case "set_calendar_todo_completed":return store.completeTodo(args.getString("id"),args.getInt("expected_revision"),LocalDate.parse(args.getString("occurrence")),args.getBoolean("completed"),operation);
                case "save_special_date":return store.write("event",args,operation);
                case "save_cycle_record":return store.write("cycle",args,operation);
                case "update_cycle_settings":return store.write("settings",new JSONObject(args.toString()).put("id","cycle-settings"),operation);
                case "update_calendar_preferences":return store.write("preferences",new JSONObject(args.toString()).put("id","calendar-preferences"),operation);
                case "delete_special_date":case "delete_cycle_record":{
                    String id=args.getString("id");JSONObject item=store.get(id);if(item==null)throw new IllegalArgumentException("记录不存在，请先查询");return store.write(name.equals("delete_special_date")?"event":"cycle",new JSONObject().put("id",id).put("expected_revision",args.getInt("expected_revision")).put("deleted",true),operation);
                }
                case "undo_calendar_operation":if(!store.settings().optBoolean("ai_access",true)&&!"event".equals(store.operationKind(args.getString("operation_id"))))throw new IllegalArgumentException("大姨妈 AI 访问已关闭");return store.undo(args.getString("operation_id"),operation);
                default:throw new IllegalArgumentException("未知日历工具");
            }
        }catch(Exception error){try{return new JSONObject().put("ok",false).put("error",error.getMessage()==null?"日历操作未完成":error.getMessage());}catch(JSONException impossible){throw new IllegalStateException(impossible);}}
    }
    private CalendarTools(){}
}
