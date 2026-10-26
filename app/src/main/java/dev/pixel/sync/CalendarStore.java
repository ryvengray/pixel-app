package dev.pixel.sync;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.time.*;
import java.util.*;

/** Local calendar facts. Predictions never become records. All writes are revisioned and idempotent. */
final class CalendarStore extends SQLiteOpenHelper {
    private final Context context;
    CalendarStore(Context c){this(c,"calendar.db");}
    CalendarStore(Context c,String name){super(c.getApplicationContext(),name,null,1);context=c.getApplicationContext();}
    public void onConfigure(SQLiteDatabase db){db.enableWriteAheadLogging();}
    public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE item(id TEXT PRIMARY KEY,kind TEXT NOT NULL,body TEXT NOT NULL)");db.execSQL("CREATE TABLE operation(id TEXT PRIMARY KEY,result TEXT NOT NULL)");db.execSQL("CREATE TABLE delivered(id TEXT PRIMARY KEY,at INTEGER NOT NULL)");}
    public void onUpgrade(SQLiteDatabase db,int old,int next){}
    JSONObject get(String id)throws JSONException {try(Cursor c=getReadableDatabase().rawQuery("SELECT body FROM item WHERE id=?",new String[]{id})){return c.moveToFirst()?new JSONObject(c.getString(0)):null;}}
    List<JSONObject> items(String kind)throws JSONException {List<JSONObject> list=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT body FROM item WHERE kind=?",new String[]{kind})){while(c.moveToNext()){JSONObject j=new JSONObject(c.getString(0));if(!j.optBoolean("deleted"))list.add(j);}}return list;}
    JSONObject settings()throws JSONException {JSONObject j=get("cycle-settings");return j==null||j.optBoolean("deleted")?new JSONObject().put("revision",j==null?0:j.optInt("revision")).put("enabled",false).put("ai_access",true).put("remind_days",2).put("remind_time","09:00").put("timezone",ZoneId.systemDefault().getId()):j;}
    JSONObject preferences()throws JSONException {JSONObject j=get("calendar-preferences");return j==null||j.optBoolean("deleted")?new JSONObject().put("revision",j==null?0:j.optInt("revision")).put("show_cycle",false).put("private_notifications",true):j;}
    private static String text(JSONObject j,String k,int max){Object o=j.opt(k);if(!(o instanceof String)||((String)o).trim().isEmpty()||((String)o).length()>max)throw new IllegalArgumentException("请填写有效的"+k);return ((String)o).trim();}
    private static void number(JSONObject j,String k,int min,int max){if(!j.has(k))return;Object v=j.opt(k);if(!(v instanceof Number)||((Number)v).doubleValue()!=((Number)v).intValue()||((Number)v).intValue()<min||((Number)v).intValue()>max)throw new IllegalArgumentException(k+"超出支持范围");}
    private void validate(String kind,JSONObject j)throws Exception{
        if(kind.equals("event")){
            text(j,"title",100);LocalDate base=LocalDate.parse(text(j,"date",10));if(base.getYear()<1901||base.getYear()>2099)throw new IllegalArgumentException("日期需在1901至2099年之间");
            if(!Arrays.asList("solar","lunar").contains(j.optString("calendar","solar")))throw new IllegalArgumentException("历法无效");
            if(!Arrays.asList("feb28","march1").contains(j.optString("leap_day","feb28")))throw new IllegalArgumentException("闰日规则无效");
            if(!Arrays.asList("last_day","skip","regular").contains(j.optString("missing_day","last_day")))throw new IllegalArgumentException("农历缺日规则无效");
            if(!Arrays.asList("date","todo").contains(j.optString("event_type","date")))throw new IllegalArgumentException("日期类型无效");
            if(!Arrays.asList(CalendarDates.REPEATS).contains(CalendarDates.repeat(j)))throw new IllegalArgumentException("重复方式无效");
            if("lunar".equals(j.optString("calendar"))&&!Arrays.asList("none","yearly").contains(CalendarDates.repeat(j)))throw new IllegalArgumentException("按日、周、两周、月重复请使用公历");
            if(!j.optBoolean("year_known",true)&&!"yearly".equals(CalendarDates.repeat(j)))throw new IllegalArgumentException("仅记月日需要每年重复");
            if(CalendarDates.todo(j)&&(!j.optBoolean("year_known",true)||!"solar".equals(j.optString("calendar","solar"))||j.optBoolean("count_up")||j.has("milestone")))throw new IllegalArgumentException("待办需要完整公历日期，不支持正数纪念或里程碑");
            number(j,"lunar_month",1,12);number(j,"lunar_day",1,30);number(j,"milestone",1,100000);number(j,"remind_days",0,365);
        }
        if(kind.equals("cycle")){
            LocalDate start=LocalDate.parse(text(j,"start",10));if(start.isAfter(LocalDate.now()))throw new IllegalArgumentException("实际开始日期不能是未来");
            if(j.has("end")&&!j.optString("end").isEmpty()){LocalDate end=LocalDate.parse(j.getString("end"));if(end.isBefore(start)||end.isAfter(LocalDate.now()))throw new IllegalArgumentException("结束日期应在开始至今天之间");}
            for(JSONObject other:items("cycle")){if(other.optString("id").equals(j.optString("id")))continue;LocalDate a=LocalDate.parse(other.getString("start")),b=LocalDate.parse(other.optString("end",LocalDate.now().toString()));LocalDate end=LocalDate.parse(j.optString("end",j.getString("start")));if(!start.isAfter(b)&&!end.isBefore(a))throw new IllegalArgumentException("与已有大姨妈记录重叠，请先修改原记录");}
        }
        if(kind.equals("settings")){number(j,"cycle_days",1,365);number(j,"duration",1,60);number(j,"remind_days",0,30);if(j.optBoolean("enabled")&&(!j.has("cycle_days")||!j.has("duration")))throw new IllegalArgumentException("请先配置周期和持续天数");}
        if(j.has("timezone"))ZoneId.of(j.getString("timezone"));if(j.has("remind_time"))LocalTime.parse(j.getString("remind_time"));if(j.optString("note").length()>2000)throw new IllegalArgumentException("备注过长");
    }
    boolean notificationsAllowed(){android.app.NotificationManager manager=context.getSystemService(android.app.NotificationManager.class);android.app.NotificationChannel channel=manager.getNotificationChannel("calendar");return manager.areNotificationsEnabled()&&(channel==null||channel.getImportance()!=android.app.NotificationManager.IMPORTANCE_NONE)&&(android.os.Build.VERSION.SDK_INT<33||context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED);}
    JSONObject write(String kind,JSONObject change,String operation)throws Exception{
        SQLiteDatabase db=getWritableDatabase();JSONObject result;db.beginTransaction();
        try{
            try(Cursor c=db.rawQuery("SELECT result FROM operation WHERE id=?",new String[]{operation})){if(c.moveToFirst()){result=new JSONObject(c.getString(0));db.setTransactionSuccessful();return result;}}
            String id=change.optString("id");if(id.isEmpty())id=UUID.randomUUID().toString();
            JSONObject before=get(id);if(before==null&&change.has("id")&&(!kind.equals("settings")&&!kind.equals("preferences")||change.optInt("expected_revision",0)!=0))throw new IllegalArgumentException("记录不存在，请重新查询");if(before!=null){if(!kind.equals(before.getString("kind")))throw new IllegalArgumentException("记录类型不匹配");if(!change.has("expected_revision")||change.getInt("expected_revision")!=before.optInt("revision"))throw new IllegalArgumentException("记录已更新，请重新读取后修改");}
            JSONObject after=before==null?new JSONObject():new JSONObject(before.toString());for(Iterator<String> it=change.keys();it.hasNext();){String k=it.next();if(!k.equals("expected_revision")){if(change.isNull(k))after.remove(k);else after.put(k,change.get(k));}}
            if(before!=null&&before.optBoolean("deleted")&&!change.has("deleted")){if(kind.equals("settings")||kind.equals("preferences"))after.put("deleted",false);else throw new IllegalArgumentException("记录已删除，请撤销删除后再修改");}
            if(kind.equals("event")&&!change.has("date")&&change.has("month")&&change.has("day"))after.put("date",LocalDate.of(2000,after.getInt("month"),after.getInt("day")).toString()).put("year_known",false).put("yearly",true);
            if(kind.equals("event")){
                if(!change.has("date")&&change.has("month")&&change.has("day")&&!change.has("repeat"))after.put("repeat","yearly");
                if(before==null&&CalendarDates.todo(after)&&!after.has("remind_days"))after.put("remind_days",0);
                if(change.has("repeat")&&!change.isNull("repeat"))after.put("yearly","yearly".equals(change.getString("repeat")));
                else if(change.has("yearly")&&!change.isNull("yearly"))after.put("repeat",change.getBoolean("yearly")?"yearly":"none");
                if(before!=null&&CalendarDates.todo(before)&&!CalendarDates.todo(after))after.remove("completed_dates");
            }
            if(kind.equals("event")&&"lunar".equals(after.optString("calendar"))&&!change.has("lunar_month")&&!change.has("lunar_day")){
                if(change.has("month")&&change.has("day"))after.put("lunar_month",change.getInt("month")).put("lunar_day",change.getInt("day"));
                else if(change.has("date")||change.has("calendar")){CalendarDates.Lunar lunar=CalendarDates.lunar(LocalDate.parse(after.getString("date")));after.put("lunar_month",lunar.month).put("lunar_day",lunar.day).put("lunar_leap",lunar.leap);}
            }
            if(kind.equals("event")&&"lunar".equals(after.optString("calendar"))&&after.has("lunar_month")&&after.has("lunar_day")){
                LocalDate base=LocalDate.parse(after.getString("date"));CalendarDates.Lunar original=CalendarDates.lunar(base);LocalDate converted=original.month==after.getInt("lunar_month")&&original.day==after.getInt("lunar_day")&&original.leap==after.optBoolean("lunar_leap")?base:CalendarDates.lunarInYear(base.getYear(),after.getInt("lunar_month"),after.getInt("lunar_day"),after.optBoolean("lunar_leap"),"skip");
                if(converted==null&&!after.optBoolean("year_known",true))for(int year=1901;year<=2099&&converted==null;year++)converted=CalendarDates.lunarInYear(year,after.getInt("lunar_month"),after.getInt("lunar_day"),after.optBoolean("lunar_leap"),"skip");
                if(converted==null)throw new IllegalArgumentException("所选年份没有该农历日期，请指定可用年份");after.put("date",converted.toString());
            }
            if(kind.equals("event")&&!after.optBoolean("year_known",true)&&(after.optBoolean("count_up")||after.has("milestone")))throw new IllegalArgumentException("计算已经多少天或里程碑需要明确的起始年份");
            after.put("id",id).put("kind",kind).put("revision",before==null?1:before.getInt("revision")+1).put("updated_at",Instant.now().toString());
            if(!after.optBoolean("deleted"))validate(kind,after);
            ContentValues v=new ContentValues();v.put("id",id);v.put("kind",kind);v.put("body",after.toString());db.insertWithOnConflict("item",null,v,SQLiteDatabase.CONFLICT_REPLACE);
            result=new JSONObject().put("ok",true).put("record",after).put("before",before==null?JSONObject.NULL:before).put("operation_id",operation).put("summary",after.optBoolean("deleted")?"已删除，可撤销":kind.equals("event")?"已保存 · "+after.optString("title"):kind.equals("cycle")?"已保存大姨妈记录":"已更新日历设置");
            if(kind.equals("event")||kind.equals("settings")){boolean notifications=notificationsAllowed();result.put("reminder_rule_saved",!after.optBoolean("deleted")&&after.optBoolean("remind",true)).put("notifications_allowed",notifications).put("delivery","local_inexact");if(!notifications&&after.optBoolean("remind",true)&&!after.optBoolean("deleted"))result.put("summary",result.getString("summary")+"；通知未开启，请在日历中允许通知");}
            v=new ContentValues();v.put("id",operation);v.put("result",result.toString());db.insertOrThrow("operation",null,v);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        CalendarUpdates.changed(context);return result;
    }
    JSONArray recentOperations(boolean cycle)throws Exception {
        JSONArray out=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,result FROM operation ORDER BY rowid DESC LIMIT 100",null)){while(c.moveToNext()&&out.length()<10){JSONObject result=new JSONObject(c.getString(1));String kind=result.has("children")?"settings":result.getJSONObject("record").getString("kind");if(cycle?(kind.equals("cycle")||kind.equals("settings")):kind.equals("event"))out.put(new JSONObject().put("operation_id",c.getString(0)).put("summary",result.optString("summary")).put("kind",kind));}}return out;
    }
    private JSONObject operation(String id)throws Exception {try(Cursor c=getReadableDatabase().rawQuery("SELECT result FROM operation WHERE id=?",new String[]{id})){return c.moveToFirst()?new JSONObject(c.getString(0)):null;}}
    private void remember(String id,JSONObject result){ContentValues v=new ContentValues();v.put("id",id);v.put("result",result.toString());getWritableDatabase().insertOrThrow("operation",null,v);}
    JSONObject configure(JSONObject setting,String firstStart,boolean show,String id)throws Exception{
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();JSONObject result;
        try{
            JSONObject previous=operation(id);if(previous!=null){db.setTransactionSuccessful();return previous;}
            JSONArray children=new JSONArray();write("settings",setting,id+":settings");children.put(id+":settings");
            if(firstStart!=null){write("cycle",new JSONObject().put("start",firstStart),id+":record");children.put(id+":record");}
            JSONObject p=preferences();p.put("id","calendar-preferences").put("expected_revision",p.optInt("revision")).put("show_cycle",show);write("preferences",p,id+":display");children.put(id+":display");
            result=new JSONObject().put("ok",true).put("operation_id",id).put("children",children).put("summary","已保存大姨妈配置"+(firstStart==null?"":"与实际开始日期"));remember(id,result);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        CalendarUpdates.changed(context);return result;
    }
    String operationKind(String id)throws Exception {JSONObject result=operation(id);if(result==null)return "";return result.has("children")?"settings":result.getJSONObject("record").getString("kind");}
    JSONObject undo(String id,String newOperation)throws Exception{
        JSONObject existing=operation(newOperation);if(existing!=null)return existing;
        JSONObject result=operation(id);if(result==null)throw new IllegalArgumentException("找不到该次修改");
        if(result.has("children")){
            SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
                JSONArray children=result.getJSONArray("children");for(int i=0;i<children.length();i++){JSONObject after=operation(children.getString(i)).getJSONObject("record"),current=get(after.getString("id"));if(current==null||current.optInt("revision")!=after.optInt("revision"))throw new IllegalArgumentException("记录已有后续修改，请打开详情处理");}
                JSONArray reversed=new JSONArray();for(int i=children.length()-1;i>=0;i--){String op=newOperation+":"+i;undo(children.getString(i),op);reversed.put(op);}
                JSONObject undone=new JSONObject().put("ok",true).put("operation_id",newOperation).put("children",reversed).put("summary","已撤销这次配置与记录");remember(newOperation,undone);db.setTransactionSuccessful();return undone;
            }finally{db.endTransaction();CalendarUpdates.changed(context);}
        }
        JSONObject after=result.getJSONObject("record"),current=get(after.getString("id"));if(current==null||current.optInt("revision")!=after.optInt("revision"))throw new IllegalArgumentException("记录已有后续修改，请打开详情处理");
        JSONObject before=result.optJSONObject("before");JSONObject change=before==null?new JSONObject().put("id",after.getString("id")).put("deleted",true):new JSONObject(before.toString());
        if(before!=null)for(Iterator<String> keys=current.keys();keys.hasNext();){String k=keys.next();if(!before.has(k)&&!Arrays.asList("id","kind","revision","updated_at").contains(k))change.put(k,JSONObject.NULL);}
        change.put("expected_revision",current.getInt("revision"));return write(after.getString("kind"),change,newOperation);
    }
    List<JSONObject> cycles()throws JSONException {List<JSONObject> list=items("cycle");list.sort(Comparator.comparing(j->j.optString("start")));return list;}
    JSONObject prediction()throws Exception {
        JSONObject settings=settings(),out=new JSONObject().put("estimated",true).put("available",false).put("basis","根据实际开始日期与配置周期估算；不能作为避孕依据");List<JSONObject> list=cycles();
        if(!settings.optBoolean("enabled")||list.isEmpty())return out;
        int length=settings.getInt("cycle_days"),spread=0;JSONArray intervals=new JSONArray();
        if(list.size()>=4){List<Integer> gaps=new ArrayList<>();for(int i=Math.max(1,list.size()-6);i<list.size();i++){int n=(int)CalendarDates.days(LocalDate.parse(list.get(i-1).getString("start")),LocalDate.parse(list.get(i).getString("start")));if(n>0){gaps.add(n);intervals.put(n);}}if(gaps.size()>=3){Collections.sort(gaps);if(settings.optBoolean("adaptive"))length=gaps.get(gaps.size()/2);spread=gaps.get(gaps.size()-1)-gaps.get(0);}}
        LocalDate last=LocalDate.parse(list.get(list.size()-1).getString("start")),next=last.plusDays(length);out.put("available",true).put("last_record_id",list.get(list.size()-1).getString("id")).put("last_start",last.toString()).put("next_start",next.toString()).put("next_end",next.plusDays(settings.getInt("duration")-1).toString()).put("cycle_days",length).put("intervals",intervals).put("variable",spread>10).put("algorithm","calendar-v1").put("overdue",next.isBefore(LocalDate.now()));
        if(spread>10){out.put("range_start",next.minusDays(spread/2).toString()).put("range_end",next.plusDays(spread/2).toString());}else out.put("ovulation",next.minusDays(14).toString()).put("fertile_start",next.minusDays(19).toString()).put("fertile_end",next.minusDays(13).toString());return out;
    }
    JSONObject completeTodo(String id,int revision,LocalDate date,boolean completed,String operation)throws Exception{
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            JSONObject previous=operation(operation);if(previous!=null){db.setTransactionSuccessful();return previous;}
            JSONObject item=get(id);if(item==null||item.optBoolean("deleted")||!CalendarDates.todo(item))throw new IllegalArgumentException("待办不存在，请重新查询");
            if(!date.equals(CalendarDates.occurrence(item,date)))throw new IllegalArgumentException("该日期不是这条待办的发生日期");
            JSONArray dates=item.optJSONArray("completed_dates"),updated=new JSONArray();if(dates!=null)for(int i=0;i<dates.length();i++)if(!date.toString().equals(dates.getString(i)))updated.put(dates.getString(i));if(completed)updated.put(date.toString());
            JSONObject result=write("event",new JSONObject().put("id",id).put("expected_revision",revision).put("completed_dates",updated),operation);
            result.put("summary",completed?"已完成这次待办":"已恢复这次待办");
            ContentValues value=new ContentValues();value.put("result",result.toString());db.update("operation",value,"id=?",new String[]{operation});db.setTransactionSuccessful();return result;
        }finally{db.endTransaction();CalendarUpdates.changed(context);}
    }
    JSONArray events(LocalDate from,LocalDate to)throws Exception {
        JSONArray out=new JSONArray();for(JSONObject item:items("event")){
            LocalDate d=CalendarDates.occurrence(item,from);int limit=0;
            while(d!=null&&!d.isAfter(to)&&limit++<800){if(!d.isBefore(from)){JSONObject copy=new JSONObject(item.toString());copy.remove("completed_dates");copy.put("completed",CalendarDates.todo(item)&&CalendarDates.completed(item,d));copy.put("occurrence",d.toString()).put("days_until",CalendarDates.days(LocalDate.now(),d)).put("elapsed_days",item.optBoolean("year_known",true)?CalendarDates.days(LocalDate.parse(item.getString("date")),LocalDate.now()):JSONObject.NULL);out.put(copy);}if(!CalendarDates.repeats(item))break;d=CalendarDates.occurrence(item,d.plusDays(1));}
            if(item.has("milestone")){LocalDate milestone=LocalDate.parse(item.getString("date")).plusDays(item.getInt("milestone"));if(!milestone.isBefore(from)&&!milestone.isAfter(to))out.put(new JSONObject(item.toString()).put("occurrence",milestone.toString()).put("days_until",CalendarDates.days(LocalDate.now(),milestone)).put("title",item.getString("title")+" · 第"+item.getInt("milestone")+"天"));}
        }
        List<JSONObject> sorted=new ArrayList<>();for(int i=0;i<out.length();i++)sorted.add(out.getJSONObject(i));sorted.sort(Comparator.comparing(j->j.optString("occurrence")));return new JSONArray(sorted);
    }
    /** Summaries show one next pending occurrence per record, including overdue one-off tasks. */
    JSONArray upcoming(LocalDate from,LocalDate to)throws Exception{
        List<JSONObject> result=new ArrayList<>();for(JSONObject item:items("event")){
            JSONObject next=nextUpcoming(item,from,to);if(next!=null)result.add(next);
        }
        result.sort(Comparator.comparing((JSONObject e)->e.optString("occurrence")).thenComparingInt(e->CalendarDates.todo(e)?0:1).thenComparing(e->e.optString("title")).thenComparing(e->e.optString("id")));
        return new JSONArray(result);
    }
    static JSONObject nextUpcoming(JSONObject item,LocalDate from,LocalDate to)throws Exception{
        LocalDate date=CalendarDates.occurrence(item,from);boolean todo=CalendarDates.todo(item);
        if(todo){
            JSONArray completed=item.optJSONArray("completed_dates");int remaining=completed==null?0:completed.length();
            while(date!=null&&CalendarDates.completed(item,date)){
                if(!CalendarDates.repeats(item)||remaining--<=0){date=null;break;}
                date=CalendarDates.occurrence(item,date.plusDays(1));
            }
        }
        if(date!=null&&(date.isAfter(to)||(!todo&&date.isBefore(from))))date=null;
        boolean milestone=false;if(item.has("milestone")){
            LocalDate day=LocalDate.parse(item.getString("date")).plusDays(item.getInt("milestone"));
            if(!day.isBefore(from)&&!day.isAfter(to)&&(date==null||day.isBefore(date))){date=day;milestone=true;}
        }
        if(date==null)return null;
        JSONObject copy=new JSONObject(item.toString());copy.remove("completed_dates");copy.put("occurrence",date.toString()).put("completed",false).put("days_until",CalendarDates.days(LocalDate.now(),date)).put("elapsed_days",item.optBoolean("year_known",true)?CalendarDates.days(LocalDate.parse(item.getString("date")),LocalDate.now()):JSONObject.NULL);
        if(milestone)copy.put("title",item.getString("title")+" · 第"+item.getInt("milestone")+"天");return copy;
    }
    boolean delivered(String key){try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM delivered WHERE id=?",new String[]{key})){return c.moveToFirst();}}
    void markDelivered(String key){ContentValues v=new ContentValues();v.put("id",key);v.put("at",System.currentTimeMillis());getWritableDatabase().insertWithOnConflict("delivered",null,v,SQLiteDatabase.CONFLICT_IGNORE);}
}
