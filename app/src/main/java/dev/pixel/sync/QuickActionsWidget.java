package dev.pixel.sync;

import android.app.PendingIntent;
import android.appwidget.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.widget.RemoteViews;

/** Six configurable entry points. Actions open the relevant screen; they never silently submit work. */
public class QuickActionsWidget extends AppWidgetProvider {
    static final String[] KEYS={"record","reminder","write","calendar","add_date","records","news","topics","memory","history","math","print","sync","settings","chat"};
    static final String[] LABELS={"语音记事","添加提醒","写一条","日历","新增日程","生活记录","新闻订阅","重要话题","长期记忆","历史记录","数学冒险岛","打印文件","Obsidian 同步","助理设置","语音对话"};
    static final String[] DEFAULTS={"record","reminder","write","calendar","news","math"};
    static final int[] SLOTS={R.id.action_slot_0,R.id.action_slot_1,R.id.action_slot_2,R.id.action_slot_3,R.id.action_slot_4,R.id.action_slot_5};
    static int index(String key){for(int i=0;i<KEYS.length;i++)if(KEYS[i].equals(key))return i;return 0;}
    static SharedPreferences prefs(Context c,int id){return c.getSharedPreferences("quick_actions_widget_"+id,0);}
    static boolean owned(Context c,int id){AppWidgetProviderInfo info=AppWidgetManager.getInstance(c).getAppWidgetInfo(id);return info!=null&&info.provider.equals(new ComponentName(c,QuickActionsWidget.class));}
    static Intent actionIntent(Context c,String key){
        if(QuickChatWidget.validMode(key))return QuickChatWidget.captureIntent(c,key);
        Class<?> target;
        switch(key){
            case "write":case "records":target=RecordsActivity.class;break;
            case "calendar":case "add_date":target=CalendarActivity.class;break;
            case "news":target=NewsSubscriptionsActivity.class;break;
            case "topics":target=TopicsActivity.class;break;
            case "memory":target=MemoryActivity.class;break;
            case "history":target=HistoryActivity.class;break;
            case "math":target=WebPageActivity.class;break;
            case "print":target=PrintActivity.class;break;
            case "sync":target=SyncActivity.class;break;
            default:target=AssistantSettingsActivity.class;
        }
        Intent intent=new Intent(c,target).setAction("dev.pixel.sync.WIDGET_OPEN_"+key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if("write".equals(key))intent.putExtra("quick_add",true);
        if("add_date".equals(key))intent.putExtra("add",true);
        // Opening sync should let the user review the screen before initiating a sync.
        if("sync".equals(key))intent.putExtra("dev.pixel.sync.extra.SKIP_AUTO_SYNC",true);
        return intent;
    }
    private static PendingIntent pending(Context c,Intent intent){return PendingIntent.getActivity(c,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    static void update(Context c,int id){
        if(!owned(c,id))return;
        RemoteViews views=new RemoteViews(c.getPackageName(),R.layout.quick_actions_widget);
        views.setOnClickPendingIntent(R.id.quick_chat,pending(c,QuickChatWidget.captureIntent(c,"chat")));
        for(int slot=0;slot<SLOTS.length;slot++){
            int index=index(prefs(c,id).getString("slot_"+slot,DEFAULTS[slot]));
            views.setTextViewText(SLOTS[slot],LABELS[index]);
            views.setContentDescription(SLOTS[slot],"打开"+LABELS[index]);
            views.setOnClickPendingIntent(SLOTS[slot],pending(c,actionIntent(c,KEYS[index])));
        }
        Intent configure=new Intent(c,QuickActionsWidgetSettingsActivity.class).setData(Uri.parse("greta-widget-settings://"+id)).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id);
        views.setOnClickPendingIntent(R.id.quick_customize,pending(c,configure));
        AppWidgetManager.getInstance(c).updateAppWidget(id,views);
    }
    @Override public void onUpdate(Context c,AppWidgetManager manager,int[] ids){for(int id:ids)update(c,id);}
    @Override public void onAppWidgetOptionsChanged(Context c,AppWidgetManager manager,int id,Bundle options){update(c,id);}
    @Override public void onDeleted(Context c,int[] ids){for(int id:ids)prefs(c,id).edit().clear().apply();}
}
