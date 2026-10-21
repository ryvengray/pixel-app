package dev.pixel.sync;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Real IME and delayed HTTP reply checks. Run only in the development emulator. */
final class HomeInteractionChecks {
    private final Instrumentation test;
    private Activity activity;
    private HomeConversationView home;
    private EditText input;
    private View composer,idle,send,user;
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
            prefs.edit().putString("draft","").putBoolean("proactive",false).commit();
            new SecureStore(context).put("device_token","home-ui-test-token");
            activity=test.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));test.waitForIdleSync();
            home=findType(activity.getWindow().getDecorView(),HomeConversationView.class);
            pet=findType(activity.getWindow().getDecorView(),PetScene.class);
            input=(EditText)home.findViewWithTag("home_input");composer=home.findViewWithTag("home_composer");idle=home.findViewWithTag("home_idle");send=home.findViewWithTag("home_send");user=home.findViewWithTag("home_user_message");
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
                    expect(Math.abs(composer.getPaddingLeft()/density-8)<1,"unexpected text inset");
                    expect(input.getPaddingLeft()==0,"default EditText padding was not removed");
                    expect(composer.getHeight()/density>=43,"composer touch target is too short");
                    expect(input.getIncludeFontPadding()==false,"extra font padding remains");
                    expect(input.getMaxLines()==2,"input should cap at two lines");
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
                    expect(Math.abs(send.getBottom()-input.getBottom())<=1,"send button should align with the last input line");
                    home.onKeyboard(true);home.onKeyboard(false);
                    expect(composer.getVisibility()==View.VISIBLE,"draft collapsed when keyboard closed");
                    expect(input.getText().toString().contains("第三行"),"draft was lost");
                });
            });
            capture("home-multiline.png");
            FakeAgent service=agent;
            check("waitingPetAndMessageStayAboveComposer",()->{
                ui(()->{home.focusInput();input.setText("home-ui-正常回复");send.performClick();});
                expect(service.received.tryAcquire(6,TimeUnit.SECONDS),"chat request did not arrive");
                ui(()->{
                    expect(pet.isThinking(),"pet does not enter thinking state");
                    expect(user.getParent()!=composer.getParent(),"user message remains attached to bottom controls");
                    int[] userAt=new int[2],composerAt=new int[2];user.getLocationOnScreen(userAt);composer.getLocationOnScreen(composerAt);
                    expect(userAt[1]+user.getHeight()<composerAt[1],"sent text is not above the composer");
                    expect(input.length()==0,"sent draft was not cleared");
                    expect(!send.isEnabled(),"cleared input still allows empty sends");
                });
                capture("home-waiting.png");
            });
            check("replyStopsThinking",()->{
                service.release.release();
                await(()->!pet.isThinking(),"thinking did not stop on success");
                await(()->"收到这条测试消息。".contentEquals(((TextView)home.findViewWithTag("home_assistant_message")).getText()),"reply is missing");
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
            result.putString("stream","\nHome interaction tests passed: "+passed+"\n");
        }catch(Throwable error){code=Activity.RESULT_CANCELED;result.putString("stream",android.util.Log.getStackTraceString(error));}
        finally{
            if(agent!=null)agent.close();
            if(activity!=null){ui(()->activity.finish());test.waitForIdleSync();}
            try(AssistantStore store=new AssistantStore(context)){
                for(String id:requestIds)store.getWritableDatabase().delete("message","group_id IN (?,?)",new String[]{id,"reply:"+id});
            }
            restore(settings,savedSettings);restore(prefs,savedPrefs);restore(secure,savedSecure);
        }
        test.finish(code,result);
    }
    private void capture(String name)throws Exception{
        test.waitForIdleSync();android.graphics.Bitmap bitmap=test.getUiAutomation().takeScreenshot();
        if(bitmap!=null)try(FileOutputStream out=new FileOutputStream(new File(test.getTargetContext().getCacheDir(),name))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);bitmap.recycle();}
    }
    private boolean imeVisible(){WindowInsets insets=activity.getWindow().getDecorView().getRootWindowInsets();return insets!=null&&insets.isVisible(WindowInsets.Type.ime());}
    private interface Check{void run()throws Exception;}
    private interface Condition{boolean get();}
    private void check(String name,Check work)throws Exception{
        Bundle report=new Bundle();report.putString("class",getClass().getName());report.putString("test",name);report.putInt("numtests",7);report.putInt("current",passed+1);test.sendStatus(1,report);
        try{work.run();passed++;test.sendStatus(0,report);}catch(Throwable error){report.putString("stack",android.util.Log.getStackTraceString(error));test.sendStatus(-2,report);throw error;}
    }
    private void await(Condition condition,String message){
        long until=SystemClock.uptimeMillis()+6000;boolean[] ok=new boolean[1];
        do{ui(()->ok[0]=condition.get());if(ok[0])return;SystemClock.sleep(50);}while(SystemClock.uptimeMillis()<until);
        throw new AssertionError(message);
    }
    private void ui(Runnable work){test.runOnMainSync(work);}
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
            if(chat){JSONObject request=new JSONObject(new String(body,StandardCharsets.UTF_8));requestIds.add(request.getString("client_message_id"));received.release();if(!release.tryAcquire(10,TimeUnit.SECONDS))return;status=fail?503:200;response=fail?"{}":"{\"content\":\"收到这条测试消息。\",\"memory_changes\":[],\"tool_results\":[]}";}
            else if(path.equals("/v1/home"))response="{\"content\":\"\"}";
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);OutputStream out=connection.getOutputStream();
            out.write(("HTTP/1.1 "+status+" OK\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();
        }catch(Exception ignored){}}
        public void close(){try{server.close();}catch(IOException ignored){}release.release(10);workers.shutdownNow();}
    }
}
