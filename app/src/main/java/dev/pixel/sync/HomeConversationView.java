package dev.pixel.sync;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.util.Log;
import android.text.*;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Conversation history, input and notification access on the home screen. */
public final class HomeConversationView extends FrameLayout {
    private static final int CHAT_PAGE_SIZE = 20;
    private final Activity activity;
    private final AssistantStore store;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService background = Executors.newFixedThreadPool(2);
    private final Runnable react;
    private final java.util.function.Consumer<Boolean> thinking;
    private final TextView state, write, speak, send;
    private final LinearLayout bottom, idle, editing;
    private final ListView conversation;
    private final ChatRows chatRows = new ChatRows();
    private List<AssistantStore.InboxItem> inbox = Collections.emptyList();
    private ArrayAdapter<AssistantStore.InboxItem> inboxRows;
    private java.util.function.Consumer<Boolean> unreadChanged = unread -> {};
    private final FrameLayout petStage,petLeft,petRight;
    private View petView;
    private final EditText input;
    private final ComposerUnderline composerUnderline;
    private VoiceInputOverlay voice;
    private Dialog detail;
    private boolean dark, busy, active, closed, touching, voiceAttempted, keyboard, syncing, composing, queued;
    private String failureText;
    private int thinkingStep;
    private long generation, lastInteraction, pausedAt;
    private float touchStartX, touchStartY;
    private boolean greetingEvaluated;
    private boolean hasOlderChat, olderChatScheduled, prependingChat;
    private String requestedMessageId;
    private String pendingGroup, inFlightGroup, resultText = "";
    private final Runnable dispatch = this::dispatch;
    private final Runnable beginVoice = () -> { if(touching) startVoice(); };
    private final Runnable poll = new Runnable(){public void run(){if(!active||closed)return;syncInbox();background.execute(()->{try{TopicRepository.synchronize(activity);}catch(Exception ignored){}});main.postDelayed(this,30000);}};

    private final Runnable thinkingTick = new Runnable(){public void run(){
        if(closed||!active||!waiting())return;
        if(reducedMotion()){state.setText("Greta 正在想…");return;}
        thinkingStep=(thinkingStep+1)%4;
        if(pendingGroup==null||!busy)state.setText("Greta 正在想"+new String(new char[thinkingStep]).replace("\0","·"));
        main.postDelayed(this,550);
    }};

