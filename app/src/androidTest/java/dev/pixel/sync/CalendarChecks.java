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
            result.putString("stream","Calendar tests passed: "+passed+"\n");
        }catch(Throwable error){code=Activity.RESULT_CANCELED;result.putString("stream",android.util.Log.getStackTraceString(error));}
        finally{if(store!=null)store.close();context.deleteDatabase("calendar-tests.db");}test.finish(code,result);
    }
    private static void collectInputs(View view,List<EditText> out){if(view instanceof EditText){out.add((EditText)view);return;}if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collectInputs(((ViewGroup)view).getChildAt(i),out);}
    private static void collect(View view,List<Spinner> out){if(view instanceof Spinner){out.add((Spinner)view);return;}if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collect(((ViewGroup)view).getChildAt(i),out);}
}
