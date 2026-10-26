package dev.pixel.sync;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;
import org.json.*;
import java.time.*;
import java.util.concurrent.Executors;

public class CalendarWidget extends AppWidgetProvider {
    public static class Agenda extends CalendarWidget {}
    public static class Countdown extends CalendarWidget {}
    static android.content.SharedPreferences prefs(Context c,int id){return c.getSharedPreferences("calendar_widget_"+id,0);}
    static String mode(Context c,int id){AppWidgetProviderInfo i=AppWidgetManager.getInstance(c).getAppWidgetInfo(id);return i!=null&&i.provider.getClassName().endsWith("$Agenda")?"agenda":i!=null&&i.provider.getClassName().endsWith("$Countdown")?"countdown":"calendar";}
    static void updateAll(Context c){AppWidgetManager m=AppWidgetManager.getInstance(c);for(Class<?> cls:new Class<?>[]{CalendarWidget.class,Agenda.class,Countdown.class})for(int id:m.getAppWidgetIds(new ComponentName(c,cls)))update(c,id);}
    public void onUpdate(Context c,AppWidgetManager m,int[] ids){for(int id:ids)update(c,id);CalendarUpdates.changed(c);}
    public void onAppWidgetOptionsChanged(Context c,AppWidgetManager m,int id,Bundle options){update(c,id);}
    public void onDeleted(Context c,int[] ids){for(int id:ids)prefs(c,id).edit().clear().apply();}
    private static final java.util.concurrent.ExecutorService actions=Executors.newSingleThreadExecutor();
    public void onReceive(Context c,Intent intent){
        super.onReceive(c,intent);if(!"dev.pixel.sync.CALENDAR_WIDGET".equals(intent.getAction()))return;
        int id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,-1);if(!CalendarWidgetActions.owned(c,id))return;
        String action=intent.getStringExtra("action");
        if("complete".equals(action)){
            PendingResult pending=goAsync();actions.execute(()->{try(CalendarStore store=new CalendarStore(c)){
                completeTodo(store,intent);updateAll(c);
            }catch(Exception error){c.getMainExecutor().execute(()->android.widget.Toast.makeText(c,"待办已更新，请刷新后重试",android.widget.Toast.LENGTH_SHORT).show());updateAll(c);}finally{pending.finish();}});return;
        }
        if("open".equals(action)){
            c.startActivity(new Intent(c,CalendarActivity.class).putExtra("date",intent.getStringExtra("date")).putExtra("item",intent.getStringExtra("item")).putExtra("add",intent.getBooleanExtra("add",false)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return;
        }
        synchronized(CalendarWidgetActions.class){android.content.SharedPreferences p=prefs(c,id);
            if("cycle".equals(action))p.edit().putBoolean("cycle",!p.getBoolean("cycle",false)).putInt("revision",p.getInt("revision",0)+1).apply();
            else if("prev".equals(action)||"next".equals(action)){int value=p.getInt("offset",0)+("prev".equals(action)?-1:1);p.edit().putInt("offset",Math.max(-120,Math.min(120,value))).putInt("revision",p.getInt("revision",0)+1).apply();}
        }update(c,id);
    }
    static JSONObject completeTodo(CalendarStore store,Intent intent)throws Exception{
        String id=intent.getStringExtra("item");LocalDate date=LocalDate.parse(intent.getStringExtra("date"));int revision=intent.getIntExtra("revision",-1);
        return store.completeTodo(id,revision,date,true,"widget-complete:"+id+":"+date+":"+revision);
    }
    private static PendingIntent collectionAction(Context c,int id){
        AppWidgetProviderInfo info=AppWidgetManager.getInstance(c).getAppWidgetInfo(id);
        Intent intent=new Intent("dev.pixel.sync.CALENDAR_WIDGET").setComponent(info==null?new ComponentName(c,CalendarWidget.class):info.provider).setData(android.net.Uri.parse("greta-calendar://widget/"+id+"/events")).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id);
        return PendingIntent.getBroadcast(c,id,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
    }
    private static PendingIntent action(Context c,int id,String action){AppWidgetProviderInfo info=AppWidgetManager.getInstance(c).getAppWidgetInfo(id);Intent i=new Intent("dev.pixel.sync.CALENDAR_WIDGET").setComponent(info==null?new ComponentName(c,CalendarWidget.class):info.provider).setData(android.net.Uri.parse("greta-calendar://widget/"+id+"/"+action)).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).putExtra("action",action);return PendingIntent.getBroadcast(c,id,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private static PendingIntent open(Context c,int id,String date,String item,boolean add){Intent i=new Intent(c,CalendarActivity.class).setData(android.net.Uri.parse("greta-calendar://open/"+id+"/"+date+"/"+item+"/"+add)).putExtra("date",date).putExtra("item",item).putExtra("add",add);return PendingIntent.getActivity(c,id,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    static void update(Context c,int id){
        try(CalendarStore store=new CalendarStore(c)){
            RemoteViews view=build(c,id,store,mode(c,id),AppWidgetManager.getInstance(c).getAppWidgetOptions(id));AppWidgetManager.getInstance(c).updateAppWidget(id,view);
        }catch(Exception error){android.util.Log.w("GretaCalendar","Widget update failed: "+error.getClass().getSimpleName());}
    }
    // Leave a scrollable task area even on a six-week month or a short widget.
    static int gridHeight(YearMonth month,int height){int rows=(month.atDay(1).getDayOfWeek().getValue()-1+month.lengthOfMonth()+6)/7;return Math.min(24+rows*44,Math.max(24+rows*28,height-94-64));}
    static RemoteViews build(Context c,int id,CalendarStore store,String mode,Bundle options)throws Exception{
        android.content.SharedPreferences p=prefs(c,id);int theme=p.getInt("theme",c.getSharedPreferences("home",0).getInt("theme",0));boolean dark=theme==2||(theme==0&&(c.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);boolean show=p.getBoolean("cycle",false);JSONObject prediction=store.prediction();int ink=dark?0xFFE7F0EC:0xFF243F37,muted=dark?0xFFA8BDB8:0xFF61776F;
        YearMonth month=YearMonth.now().plusMonths(p.getInt("offset",0));RemoteViews v=new RemoteViews(c.getPackageName(),R.layout.calendar_widget);v.setInt(R.id.calendar_widget_root,"setBackgroundResource",dark?R.drawable.calendar_widget_dark:R.drawable.calendar_widget_light);v.setTextViewText(R.id.widget_month,typography(c,month.getMonthValue()+"月 "+month.getYear()));
        for(int view:new int[]{R.id.widget_month,R.id.widget_prev,R.id.widget_next,R.id.widget_cycle,R.id.widget_add})v.setTextColor(view,ink);v.setTextColor(R.id.widget_summary,muted);
        v.setOnClickPendingIntent(R.id.widget_prev,action(c,id,"prev"));v.setOnClickPendingIntent(R.id.widget_next,action(c,id,"next"));if(!show&&!store.settings().optBoolean("enabled")&&store.cycles().isEmpty()){
            Intent setup=new Intent(c,CalendarActivity.class).setData(android.net.Uri.parse("greta-calendar://cycle-setup/"+id)).putExtra("cycle_setup",true);
            v.setOnClickPendingIntent(R.id.widget_cycle,PendingIntent.getActivity(c,id,setup,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        }else v.setOnClickPendingIntent(R.id.widget_cycle,action(c,id,"cycle"));v.setTextColor(R.id.widget_cycle,show?(dark?0xFFB7DAC5:0xFF38694F):muted);v.setOnClickPendingIntent(R.id.widget_add,open(c,id,LocalDate.now().toString(),"",true));v.setOnClickPendingIntent(R.id.widget_month,open(c,id,month.atDay(1).toString(),"",false));
        v.setViewVisibility(R.id.widget_header,"countdown".equals(mode)?View.GONE:View.VISIBLE);v.setViewVisibility(R.id.widget_summary,"calendar".equals(mode)?View.VISIBLE:View.GONE);v.setInt(R.id.calendar_widget_root,"setGravity","countdown".equals(mode)?android.view.Gravity.CENTER_VERTICAL:android.view.Gravity.TOP);
        v.setViewVisibility(R.id.widget_calendar_frame,"calendar".equals(mode)?View.VISIBLE:View.GONE);v.setViewVisibility(R.id.widget_prev,"calendar".equals(mode)?View.VISIBLE:View.GONE);v.setViewVisibility(R.id.widget_next,"calendar".equals(mode)?View.VISIBLE:View.GONE);
        if("calendar".equals(mode)){
            CalendarMonthView grid=new CalendarMonthView(c);grid.forWidget();grid.bind(month,LocalDate.now(),dark,show,store,null);int rows=(month.atDay(1).getDayOfWeek().getValue()-1+month.lengthOfMonth()+6)/7;int width=Math.max(200,Math.min(700,options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,350)-28));int gridHeight=gridHeight(month,options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,400));v.setViewLayoutHeight(R.id.widget_calendar_frame,gridHeight,android.util.TypedValue.COMPLEX_UNIT_DIP);
            // Widget options are dp; render at physical resolution so the launcher never
            // has to enlarge a low-resolution month image on high-density phones.
            android.util.DisplayMetrics metrics=c.getResources().getDisplayMetrics();
            int pixelWidth=Math.max(1,Math.round(width*metrics.density));
            int pixelHeight=Math.max(1,Math.round(gridHeight*metrics.density));
            Bitmap bitmap=Bitmap.createBitmap(pixelWidth,pixelHeight,Bitmap.Config.ARGB_8888);
            bitmap.setDensity(metrics.densityDpi);
            Canvas canvas=new Canvas(bitmap);canvas.scale(pixelWidth/(float)width,pixelHeight/(float)gridHeight);
            grid.drawMonth(canvas,width,gridHeight);v.setImageViewBitmap(R.id.widget_calendar_image,bitmap);
            v.removeAllViews(R.id.widget_calendar_hits);LocalDate first=month.atDay(1).minusDays(month.atDay(1).getDayOfWeek().getValue()-1);
            for(int row=0;row<rows;row++){RemoteViews week=new RemoteViews(c.getPackageName(),R.layout.calendar_widget_row);for(int col=0;col<7;col++){LocalDate d=first.plusDays(row*7+col);RemoteViews day=new RemoteViews(c.getPackageName(),R.layout.calendar_widget_day);int phase=grid.phase(d);String phaseLabel=phase==1?"，已记录大姨妈":phase==2?"，预计大姨妈":phase==4?"，预计排卵日":phase==3?"，预计易孕期":"";day.setContentDescription(R.id.widget_day,d+"，农历"+CalendarDates.lunar(d).label()+phaseLabel);day.setOnClickPendingIntent(R.id.widget_day,open(c,id,d.toString(),"",false));week.addView(R.id.widget_week,day);}v.addView(R.id.widget_calendar_hits,week);}
        }
        RemoteViews.RemoteCollectionItems.Builder items=new RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1);
        int count=0;JSONArray events=store.upcoming(LocalDate.now(),LocalDate.now().plusYears(1));
        if("countdown".equals(mode)){
            String chosen=p.getString("event","");JSONObject item=chosen.isEmpty()?null:store.get(chosen);
            if(item!=null&&!item.optBoolean("deleted")){
                JSONObject next=CalendarDates.todo(item)?CalendarStore.nextUpcoming(item,LocalDate.now(),LocalDate.now().plusYears(20)):null;
                LocalDate d=next!=null?LocalDate.parse(next.getString("occurrence")):CalendarDates.occurrence(item,LocalDate.now());
                if(d!=null){boolean completed=CalendarDates.todo(item)&&next==null;String title=item.getString("title");long days=CalendarDates.days(LocalDate.now(),d);long value=item.optBoolean("count_up")?CalendarDates.days(LocalDate.parse(item.getString("date")),LocalDate.now()):days;
                    String label=item.optBoolean("count_up")?(value<0?"天后开始":"已经"):(value<0?(CalendarDates.todo(item)?"已逾期":"已过"):"天后");String display=completed?"已完成":value==0&&!item.optBoolean("count_up")?"今天":String.valueOf(Math.abs(value));
                    JSONObject shown=new JSONObject(item.toString()).put("completed",completed);RemoteViews chosenRow=widgetRow(c,shown,display+"\n"+title+(completed||display.equals("今天")?"":" · "+label),ink,d,true);chosenRow.setInt(R.id.widget_event_row,"setMinimumHeight",Math.round(Math.max(80,options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,140)-28)*c.getResources().getDisplayMetrics().density));items.addItem(count++,chosenRow);
                }
            }v.setViewVisibility(R.id.widget_cycle,View.GONE);
        }else{
            for(int n=0;n<Math.min(events.length(),100);n++){
                JSONObject e=events.getJSONObject(n);long days=e.getLong("days_until");String when=days==0?"今天":days<0?"已逾期"+(-days)+"天":days+"天后";
                items.addItem(stableId(e.getString("id")),widgetRow(c,e,e.getString("title")+"  ·  "+when+"\n"+e.getString("occurrence")+" · "+CalendarDates.repeatLabel(e),ink,LocalDate.parse(e.getString("occurrence")),false));count++;
            }
            if(show&&prediction.optBoolean("available")){
                LocalDate due=LocalDate.parse(prediction.getString("next_start"));long days=CalendarDates.days(LocalDate.now(),due);
                JSONObject period=new JSONObject().put("id","");items.addItem(Long.MIN_VALUE,widgetRow(c,period,"大姨妈 · "+(days<0?"尚未记录本次开始":"预计"+days+"天后")+"\n仅为日期估算，供记录参考",ink,due,false));count++;
            }
        }
        if(count==0){RemoteViews empty=new RemoteViews(c.getPackageName(),R.layout.calendar_widget_event);empty.setViewVisibility(R.id.widget_event_complete,View.GONE);empty.setTextViewText(R.id.widget_event,"countdown".equals(mode)?"长按组件，选择要展示的纪念日":"没有待办事项 · 点击添加日期");empty.setTextColor(R.id.widget_event,muted);empty.setOnClickFillInIntent(R.id.widget_event,new Intent().putExtra("action","open").putExtra("date",LocalDate.now().toString()).putExtra("add",true));items.addItem(0,empty);}
        v.setPendingIntentTemplate(R.id.widget_events,collectionAction(c,id));v.setRemoteAdapter(R.id.widget_events,items.build());
        String summary="Greta · 农历"+CalendarDates.lunar(LocalDate.now()).label();if(show&&prediction.optBoolean("available")){long days=CalendarDates.days(LocalDate.now(),LocalDate.parse(prediction.getString("next_start")));summary=days<0?"大姨妈预计日期已过 · 尚未记录本次开始":"大姨妈预计 "+days+" 天后 · 仅供参考";}v.setTextViewText(R.id.widget_summary,typography(c,summary));return v;
    }
    private static long stableId(String id){long hash=0xcbf29ce484222325L;for(int i=0;i<id.length();i++){hash^=id.charAt(i);hash*=0x100000001b3L;}return hash;}
    private static CharSequence typography(Context c,CharSequence text){if(c.getSharedPreferences("home",0).getInt("font",0)!=1)return text;android.text.SpannableString value=new android.text.SpannableString(text);value.setSpan(new android.text.style.TypefaceSpan("serif"),0,value.length(),0);return value;}
    static RemoteViews widgetRow(Context c,JSONObject e,String text,int ink,LocalDate date,boolean countdown)throws Exception{
        RemoteViews row=new RemoteViews(c.getPackageName(),R.layout.calendar_widget_event);CharSequence styled=text;
        if(countdown){android.text.SpannableString span=new android.text.SpannableString(text);int split=text.indexOf('\n');if(split>0){span.setSpan(new android.text.style.AbsoluteSizeSpan(40,true),0,split,0);span.setSpan(new android.text.style.AbsoluteSizeSpan(14,true),split,text.length(),0);}styled=span;row.setInt(R.id.widget_event,"setGravity",android.view.Gravity.CENTER);}
        row.setTextViewText(R.id.widget_event,typography(c,styled));row.setTextColor(R.id.widget_event,ink);
        row.setOnClickFillInIntent(R.id.widget_event,new Intent().putExtra("action","open").putExtra("date",date.toString()).putExtra("item",e.optString("id")));
        boolean todo=CalendarDates.todo(e)&&!e.optBoolean("completed");row.setViewVisibility(R.id.widget_event_complete,todo?View.VISIBLE:View.GONE);
        if(todo){row.setTextColor(R.id.widget_event_complete,ink);row.setContentDescription(R.id.widget_event_complete,"完成待办："+e.optString("title")+"，"+date);row.setOnClickFillInIntent(R.id.widget_event_complete,completionIntent(e,date));}
        return row;
    }
    static Intent completionIntent(JSONObject item,LocalDate date){return new Intent().putExtra("action","complete").putExtra("item",item.optString("id")).putExtra("date",date.toString()).putExtra("revision",item.optInt("revision"));}
}
