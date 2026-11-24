package dev.pixel.sync;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.RemoteViews;

/** Direct activity PendingIntents keep microphone access in a visible foreground activity. */
public class QuickChatWidget extends AppWidgetProvider {
    static final String EXTRA_CAPTURE = "dev.pixel.sync.QUICK_CAPTURE";
    static boolean validMode(String mode) {
        return "chat".equals(mode)||"record".equals(mode)||"reminder".equals(mode);
    }
    static Intent captureIntent(Context context,String mode) {
        return new Intent(context,MainActivity.class)
                .setData(Uri.parse("greta-capture://"+mode))
                .putExtra(EXTRA_CAPTURE,mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
    @Override public void onUpdate(Context context,AppWidgetManager manager,int[] ids) {
        for(int id:ids)update(context,manager,id);
    }
    @Override public void onAppWidgetOptionsChanged(Context context,AppWidgetManager manager,int id,Bundle options) {
        update(context,manager,id);
    }
    private static void update(Context context,AppWidgetManager manager,int id) {
        RemoteViews views=new RemoteViews(context.getPackageName(),R.layout.quick_chat_widget);
        bind(context,views,R.id.quick_chat,"chat");
        bind(context,views,R.id.quick_record,"record");
        bind(context,views,R.id.quick_reminder,"reminder");
        manager.updateAppWidget(id,views);
    }
    private static void bind(Context context,RemoteViews views,int viewId,String mode) {
        views.setOnClickPendingIntent(viewId,PendingIntent.getActivity(context,0,captureIntent(context,mode),
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
    }
}
