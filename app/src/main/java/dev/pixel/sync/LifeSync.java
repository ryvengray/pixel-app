package dev.pixel.sync;

import android.app.job.*;
import android.content.*;

/** Persisted, coalesced jobs: local export has no network requirement; Git does. */
public final class LifeSync extends JobService {
    static final int EXPORT_JOB=4201,NETWORK_JOB=4202;
    private final java.util.Set<JobParameters> stopped=java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
    public static void schedule(Context context){
        try{schedule(context,false);}catch(RuntimeException error){context.getSharedPreferences("life_sync",0).edit().putString("error","后台同步暂未安排，记录已保存在手机；打开生活记录后重试").apply();}
    }
    private static void schedule(Context context,boolean network){
        if(context.getSharedPreferences("life_sync",0).getString("tree","").isEmpty())return;
        JobInfo.Builder job=new JobInfo.Builder(network?NETWORK_JOB:EXPORT_JOB,new ComponentName(context,LifeSync.class))
            .setPersisted(true).setMinimumLatency(network?10000:1500).setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL);
        if(network)job.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
        int result=context.getSystemService(JobScheduler.class).schedule(job.build());
        if(result!=JobScheduler.RESULT_SUCCESS)context.getSharedPreferences("life_sync",0).edit().putString("error","后台任务暂未安排，打开生活记录后重试").apply();
    }
    @Override public boolean onStartJob(JobParameters params){
        worker.execute(()->{
            boolean retry=false;
            try{
                synchronized(SyncRunner.class){
                    if(stopped.contains(params)||getSharedPreferences("life_sync",0).getString("tree","").isEmpty()){jobFinished(params,false);return;}
                    // Never modify vault files while the existing Git script is running.
                    if(getSharedPreferences("sync",0).getBoolean("running",false)){retry=true;}
                    else try(LifeRecords records=new LifeRecords(this)){
                        VaultExport.export(this,records);
                        if(records.pendingSync()){
                            if(params.getJobId()==NETWORK_JOB){if(!stopped.contains(params))SyncRunner.start(this);}else schedule(this,true);
                        }
                        getSharedPreferences("life_sync",0).edit().remove("error").apply();
                    }
                }
            }catch(Exception error){
                getSharedPreferences("life_sync",0).edit().putString("error","待同步："+error.getMessage()).apply();
                // Revoked directory/Termux permissions need user action. Transient I/O retries back off.
                retry=!(error instanceof SecurityException||error instanceof android.content.pm.PackageManager.NameNotFoundException);
            }
            jobFinished(params,retry);
        });return true;
    }
    @Override public boolean onStopJob(JobParameters params){stopped.add(params);return true;}
    @Override public void onDestroy(){worker.shutdown();super.onDestroy();}
}
