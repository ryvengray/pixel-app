package dev.pixel.sync;

import android.content.ComponentName;
import android.content.Context;
import android.service.notification.*;
import java.util.concurrent.*;

/** Only new opted-in X social notifications; never enumerates notification history. */
public final class XNotificationListener extends NotificationListenerService {
    private static volatile boolean connected;
    static boolean connected(){return connected;}
    static void reconnect(Context context){
        if(!connected&&XNotificationStore.enabled(context)&&XNotificationStore.access(context)){
            requestRebind(new ComponentName(context,XNotificationListener.class));
        }
    }
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    @Override public void onNotificationPosted(StatusBarNotification sbn){
        if(sbn==null||!"com.twitter.android".equals(sbn.getPackageName())||!XNotificationStore.enabled(this))return;
        worker.execute(()->{if(XNotificationStore.capture(this,sbn.getPackageName(),sbn.getNotification(),sbn.getPostTime()))XNotificationSync.schedule(this,true);});
    }
    @Override public void onListenerConnected(){connected=true;XNotificationStore.refreshPermission(this);XNotificationSync.schedule(this,true);}
    @Override public void onListenerDisconnected(){connected=false;XNotificationStore.refreshPermission(this);XNotificationSync.schedule(this,true);}
    @Override public void onDestroy(){connected=false;worker.shutdown();super.onDestroy();}
}
