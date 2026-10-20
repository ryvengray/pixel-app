package dev.pixel.sync;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextWatcher;
import android.text.Editable;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** A warm, character-led chat surface embedded in the home screen. */
public final class ChatPanel extends FrameLayout {
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable onReply,onClose;
    private final AssistantStore store;
    private LinearLayout timeline, welcome;
    private View typingRow;
    private ScrollView scroll;
    private EditText input;
    private TextView send,status,close,name;
    private FrameLayout badge;
    private View divider,composerSurface;
    private boolean dark;
    private boolean busy;
    private int foreground,secondary,surfaceColor,cardColor,borderColor,accent;
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}

    public ChatPanel(Context context,Runnable onReply,Runnable onClose){
        super(context);
        this.onReply=onReply;this.onClose=onClose;
        store=new AssistantStore(context);
        setClipToPadding(false);
        LinearLayout column=new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20),dp(18),dp(20),dp(12));
        addView(column,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout header=new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0,0,0,dp(8));
        badge=assistantMark(38);
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(38),dp(38));
        mp.rightMargin=dp(11);header.addView(badge,mp);
        LinearLayout identity=new LinearLayout(context);identity.setOrientation(LinearLayout.VERTICAL);
        name=text("Greta",17);name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));name.setTextColor(foreground);
        status=text("你的随身小助手",12);status.setTextColor(secondary);
        identity.addView(name);identity.addView(status);
        header.addView(identity,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(new View(context),new LinearLayout.LayoutParams(dp(52),dp(1)));
        close=text("×",27);close.setGravity(Gravity.CENTER);close.setContentDescription("关闭聊天");
        close.setOnClickListener(v->{if(onClose!=null)onClose.run();});
        header.addView(close,new LinearLayout.LayoutParams(dp(42),dp(42)));
        close.setVisibility(View.GONE);
        column.addView(header);

        divider=new View(context);divider.setBackgroundColor(borderColor);
        LinearLayout.LayoutParams dpv=new LinearLayout.LayoutParams(-1,dp(1));dpv.bottomMargin=dp(7);column.addView(divider,dpv);

        scroll=new ScrollView(context);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        timeline=new LinearLayout(context);timeline.setOrientation(LinearLayout.VERTICAL);timeline.setPadding(0,dp(10),0,dp(16));
        scroll.addView(timeline,new ScrollView.LayoutParams(-1,-2));
        column.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout composerWrap=new LinearLayout(context);composerWrap.setOrientation(LinearLayout.VERTICAL);
        composerWrap.setPadding(dp(5),dp(4),dp(5),dp(4));
        composerWrap.setBackground(rounded(Color.WHITE,24,Color.LTGRAY,1));
        LinearLayout composer=new LinearLayout(context);composer.setGravity(Gravity.CENTER_VERTICAL);
        input=new EditText(context);input.setHint("跟 Greta 说说你的想法…");input.setTextSize(15);
        input.setPadding(dp(10),dp(5),dp(8),dp(5));input.setMinHeight(dp(36));input.setMaxLines(4);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v,actionId,event)->{if(actionId==EditorInfo.IME_ACTION_SEND){sendMessage();return true;}return false;});
        input.setOnFocusChangeListener((v,focused)->updateComposerSurface(focused));
        composer.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        send=text("发送",13);send.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));send.setGravity(Gravity.CENTER);send.setContentDescription("发送消息");
        send.setOnClickListener(v->sendMessage());
        composer.addView(send,new LinearLayout.LayoutParams(dp(54),dp(34)));
        composerWrap.addView(composer);
        composerSurface=composerWrap;
        column.addView(composerWrap);
        input.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){setSending(s.length()>0&&!busy);}
            public void afterTextChanged(Editable e){}
        });
        setSending(false);
        setElevation(0);
    }

    public void setTheme(boolean value){
        dark=value;
        foreground=Color.parseColor(dark?"#E8F0E8":"#263C31");
        secondary=Color.parseColor(dark?"#9DB1A2":"#758779");
        surfaceColor=Color.parseColor(dark?"#172B22":"#F8FAF5");
        cardColor=Color.parseColor(dark?"#24392E":"#FFFFFF");
        borderColor=Color.parseColor(dark?"#354B3E":"#E1E9DF");
        accent=Color.parseColor(dark?"#83B88F":"#39644D");
        setBackgroundColor(Color.TRANSPARENT);
        status.setTextColor(secondary);close.setTextColor(secondary);name.setTextColor(foreground);
        divider.setBackgroundColor(borderColor);updateComposerSurface(input.hasFocus());
        badge.setBackground(rounded(Color.parseColor(dark?"#3B6148":"#DCEBDD"),19,0,0));
        input.setTextColor(foreground);input.setHintTextColor(secondary);
        setSending(input.length()>0&&!busy);
        render();
    }

    public void open(){render();}
    public void close(){((InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);}
    public void shutdown(){network.shutdownNow();}

    private void render(){
        timeline.removeAllViews();List<AssistantStore.Message> messages=store.recentMessages(80);
        if(messages.isEmpty())addWelcome();
        else for(AssistantStore.Message message:messages)addMessageRow(message.role,message.content);
        scrollToLatest(false);
    }

    private void addWelcome(){
        welcome=new LinearLayout(getContext());welcome.setOrientation(LinearLayout.VERTICAL);
        welcome.setPadding(dp(17),dp(16),dp(17),dp(16));welcome.setBackground(rounded(cardColor,20,borderColor,1));
        TextView hello=text("嗨，我是 Greta",20);hello.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));hello.setTextColor(foreground);
        TextView blurb=text("我在这里陪你整理想法、记住重要的事，也可以一步步帮你完成任务。你想从哪里开始？",14);
        blurb.setTextColor(secondary);blurb.setLineSpacing(dp(3),1f);blurb.setPadding(0,dp(7),0,0);
        welcome.addView(hello);welcome.addView(blurb);
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(-1,-2);wp.bottomMargin=dp(18);timeline.addView(welcome,wp);
        TextView promptLabel=text("试着这样开始",12);promptLabel.setTextColor(secondary);promptLabel.setPadding(dp(2),0,0,dp(8));timeline.addView(promptLabel);
        addSuggestion("帮我把一个想法理清楚");addSuggestion("记住一件对我很重要的事");addSuggestion("我今天有点累，陪我聊聊");
    }

    private void addSuggestion(String label){
        TextView chip=text(label,13);chip.setTextColor(foreground);chip.setPadding(dp(14),dp(10),dp(14),dp(10));chip.setBackground(rounded(cardColor,18,borderColor,1));
        chip.setOnClickListener(v->{input.setText(label);input.setSelection(input.length());sendMessage();});
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.bottomMargin=dp(8);timeline.addView(chip,p);
    }

    private void addMessageRow(String role,String content){
        boolean assistant="assistant".equals(role);
        LinearLayout row=new LinearLayout(getContext());row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.TOP);
        if(assistant){View mark=assistantMark(26);LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(dp(26),dp(26));a.rightMargin=dp(8);a.topMargin=dp(2);row.addView(mark,a);}
        else {View spacer=new View(getContext());row.addView(spacer,new LinearLayout.LayoutParams(0,dp(1),1));}
        TextView bubble=text(content,15);bubble.setTextColor(assistant?foreground:Color.WHITE);bubble.setLineSpacing(dp(2),1f);
        bubble.setPadding(dp(14),dp(11),dp(14),dp(11));bubble.setBackground(rounded(assistant?cardColor:accent,18,assistant?borderColor:accent,assistant?1:0));
        bubble.setMaxWidth((int)(getResources().getDisplayMetrics().widthPixels*.70f));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,-2);bp.weight=0;bp.bottomMargin=dp(4);
        row.addView(bubble,bp);
        if(!assistant){FrameLayout mark=userMark(28);LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(dp(28),dp(28));a.leftMargin=dp(8);a.topMargin=dp(2);row.addView(mark,a);}
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.bottomMargin=dp(8);timeline.addView(row,rp);
    }

    private void sendMessage(){
        String message=input.getText().toString().trim();if(message.isEmpty()||!send.isEnabled())return;
        if(getContext() instanceof android.app.Activity)DecisionActions.cancelPending((android.app.Activity)getContext());
        input.setText("");if(welcome!=null){timeline.removeAllViews();welcome=null;}
        store.addMessage("user",message);addMessageRow("user",message);typingRow=addTypingRow();scrollToLatest(true);busy=true;setSending(false);status.setText("正在认真听你说…");
        List<AssistantStore.Message> messages=store.recentMessages(18);List<AssistantStore.Memory> memories=store.memories(false);
        network.execute(()->{
            try{
                AssistantApi.Reply reply=AssistantApi.chat(getContext(),messages,memories);
                for(AssistantStore.Memory memory:reply.memories)store.upsertMemory(memory);
                store.addMessage("assistant",reply.content);
                main.post(()->{removeTypingRow();addMessageRow("assistant",reply.content);status.setText(reply.directFallback?"已通过本地密钥连接":"我在这里，继续说吧");busy=false;setSending(input.length()>0);scrollToLatest(true);if(onReply!=null)onReply.run();if(getContext() instanceof android.app.Activity)DecisionActions.open((android.app.Activity)getContext(),reply.clientAction);});
            }catch(Exception e){main.post(()->{removeTypingRow();addMessageRow("assistant","刚才连接不太顺利，请稍后再试一次。");status.setText("暂时没有连上");busy=false;setSending(input.length()>0);scrollToLatest(true);});}
        });
    }

    private View addTypingRow(){
        LinearLayout row=new LinearLayout(getContext());row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.TOP);
        View mark=assistantMark(26);LinearLayout.LayoutParams avatar=new LinearLayout.LayoutParams(dp(26),dp(26));avatar.rightMargin=dp(8);avatar.topMargin=dp(2);row.addView(mark,avatar);
        TextView label=text("正在组织回复…",13);label.setTextColor(secondary);label.setPadding(dp(13),dp(10),dp(13),dp(10));label.setBackground(rounded(cardColor,16,borderColor,1));
        row.addView(label,new LinearLayout.LayoutParams(-2,-2));LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(8);timeline.addView(row,params);return row;
    }
    private void removeTypingRow(){if(typingRow!=null){timeline.removeView(typingRow);typingRow=null;}}

    private void setSending(boolean enabled){
        send.setEnabled(enabled);send.setAlpha(enabled?1f:.55f);
        send.setTextColor(Color.WHITE);send.setBackground(rounded(enabled?accent:Color.parseColor(dark?"#506558":"#AAB9AA"),17,0,0));
    }

    private void updateComposerSurface(boolean focused){
        if(composerSurface!=null)composerSurface.setBackground(rounded(cardColor==0?Color.WHITE:cardColor,23,focused?accent:borderColor,focused?2:1));
    }

    private FrameLayout assistantMark(int size){
        FrameLayout mark=new FrameLayout(getContext());mark.setBackground(rounded(Color.parseColor(dark?"#3B6148":"#DCEBDD"),size/2,0,0));
        ImageView portrait=new ImageView(getContext());portrait.setImageResource(R.drawable.gray_character);portrait.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mark.addView(portrait,new FrameLayout.LayoutParams(-1,-1));
        View dot=new View(getContext());dot.setBackground(rounded(Color.parseColor("#75B77C"),5,Color.parseColor(dark?"#172B22":"#F8FAF5"),dp(1)));
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(9),dp(9),Gravity.BOTTOM|Gravity.RIGHT);mark.addView(dot,p);return mark;
    }
    private FrameLayout userMark(int size){
        FrameLayout mark=new FrameLayout(getContext());
        mark.setContentDescription("我的头像占位");
        mark.setBackground(rounded(Color.parseColor(dark?"#354A5D":"#DFE8F1"),size/2,0,0));
        TextView glyph=text("我",12);glyph.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        glyph.setTextColor(Color.parseColor(dark?"#D5E4F0":"#49647B"));glyph.setGravity(Gravity.CENTER);
        mark.addView(glyph,new FrameLayout.LayoutParams(-1,-1));return mark;
    }
    private void scrollToLatest(boolean smooth){
        if(scroll==null)return;
        timeline.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener(){
            @Override public void onGlobalLayout(){
                timeline.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                int bottom=Math.max(0,timeline.getMeasuredHeight()-scroll.getHeight()+scroll.getPaddingBottom());
                if(smooth)scroll.smoothScrollTo(0,bottom);else scroll.scrollTo(0,bottom);
            }
        });
        timeline.requestLayout();
    }
    private TextView text(String value,int size){TextView v=new TextView(getContext());v.setText(value);v.setTextSize(size);return v;}
    private GradientDrawable rounded(int color,int radius,int strokeColor,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(stroke>0)d.setStroke(dp(stroke),strokeColor);return d;}
    private GradientDrawable surface(int color,int tl,int tr,int br,int bl){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadii(new float[]{dp(tl),dp(tl),dp(tr),dp(tr),dp(br),dp(br),dp(bl),dp(bl)});return d;}
}