    public HomeConversationView(Activity activity, Runnable react) {this(activity,react,value->{});}
    public HomeConversationView(Activity activity, Runnable react,java.util.function.Consumer<Boolean> thinking) {
        super(activity); this.activity=activity;this.react=react;this.thinking=thinking;
        store=new AssistantStore(activity);prefs=activity.getSharedPreferences("home_conversation",0);
        setFocusableInTouchMode(true);
        conversation=new ListView(activity){
            @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight){
                int children=getChildCount();
                boolean atEnd=children>0&&getLastVisiblePosition()==getCount()-1
                        &&getChildAt(children-1).getBottom()<=oldHeight-getListPaddingBottom();
                super.onSizeChanged(width,height,oldWidth,oldHeight);
                if(!prependingChat&&height>0&&(oldHeight==0||atEnd)&&chatRows.getCount()>0){
                    setSelectionFromTop(chatRows.getCount()-1,0);
                }
            }
            @Override public boolean onGenericMotionEvent(MotionEvent event){
                boolean handled=super.onGenericMotionEvent(event);
                if(event.getActionMasked()==MotionEvent.ACTION_SCROLL&&event.getAxisValue(MotionEvent.AXIS_VSCROLL)>0){
                    scheduleOlderChat();
                }
                return handled;
            }
            @Override protected void layoutChildren(){
                super.layoutChildren();
                if(prependingChat){
                    prependingChat=false;
                    setTranscriptMode(AbsListView.TRANSCRIPT_MODE_NORMAL);
                }
            }
        };
        conversation.setTag("home_conversation_area");
        conversation.setDivider(null);
        conversation.setVerticalScrollBarEnabled(false);
        conversation.setSelector(android.R.color.transparent);
        conversation.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_NORMAL);
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-1,0,Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        cp.topMargin=dp(84);
        addView(conversation,cp);
        state=label("",13);
        state.setTag("home_reply_state");
        state.setPadding(dp(6),dp(4),dp(6),dp(8));
        state.setOnClickListener(v->retry());
        conversation.addFooterView(state,null,false);
        conversation.setAdapter(chatRows);
        conversation.setOnScrollListener(new AbsListView.OnScrollListener(){
            private boolean scrolling;
            @Override public void onScrollStateChanged(AbsListView view,int state){
                scrolling=state!=SCROLL_STATE_IDLE;
                if(scrolling)scheduleOlderChat();
            }
            @Override public void onScroll(AbsListView view,int first,int visible,int total){
                if(scrolling&&first==0)scheduleOlderChat();
            }
        });
        conversation.setOnItemClickListener((parent,row,position,id)->{
            AssistantStore.Message message=chatRows.getItem(position);
            showText(speaker(message),message.content);
        });
        petStage=new FrameLayout(activity);petStage.setTag("home_pet_stage");addView(petStage,new FrameLayout.LayoutParams(-1,0,Gravity.TOP));
        petLeft=new FrameLayout(activity);petLeft.setTag("home_pet_left_slot");petStage.addView(petLeft,new FrameLayout.LayoutParams(0,-1,Gravity.LEFT));
        petRight=new FrameLayout(activity);petRight.setTag("home_pet_right_slot");petStage.addView(petRight,new FrameLayout.LayoutParams(0,-1,Gravity.RIGHT));
        bottom=new LinearLayout(activity);bottom.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);addView(bottom,bp);
        idle=new LinearLayout(activity);idle.setTag("home_idle");idle.setGravity(Gravity.CENTER_VERTICAL);idle.setPadding(0,0,dp(16),0);
        write=label("轻点输入…",16);write.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);write.setPadding(dp(24),0,0,0);write.setIncludeFontPadding(false);write.setLetterSpacing(.02f);write.setMinHeight(dp(60));write.setOnClickListener(v->focusInput());
        idle.addView(write,new LinearLayout.LayoutParams(0,-2,1));
        speak=label("按住说话",15);speak.setGravity(Gravity.CENTER);speak.setIncludeFontPadding(false);speak.setLetterSpacing(.02f);speak.setMinHeight(dp(60));speak.setSoundEffectsEnabled(false);
        speak.setOnTouchListener((v,event)->voiceTouch(event));
        speak.setOnClickListener(v->Toast.makeText(activity,"按住说话，松开发送文字",Toast.LENGTH_SHORT).show());
        speak.setOnLongClickListener(v->{startVoice();return true;});
        idle.addView(speak,new LinearLayout.LayoutParams(dp(112),-2));bottom.addView(idle);
        editing=new LinearLayout(activity);editing.setTag("home_composer");editing.setGravity(Gravity.CENTER_VERTICAL);editing.setBaselineAligned(false);editing.setPadding(dp(24),0,dp(20),0);editing.setMinimumHeight(dp(60));
        input=new EditText(activity);input.setTag("home_input");input.setTextSize(16);input.setHint("跟 Greta 说说…");input.setContentDescription("给 Greta 的消息");
        input.setBackgroundColor(Color.TRANSPARENT);input.setIncludeFontPadding(false);input.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);input.setPadding(0,dp(10),dp(10),dp(10));
        input.setMaxLines(2);input.setMinLines(1);input.setMinHeight(dp(60));input.setLineSpacing(dp(3),1f);input.setHorizontallyScrolling(false);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND|EditorInfo.IME_FLAG_NO_EXTRACT_UI);input.setText(prefs.getString("draft",""));composing=input.length()>0;
        input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){} public void afterTextChanged(Editable e){} public void onTextChanged(CharSequence s,int a,int b,int c){lastInteraction=System.currentTimeMillis();prefs.edit().putString("draft",s.toString()).apply();updateSend();}});
        input.setOnFocusChangeListener((v,focused)->{if(focused)composing=true;updateComposer();});
        input.setOnEditorActionListener((v,a,e)->{if(a==EditorInfo.IME_ACTION_SEND){submitInput();return true;}return false;});
        editing.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        send=label("↑",23);send.setTag("home_send");send.setGravity(Gravity.CENTER);send.setIncludeFontPadding(false);send.setContentDescription("发送消息");
        send.setOnClickListener(v->submitInput());editing.addView(send,new LinearLayout.LayoutParams(dp(44),dp(44)));
        bottom.addView(editing);
        composerUnderline=new ComposerUnderline(activity);composerUnderline.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);bottom.addView(composerUnderline,new LinearLayout.LayoutParams(-1,dp(2)));updateComposer();
        resultText=prefs.getString("result","");restore();setTheme(false);
    }
    @Override protected void onMeasure(int width,int height){
        super.onMeasure(width,height);
        // Share the usable space between two non-overlapping zones, including IME frames.
        int available=Math.max(0,getMeasuredHeight()-getPaddingTop()-getPaddingBottom()-dp(84)-bottom.getMeasuredHeight());
        int gap=Math.min(dp(20),available/6),content=available-2*gap;
        int messages=Math.round(content*.62f),stage=content-messages;
        if(stage<dp(48)){messages=available;stage=0;gap=0;}
        FrameLayout.LayoutParams cp=(FrameLayout.LayoutParams)conversation.getLayoutParams(),pp=(FrameLayout.LayoutParams)petStage.getLayoutParams();
        boolean changed=false;
        int chatWidth=Math.min(dp(680),Math.max(0,getMeasuredWidth()-getPaddingLeft()-getPaddingRight()-dp(48)));
        if(cp.height!=messages||cp.width!=chatWidth){
            cp.height=messages;cp.width=chatWidth;conversation.setLayoutParams(cp);changed=true;
        }
        int stageTop=dp(84)+messages+gap;
        if(pp.height!=stage||pp.topMargin!=stageTop){pp.height=stage;pp.topMargin=stageTop;petStage.setLayoutParams(pp);changed=true;}
        if(petView!=null){
            int stageWidth=Math.max(0,getMeasuredWidth()-getPaddingLeft()-getPaddingRight());
            int size=Math.max(0,Math.min(dp(210),Math.min(Math.round(stageWidth*.46f),Math.round(stage*.76f))));
            FrameLayout.LayoutParams anchor=(FrameLayout.LayoutParams)petView.getLayoutParams();
            if(anchor.width!=size||anchor.height!=size){anchor.width=size;anchor.height=size;petView.setLayoutParams(anchor);changed=true;}
            int visible=stage>=dp(48)?VISIBLE:INVISIBLE;if(petView.getVisibility()!=visible)petView.setVisibility(visible);
            int sideWidth=Math.max(0,(stageWidth-size)/2-dp(12));
            for(FrameLayout slot:new FrameLayout[]{petLeft,petRight}){FrameLayout.LayoutParams side=(FrameLayout.LayoutParams)slot.getLayoutParams();if(side.width!=sideWidth){side.width=sideWidth;slot.setLayoutParams(side);changed=true;}}
        }
        if(changed)super.onMeasure(width,height);
    }
    void mountPet(View pet,Runnable placementChanged){
        petView=pet;petStage.addView(pet,new FrameLayout.LayoutParams(0,0,Gravity.CENTER));
        petStage.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->placementChanged.run());
        pet.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->placementChanged.run());requestLayout();
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView label(String text,int size){TextView v=new TextView(activity);v.setText(text);v.setTextSize(size);return v;}
    private GradientDrawable surface(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    public void setTheme(boolean value){dark=value;int fg=Color.parseColor(dark?"#E5EDE3":"#263C31"),muted=Color.parseColor(dark?"#A5B9AA":"#59715F");
        for(TextView v:new TextView[]{write,speak,send,input})v.setTextColor(fg);
        state.setTextColor(muted);input.setHintTextColor(muted);
        chatRows.notifyDataSetChanged();
        idle.setBackgroundColor(Color.TRANSPARENT);editing.setBackgroundColor(Color.TRANSPARENT);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{Color.TRANSPARENT,Color.parseColor(dark?"#B3101F27":"#CCF1F5EE"),Color.parseColor(dark?"#E6101F27":"#F5F1F5EE")}));
        write.setTextColor(fg);speak.setTextColor(fg);
        send.setTextColor(Color.parseColor(dark?"#102019":"#FFFFFF"));send.setBackground(surface(Color.parseColor(dark?"#8BCB9A":"#327C4E"),22));
        render();
    }
    private List<AssistantStore.Message> chatPage(long before){
        List<AssistantStore.Message> page=store.chatBefore(before,CHAT_PAGE_SIZE+1);
        hasOlderChat=page.size()>CHAT_PAGE_SIZE;
        if(hasOlderChat)page.remove(0);
        return page;
    }
    private void scheduleOlderChat(){
        if(!active||!hasOlderChat||olderChatScheduled||prependingChat||conversation.canScrollVertically(-1))return;
        olderChatScheduled=true;
        main.post(()->{
            olderChatScheduled=false;
            if(!active||closed||conversation.canScrollVertically(-1)||conversation.getChildCount()==0)return;
            List<AssistantStore.Message> older=chatPage(chatRows.getItemId(0));
            if(older.isEmpty())return;
            int top=conversation.getChildAt(0).getTop()-conversation.getListPaddingTop();
            // Keep the same message at the same offset when its adapter position shifts.
            prependingChat=true;
            conversation.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_DISABLED);
            chatRows.add(0,older);
            conversation.setSelectionFromTop(older.size(),top);
        });
    }
    private void restore(){
        int count=chatRows.getCount();
        chatRows.add(count,count==0?chatPage(Long.MAX_VALUE):store.chatAfter(chatRows.getItemId(count-1)));
        if(resultText.isEmpty()){
            AssistantStore.Message tool=store.latestChatMessage("tool");
            if(tool!=null)resultText=tool.content;
        }
        AssistantStore.Message latestUser=store.latestChatMessage("user");
        pendingGroup=latestUser!=null&&!latestUser.status.equals("sent")&&!latestUser.groupId.equals(inFlightGroup)
                ?latestUser.groupId:null;
        render();
    }
    private String speaker(AssistantStore.Message message){
        if(message.role.equals("user"))return "你";
        return message.role.equals("tool")?"工具结果":"Greta";
    }
    private int foreground(){return Color.parseColor(dark?"#E5EDE3":"#263C31");}
    private int muted(){return Color.parseColor(dark?"#A5B9AA":"#59715F");}

    private final class ChatRows extends BaseAdapter {
        private final List<AssistantStore.Message> messages=new ArrayList<>();
        void add(int position,List<AssistantStore.Message> page){
            if(page.isEmpty())return;
            messages.addAll(position,page);
            notifyDataSetChanged();
        }
        @Override public int getCount(){return messages.size();}
        @Override public AssistantStore.Message getItem(int position){return messages.get(position);}
        @Override public long getItemId(int position){return getItem(position).id;}
        @Override public boolean hasStableIds(){return true;}
        @Override public View getView(int position,View recycled,ViewGroup parent){
            ChatRow row=recycled instanceof ChatRow?(ChatRow)recycled:new ChatRow();
            row.bind(getItem(position));
            return row;
        }
    }

    private final class ChatRow extends LinearLayout {
        private final TextView caption,body;
        ChatRow(){
            super(activity);
            setOrientation(VERTICAL);
            setPadding(0,0,0,dp(12));
            caption=label("",11);
            body=label("",15);
            body.setEllipsize(TextUtils.TruncateAt.END);
            addView(caption,new LinearLayout.LayoutParams(-2,-2));
            addView(body,new LinearLayout.LayoutParams(-2,-2));
        }
        void bind(AssistantStore.Message message){
            boolean user=message.role.equals("user");
            boolean tool=message.role.equals("tool");
            setGravity(user?Gravity.END:Gravity.START);
            caption.setText(speaker(message));
            caption.setTextColor(muted());
            caption.setPadding(user?0:dp(19),0,user?dp(12):0,dp(4));
            body.setText(message.content);
            body.setTag(user?"home_user_message":tool?"home_tool_result":"home_assistant_message");
            body.setTextColor(foreground());
            body.setTextSize(user||tool?15:17);
            body.setMaxWidth(dp(user?280:360));
            body.setMaxLines(user?3:4);
            body.setLineSpacing(dp(user?4:5),1f);
            body.setGravity(user?Gravity.END:Gravity.START);
            body.setBackground(new ConversationSurface(!user,dark,getResources().getDisplayMetrics().density));
            body.setPadding(dp(user?16:21),dp(user?9:13),dp(user?17:22),dp(user?9:15));
            AppTypography.apply(this);
        }
    }
    private boolean waiting(){return busy||queued;}
    private boolean reducedMotion(){return activity.getSharedPreferences("home",0).getBoolean("reduceMotion",false);}
    private void updateFeedback(){
        boolean show=active&&waiting();thinking.accept(show);
        if(show&&!reducedMotion()){if(!main.hasCallbacks(thinkingTick))main.postDelayed(thinkingTick,550);}
        else main.removeCallbacks(thinkingTick);
    }
    private void updateSend(){if(send!=null){String draft=input.getText().toString();boolean ready=!draft.trim().isEmpty();send.setEnabled(ready);send.setAlpha(ready?1f:.28f);if(composerUnderline!=null)composerUnderline.update(dark,composing||keyboard,draft.codePointCount(0,draft.length()),!reducedMotion());}}
    private void updateComposer(){
        boolean show=composing||keyboard;
        editing.setVisibility(show?VISIBLE:GONE);idle.setVisibility(show?GONE:VISIBLE);updateSend();
    }
    private void render(){
        if(waiting())state.setText(busy&&pendingGroup!=null?"收到补充，接着处理…":"Greta 正在想…");
        else state.setText(pendingGroup==null?"":failureText==null?"尚未发送 · 点击重试":failureText);
        state.setVisibility(state.length()==0?GONE:VISIBLE);updateComposer();updateFeedback();
    }
    public void focusInput(){
        lastInteraction=System.currentTimeMillis();composing=true;updateComposer();input.requestFocus();input.setSelection(input.length());
        input.post(this::showInputKeyboard);
    }
    private void showInputKeyboard(){
        if(closed||!composing||!input.hasFocus())return;
        WindowInsetsController controller=input.getWindowInsetsController();
        if(controller!=null)controller.show(WindowInsets.Type.ime());
        else ((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);
    }
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused&&input!=null&&input.hasFocus()&&composing)input.post(this::showInputKeyboard);}
    void dismissKeyboardOutside(float rawX,float rawY){
        if(voice!=null||(!input.hasFocus()&&!keyboard))return;
        Rect bounds=new Rect();if(editing.getGlobalVisibleRect(bounds)&&bounds.contains((int)rawX,(int)rawY))return;
        composing=input.length()>0;input.clearFocus();requestFocus();
        ((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
        updateComposer();
    }
    public void onKeyboard(boolean shown){
        boolean was=keyboard;keyboard=shown;
        if(shown)composing=true;
        // Initial false insets can arrive between tapping Write and IME appearance.
        // Only an actual visible -> hidden transition may collapse an empty editor.
        else if(was&&input.length()==0){composing=false;input.clearFocus();}
        updateComposer();
    }
    private void submitInput(){
        String text=input.getText().toString().trim();if(text.isEmpty())return;
        input.setText("");composing=false;input.clearFocus();
        ((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
        updateComposer();submit(text);
    }
    private void submit(String text){
        List<AssistantStore.Message> intent=new ArrayList<>(store.conversation(28));intent.add(new AssistantStore.Message("user",text,System.currentTimeMillis()));
        if(ClientTools.recordEvidence(intent)!=null){RecordLocation.withPermission(activity,()->submitReady(text));return;}
        submitReady(text);
    }
    private void submitReady(String text){
        DecisionActions.cancelPending(activity);queued=true;failureText=null;
        lastInteraction=System.currentTimeMillis();prefs.edit().putLong("last_conversation_at",lastInteraction).apply();generation++;
        if(pendingGroup==null)pendingGroup=UUID.randomUUID().toString();
        // A request that has already been attempted must remain immutable on retry.
        boolean frozen=prefs.contains("attempted:"+pendingGroup);
        if(frozen){pendingGroup=UUID.randomUUID().toString();}
        store.addMessage("user",text,pendingGroup,"pending",null,System.currentTimeMillis());restore();
        main.removeCallbacks(dispatch);main.postDelayed(dispatch,800);
    }
    private void retry(){if(pendingGroup!=null&&!busy){main.removeCallbacks(dispatch);dispatch();}}
    private void dispatch(){
        if(closed||busy||pendingGroup==null)return;
        queued=false;failureText=null;busy=true;inFlightGroup=pendingGroup;pendingGroup=null;final String group=inFlightGroup;final long version=generation;
        prefs.edit().putBoolean("attempted:"+group,true).apply();store.setStatus(group,"pending");render();
        List<AssistantStore.Message> turns=store.conversation(28);
        List<AssistantStore.Message> supplements=new ArrayList<>();for(AssistantStore.Message m:turns)if(m.role.equals("user")&&m.groupId.equals(group))supplements.add(m);turns.removeAll(supplements);turns.addAll(supplements);
        List<AssistantStore.Memory> memories=store.memories(false);
        network.execute(()->{try{
            AssistantApi.Reply reply=AssistantApi.chat(activity,turns,memories,group);
            for(AssistantStore.Memory memory:reply.memories)store.upsertMemory(memory);
            if(!reply.content.trim().isEmpty())store.addMessage("assistant",reply.content,"reply:"+group,"sent","reply:"+group,System.currentTimeMillis());
            if(!reply.toolResults.isEmpty())store.addMessage("tool",reply.toolResults,"reply:"+group,"sent","tools:"+group,System.currentTimeMillis());
            store.setStatus(group,"sent");
            main.post(()->{if(closed)return;busy=false;inFlightGroup=null;
                if(!reply.toolResults.isEmpty()){resultText=reply.toolResults;prefs.edit().putString("result",resultText).apply();}
                restore();
                if(version==generation){react.run();DecisionActions.open(activity,reply.clientAction);}
                if(pendingGroup!=null)dispatch();else syncInbox();
            });
        }catch(Exception e){store.setStatus(group,"failed");main.post(()->{if(closed)return;busy=false;queued=false;inFlightGroup=null;
            // Keep a failed older request available in history; the current turn includes the user's supplements.
            if(pendingGroup==null)pendingGroup=group;
            failureText="未能确认发送结果 · 点击重试";render();
        });}});
    }
    public void resume(){active=true;refreshNotifications();if(!busy)restore();else render();main.removeCallbacks(poll);main.post(poll);if(pausedAt>0&&System.currentTimeMillis()-pausedAt>30*60*1000L)greetingEvaluated=false;main.postDelayed(this::maybeGreet,1400);}
    public void pause(){active=false;updateFeedback();pausedAt=System.currentTimeMillis();main.removeCallbacks(poll);main.removeCallbacks(beginVoice);touching=false;resetVoicePress();if(voice!=null)voice.interrupt();}
    private boolean idleForProactive(){return active&&!closed&&!busy&&pendingGroup==null&&!keyboard&&input.length()==0&&!touching&&voice==null&&(detail==null||!detail.isShowing())&&activity.hasWindowFocus()&&System.currentTimeMillis()-lastInteraction>1200;}
    private void maybeGreet(){
        if(greetingEvaluated||!idleForProactive()||!prefs.getBoolean("proactive",true))return;
        greetingEvaluated=true;
        long now=System.currentTimeMillis();java.time.ZonedDateTime time=java.time.ZonedDateTime.now();int hour=time.getHour();
        if(hour<8||hour>=23||now-prefs.getLong("last_greeting",0)<4*3600000L||now-prefs.getLong("last_conversation_at",0)<15*60000L)return;
        String day=time.toLocalDate().toString();int count=day.equals(prefs.getString("greeting_day",""))?prefs.getInt("greeting_count",0):0;if(count>=2)return;
        long version=generation;List<AssistantStore.Message> context=store.conversation(12);
        background.execute(()->{String opening="";try{opening=AssistantApi.homeOpening(activity,context);}catch(Exception ignored){}
            if(opening.isEmpty()&&context.isEmpty())opening="嗨，我在这里。有什么想跟我说的吗？";
            final String text=opening;main.post(()->{if(text.isEmpty()||version!=generation||!idleForProactive()||!prefs.getBoolean("proactive",true))return;
                prefs.edit().putLong("last_greeting",System.currentTimeMillis()).putString("greeting_day",day).putInt("greeting_count",count+1).apply();store.saveNotifications(Collections.singletonList(new AssistantStore.InboxItem(
                        "opening:"+UUID.randomUUID(),"Greta",text,System.currentTimeMillis(),false)));
                refreshNotifications();react.run();
            });
        });
    }
    void observeUnread(java.util.function.Consumer<Boolean> listener){
        unreadChanged=listener;
        refreshNotifications();
    }
    private void refreshNotifications(){
        inbox=new ArrayList<>();
        boolean unread=false;
        for(AssistantStore.InboxItem item:store.notifications()){
            if(TopicRepository.blocksIncoming(activity,item.id))continue;
            inbox.add(item);
            unread|=!item.read;
        }
        unreadChanged.accept(unread);
        if(inboxRows!=null){
            inboxRows.setNotifyOnChange(false);
            inboxRows.clear();
            inboxRows.addAll(inbox);
            inboxRows.notifyDataSetChanged();
        }
    }
    void showNotifications(){
        lastInteraction=System.currentTimeMillis();
        refreshNotifications();
        FrameLayout panel=new FrameLayout(activity);
        int height=Math.min(dp(440),getHeight()/2);
        panel.setMinimumHeight(height);
        ListView list=new ListView(activity);
        list.setTag("home_notifications_list");
        panel.addView(list,new FrameLayout.LayoutParams(-1,height));
        TextView empty=label("暂无通知",15);
        empty.setTextColor(muted());empty.setGravity(Gravity.CENTER);
        panel.addView(empty,new FrameLayout.LayoutParams(-1,height));
        list.setEmptyView(empty);
        inboxRows=new ArrayAdapter<AssistantStore.InboxItem>(activity,android.R.layout.simple_list_item_1,new ArrayList<>(inbox)){
            @Override public View getView(int position,View recycled,ViewGroup parent){
                AssistantStore.InboxItem item=getItem(position);
                TextView row=recycled instanceof TextView?(TextView)recycled:label("",15);
                String time=java.time.Instant.ofEpochMilli(item.createdAt).atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
                row.setText((item.read?"":"●  ")+item.title+"\n"+time);
                row.setTextColor(foreground());
                row.setPadding(dp(24),dp(12),dp(24),dp(12));
                AppTypography.apply(row);
                return row;
            }
        };
        list.setAdapter(inboxRows);
        list.setOnItemClickListener((parent,row,position,id)->openNotification(inboxRows.getItem(position)));
        showDialog("通知",panel);
        detail.setOnDismissListener(dialog->inboxRows=null);
        syncInbox();
    }
    private void openNotification(AssistantStore.InboxItem item){
        store.readNotification(item.id);
        refreshNotifications();
        showText(item.title,item.body);
    }
    public void openIncoming(String id){
        if(id==null||id.isEmpty()||TopicRepository.blocksIncoming(activity,id))return;
        requestedMessageId=id;
        if(!openRequestedNotification())syncInbox();
    }
    private boolean openRequestedNotification(){
        if(requestedMessageId==null||!active)return false;
        for(AssistantStore.InboxItem item:inbox){
            if(item.id.equals(requestedMessageId)){
                requestedMessageId=null;
                openNotification(item);
                return true;
            }
        }
        return false;
    }
    public void syncInbox(){
        if(syncing||closed)return;
        syncing=true;
        background.execute(()->{
            try{
                JSONArray items=AssistantApi.inbox(activity);
                main.post(()->saveInboxPage(items));
            }catch(Exception ignored){main.post(()->syncing=false);}
        });
    }
    private void saveInboxPage(JSONArray items){
        syncing=false;
        if(closed)return;
        long previous=prefs.getLong("inbox_sequence",0),sequence=previous;
        List<AssistantStore.InboxItem> received=new ArrayList<>();
        for(int i=0;i<items.length();i++){
            JSONObject item=items.optJSONObject(i);
            if(item==null)continue;
            sequence=Math.max(sequence,item.optLong("sequence",0));
            String id=item.optString("id"),body=item.optString("body");
            if(id.isEmpty()||body.isEmpty()||TopicRepository.blocksIncoming(activity,id))continue;
            long timestamp=System.currentTimeMillis();
            try{timestamp=java.time.Instant.parse(item.optString("created_at")).toEpochMilli();}
            catch(java.time.format.DateTimeParseException ignored){}
            received.add(new AssistantStore.InboxItem(id,item.optString("title","Greta 提醒你"),body,timestamp,false));
        }
        try{
            store.saveNotifications(received);
        }catch(android.database.sqlite.SQLiteException error){
            Log.w("GretaInbox","Could not save notifications",error);
            return;
        }
        prefs.edit().putLong("inbox_sequence",sequence).apply();
        if(!received.isEmpty())refreshNotifications();
        openRequestedNotification();
        if(items.length()==100&&sequence>previous)syncInbox();
    }
    private void showText(String title,String text){if(text.isEmpty())return;lastInteraction=System.currentTimeMillis();activity.startActivity(new Intent(activity,BriefingActivity.class).putExtra("title",title).putExtra("body",text));}
    private void showDialog(String title,View body){Context themed=new android.view.ContextThemeWrapper(activity,dark?android.R.style.Theme_Material_Dialog_Alert:android.R.style.Theme_Material_Light_Dialog_Alert);detail=new AlertDialog.Builder(themed).setTitle(title).setView(body).setPositiveButton("收起",null).create();detail.show();AppTypography.apply(detail.getWindow().getDecorView());}
    public void showHistory(){lastInteraction=System.currentTimeMillis();Intent intent=new Intent(activity,HistoryActivity.class);intent.putExtra("dark",dark);activity.startActivity(intent);}
    public void showResults(){
        lastInteraction=System.currentTimeMillis();
        LinearLayout list=new LinearLayout(activity);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(dp(18),0,dp(18),0);
        TextView loading=label("正在读取提醒…",13);loading.setTextColor(Color.parseColor(dark?"#E5EDE3":"#263C31"));list.addView(loading);
        if(!resultText.isEmpty()){TextView latest=label(resultText,15);latest.setTextColor(Color.parseColor(dark?"#E5EDE3":"#263C31"));latest.setPadding(0,dp(12),0,dp(16));latest.setTextIsSelectable(true);android.text.util.Linkify.addLinks(latest,android.text.util.Linkify.WEB_URLS);latest.setOnClickListener(v->showText("最近结果",resultText));list.addView(latest);}
        List<AssistantStore.Message> records=store.recentMessages(200);for(int i=records.size()-1;i>=0;i--){AssistantStore.Message m=records.get(i);if(!m.role.equals("tool")||m.content.equals(resultText))continue;TextView row=label(m.content,15);row.setTextColor(Color.parseColor(dark?"#E5EDE3":"#263C31"));row.setPadding(0,dp(12),0,dp(16));row.setTextIsSelectable(true);android.text.util.Linkify.addLinks(row,android.text.util.Linkify.WEB_URLS);row.setOnClickListener(v->showText("内容详情",m.content));list.addView(row);}
        ScrollView scroll=new ScrollView(activity);scroll.addView(list);showDialog("工具与最近结果",scroll);
        background.execute(()->{try{JSONArray reminders=AssistantApi.reminders(activity);main.post(()->{if(closed)return;loading.setText(reminders.length()==0?"暂无待办提醒":"待办提醒");int at=1;
            for(int i=0;i<reminders.length();i++){JSONObject item=reminders.optJSONObject(i);if(item==null)continue;
                LinearLayout entry=new LinearLayout(activity);entry.setOrientation(LinearLayout.VERTICAL);
                TextView title=label(item.optString("title")+" · "+friendlyTime(item.optString("due_at")),15);title.setTextColor(Color.parseColor(dark?"#E5EDE3":"#263C31"));entry.addView(title);
                LinearLayout actions=new LinearLayout(activity);Button complete=new Button(activity);complete.setText("完成");Button later=new Button(activity);later.setText("15 分钟后提醒");actions.addView(complete);actions.addView(later);entry.addView(actions);
                complete.setOnClickListener(v->actOnReminder(item.optString("id"),"complete",entry));later.setOnClickListener(v->actOnReminder(item.optString("id"),"snooze",entry));list.addView(entry,at++);
            }
        });}catch(Exception e){main.post(()->loading.setText("暂时无法读取提醒；以下是已保存结果"));}});
    }
    private String friendlyTime(String value){try{return java.time.OffsetDateTime.parse(value).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));}catch(Exception e){return value;}}
    private void actOnReminder(String id,String action,View row){row.setEnabled(false);background.execute(()->{try{AssistantApi.reminderAction(activity,id,action);main.post(()->{if(closed)return;((android.view.ViewGroup)row.getParent()).removeView(row);String text=action.equals("complete")?"提醒已完成":"已延后 15 分钟";Toast.makeText(activity,text,Toast.LENGTH_SHORT).show();});}catch(Exception e){main.post(()->{row.setEnabled(true);Toast.makeText(activity,"操作未完成，请重试",Toast.LENGTH_SHORT).show();});}});}
    private void resetVoicePress(){
        speak.setScaleX(1f);speak.setScaleY(1f);speak.setAlpha(1f);
        if(speak.getParent()!=null)speak.getParent().requestDisallowInterceptTouchEvent(false);
    }
    private boolean voiceTouch(android.view.MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){touching=true;voiceAttempted=false;speak.getParent().requestDisallowInterceptTouchEvent(true);speak.setScaleX(.94f);speak.setScaleY(.94f);speak.setAlpha(.72f);touchStartX=event.getRawX();touchStartY=event.getRawY();lastInteraction=System.currentTimeMillis();main.postDelayed(beginVoice,500);return true;}
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE){float dx=event.getRawX()-touchStartX,dy=event.getRawY()-touchStartY;if(voice==null&&Math.abs(dy)>dp(20))touching=false;if(voice!=null)voice.move(event.getRawX(),event.getRawY());return true;}
        if(event.getActionMasked()==MotionEvent.ACTION_UP){main.removeCallbacks(beginVoice);touching=false;resetVoicePress();if(voice!=null)voice.release();else if(!voiceAttempted)speak.performClick();return true;}
        if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){main.removeCallbacks(beginVoice);touching=false;resetVoicePress();if(voice!=null)voice.interrupt();return true;}return true;
    }
    private void startVoice(){if(voice!=null)return;voiceAttempted=true;
        if(activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){touching=false;activity.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},63);Toast.makeText(activity,"授权后，请重新按住说话",Toast.LENGTH_SHORT).show();return;}
        voice=new VoiceInputOverlay(activity,new VoiceInputOverlay.Listener(){
            public void completed(String text,boolean edit){voice=null;resetVoicePress();if(edit){input.setText(text);if(active)focusInput();}else submit(text);}
            public void dismissed(){voice=null;resetVoicePress();}
        });
        if(touching)voice.setHoldOrigin(touchStartX,touchStartY);
        else{int[] location=new int[2];speak.getLocationOnScreen(location);voice.setHoldOrigin(location[0]+speak.getWidth()/2f,location[1]+speak.getHeight()/2f);}
        voice.start();
    }
    public void shutdown(){pause();closed=true;main.removeCallbacksAndMessages(null);if(detail!=null)detail.dismiss();background.shutdownNow();network.execute(store::close);network.shutdown();}
}
