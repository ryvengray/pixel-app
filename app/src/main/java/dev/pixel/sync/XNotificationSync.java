package dev.pixel.sync;

import android.app.job.*;
import android.content.*;
import org.json.*;
import java.util.concurrent.*;

/** Durable upload retry; summary generation is scheduled on the private service. */
public final class XNotificationSync extends JobService {
    private static final int PERIODIC=4301,UPLOAD=4302;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final java.util.Set<JobParameters> stopped=ConcurrentHashMap.newKeySet();
    static void schedule(Context c,boolean immediate){
        if(XNotificationStore.prefs(c).getLong("revision",0)==0)return;
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);ComponentName component=new ComponentName(c,XNotificationSync.class);
        try{
            if(scheduler.getPendingJob(PERIODIC)==null)scheduler.schedule(new JobInfo.Builder(PERIODIC,component).setPersisted(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15*60000L).build());
            if(immediate&&scheduler.getPendingJob(UPLOAD)==null)scheduler.schedule(new JobInfo.Builder(UPLOAD,component).setPersisted(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(1000).setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());
        }catch(RuntimeException e){XNotificationStore.prefs(c).edit().putString("error","后台同步暂未安排，请打开此页面重试").apply();}
    }
    @Override public boolean onStartJob(JobParameters params){worker.execute(()->{
        boolean retry=false;
        try{
            XNotificationStore.refreshPermission(this);SharedPreferences p=XNotificationStore.prefs(this);long revision=p.getLong("revision",0);
            boolean enabled=p.getBoolean("enabled",false)&&XNotificationStore.access(this);
            JSONArray authors=new JSONArray();for(String author:XNotificationStore.authors(p.getString("authors",XNotificationStore.DEFAULT_AUTHORS)).keySet())authors.put(author);
            JSONObject settings=new JSONObject().put("enabled",enabled).put("revision",revision).put("hour",p.getInt("hour",9)).put("timezone",java.util.TimeZone.getDefault().getID()).put("authors",authors).put("clear_pending",p.getBoolean("purge",false));
            JSONObject result=AssistantApi.xNotificationRequest(this,"settings",settings);if(!result.optBoolean("ok"))throw new IllegalStateException("设置已变化，请重新保存");
            synchronized(XNotificationStore.class){if(p.getLong("revision",0)==revision)p.edit().putBoolean("purge",false).commit();}
            if(enabled){try(XNotificationStore store=new XNotificationStore(this)){
                for(int batch=0;batch<10&&!stopped.contains(params);batch++){
                    if(p.getLong("revision",0)!=revision||!XNotificationStore.enabled(this)||!XNotificationStore.access(this))break;
                    JSONArray items=store.pending();if(items.length()==0)break;
                    JSONObject response=AssistantApi.xNotificationRequest(this,"batch",new JSONObject().put("revision",revision).put("items",items));
                    JSONArray ids=response.optJSONArray("accepted");if(ids==null||ids.length()==0)throw new IllegalStateException("通知未被接收，请检查关注账号设置");store.acknowledge(ids);
                }
            }}
            if(p.getLong("revision",0)!=revision)retry=true;
            p.edit().remove("error").putLong("last_sync",System.currentTimeMillis()).apply();
        }catch(Exception e){retry=true;XNotificationStore.prefs(this).edit().putString("error","等待同步，请检查助理服务连接").apply();}
        if(!stopped.contains(params))jobFinished(params,retry);
    });return true;}
    @Override public boolean onStopJob(JobParameters params){stopped.add(params);return true;}
    @Override public void onDestroy(){worker.shutdown();super.onDestroy();}
}
