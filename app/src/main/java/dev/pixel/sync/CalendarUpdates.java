package dev.pixel.sync;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;
import org.json.*;

/** One local scheduler owns calendar notifications; no server duplicate or exact-alarm permission. */
public final class CalendarUpdates extends BroadcastReceiver {
    private static final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor();
    static void changed(Context context){Context app=context.getApplicationContext();worker.execute(()->refresh(app));}
    public void onReceive(Context context,Intent intent){PendingResult pending=goAsync();worker.execute(()->{try{refresh(context.getApplicationContext());}finally{pending.finish();}});}
    private static final class Due {String id,body,date;long at,expires;boolean privateContent;Due(String i,String b,LocalDate d,int ahead,LocalTime t,ZoneId z,boolean p){id=i;body=b;date=d.toString();at=d.minusDays(ahead).atTime(t).atZone(z).toInstant().toEpochMilli();expires=d.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli();privateContent=p;}}
    private static void refresh(Context context){
        try(CalendarStore store=new CalendarStore(context)){
            CalendarWidget.updateAll(context);
            List<Due> tasks=new ArrayList<>();long now=System.currentTimeMillis();
            for(JSONObject e:store.items("event")){
                if(!e.optBoolean("remind",true))continue;ZoneId zone=e.optBoolean("follow_timezone",true)?ZoneId.systemDefault():ZoneId.of(e.optString("timezone",ZoneId.systemDefault().getId()));LocalDate today=LocalDate.now(zone);int offset=e.optInt("remind_days",1);LocalTime time=LocalTime.parse(e.optString("remind_time","09:00"));
                for(LocalDate occurrence:CalendarDates.reminderOccurrences(e,today))tasks.add(new Due("event:"+e.getString("id")+":"+occurrence,e.getString("title")+" · "+occurrence,occurrence,offset,time,zone,false));
                if(e.has("milestone")){LocalDate d=LocalDate.parse(e.getString("date")).plusDays(e.getInt("milestone"));tasks.add(new Due("milestone:"+e.getString("id")+":"+d,e.getString("title")+" · 第"+e.getInt("milestone")+"天",d,offset,time,zone,false));}
            }
            JSONObject settings=store.settings(),prediction=store.prediction();
            if(settings.optBoolean("remind",true)&&prediction.optBoolean("available")){
                LocalDate d=LocalDate.parse(prediction.getString("next_start"));String last=prediction.optString("last_record_id",prediction.getString("last_start"));tasks.add(new Due("cycle:"+last+":"+d,"大姨妈预计 "+d+" 来，记得准备一下",d,settings.optInt("remind_days",2),LocalTime.parse(settings.optString("remind_time","09:00")),(settings.optBoolean("follow_timezone",true)?ZoneId.systemDefault():ZoneId.of(settings.optString("timezone",ZoneId.systemDefault().getId()))),true));
            }
            NotificationManager manager=context.getSystemService(NotificationManager.class);NotificationChannel channel=new NotificationChannel("calendar","日历提醒",NotificationManager.IMPORTANCE_DEFAULT);channel.enableLights(false);manager.createNotificationChannel(channel);
            Set<String> active=new HashSet<>();for(Due due:tasks)if(due.expires>now)active.add("calendar:"+due.id);Set<String> posted=new HashSet<>();for(android.service.notification.StatusBarNotification note:manager.getActiveNotifications())if(note.getTag()!=null)posted.add(note.getTag());
            for(android.service.notification.StatusBarNotification note:manager.getActiveNotifications())if(note.getTag()!=null&&note.getTag().startsWith("calendar:")&&!active.contains(note.getTag()))manager.cancel(note.getTag(),note.getId());
            long next=LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();boolean allowed=(Build.VERSION.SDK_INT<33||context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)&&manager.areNotificationsEnabled();
            for(Due due:tasks){
                if(due.at>now){next=Math.min(next,due.at);continue;}if(due.expires<=now||(store.delivered(due.id)&&!posted.contains("calendar:"+due.id)))continue;
                if(!allowed)continue;
                Intent open=new Intent(context,CalendarActivity.class).putExtra("date",due.date).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent pi=PendingIntent.getActivity(context,due.id.hashCode(),open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                boolean discreet=due.privateContent&&store.preferences().optBoolean("private_notifications",true);
                Notification publicVersion=new Notification.Builder(context,"calendar").setSmallIcon(R.drawable.ic_chat_history).setContentTitle("Greta · 日历").setContentText("你有一条日历提醒").build();
                Notification note=new Notification.Builder(context,"calendar").setSmallIcon(R.drawable.ic_chat_history).setContentTitle("Greta · 日历").setContentText(discreet?"你有一条日历提醒":due.body).setStyle(new Notification.BigTextStyle().bigText(discreet?"进入日历查看详情":due.body)).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicVersion).setContentIntent(pi).setOnlyAlertOnce(true).setAutoCancel(true).build();
                manager.notify("calendar:"+due.id,due.id.hashCode(),note);store.markDelivered(due.id);
            }
            AlarmManager alarms=context.getSystemService(AlarmManager.class);PendingIntent alarm=PendingIntent.getBroadcast(context,0,new Intent(context,CalendarUpdates.class).setAction("dev.pixel.sync.CALENDAR_TICK"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,Math.max(now+60000,next),alarm);
        }catch(Exception error){android.util.Log.w("GretaCalendar","Unable to refresh calendar: "+error.getClass().getSimpleName());}
    }
}
