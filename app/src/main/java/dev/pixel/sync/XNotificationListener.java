package dev.pixel.sync;

import android.service.notification.*;
import java.util.concurrent.*;

/** Only new opted-in X social notifications; never enumerates notification history. */
public final class XNotificationListener extends NotificationListenerService {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    @Override public void onNotificationPosted(StatusBarNotification sbn){
        if(sbn==null||!"com.twitter.android".equals(sbn.getPackageName())||!XNotificationStore.enabled(this))return;
        worker.execute(()->{if(XNotificationStore.capture(this,sbn.getPackageName(),sbn.getNotification(),sbn.getPostTime()))XNotificationSync.schedule(this,true);});
    }
    @Override public void onListenerConnected(){XNotificationStore.refreshPermission(this);XNotificationSync.schedule(this,true);}
    @Override public void onListenerDisconnected(){XNotificationStore.refreshPermission(this);XNotificationSync.schedule(this,true);}
    @Override public void onDestroy(){worker.shutdown();super.onDestroy();}
}
