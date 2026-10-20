package dev.pixel.sync;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import java.util.Map;

/** Exercises countdown/locking with no Termux installed, never touching a real vault. */
final class SyncInteractionChecks {
    private final Instrumentation test;
    private Activity current;
    private SharedPreferences prefs;
    private int passed;
    SyncInteractionChecks(Instrumentation test){this.test=test;}
    void run(){
        Context context=test.getTargetContext();prefs=context.getSharedPreferences("sync",0);
        Map<String,?> saved=prefs.getAll();Bundle result=new Bundle();int code=Activity.RESULT_OK;
        try{
            expect(Build.HARDWARE.equals("ranchu")||Build.HARDWARE.equals("goldfish"),"Use an independent Android emulator for these tests");
            boolean installed;try{context.getPackageManager().getPackageInfo("com.termux",0);installed=true;}catch(PackageManager.NameNotFoundException missing){installed=false;}
            expect(!installed,"Tests require an emulator without Termux to avoid running actual sync commands");
            check("countdownCanBeCancelled",()->{
                reset();launch();await(()->view("sync_countdown").getVisibility()==View.VISIBLE,"countdown did not appear");
                ui(()->{expect(((TextView)view("sync_countdown_text")).getText().toString().contains("秒后自动同步"),"countdown label missing");view("sync_cancel_auto").performClick();});
                SystemClock.sleep(3300);
                ui(()->{expect(view("sync_countdown").getVisibility()==View.GONE,"cancel did not hide countdown");expect(view("sync_start").isEnabled(),"manual sync should remain available");});
                expect("ready".equals(prefs.getString("status","")),"cancelled countdown launched a task");close();
            });
            check("autoStartsAfterThreeSeconds",()->{
                reset();long opened=SystemClock.elapsedRealtime();launch();
                await(()->"启动失败".equals(prefs.getString("status","")),"automatic start was not attempted");
                expect(SystemClock.elapsedRealtime()-opened>=2900,"automatic start happened before three seconds");
                expect(!prefs.getBoolean("running",false),"failed start left running flag set");
                ui(()->{expect(view("sync_loading").getVisibility()==View.GONE,"failed start left spinner visible");expect(view("sync_start").isEnabled(),"failed start did not restore button");});close();
            });
            check("manualStartSkipsCountdown",()->{
                reset();launch();await(()->view("sync_countdown").getVisibility()==View.VISIBLE,"countdown missing");
                ui(()->view("sync_start").performClick());
                expect("启动失败".equals(prefs.getString("status","")),"manual start did not attempt immediately");
                ui(()->expect(view("sync_countdown").getVisibility()==View.GONE,"manual start left timer armed"));close();
            });
            check("dismissCancelsTimer",()->{
                reset();launch();await(()->view("sync_countdown").getVisibility()==View.VISIBLE,"countdown missing");close();SystemClock.sleep(3300);
                expect("ready".equals(prefs.getString("status","")),"dismissed tool still started sync");
            });
            check("runningAndStaleTasksStayLocked",()->{
                reset();prefs.edit().putBoolean("running",true).putString("runId","sync-test-running").putLong("started",System.currentTimeMillis()-700000).commit();launch();
                ui(()->{
                    expect(!view("sync_start").isEnabled(),"running task allowed another click");
                    expect(view("sync_loading").getVisibility()==View.VISIBLE,"running spinner missing");
                    expect(view("sync_countdown").getVisibility()==View.GONE,"running task armed another timer");
                    view("sync_start").performClick();
                });
                expect(prefs.getBoolean("running",false),"duplicate call cleared the active task");
                expect("sync-test-running".equals(prefs.getString("runId","")),"duplicate call replaced run id");
            });
            check("completionUnlocksAndDoesNotRestart",()->{
                deliver("foreign-run",true);expect(prefs.getBoolean("running",false),"foreign callback completed active task");
                deliver("sync-test-running",true);
                await(()->view("sync_start").isEnabled()&&view("sync_loading").getVisibility()==View.GONE,"completion did not unlock UI");
                expect("同步成功".equals(prefs.getString("status","")),"success result missing");SystemClock.sleep(3300);
                expect("同步成功".equals(prefs.getString("status","")),"completion rearmed auto sync");close();
            });
            check("failureResultUnlocks",()->{
                reset();prefs.edit().putBoolean("running",true).putString("runId","sync-test-failed").putLong("started",System.currentTimeMillis()).commit();launch();
                deliver("sync-test-failed",false);await(()->view("sync_start").isEnabled(),"failed task did not unlock UI");
                expect("同步失败".equals(prefs.getString("status","")),"failure result missing");close();
            });
            check("fullscreenCancelsPendingAutoStart",()->{
                reset();launch();await(()->view("sync_countdown").getVisibility()==View.VISIBLE,"countdown missing");
                Instrumentation.ActivityMonitor monitor=test.addMonitor(SyncActivity.class.getName(),null,false);
                ui(()->{Intent intent=new Intent(current.getIntent()).setFlags(0).putExtra(ToolActivity.EXTRA_PRESENTATION,"page");((SyncActivity)current).prepareFullPage(intent);current.startActivity(intent);current.finish();});
                Activity replacement=monitor.waitForActivityWithTimeout(4000);test.removeMonitor(monitor);expect(replacement instanceof SyncActivity,"fullscreen tool missing");current=replacement;
                SystemClock.sleep(3300);expect("ready".equals(prefs.getString("status","")),"fullscreen switch rearmed timer");close();
            });
            check("recreationDoesNotRearm",()->{
                reset();launch();await(()->view("sync_countdown").getVisibility()==View.VISIBLE,"countdown missing");
                Instrumentation.ActivityMonitor monitor=test.addMonitor(SyncActivity.class.getName(),null,false);ui(current::recreate);
                Activity replacement=monitor.waitForActivityWithTimeout(4000);test.removeMonitor(monitor);expect(replacement instanceof SyncActivity,"recreated tool missing");current=replacement;
                SystemClock.sleep(3300);expect("ready".equals(prefs.getString("status","")),"recreation rearmed timer");close();
            });
            result.putString("stream","\nSync interaction tests passed: "+passed+"\n");
        }catch(Throwable error){code=Activity.RESULT_CANCELED;result.putString("stream",android.util.Log.getStackTraceString(error));}
        finally{
            close();SharedPreferences.Editor restore=prefs.edit().clear();
            for(Map.Entry<String,?> entry:saved.entrySet()){
                Object value=entry.getValue();String key=entry.getKey();
                if(value instanceof String)restore.putString(key,(String)value);else if(value instanceof Boolean)restore.putBoolean(key,(Boolean)value);
                else if(value instanceof Long)restore.putLong(key,(Long)value);else if(value instanceof Integer)restore.putInt(key,(Integer)value);
            }restore.commit();
        }
        test.finish(code,result);
    }
    private void reset(){prefs.edit().clear().putString("status","ready").commit();}
    private void launch(){current=test.startActivitySync(new Intent(test.getTargetContext(),SyncActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));test.waitForIdleSync();}
    private View view(String tag){return current.getWindow().getDecorView().findViewWithTag(tag);}
    private void close(){if(current!=null){Activity previous=current;ui(previous::finish);current=null;test.waitForIdleSync();}}
    private void deliver(String runId,boolean ok){ui(()->{
        Bundle payload=new Bundle();payload.putInt("exitCode",ok?0:1);payload.putInt("err",-1);payload.putString("stdout","test result");
        new ResultReceiver().onReceive(test.getTargetContext(),new Intent().putExtra("runId",runId).putExtra("result",payload));
    });}
    private interface Check{void run()throws Exception;}
    private interface Condition{boolean get();}
    private void check(String name,Check work)throws Exception{
        Bundle report=new Bundle();report.putString("class",getClass().getName());report.putString("test",name);report.putInt("numtests",9);report.putInt("current",passed+1);test.sendStatus(1,report);
        try{work.run();passed++;test.sendStatus(0,report);}catch(Throwable error){report.putString("stack",android.util.Log.getStackTraceString(error));test.sendStatus(-2,report);throw error;}
    }
    private void await(Condition condition,String message){long until=SystemClock.elapsedRealtime()+5000;boolean[] ok=new boolean[1];do{ui(()->ok[0]=condition.get());if(ok[0])return;SystemClock.sleep(40);}while(SystemClock.elapsedRealtime()<until);throw new AssertionError(message);}
    private void ui(Runnable work){test.runOnMainSync(work);}
    private static void expect(boolean value,String message){if(!value)throw new AssertionError(message);}
}
