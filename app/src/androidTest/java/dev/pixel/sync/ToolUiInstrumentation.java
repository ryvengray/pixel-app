package dev.pixel.sync;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.json.JSONObject;

/** Dependency-free instrumentation for the actual drawer windows and local dispatch. */
public final class ToolUiInstrumentation extends Instrumentation {
    private Activity current;
    private int passed;
    private boolean homeSuite,syncSuite,recordSuite,imageSuite;
    @Override public void onCreate(Bundle args){super.onCreate(args);homeSuite=args!=null&&"home".equals(args.getString("suite"));syncSuite=args!=null&&"sync".equals(args.getString("suite"));recordSuite=args!=null&&"records".equals(args.getString("suite"));imageSuite=args!=null&&"images".equals(args.getString("suite"));start();}
    @Override public void onStart(){
        if(imageSuite){new ImageImportChecks(this).run();return;}
        if(recordSuite){new RecordChecks(this).run();return;}
        if(homeSuite){new HomeInteractionChecks(this).run();return;}
        if(syncSuite){new SyncInteractionChecks(this).run();return;}
        SharedPreferences prefs=getTargetContext().getSharedPreferences("gray_settings",0);
        String previous=prefs.getString("tool_presentation",null),server=prefs.getString("server_url",null);
        boolean decision=prefs.getBoolean("decision_enabled",false);
        Bundle result=new Bundle();int code=Activity.RESULT_OK;
        try{
            prefs.edit().putString("server_url","").putString("tool_presentation","drawer").putBoolean("decision_enabled",false).commit();
            check("drawerDefault",()->{
                current=launch(SyncActivity.class,null);waitForIdleSync();
                runOnMainSync(()->{
                    expect(((ToolActivity)current).isToolDrawer(),"expected drawer");
                    expect(current.getWindow().getAttributes().gravity==Gravity.BOTTOM,"expected bottom gravity");
                    expect(find(current.getWindow().getDecorView(),"立即同步")!=null,"sync content missing");
                    expect(findDescription(current.getWindow().getDecorView(),"关闭工具")!=null,"close control missing");
                });
            });
            close();
            check("pagePreference",()->{
                prefs.edit().putString("tool_presentation","page").commit();current=launch(SyncActivity.class,null);
                runOnMainSync(()->expect(!((ToolActivity)current).isToolDrawer(),"page preference ignored"));
            });
            close();prefs.edit().putString("tool_presentation","drawer").commit();
            check("printDraft",()->{
                Intent extras=new Intent().putExtra(PrintActivity.EXTRA_DRAFT_TEXT,"午饭花了28元");current=launch(PrintActivity.class,extras);
                runOnMainSync(()->expect(find(current.getWindow().getDecorView(),"午饭花了28元")!=null,"print draft lost"));
            });
            check("expandPreservesEditedDraft",()->{
                ActivityMonitor monitor=addMonitor(PrintActivity.class.getName(),null,false);
                runOnMainSync(()->{
                    ((TextView)find(current.getWindow().getDecorView(),"午饭花了28元")).setText("修改后的打印草稿");
                    findDescription(current.getWindow().getDecorView(),"在新界面打开工具").performClick();
                });
                Activity expanded=monitor.waitForActivityWithTimeout(4000);removeMonitor(monitor);
                expect(expanded instanceof PrintActivity,"full page did not open");current=expanded;
                runOnMainSync(()->{
                    expect(!((ToolActivity)current).isToolDrawer(),"expanded page is still a drawer");
                    expect(find(current.getWindow().getDecorView(),"修改后的打印草稿")!=null,"edited draft lost during expand");
                });
            });
            close();
            check("topicsDrawer",()->{
                current=launch(TopicsActivity.class,null);
                runOnMainSync(()->expect(((ToolActivity)current).isToolDrawer(),"topics drawer missing"));
            });
            close();
            check("dispatchWithoutBgeAndDeduplicate",()->{
                current=launch(MainActivity.class,null);waitForIdleSync();
                ActivityMonitor monitor=addMonitor(SyncActivity.class.getName(),null,false);
                JSONObject action=new JSONObject().put("name","open_sync").put("arguments",new JSONObject()).put("id","ui-"+System.nanoTime());
                Activity main=current;
                runOnMainSync(()->DecisionActions.open(main,action));
                Activity opened=monitor.waitForActivityWithTimeout(4000);
                expect(opened instanceof SyncActivity,"LLM local action was not opened with BGE disabled");
                runOnMainSync(opened::finish);waitForIdleSync();
                runOnMainSync(()->DecisionActions.open(main,action));waitForIdleSync();
                expect(monitor.getHits()==1,"same action opened twice");removeMonitor(monitor);
            });
            check("pendingActionWaitsForFocus",()->{
                Activity main=current;
                android.app.Dialog[] dialog=new android.app.Dialog[1];
                runOnMainSync(()->{
                    dialog[0]=new android.app.Dialog(main);dialog[0].setContentView(new TextView(main));dialog[0].show();
                });waitForIdleSync();waitForFocus(main,false);
                ActivityMonitor monitor=addMonitor(SyncActivity.class.getName(),null,false);
                JSONObject action=new JSONObject().put("name","open_sync").put("arguments",new JSONObject()).put("id","pending-"+System.nanoTime());
                runOnMainSync(()->DecisionActions.open(main,action));waitForIdleSync();
                expect(monitor.getHits()==0,"action opened over another focused window");
                runOnMainSync(()->dialog[0].dismiss());
                Activity opened=monitor.waitForActivityWithTimeout(4000);removeMonitor(monitor);
                expect(opened instanceof SyncActivity,"pending action was discarded on focus change");runOnMainSync(opened::finish);waitForIdleSync();
            });
            check("validationAndQuestions",()->{
                expect(!ClientTools.valid("shell",new JSONObject()),"unknown action accepted");
                expect(!ClientTools.valid("open_sync",new JSONObject().put("command","rm")),"unknown parameter accepted");
                expect(!ClientTools.valid("open_print",new JSONObject().put("text",42)),"invalid text type accepted");
                expect(ClientTools.explicitCommand("同步笔记"),"sync command rejected");
                expect(!ClientTools.explicitCommand("如何同步笔记"),"question accepted");
                expect(!ClientTools.explicitCommand("不要同步笔记"),"negative accepted");
                expect(ClientTools.definitions(getTargetContext()).length()==5,"shared tool assets missing");
            });
            result.putString("stream","\nTool UI tests passed: "+passed+"\n");
        }catch(Throwable error){
            code=Activity.RESULT_CANCELED;result.putString("stream",android.util.Log.getStackTraceString(error));
        }finally{
            close();SharedPreferences.Editor restore=prefs.edit().putBoolean("decision_enabled",decision);
            if(previous==null)restore.remove("tool_presentation");else restore.putString("tool_presentation",previous);
            if(server==null)restore.remove("server_url");else restore.putString("server_url",server);
            restore.commit();
        }
        finish(code,result);
    }
    private interface Check {void run()throws Exception;}
    private void check(String name,Check work)throws Exception{
        Bundle report=new Bundle();report.putString("class",getClass().getName());report.putString("test",name);report.putInt("numtests",8);report.putInt("current",passed+1);
        sendStatus(1,report);
        try{work.run();passed++;sendStatus(0,report);}catch(Throwable error){report.putString("stack",android.util.Log.getStackTraceString(error));sendStatus(-2,report);throw error;}
    }
    private Activity launch(Class<?> type,Intent extras){
        Intent intent=new Intent(getTargetContext(),type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if(extras!=null)intent.putExtras(extras);return startActivitySync(intent);
    }
    private void close(){if(current!=null){Activity previous=current;runOnMainSync(previous::finish);current=null;waitForIdleSync();}}
    private void waitForFocus(Activity activity,boolean expected){
        long end=android.os.SystemClock.uptimeMillis()+4000;boolean[] focus=new boolean[1];
        do{
            runOnMainSync(()->focus[0]=activity.hasWindowFocus());
            if(focus[0]==expected)return;
            android.os.SystemClock.sleep(50);
        }while(android.os.SystemClock.uptimeMillis()<end);
        throw new AssertionError("window focus did not settle");
    }
    private static void expect(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static View find(View root,String label){
        if(root instanceof TextView&&label.contentEquals(((TextView)root).getText()))return root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=find(((ViewGroup)root).getChildAt(i),label);if(found!=null)return found;}
        return null;
    }
    private static View findDescription(View root,String label){
        if(label.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=findDescription(((ViewGroup)root).getChildAt(i),label);if(found!=null)return found;}
        return null;
    }
}
