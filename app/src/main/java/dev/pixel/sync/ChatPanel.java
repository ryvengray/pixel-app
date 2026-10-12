package dev.pixel.sync;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Chat panel embedded in the home screen: message timeline plus a messaging-style composer. */
public final class ChatPanel extends FrameLayout {
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable onReply,onClose;
    private final AssistantStore store;
    private LinearLayout timeline;
    private ScrollView scroll;
    private EditText input;
    private Button send;
    private TextView status,close;
    private boolean dark;
    private int foreground,secondary;
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}

    public ChatPanel(Context context,Runnable onReply,Runnable onClose){
        super(context);
        this.onReply=onReply;this.onClose=onClose;
        store=new AssistantStore(context);
        LinearLayout column=new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(18),dp(10),dp(18),dp(12));
        addView(column,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout header=new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        status=text("你的完整记忆保存在这台手机上",12);
        header.addView(status,new LinearLayout.LayoutParams(0,-2,1));
        close=text("✕",17);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("关闭聊天");
        close.setOnClickListener(v->{if(onClose!=null)onClose.run();});
        header.addView(close,new LinearLayout.LayoutParams(dp(40),dp(40)));
        column.addView(header);

        scroll=new ScrollView(context);
        timeline=new LinearLayout(context);
        timeline.setOrientation(LinearLayout.VERTICAL);
        timeline.setPadding(0,dp(6),0,dp(6));
        scroll.addView(timeline);
        column.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout composer=new LinearLayout(context);
        composer.setGravity(Gravity.BOTTOM);
        input=new EditText(context);
        input.setHint("和 Gray 说点什么");
        input.setMaxLines(4);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v,actionId,event)->{if(actionId==EditorInfo.IME_ACTION_SEND){sendMessage();return true;}return false;});
        composer.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        send=new Button(context);
        send.setText("发送");
        send.setAllCaps(false);
        send.setMinHeight(dp(44));
        send.setOnClickListener(v->sendMessage());
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-2,-2);
        sp.leftMargin=dp(10);
        composer.addView(send,sp);
        column.addView(composer);
        setElevation(dp(8));
    }

    public void setTheme(boolean value){
        dark=value;
        foreground=Color.parseColor(dark?"#E5EDE3":"#263C31");
        secondary=Color.parseColor(dark?"#92A99A":"#708675");
        setBackground(surface(Color.parseColor(dark?"#172B22":"#F5F7F0"),26,26,0,0));
        status.setTextColor(secondary);
        close.setTextColor(foreground);
        input.setTextColor(foreground);
        input.setHintTextColor(secondary);
        GradientDrawable field=new GradientDrawable();
        field.setColor(Color.parseColor(dark?"#294536":"#FFFFFF"));
        field.setCornerRadius(dp(22));
        field.setStroke(dp(1),Color.parseColor(dark?"#4D6156":"#C6D4C4"));
        input.setBackground(field);
        setSending(send.isEnabled());
        render();
    }

    public void open(){
        render();
        input.postDelayed(()->{
            input.requestFocus();
            ((InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input,0);
        },180);
    }

    public void close(){
        ((InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
    }

    public void shutdown(){network.shutdownNow();}

    private void render(){
        timeline.removeAllViews();
        int maxWidth=(int)(getResources().getDisplayMetrics().widthPixels*0.78f);
        for(AssistantStore.Message message:store.recentMessages(80)){
            TextView view=bubble(message.role,message.content);
            view.setMaxWidth(maxWidth);
            timeline.addView(view);
        }
        scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));
    }

    private TextView bubble(String role,String content){
        TextView view=text(content,16);
        boolean assistant="assistant".equals(role);
        view.setTextColor(Color.parseColor(assistant?(dark?"#E5EDE3":"#263C31"):"#FFFFFF"));
        view.setPadding(dp(14),dp(10),dp(14),dp(10));
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(Color.parseColor(assistant?(dark?"#294536":"#E3ECDC"):"#39644D"));
        bg.setCornerRadius(dp(16));
        view.setBackground(bg);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);
        p.gravity=assistant?Gravity.LEFT:Gravity.RIGHT;
        p.topMargin=dp(7);
        view.setLayoutParams(p);
        return view;
    }

    private void addBubble(String role,String content){
        TextView view=bubble(role,content);
        view.setMaxWidth((int)(getResources().getDisplayMetrics().widthPixels*0.78f));
        timeline.addView(view);
        scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void sendMessage(){
        String text=input.getText().toString().trim();
        if(text.isEmpty()||!send.isEnabled())return;
        input.setText("");
        store.addMessage("user",text);
        addBubble("user",text);
        setSending(false);
        status.setText("Gray 正在想…");
        List<AssistantStore.Message> messages=store.recentMessages(18);
        List<AssistantStore.Memory> memories=store.memories(false);
        network.execute(()->{
            try{
                AssistantApi.Reply reply=AssistantApi.chat(getContext(),messages,memories);
                for(AssistantStore.Memory memory:reply.memories)store.upsertMemory(memory);
                store.addMessage("assistant",reply.content);
                main.post(()->{
                    addBubble("assistant",reply.content);
                    status.setText(reply.directFallback?"服务端不可用，已使用本地密钥直连":"已使用你的本地记忆");
                    setSending(true);
                    scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));
                    if(onReply!=null)onReply.run();
                });
            }catch(Exception e){
                main.post(()->{status.setText("没有发出消息："+e.getMessage());setSending(true);});
            }
        });
    }

    private void setSending(boolean enabled){
        send.setEnabled(enabled);
        GradientDrawable d=new GradientDrawable();
        d.setColor(Color.parseColor(enabled?(dark?"#4A7A5F":"#39644D"):(dark?"#3A4D41":"#B9C9BA")));
        d.setCornerRadius(dp(20));
        send.setBackground(d);
        send.setTextColor(enabled?Color.WHITE:Color.parseColor(dark?"#7E9388":"#8AA08F"));
    }

    private TextView text(String value,int size){TextView v=new TextView(getContext());v.setText(value);v.setTextSize(size);return v;}
    private GradientDrawable surface(int color,int tl,int tr,int br,int bl){
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);
        d.setCornerRadii(new float[]{dp(tl),dp(tl),dp(tr),dp(tr),dp(br),dp(br),dp(bl),dp(bl)});
        return d;
    }
}
