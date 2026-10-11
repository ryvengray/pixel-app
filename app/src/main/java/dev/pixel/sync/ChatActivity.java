package dev.pixel.sync;

import android.app.*;
import android.os.*;
import android.graphics.Color;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public class ChatActivity extends Activity {
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private AssistantStore store; private LinearLayout timeline; private ScrollView scroll; private EditText input; private Button send; private TextView status;
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle state){super.onCreate(state);store=new AssistantStore(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(22),dp(18),dp(14));
        TextView title=new TextView(this);title.setText("和 Gray 聊聊");title.setTextSize(25);root.addView(title);
        status=new TextView(this);status.setText("你的完整记忆保存在这台手机上");status.setTextSize(12);root.addView(status);
        scroll=new ScrollView(this);timeline=new LinearLayout(this);timeline.setOrientation(LinearLayout.VERTICAL);timeline.setPadding(0,dp(12),0,dp(8));scroll.addView(timeline);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout composer=new LinearLayout(this);composer.setGravity(Gravity.CENTER_VERTICAL);input=new EditText(this);input.setHint("和 Gray 说点什么");input.setMaxLines(4);composer.addView(input,new LinearLayout.LayoutParams(0,-2,1));send=new Button(this);send.setText("发送");send.setOnClickListener(v->send());composer.addView(send);root.addView(composer);setContentView(root);render();
    }
    private void render(){timeline.removeAllViews();for(AssistantStore.Message message:store.recentMessages(80))addBubble(message.role,message.content);scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));}
    private void addBubble(String role,String content){TextView view=new TextView(this);view.setText(content);view.setTextSize(16);view.setTextColor(Color.parseColor("assistant".equals(role)?"#263C31":"#FFFFFF"));view.setPadding(dp(14),dp(10),dp(14),dp(10));android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(Color.parseColor("assistant".equals(role)?"#E3ECDC":"#39644D"));bg.setCornerRadius(dp(16));view.setBackground(bg);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.gravity="assistant".equals(role)?Gravity.LEFT:Gravity.RIGHT;p.topMargin=dp(7);view.setLayoutParams(p);timeline.addView(view);}
    private void send(){String text=input.getText().toString().trim();if(text.isEmpty())return;input.setText("");store.addMessage("user",text);addBubble("user",text);send.setEnabled(false);status.setText("Gray 正在想…");((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
        List<AssistantStore.Message> messages=store.recentMessages(18);List<AssistantStore.Memory> memories=store.memories(false);network.execute(()->{try{AssistantApi.Reply reply=AssistantApi.chat(this,messages,memories);for(AssistantStore.Memory memory:reply.memories)store.upsertMemory(memory);store.addMessage("assistant",reply.content);main.post(()->{addBubble("assistant",reply.content);status.setText(reply.directFallback?"服务端不可用，已使用本地密钥直连":"已使用你的本地记忆");send.setEnabled(true);scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));});}catch(Exception e){main.post(()->{status.setText("没有发出消息："+e.getMessage());send.setEnabled(true);});}});
    }
    @Override protected void onDestroy(){network.shutdownNow();super.onDestroy();}
}
