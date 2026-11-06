package dev.pixel.sync;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Real IME and delayed HTTP reply checks. Run only in the development emulator. */
final class HomeInteractionChecks {
    private static final String BRIEFING = "## 今日重点\n\n"
            + "- **模型发布**：示例模型新增视觉理解能力。\n"
            + "- **开发者工具**：示例工具更新了本地调试流程。\n\n"
            + "## 为什么值得关注\n\n> 示例简报根据公开标题整理。\n\n"
            + "| 来源 | 状态 |\n| --- | --- |\n| 官方博客 | 已发布 |\n\n"
            + "## 来源与发布时间\n\n- [官方动态](https://example.org/news)";
    private final Instrumentation test;
    private Activity activity;
    private HomeConversationView home;
    private EditText input;
    private View composer,idle,send;
    private ListView conversation;
    private final String fixture="home-check:"+UUID.randomUUID();
    private PetScene pet;
    private int passed;
    private final List<String> requestIds=new CopyOnWriteArrayList<>();
    HomeInteractionChecks(Instrumentation test){this.test=test;}

    void run(){
        Context context=test.getTargetContext();
        SharedPreferences settings=context.getSharedPreferences("gray_settings",0),prefs=context.getSharedPreferences("home_conversation",0);
        Map<String,?> savedSettings=settings.getAll(),savedPrefs=prefs.getAll();
        SharedPreferences secure=context.getSharedPreferences("gray_secure",0);Map<String,?> savedSecure=secure.getAll();
        FakeAgent agent=null;Bundle result=new Bundle();int code=Activity.RESULT_OK;
        try{
            agent=new FakeAgent();agent.start();
            settings.edit().putString("server_url","http://127.0.0.1:"+agent.server.getLocalPort()).putBoolean("decision_enabled",false).commit();
            prefs.edit().putString("draft","").putString("result","操作未完成，请检查请求").putBoolean("proactive",false).putLong("inbox_sequence",0).commit();
            new SecureStore(context).put("device_token","home-ui-test-token");
            check("legacyInboxMigratesWithoutPollutingChat",()->checkLegacyInbox(context));
            try(AssistantStore store=new AssistantStore(context)){
                for(int i=0;i<32;i++){
                    store.addMessage("user","旧用户消息 "+i,fixture,"sent",null,i*2);
                    store.addMessage("assistant","旧助手回复 "+i,fixture,"sent",null,i*2+1);
                }
            }
            activity=test.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));test.waitForIdleSync();
            await(()->activity.hasWindowFocus(),"activity did not receive window focus");
            home=findType(activity.getWindow().getDecorView(),HomeConversationView.class);
            pet=findType(activity.getWindow().getDecorView(),PetScene.class);
            input=(EditText)home.findViewWithTag("home_input");composer=home.findViewWithTag("home_composer");idle=home.findViewWithTag("home_idle");send=home.findViewWithTag("home_send");conversation=home.findViewWithTag("home_conversation_area");
            check("notificationsOpenAndPersistReadState",()->{
                View bell=activity.getWindow().getDecorView().findViewWithTag("home_notifications");
                View dot=activity.getWindow().getDecorView().findViewWithTag("home_notification_unread");
                await(()->dot.getVisibility()==View.VISIBLE,"unread dot did not appear");
                ui(()->{
                    expect(!conversation.canScrollVertically(1),"restored chat did not start at the latest message");
                    expect(findMessage(BRIEFING)==null,"notification leaked into chat");
                    bell.performClick();
                });
                java.lang.reflect.Field detail=HomeConversationView.class.getDeclaredField("detail");
                detail.setAccessible(true);
                android.app.Dialog notificationDialog=(android.app.Dialog)detail.get(home);
                Instrumentation.ActivityMonitor monitor=test.addMonitor(BriefingActivity.class.getName(),null,false);
                try{
                    ui(()->{
                        ListView list=notificationDialog.getWindow().getDecorView().findViewWithTag("home_notifications_list");
                        expect(list.getCount()==1,"notification list should contain one item");
                        list.performItemClick(null,0,0);
                    });
                    Activity briefing=test.waitForMonitorWithTimeout(monitor,6000);
                    expect(briefing!=null,"notification detail did not open");
                    await(briefing::hasWindowFocus,"notification detail did not receive focus");
                    ui(()->{
                        expect(BRIEFING.equals(briefing.getIntent().getStringExtra("body")),"wrong notification detail");
                        expect(dot.getVisibility()==View.GONE,"unread dot did not clear");
                    });
                    android.webkit.WebView reader=findType(briefing.getWindow().getDecorView(),android.webkit.WebView.class);
                    await(()->reader.getContentHeight()>100,"Markdown article did not render");
                    capture("home-briefing.png");
                    ui(briefing::finish);
                    await(()->notificationDialog.getWindow().getDecorView().hasWindowFocus(),"notification list did not regain focus");
                    ui(notificationDialog::dismiss);
                }finally{test.removeMonitor(monitor);}
                try(AssistantStore store=new AssistantStore(context)){
                    expect(store.notifications().stream().anyMatch(item->item.id.equals(fixture)&&item.read),"read status was not saved");
                }
                await(()->activity.hasWindowFocus(),"home did not regain focus");
            });
            check("olderChatLoadsInPagesWithoutMovingAnchor",()->{
                ui(()->expect(conversation.getCount()==21,"expected 20 initial messages and the status footer"));
                for(int count:new int[]{41,61,65}){
                    ui(()->conversation.setSelectionFromTop(0,0));
                    await(()->!conversation.canScrollVertically(-1),"could not reach the top of the loaded page");
                    long[] anchor=new long[1];int[] offset=new int[1];
                    ui(()->{
                        anchor[0]=conversation.getItemIdAtPosition(0);
                        offset[0]=conversation.getChildAt(0).getTop();
                    });
                    if(count==61)ui(this::scrollOlderWithMouse);else pullOlderChat();
                    await(()->conversation.getCount()==count&&conversation.getFirstVisiblePosition()>0,
                            "scrolling up did not add the expected page");
                    ui(()->{
                        expect(conversation.getItemIdAtPosition(conversation.getFirstVisiblePosition())==anchor[0],
                                "prepending moved the visible message");
                        expect(conversation.getChildAt(0).getTop()==offset[0],"prepending moved the visible offset");
                    });
                }
                ui(()->conversation.setSelectionFromTop(0,0));
                await(()->!conversation.canScrollVertically(-1),"could not reach the earliest message");
                pullOlderChat();
                ui(()->{
                    expect(conversation.getCount()==65,"reaching the oldest message added duplicates");
                    conversation.setSelectionFromTop(conversation.getCount()-2,0);
                });
                await(()->!conversation.canScrollVertically(1),"could not return to the latest message");
            });
            check("earlyFalseInsetsKeepEditor",()->ui(()->{
                home.focusInput();home.onKeyboard(false);
                expect(composer.getVisibility()==View.VISIBLE,"editor disappeared before IME became visible");
                expect(idle.getVisibility()==View.GONE,"idle hints leaked while editing");
                expect(!send.isEnabled(),"empty send should be disabled");
            }));
            check("realKeyboardAndCompactComposer",()->{
                await(()->imeVisible(),"keyboard did not appear");
                ui(()->{
                    expect(idle.getVisibility()==View.GONE,"idle hints overlap keyboard");
                    expect(input.hasFocus(),"editor lost focus");
                    float density=activity.getResources().getDisplayMetrics().density;
                    expect(Math.abs(composer.getPaddingLeft()/density-24)<1,"unexpected text inset");
                    expect(composer.getWidth()==home.getWidth(),"composer should span the page width");
                    expect(input.getPaddingLeft()==0,"default EditText padding was not removed");
                    expect(composer.getHeight()/density>=43,"composer touch target is too short");
                    expect(input.getIncludeFontPadding()==false,"extra font padding remains");
                    expect(input.getMaxLines()==2,"input should cap at two lines");
                    expectSeparatedZones();
                });
            });
            capture("home-editor.png");
            int[] single=new int[1];
            check("multilineGrowsWithoutLosingDraft",()->{
                ui(()->{input.setText("舒服的输入框");single[0]=composer.getHeight();});test.waitForIdleSync();
                ui(()->input.setText("第一行\n第二行\n第三行"));test.waitForIdleSync();
                ui(()->{
                    expect(composer.getHeight()>single[0],"multiline composer did not grow");
                    expect(send.isEnabled(),"nonempty send is disabled");
                    home.onKeyboard(true);home.onKeyboard(false);
                    expect(composer.getVisibility()==View.VISIBLE,"draft collapsed when keyboard closed");
                    expect(input.getText().toString().contains("第三行"),"draft was lost");
                });
            });
            capture("home-multiline.png");
            check("outsideTapClosesKeyboardAndKeepsDraft",()->{
                ui(()->home.focusInput());await(()->imeVisible(),"keyboard did not reopen for outside tap");
                tapPet();await(()->!imeVisible(),"tapping the pet did not dismiss the keyboard");
                ui(()->{expect(!input.hasFocus(),"outside tap retained input focus");expect(input.getText().toString().contains("第三行"),"outside tap discarded the draft");home.focusInput();input.setText("");});
                await(()->imeVisible(),"keyboard did not reopen for empty draft");tapPet();
                await(()->!imeVisible(),"outside tap did not dismiss the empty keyboard");await(()->idle.getVisibility()==View.VISIBLE,"empty input did not return to idle");
            });
            FakeAgent service=agent;
            check("waitingPetAndMessageStayAboveComposer",()->{
                ui(()->{home.focusInput();input.setText("home-ui-正常回复");send.performClick();});
                expect(service.received.tryAcquire(6,TimeUnit.SECONDS),"chat request did not arrive");
                ui(()->{
                    expect(pet.isThinking(),"pet does not enter thinking state");
                    View user=findMessage("home-ui-正常回复");
                    expect(user!=null,"sent message was not visible at the bottom");
                    expect(user.getParent()!=composer.getParent(),"user message remains attached to bottom controls");
                    int[] userAt=new int[2],composerAt=new int[2];user.getLocationOnScreen(userAt);composer.getLocationOnScreen(composerAt);
                    expect(userAt[1]+user.getHeight()<composerAt[1],"sent text is not above the composer");
                    expect(input.length()==0,"sent draft was not cleared");
                    expect(!send.isEnabled(),"cleared input still allows empty sends");
                });
                capture("home-waiting.png");
            });
            check("replyStopsThinking",()->{
                ui(()->conversation.setSelection(0));
                await(()->conversation.getFirstVisiblePosition()==0,"could not browse old messages");
                service.release.release();
                await(()->!pet.isThinking(),"thinking did not stop on success");
                ui(()->expect(conversation.getFirstVisiblePosition()==0,"reply jumped to bottom while browsing history"));
                ui(()->conversation.setSelectionFromTop(conversation.getCount()-2,0));
                await(()->findMessage("收到这条测试消息。")!=null&&findMessage("聊天工具测试")!=null,"reply or tool result is missing");
            });
            check("failedReplyStopsThinkingAndCanRetry",()->{
                service.fail=true;
                ui(()->{home.focusInput();input.setText("home-ui-失败重试");send.performClick();});
                expect(service.received.tryAcquire(6,TimeUnit.SECONDS),"second chat request missing");
                service.release.release();
                await(()->!pet.isThinking(),"thinking did not stop on failure");
                ui(()->expect(((TextView)home.findViewWithTag("home_reply_state")).getText().toString().contains("重试"),"retry state is missing"));
                service.fail=false;
                ui(()->home.findViewWithTag("home_reply_state").performClick());
                expect(service.received.tryAcquire(6,TimeUnit.SECONDS),"retry was not sent");
                service.release.release();await(()->!pet.isThinking(),"thinking did not stop after retry");
            });
            check("emptyKeyboardDismissReturnsIdle",()->{
                ui(()->{home.focusInput();input.setText("");});await(()->imeVisible(),"keyboard failed to reopen");
                ui(()->((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0));
                await(()->!imeVisible(),"keyboard did not close");
                await(()->idle.getVisibility()==View.VISIBLE,"idle controls did not return");
                ui(()->expect(composer.getVisibility()==View.GONE,"empty editor remained visible"));
            });
            capture("home-idle.png");
            check("idleMessagesRemainAbovePetWithFreeSides",()->ui(this::expectSeparatedZones));
            result.putString("stream","\nHome interaction tests passed: "+passed+"\n");
        }catch(Throwable error){code=Activity.RESULT_CANCELED;result.putString("stream",android.util.Log.getStackTraceString(error));}
        finally{
            if(agent!=null)agent.close();
            if(activity!=null){ui(()->activity.finish());test.waitForIdleSync();}
            try(AssistantStore store=new AssistantStore(context)){
                store.getWritableDatabase().delete("message","group_id=?",new String[]{fixture});
                store.getWritableDatabase().delete("notification","id=?",new String[]{fixture});
                for(String id:requestIds)store.getWritableDatabase().delete("message","group_id IN (?,?)",new String[]{id,"reply:"+id});
            }
            restore(settings,savedSettings);restore(prefs,savedPrefs);restore(secure,savedSecure);
        }
        test.finish(code,result);
    }
    private View findMessage(String text){
        for(int i=0;i<conversation.getChildCount();i++){
            View row=conversation.getChildAt(i);
            for(String tag:new String[]{"home_user_message","home_assistant_message","home_tool_result"}){
                TextView body=row.findViewWithTag(tag);
                if(body!=null&&text.contentEquals(body.getText()))return body;
            }
        }
        return null;
    }
    private void checkLegacyInbox(Context context){
        String database=fixture+".db";
        try{
            try(AssistantStore legacy=new AssistantStore(context,database)){
                legacy.getWritableDatabase().execSQL("DROP TABLE notification");
                legacy.addMessage("user","旧版聊天","chat","sent",null,1);
                legacy.addMessage("assistant","科技资讯正文","inbox:news","sent","inbox:news",2);
                legacy.addMessage("tool","科技资讯\n科技资讯正文","inbox:news","sent","inbox-card:news",2);
                legacy.addMessage("tool","提醒已完成");
                legacy.addMessage("tool","提醒已完成","reply:chat","sent","tools:chat",3);
                legacy.getWritableDatabase().setVersion(6);
            }
            try(AssistantStore migrated=new AssistantStore(context,database)){
                List<AssistantStore.InboxItem> items=migrated.notifications();
                expect(items.size()==1&&"科技资讯".equals(items.get(0).title),"old inbox pair did not migrate");
                expect(migrated.chatBefore(Long.MAX_VALUE,10).size()==2,"legacy notification leaked or tool result was lost");
                expect(migrated.conversation(10).size()==1,"notification leaked into assistant context");
                migrated.readNotification("news");
                migrated.saveNotifications(items);
                expect(migrated.notifications().get(0).read,"sync reset a read notification");
            }
        }finally{context.deleteDatabase(database);}
    }
    private void capture(String name)throws Exception{
        test.waitForIdleSync();android.graphics.Bitmap bitmap=test.getUiAutomation().takeScreenshot();
        if(bitmap!=null)try(FileOutputStream out=new FileOutputStream(new File(test.getTargetContext().getCacheDir(),name))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);bitmap.recycle();}
    }
    private boolean imeVisible(){WindowInsets insets=activity.getWindow().getDecorView().getRootWindowInsets();return insets!=null&&insets.isVisible(WindowInsets.Type.ime());}
    private Rect screenBounds(View view){int[] at=new int[2];view.getLocationOnScreen(at);return new Rect(at[0],at[1],at[0]+view.getWidth(),at[1]+view.getHeight());}
    private void expectSeparatedZones(){
        View conversation=home.findViewWithTag("home_conversation_area"),stage=home.findViewWithTag("home_pet_stage"),anchor=home.findViewWithTag("home_pet_touch");
        Rect messages=screenBounds(conversation),petArea=screenBounds(stage),petAt=screenBounds(anchor),inputAt=screenBounds(composer.getVisibility()==View.VISIBLE?composer:idle);
        expect(messages.bottom<petArea.top,"messages overlap the pet stage");
        expect(petArea.bottom<inputAt.top,"pet stage overlaps the input");
        expect(anchor.getVisibility()==View.VISIBLE&&petArea.contains(petAt),"pet anchor does not fit in the lower stage");
        float density=activity.getResources().getDisplayMetrics().density;
        for(String tag:new String[]{"home_pet_left_slot","home_pet_right_slot"}){
            View slot=home.findViewWithTag(tag);expect(slot.getWidth()/density>=48,"pet side does not reserve usable space");
            expect(!Rect.intersects(screenBounds(slot),petAt),"side content overlaps the pet anchor");
        }
    }
    private void scrollOlderWithMouse(){
        MotionEvent.PointerProperties pointer=new MotionEvent.PointerProperties();
        pointer.id=0;pointer.toolType=MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coords=new MotionEvent.PointerCoords();
        coords.setAxisValue(MotionEvent.AXIS_VSCROLL,1);
        long now=SystemClock.uptimeMillis();
        MotionEvent event=MotionEvent.obtain(now,now,MotionEvent.ACTION_SCROLL,1,
                new MotionEvent.PointerProperties[]{pointer},new MotionEvent.PointerCoords[]{coords},
                0,0,1,1,0,0,android.view.InputDevice.SOURCE_MOUSE,0);
        try{conversation.onGenericMotionEvent(event);}finally{event.recycle();}
    }
    private void pullOlderChat(){
        int[] start=new int[2];
        ui(()->{
            conversation.getLocationOnScreen(start);
            start[0]+=conversation.getWidth()/2;
            start[1]+=conversation.getHeight()/3;
        });
        long down=SystemClock.uptimeMillis();
        sendPointer(down,MotionEvent.ACTION_DOWN,start[0],start[1]);
        SystemClock.sleep(30);
        sendPointer(down,MotionEvent.ACTION_MOVE,start[0],start[1]+60);
        SystemClock.sleep(180);
        sendPointer(down,MotionEvent.ACTION_UP,start[0],start[1]+60);
        test.waitForIdleSync();
    }
    private void sendPointer(long down,int action,float x,float y){
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);
        try{test.sendPointerSync(event);}finally{event.recycle();}
    }
    private void tapPet(){
        int[] location=new int[2];ui(()->{View target=activity.findViewById(android.R.id.content).findViewWithTag("home_pet_touch");target.getLocationOnScreen(location);location[0]+=target.getWidth()/2;location[1]+=target.getHeight()/2;});
        long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,location[0],location[1],0),up=MotionEvent.obtain(now,now+40,MotionEvent.ACTION_UP,location[0],location[1],0);
        try{test.sendPointerSync(down);test.sendPointerSync(up);}finally{down.recycle();up.recycle();}test.waitForIdleSync();
    }
    private interface Check{void run()throws Exception;}
    private interface Condition{boolean get();}
    private void check(String name,Check work)throws Exception{
        Bundle report=new Bundle();report.putString("class",getClass().getName());report.putString("test",name);report.putInt("numtests",12);report.putInt("current",passed+1);test.sendStatus(1,report);
        try{work.run();passed++;test.sendStatus(0,report);}catch(Throwable error){report.putString("stack",android.util.Log.getStackTraceString(error));test.sendStatus(-2,report);throw error;}
    }
    private void await(Condition condition,String message){
        long until=SystemClock.uptimeMillis()+6000;boolean[] ok=new boolean[1];
        do{ui(()->ok[0]=condition.get());if(ok[0])return;SystemClock.sleep(50);}while(SystemClock.uptimeMillis()<until);
        throw new AssertionError(message);
    }
    private void ui(Runnable work){
        Throwable[] failure=new Throwable[1];
        test.runOnMainSync(()->{try{work.run();}catch(Throwable error){failure[0]=error;}});
        if(failure[0]!=null)throw new AssertionError(failure[0]);
    }
    private static void expect(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static <T> T findType(View root,Class<T> type){
        if(type.isInstance(root))return type.cast(root);
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){T found=findType(((ViewGroup)root).getChildAt(i),type);if(found!=null)return found;}return null;
    }
    @SuppressWarnings("unchecked") private static void restore(SharedPreferences prefs,Map<String,?> snapshot){
        SharedPreferences.Editor edit=prefs.edit().clear();for(Map.Entry<String,?> entry:snapshot.entrySet()){
            String key=entry.getKey();Object value=entry.getValue();
            if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
            else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);
            else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof Set)edit.putStringSet(key,(Set<String>)value);
        }edit.commit();
    }
    private final class FakeAgent implements AutoCloseable{
        final ServerSocket server=new ServerSocket(0,20,InetAddress.getByName("127.0.0.1"));
        final Semaphore received=new Semaphore(0),release=new Semaphore(0);
        final ExecutorService workers=Executors.newCachedThreadPool();volatile boolean fail;
        FakeAgent()throws IOException{}
        void start(){workers.execute(()->{while(!server.isClosed())try{Socket socket=server.accept();workers.execute(()->serve(socket));}catch(IOException closed){break;}});}
        private void serve(Socket socket){try(Socket connection=socket){
            connection.setSoTimeout(10000);InputStream input=connection.getInputStream();ByteArrayOutputStream head=new ByteArrayOutputStream();int previous=0;
            while(head.size()<16000){int next=input.read();if(next<0)return;head.write(next);previous=((previous<<8)|next);if(previous==0x0d0a0d0a)break;}
            String headers=head.toString("UTF-8");String path=headers.split(" ")[1];int length=0;
            for(String line:headers.split("\r\n"))if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
            if(length<0||length>256000)return;byte[] body=new byte[length];int at=0;while(at<length){int n=input.read(body,at,length-at);if(n<0)return;at+=n;}
            boolean chat=path.equals("/v1/chat");String response="[]";int status=200;
            if(chat){JSONObject request=new JSONObject(new String(body,StandardCharsets.UTF_8));requestIds.add(request.getString("client_message_id"));received.release();if(!release.tryAcquire(10,TimeUnit.SECONDS))return;status=fail?503:200;response=fail?"{}":"{\"content\":\"收到这条测试消息。\",\"memory_changes\":[],\"tool_results\":[{\"summary\":\"聊天工具测试\"}]}";}
            else if(path.equals("/v1/inbox?after_sequence=0")){
                response=new org.json.JSONArray().put(new JSONObject().put("id",fixture).put("sequence",1)
                        .put("title","AI 新进展 · 今日简报").put("body",BRIEFING)).toString();
            }
            else if(path.equals("/v1/home"))response="{\"content\":\"\"}";
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);OutputStream out=connection.getOutputStream();
            out.write(("HTTP/1.1 "+status+" OK\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();
        }catch(Exception ignored){}}
        public void close(){try{server.close();}catch(IOException ignored){}release.release(10);workers.shutdownNow();}
    }
}
