package dev.pixel.sync;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import java.util.IdentityHashMap;

public final class GretaApplication extends Application {
    @Override public void onCreate(){
        super.onCreate();
        CalendarUpdates.changed(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks(){
            private final IdentityHashMap<Activity,ViewTreeObserver.OnGlobalLayoutListener> listeners=new IdentityHashMap<>();
            @Override public void onActivityPostCreated(Activity activity,Bundle state){
                View root=activity.getWindow().getDecorView();
                ViewTreeObserver.OnGlobalLayoutListener listener=()->AppTypography.apply(root);
                listeners.put(activity,listener);root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
                AppTypography.apply(root);
            }
            @Override public void onActivityPostResumed(Activity activity){AppTypography.apply(activity.getWindow().getDecorView());}
            @Override public void onActivityDestroyed(Activity activity){
                ViewTreeObserver.OnGlobalLayoutListener listener=listeners.remove(activity);
                if(listener!=null)activity.getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(listener);
            }
            @Override public void onActivityCreated(Activity activity,Bundle state){}
            @Override public void onActivityStarted(Activity activity){}
            @Override public void onActivityResumed(Activity activity){}
            @Override public void onActivityPaused(Activity activity){}
            @Override public void onActivityStopped(Activity activity){}
            @Override public void onActivitySaveInstanceState(Activity activity,Bundle state){}
        });
    }
}
