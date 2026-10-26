package dev.pixel.sync;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.time.*;
import java.util.*;

/** Isolated facts and deterministic recurrence checks; never touches a real user's calendar. */
final class CalendarChecks {
    private final Instrumentation test;private int passed;private Context context;private CalendarStore store;
    CalendarChecks(Instrumentation test){this.test=test;}
    private interface Check{void run()throws Exception;}
    private void check(String name,Check check)throws Exception{check.run();passed++;Bundle status=new Bundle();status.putString("test",name);status.putInt("current",passed);test.sendStatus(0,status);}
    private static void expect(boolean value,String message){if(!value)throw new AssertionError(message);}
    private JSONObject task(String date,String repeat)throws Exception{return new JSONObject().put("title","测试待办").put("event_type","todo").put("date",date).put("repeat",repeat).put("calendar","solar").put("year_known",true).put("remind",false);}
    private void next(JSONObject event,String after,String expected){expect(Objects.equals(CalendarDates.occurrence(event,LocalDate.parse(after)),expected==null?null:LocalDate.parse(expected)),event+" after "+after+" expected "+expected);}
    private JSONObject save(JSONObject args)throws Exception{return store.write("event",args,UUID.randomUUID().toString()).getJSONObject("record");}
    private void rejected(JSONObject args)throws Exception{boolean rejected=false;try{save(args);}catch(IllegalArgumentException error){rejected=true;}expect(rejected,"invalid calendar entry accepted");}
    private static View find(View view,String value){if(view instanceof TextView&&value.equals(((TextView)view).getText().toString()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),value);if(found!=null)return found;}return null;}
    void run(){
        context=test.getTargetContext();Bundle result=new Bundle();int code=Activity.RESULT_OK;
        try{
            expect(Build.HARDWARE.equals("ranchu")||Build.HARDWARE.equals("goldfish"),"Use an independent emulator");context.deleteDatabase("calendar-tests.db");store=new CalendarStore(context,"calendar-tests.db");
            check("dailyWeeklyBiweeklyAnchors",()->{
                for(String repeat:new String[]{"daily","weekly","biweekly"})next(task("2026-10-26",repeat),"2026-10-01","2026-10-26");
                next(task("2026-10-26","daily"),"2026-10-27","2026-10-27");next(task("2026-10-26","weekly"),"2026-10-27","2026-11-02");next(task("2026-10-26","biweekly"),"2026-11-02","2026-11-09");next(task("2026-10-26","biweekly"),"2026-11-09","2026-11-09");
            });
            check("monthlyDoesNotDriftAfterShortMonths",()->{
                JSONObject e=task("2024-01-31","monthly");next(e,"2024-02-01","2024-02-29");next(e,"2024-03-01","2024-03-31");next(e,"2025-02-01","2025-02-28");next(e,"2025-04-01","2025-04-30");next(e,"2025-05-01","2025-05-31");next(e,"2023-01-01","2024-01-31");
            });
            check("yearlyLeapAndLegacyCompatibility",()->{
                JSONObject e=task("2024-02-29","yearly");next(e,"2025-01-01","2025-02-28");e.put("leap_day","march1");next(e,"2025-01-01","2025-03-01");next(e,"2028-01-01","2028-02-29");
                e.remove("repeat");e.put("yearly",true);next(e,"2026-03-02","2027-03-01");e.put("yearly",false);next(e,"2026-01-01","2024-02-29");
                JSONObject lunar=new JSONObject().put("title","农历生日").put("date","2026-09-25").put("calendar","lunar").put("yearly",true);next(lunar,"2026-09-25","2026-09-25");expect(CalendarDates.occurrence(lunar,LocalDate.parse("2026-09-26")).isAfter(LocalDate.parse("2026-09-26")),"legacy lunar recurrence lost");
            });
            check("allDailyOccurrencesBeyondOldTwentyLimit",()->{
                JSONObject e=save(task("2026-01-01","daily"));JSONArray events=store.events(LocalDate.parse("2026-01-01"),LocalDate.parse("2026-02-01"));expect(events.length()==32,"daily occurrences truncated");expect(!events.getJSONObject(31).optBoolean("completed"),"new occurrence completed");store.write("event",new JSONObject().put("id",e.getString("id")).put("expected_revision",1).put("deleted",true),UUID.randomUUID().toString());
            });
            check("perOccurrenceCompletionRestartRetryUndo",()->{
                JSONObject e=save(task("2026-10-26","weekly"));String id=e.getString("id"),op=UUID.randomUUID().toString();LocalDate first=LocalDate.parse("2026-10-26"),second=first.plusWeeks(1);
                JSONObject completed=store.completeTodo(id,1,first,true,op);store.completeTodo(id,1,first,true,op);
                try(CalendarStore reopened=new CalendarStore(context,"calendar-tests.db")){JSONObject saved=reopened.get(id);expect(saved.getInt("revision")==2,"completion retry duplicated");expect(CalendarDates.completed(saved,first)&&!CalendarDates.completed(saved,second),"completion affected next occurrence");}
                JSONArray events=store.events(first,second);expect(events.getJSONObject(0).getBoolean("completed")&&!events.getJSONObject(1).getBoolean("completed"),"query completion status wrong");
                store.undo(completed.getString("operation_id"),UUID.randomUUID().toString());expect(!CalendarDates.completed(store.get(id),first),"undo did not restore pending occurrence");
                boolean rejected=false;try{store.completeTodo(id,store.get(id).getInt("revision"),first.plusDays(1),true,UUID.randomUUID().toString());}catch(IllegalArgumentException error){rejected=true;}expect(rejected,"completion on non-occurrence accepted");
                rejected=false;try{store.completeTodo(id,1,first,true,UUID.randomUUID().toString());}catch(IllegalArgumentException error){rejected=true;}expect(rejected,"stale completion revision accepted");
            });
            check("repeatPatchesRetainLegacyClients",()->{
                JSONObject e=save(task("2026-10-26","biweekly"));String id=e.getString("id");expect(!e.getBoolean("yearly"),"legacy yearly out of sync");
                e=save(new JSONObject().put("id",id).put("expected_revision",1).put("note","改备注"));expect(CalendarDates.repeat(e).equals("biweekly"),"patch cleared repeat");
                e=save(new JSONObject().put("id",id).put("expected_revision",2).put("yearly",true));expect(CalendarDates.repeat(e).equals("yearly"),"legacy yearly patch ignored");
            });
            check("undoRepeatPatchOnLegacyRecord",()->{
                JSONObject e=save(new JSONObject().put("title","旧生日").put("date","2020-10-26").put("yearly",true));String id=e.getString("id");
                // Existing releases stored only yearly; simulate that persisted representation.
                e.remove("repeat");android.content.ContentValues values=new android.content.ContentValues();values.put("body",e.toString());store.getWritableDatabase().update("item",values,"id=?",new String[]{id});
                JSONObject patched=store.write("event",new JSONObject().put("id",id).put("expected_revision",1).put("repeat","monthly"),UUID.randomUUID().toString());store.undo(patched.getString("operation_id"),UUID.randomUUID().toString());expect(CalendarDates.repeat(store.get(id)).equals("yearly"),"undo broke legacy annual recurrence");
            });
            check("validationAndToolSchema",()->{
                rejected(task("2026-10-26","invalid"));rejected(task("2026-10-26","weekly").put("calendar","lunar"));rejected(task("2026-10-26","yearly").put("year_known",false));rejected(task("2026-10-26","monthly").put("milestone",100));
                expect(CalendarTools.valid("save_special_date",task("2026-10-26","biweekly")),"to-do tool fields rejected");expect(!CalendarTools.valid("save_special_date",task("2026-10-26","daily").put("completed_dates",new JSONArray())),"raw completion history accepted");
                JSONObject args=new JSONObject().put("id","task").put("expected_revision",1).put("occurrence","2026-10-26").put("completed",true);expect(CalendarTools.valid("set_calendar_todo_completed",args),"completion tool rejected");args.remove("occurrence");expect(!CalendarTools.valid("set_calendar_todo_completed",args),"completion without occurrence accepted");
            });
            check("remindersAdvanceAndSkipCompletedOccurrences",()->{
                JSONObject e=task("2026-10-01","daily").put("remind_days",0);LocalDate today=LocalDate.parse("2026-10-26");List<LocalDate> dates=CalendarDates.reminderOccurrences(e,today);expect(dates.contains(today)&&dates.contains(today.plusDays(1)),"daily reminder stuck on past date");
                e.put("completed_dates",new JSONArray().put(today.toString()));dates=CalendarDates.reminderOccurrences(e,today);expect(!dates.contains(today)&&dates.contains(today.plusDays(1)),"completed occurrence still reminded or next reminder lost");
                e.put("remind_days",7);expect(CalendarDates.reminderOccurrences(e,today).contains(today.plusDays(7)),"advance reminder missed");
                e=task("2026-10-27","yearly").put("remind_days",7);expect(CalendarDates.reminderOccurrences(e,today).contains(LocalDate.parse("2026-10-27")),"missed advance reminder no longer catches up");
                e=task("2026-10-01","weekly").put("remind_days",1);dates=CalendarDates.reminderOccurrences(e,today);expect(dates.get(0).equals(LocalDate.parse("2026-10-29")),"weekly next reminder missed");
            });
            check("calendarAddFormOffersTodosAndAllRepeatRules",()->{
                Activity activity=test.startActivitySync(new Intent(context,CalendarActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));test.waitForIdleSync();
                try{test.runOnMainSync(()->{try{
                    java.lang.reflect.Field storeField=CalendarActivity.class.getDeclaredField("store");storeField.setAccessible(true);((CalendarStore)storeField.get(activity)).close();storeField.set(activity,new CalendarStore(context,"calendar-ui-tests.db"));
                    java.lang.reflect.Method method=CalendarActivity.class.getDeclaredMethod("eventEditor",JSONObject.class);method.setAccessible(true);method.invoke(activity,new JSONObject().put("event_type","todo"));
                    java.lang.reflect.Field field=CalendarActivity.class.getDeclaredField("editor");field.setAccessible(true);Dialog dialog=(Dialog)field.get(activity);View content=dialog.getWindow().getDecorView();expect(find(content,"类型")!=null&&find(content,"重复")!=null,"to-do fields missing");
                    List<Spinner> spinners=new ArrayList<>();collect(content,spinners);expect(spinners.get(0).getSelectedItem().equals("待办"),"wrong task type");Spinner repeat=spinners.get(1);expect(repeat.getCount()==6,"repeat choices missing");for(int i=0;i<6;i++)expect(repeat.getItemAtPosition(i).equals(CalendarDates.REPEAT_LABELS[i]),"wrong repeat choice");
                    List<EditText> inputs=new ArrayList<>();collectInputs(content,inputs);inputs.get(0).setText("回归待办");repeat.setSelection(3);((Switch)find(content,"提醒我")).setChecked(false);find(content,"保存").performClick();
                    CalendarStore saved=(CalendarStore)storeField.get(activity);List<JSONObject> entries=saved.items("event");expect(entries.size()==1,"form did not save exactly one task");JSONObject entry=entries.get(0);expect(CalendarDates.todo(entry)&&CalendarDates.repeat(entry).equals("biweekly")&&entry.getBoolean("year_known"),"form saved wrong type or repetition");
                    JSONObject occurrence=saved.events(LocalDate.now(),LocalDate.now()).getJSONObject(0);method.invoke(activity,occurrence);dialog=(Dialog)field.get(activity);find(dialog.getWindow().getDecorView(),"标记完成 · "+LocalDate.now()).performClick();expect(CalendarDates.completed(saved.get(entry.getString("id")),LocalDate.now()),"form completion did not persist");
                }catch(Exception error){throw new RuntimeException(error);}});}finally{test.runOnMainSync(activity::finish);test.waitForIdleSync();context.deleteDatabase("calendar-ui-tests.db");}
            });
            check("upcomingDeduplicatesByRecordAndAdvancesCompletedTasks",()->{
                String db="calendar-upcoming-tests.db";context.deleteDatabase(db);try(CalendarStore isolated=new CalendarStore(context,db)){
                    LocalDate today=LocalDate.now();JSONObject biweekly=isolated.write("event",task(today.toString(),"biweekly"),UUID.randomUUID().toString()).getJSONObject("record");
                    JSONObject second=isolated.write("event",task(today.plusDays(1).toString(),"weekly"),UUID.randomUUID().toString()).getJSONObject("record");
                    JSONArray upcoming=isolated.upcoming(today,today.plusYears(1));expect(upcoming.length()==2,"repeated series flooded summary or equal titles were merged");expect(upcoming.getJSONObject(0).getString("id").equals(biweekly.getString("id")),"today task not first");
                    expect(isolated.events(today,today.plusDays(30)).length()>2,"calendar dates lost repetitions");
                    isolated.completeTodo(biweekly.getString("id"),1,today,true,UUID.randomUUID().toString());upcoming=isolated.upcoming(today,today.plusYears(1));
                    expect(upcoming.length()==2&&upcoming.getJSONObject(0).getString("id").equals(second.getString("id")),"pending items not chronologically sorted");expect(upcoming.getJSONObject(1).getString("occurrence").equals(today.plusDays(14).toString()),"completed task did not advance exactly two weeks");
                    JSONObject once=isolated.write("event",task(today.minusDays(2).toString(),"none"),UUID.randomUUID().toString()).getJSONObject("record");expect(isolated.upcoming(today,today.plusYears(1)).getJSONObject(0).getString("id").equals(once.getString("id")),"overdue one-off task disappeared");
                    isolated.completeTodo(once.getString("id"),1,today.minusDays(2),true,UUID.randomUUID().toString());expect(isolated.upcoming(today,today.plusYears(1)).length()==2,"completed one-off task still in summary");
                }finally{context.deleteDatabase(db);}
            });
            check("widgetCompletionIsIdempotentAndOccurrenceSpecific",()->{
                String db="calendar-widget-complete-tests.db";context.deleteDatabase(db);try(CalendarStore isolated=new CalendarStore(context,db)){
                    LocalDate today=LocalDate.now();JSONObject e=isolated.write("event",task(today.toString(),"biweekly"),UUID.randomUUID().toString()).getJSONObject("record");Intent click=CalendarWidget.completionIntent(e,today);
                    CalendarWidget.completeTodo(isolated,click);CalendarWidget.completeTodo(isolated,click);JSONObject current=isolated.get(e.getString("id"));expect(current.getInt("revision")==2,"double widget tap duplicated completion");expect(CalendarDates.completed(current,today)&&!CalendarDates.completed(current,today.plusDays(14)),"widget completed the whole series");
                    JSONObject next=isolated.upcoming(today,today.plusYears(1)).getJSONObject(0);expect(next.getString("occurrence").equals(today.plusDays(14).toString()),"summary did not advance after widget completion");
                    Intent stale=CalendarWidget.completionIntent(e,today.plusDays(14));boolean rejected=false;try{CalendarWidget.completeTodo(isolated,stale);}catch(IllegalArgumentException error){rejected=true;}expect(rejected,"stale widget changed a newer record");
                }finally{context.deleteDatabase(db);}
            });
            check("shortWidgetKeepsScrollableTasksAndCompletionButton",()->{
                String db="calendar-widget-view-tests.db";context.deleteDatabase(db);int widget=-41257;
                try(CalendarStore isolated=new CalendarStore(context,db)){
                    LocalDate today=LocalDate.now();JSONObject fixture=isolated.write("event",task(today.toString(),"biweekly").put("title","今天的组件待办"),UUID.randomUUID().toString()).getJSONObject("record");
                    int offset=(int)java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.now(),YearMonth.of(2026,8));CalendarWidget.prefs(context,widget).edit().putInt("offset",offset).putInt("theme",1).putString("event",fixture.getString("id")).commit();
                    android.os.Bundle options=new android.os.Bundle();options.putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,360);options.putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,300);
                    for(String mode:new String[]{"calendar","agenda","countdown"})test.runOnMainSync(()->{try{
                        options.putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,mode.equals("calendar")?360:mode.equals("agenda")?120:140);
                        android.appwidget.AppWidgetHostView frame=new android.appwidget.AppWidgetHostView(context);android.appwidget.AppWidgetProviderInfo info=android.appwidget.AppWidgetManager.getInstance(context).getInstalledProviders().stream().filter(provider->provider.provider.equals(new ComponentName(context,CalendarWidget.class))).findFirst().get();frame.setAppWidget(widget,info);View view=CalendarWidget.build(context,widget,isolated,mode,options).apply(context,frame);frame.addView(view);
                        float density=context.getResources().getDisplayMetrics().density;int width=Math.round(300*density),height=Math.round((mode.equals("calendar")?360:mode.equals("agenda")?120:140)*density);frame.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));frame.layout(0,0,width,height);
                        ListView list=view.findViewById(R.id.widget_events);expect(list.getHeight()>=Math.round(44*density),"short "+mode+" widget has only "+list.getHeight()+" pixels for tasks");expect(list.getAdapter()!=null&&list.getAdapter().getCount()==1,"widget hides today task or repeats the same series");View row=list.getAdapter().getView(0,null,list);expect(((TextView)row.findViewById(R.id.widget_event)).getText().toString().contains("今天的组件待办"),"today task missing");expect(row.findViewById(R.id.widget_event_complete).getVisibility()==View.VISIBLE,"widget completion control missing");
                        if(mode.equals("calendar")){android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);frame.draw(new android.graphics.Canvas(bitmap));try(java.io.OutputStream output=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),"calendar-widget-short-todo.png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}bitmap.recycle();}
                    }catch(Exception error){throw new RuntimeException(error);}});
                }finally{CalendarWidget.prefs(context,widget).edit().clear().commit();context.deleteDatabase(db);}
            });
            check("boundWidgetCheckboxCommitsThroughBroadcast",()->{
                android.appwidget.AppWidgetManager manager=android.appwidget.AppWidgetManager.getInstance(context);android.appwidget.AppWidgetHost host=new android.appwidget.AppWidgetHost(context,41257);int widget=-1;Activity activity=null;String recordId=null;
                try(CalendarStore phone=new CalendarStore(context)){
                    test.getUiAutomation().adoptShellPermissionIdentity();widget=host.allocateAppWidgetId();expect(manager.bindAppWidgetIdIfAllowed(widget,new ComponentName(context,CalendarWidget.class)),"cannot bind isolated test widget");test.getUiAutomation().dropShellPermissionIdentity();host.startListening();
                    LocalDate today=LocalDate.now();String title="组件点击测试-"+UUID.randomUUID();JSONObject item=phone.write("event",task(today.toString(),"biweekly").put("title",title),UUID.randomUUID().toString()).getJSONObject("record");recordId=item.getString("id");
                    Bundle options=new Bundle();options.putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,360);options.putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,300);manager.updateAppWidgetOptions(widget,options);
                    activity=test.startActivitySync(new Intent(context,CalendarActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Activity screen=activity;int bound=widget;final android.appwidget.AppWidgetHostView[] hosted={null};
                    test.runOnMainSync(()->{try{FrameLayout frame=new FrameLayout(screen);hosted[0]=host.createView(screen,bound,manager.getAppWidgetInfo(bound));frame.addView(hosted[0],new FrameLayout.LayoutParams(Math.round(300*context.getResources().getDisplayMetrics().density),Math.round(360*context.getResources().getDisplayMetrics().density)));screen.setContentView(frame);hosted[0].updateAppWidget(CalendarWidget.build(context,bound,phone,"calendar",options));}catch(Exception error){throw new RuntimeException(error);}});test.waitForIdleSync();
                    final boolean[] clicked={false};for(int attempt=0;attempt<30&&!clicked[0];attempt++){test.runOnMainSync(()->{View control=findDescription(hosted[0],"完成待办："+title+"，"+today);if(control!=null)clicked[0]=control.performClick();});if(!clicked[0])SystemClock.sleep(100);}
                    expect(clicked[0],"bound widget completion button not clickable");boolean done=false;for(int attempt=0;attempt<50;attempt++){if(CalendarDates.completed(phone.get(recordId),today)){done=true;break;}SystemClock.sleep(100);}expect(done,"widget click did not reach phone database");expect(!CalendarDates.completed(phone.get(recordId),today.plusDays(14)),"widget click completed next repeat");
                }finally{
                    test.getUiAutomation().dropShellPermissionIdentity();if(activity!=null){Activity screen=activity;test.runOnMainSync(screen::finish);test.waitForIdleSync();}host.stopListening();if(widget!=-1){host.deleteAppWidgetId(widget);CalendarWidget.prefs(context,widget).edit().clear().commit();}
                    if(recordId!=null)try(CalendarStore cleanup=new CalendarStore(context)){cleanup.getWritableDatabase().delete("item","id=?",new String[]{recordId});cleanup.getWritableDatabase().delete("operation","result LIKE ?",new String[]{"%"+recordId+"%"});}
                }
            });
            result.putString("stream","Calendar tests passed: "+passed+"\n");
        }catch(Throwable error){code=Activity.RESULT_CANCELED;result.putString("stream",android.util.Log.getStackTraceString(error));}
        finally{if(store!=null)store.close();context.deleteDatabase("calendar-tests.db");}test.finish(code,result);
    }
    private static View findDescription(View view,String label){if(view==null)return null;if(label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findDescription(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    private static void collectInputs(View view,List<EditText> out){if(view instanceof EditText){out.add((EditText)view);return;}if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collectInputs(((ViewGroup)view).getChildAt(i),out);}
    private static void collect(View view,List<Spinner> out){if(view instanceof Spinner){out.add((Spinner)view);return;}if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collect(((ViewGroup)view).getChildAt(i),out);}
}
