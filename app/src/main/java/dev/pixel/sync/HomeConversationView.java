package dev.pixel.sync;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** The home is the conversation. Only its two latest utterances are projected here. */
public final class HomeConversationView extends FrameLayout {
    private final Activity activity;
    private final AssistantStore store;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService background = Executors.newFixedThreadPool(2);
    private final Runnable react;
    private final java.util.function.Consumer<Boolean> thinking;
    private final TextView assistant, user, state, result, write, speak, send;
    private final LinearLayout bottom, idle, editing, utterances;
    private final ScrollView conversation;
    private final EditText input;
    private final ComposerUnderline composerUnderline;
    private VoiceInputOverlay voice;
    private Dialog detail;
    private boolean dark, busy, active, closed, touching, keyboard, syncing, composing, queued;
    private String failureText;
    private int thinkingStep;
    private long generation, lastInteraction, pausedAt;
    private float touchStartX, touchStartY;
    private boolean greetingEvaluated;
    private String requestedMessageId;
    private String pendingGroup, inFlightGroup, assistantText = "", userText = "", resultText = "";
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
        setClipChildren(false);
        conversation=new ScrollView(activity);conversation.setFillViewport(false);conversation.setVerticalScrollBarEnabled(false);conversation.setClipToPadding(false);
        utterances=new LinearLayout(activity);utterances.setOrientation(LinearLayout.VERTICAL);utterances.setPadding(0,0,0,dp(8));
        conversation.addView(utterances,new ScrollView.LayoutParams(-1,-2));
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-1,dp(240),Gravity.TOP);cp.setMargins(dp(24),dp(88),dp(24),0);addView(conversation,cp);
        user=label("",15);user.setTag("home_user_message");user.setPadding(dp(16),dp(11),dp(16),dp(11));user.setMaxLines(3);user.setMaxWidth(dp(280));user.setEllipsize(TextUtils.TruncateAt.END);
        user.setContentDescription("我刚刚说的话，点击展开");user.setOnClickListener(v->showText("我",userText));
        LinearLayout.LayoutParams up=new LinearLayout.LayoutParams(-2,-2);up.gravity=Gravity.END;up.leftMargin=dp(40);up.bottomMargin=dp(12);utterances.addView(user,up);
        assistant=label("",16);assistant.setTag("home_assistant_message");assistant.setPadding(dp(17),dp(12),dp(17),dp(12));assistant.setMaxLines(4);assistant.setEllipsize(TextUtils.TruncateAt.END);
        assistant.setMaxWidth(dp(290));assistant.setContentDescription("Greta 最新发言，点击展开");assistant.setOnClickListener(v->showText("Greta",assistantText));
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-2,-2);ap.gravity=Gravity.START;ap.rightMargin=dp(20);utterances.addView(assistant,ap);
        TextView tail=label("▾",20);tail.setTag("tail");tail.setIncludeFontPadding(false);
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(dp(24),dp(14));tp.leftMargin=dp(28);tp.topMargin=-dp(4);utterances.addView(tail,tp);
        state=label("",13);state.setTag("home_reply_state");state.setPadding(dp(6),dp(4),dp(6),dp(8));state.setOnClickListener(v->retry());utterances.addView(state);
        bottom=new LinearLayout(activity);bottom.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);bp.setMargins(dp(16),0,dp(16),dp(10));addView(bottom,bp);
        result=label("",13);result.setMaxLines(2);result.setEllipsize(TextUtils.TruncateAt.END);result.setPadding(dp(12),dp(9),dp(12),dp(9));
        result.setOnClickListener(v->showResults());result.setOnLongClickListener(v->{resultText="最近结果 · 点击查看";prefs.edit().putString("result",resultText).apply();render();return true;});
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.bottomMargin=dp(8);bottom.addView(result,rp);
        idle=new LinearLayout(activity);idle.setTag("home_idle");idle.setGravity(Gravity.CENTER_VERTICAL);
        write=label("轻点输入…",14);write.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);write.setPadding(dp(8),0,0,0);write.setMinHeight(dp(44));write.setOnClickListener(v->focusInput());
        idle.addView(write,new LinearLayout.LayoutParams(0,-2,1));
        speak=label("按住说话",14);speak.setGravity(Gravity.CENTER);speak.setMinHeight(dp(44));
        speak.setOnTouchListener((v,event)->voiceTouch(event));
        speak.setOnClickListener(v->Toast.makeText(activity,"按住说话，松开发送文字",Toast.LENGTH_SHORT).show());
        speak.setOnLongClickListener(v->{startVoice();return true;});
        idle.addView(speak,new LinearLayout.LayoutParams(dp(112),-2));bottom.addView(idle);
        editing=new LinearLayout(activity);editing.setTag("home_composer");editing.setGravity(Gravity.BOTTOM);editing.setBaselineAligned(false);editing.setPadding(dp(8),0,0,0);editing.setMinimumHeight(dp(44));
        input=new EditText(activity);input.setTag("home_input");input.setTextSize(16);input.setHint("跟 Greta 说说…");input.setContentDescription("给 Greta 的消息");
        input.setBackgroundColor(Color.TRANSPARENT);input.setIncludeFontPadding(false);input.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);input.setPadding(0,dp(6),dp(10),dp(6));
        input.setMaxLines(2);input.setMinLines(1);input.setMinHeight(dp(44));input.setLineSpacing(dp(3),1f);input.setHorizontallyScrolling(false);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND|EditorInfo.IME_FLAG_NO_EXTRACT_UI);input.setText(prefs.getString("draft",""));composing=input.length()>0;
        input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){} public void afterTextChanged(Editable e){} public void onTextChanged(CharSequence s,int a,int b,int c){lastInteraction=System.currentTimeMillis();prefs.edit().putString("draft",s.toString()).apply();updateSend();}});
        input.setOnFocusChangeListener((v,focused)->{if(focused)composing=true;updateComposer();});
        input.setOnEditorActionListener((v,a,e)->{if(a==EditorInfo.IME_ACTION_SEND){submitInput();return true;}return false;});
        editing.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        send=label("↑",23);send.setTag("home_send");send.setGravity(Gravity.CENTER);send.setIncludeFontPadding(false);send.setContentDescription("发送消息");
        send.setOnClickListener(v->submitInput());editing.addView(send,new LinearLayout.LayoutParams(dp(44),dp(44)));
        bottom.addView(editing);
        composerUnderline=new ComposerUnderline(activity);composerUnderline.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);bottom.addView(composerUnderline,new LinearLayout.LayoutParams(-1,dp(7)));updateComposer();
        resultText=prefs.getString("result","");restore();setTheme(false);
    }
    @Override protected void onMeasure(int width,int height){
        super.onMeasure(width,height);
        // Bound the conversation above the composer even on small screens/landscape.
        int available=getMeasuredHeight()-getPaddingTop()-getPaddingBottom()-dp(88)-bottom.getMeasuredHeight()-dp(24);
        FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)conversation.getLayoutParams();int limit=Math.max(0,Math.min(dp(250),available));
        if(p.height!=limit){p.height=limit;conversation.setLayoutParams(p);super.onMeasure(width,height);}
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView label(String text,int size){TextView v=new TextView(activity);v.setText(text);v.setTextSize(size);return v;}
    private GradientDrawable surface(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    public void setTheme(boolean value){dark=value;int fg=Color.parseColor(dark?"#E5EDE3":"#263C31"),muted=Color.parseColor(dark?"#A5B9AA":"#59715F");
        for(TextView v:new TextView[]{assistant,user,write,speak,send,result,input})v.setTextColor(fg);state.setTextColor(muted);input.setHintTextColor(muted);
        int bubble=Color.parseColor(dark?"#E62B4235":"#EBE0EBDD");assistant.setBackground(surface(bubble,22));((TextView)findViewWithTag("tail")).setTextColor(bubble);
        user.setBackground(surface(Color.parseColor(dark?"#DC365441":"#DBD3E3CE"),20));
        idle.setBackgroundColor(Color.TRANSPARENT);editing.setBackgroundColor(Color.TRANSPARENT);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{Color.TRANSPARENT,Color.parseColor(dark?"#C0101F27":"#E6F1F5EE")}));
        write.setTextColor(muted);speak.setTextColor(muted);
        send.setTextColor(Color.parseColor(dark?"#102019":"#FFFFFF"));send.setBackground(surface(Color.parseColor(dark?"#8BCB9A":"#327C4E"),22));
        result.setBackground(surface(Color.parseColor(dark?"#CC243B2E":"#CCDDE8D7"),16));render();
    }
    private void restore(){
        List<AssistantStore.Message> messages=store.recentMessages(300);messages.sort((a,b)->a.createdAt==b.createdAt?Long.compare(a.id,b.id):Long.compare(a.createdAt,b.createdAt));String ag=null,ug=null;assistantText="";userText="";
        for(int i=messages.size()-1;i>=0;i--){AssistantStore.Message m=messages.get(i);String g=m.groupId.isEmpty()?"legacy:"+m.id:prefs.getString("display:"+m.groupId,m.groupId);
            if(m.role.equals("assistant")&&!prefs.getBoolean("superseded:"+m.groupId,false)){if(ag==null)ag=g;if(ag.equals(g))assistantText=m.content+(assistantText.isEmpty()?"":"\n"+assistantText);}
            if(m.role.equals("user")){if(ug==null)ug=g;if(ug.equals(g))userText=m.content+(userText.isEmpty()?"":"\n"+userText);}
        }
        if(resultText.isEmpty())for(int i=messages.size()-1;i>=0;i--)if(messages.get(i).role.equals("tool")){resultText=messages.get(i).content;break;}
        pendingGroup=null;
        for(int i=messages.size()-1;i>=0;i--){AssistantStore.Message m=messages.get(i);if(!m.role.equals("user"))continue;if(!m.status.equals("sent")&&!m.groupId.equals(inFlightGroup))pendingGroup=m.groupId;break;}
        render();
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
    private void reveal(View view){
        if(!active||reducedMotion()||view.getVisibility()!=VISIBLE)return;
        view.animate().cancel();view.setAlpha(0f);view.setTranslationY(dp(7));
        view.animate().alpha(1f).translationY(0).setDuration(180).start();
    }
    private void render(){
        assistant.setText(assistantText);boolean showAssistant=!waiting()&&pendingGroup==null&&!assistantText.isEmpty();
        assistant.setVisibility(showAssistant?VISIBLE:GONE);findViewWithTag("tail").setVisibility(showAssistant?VISIBLE:GONE);
        user.setText(userText);user.setVisibility(userText.isEmpty()?GONE:VISIBLE);result.setText(resultText);result.setVisibility(resultText.isEmpty()?GONE:VISIBLE);
        if(waiting())state.setText(busy&&pendingGroup!=null?"收到补充，接着处理…":"Greta 正在想…");
        else state.setText(pendingGroup==null?"":failureText==null?"尚未发送 · 点击重试":failureText);
        state.setVisibility(state.length()==0?GONE:VISIBLE);updateComposer();updateFeedback();
    }
    public void focusInput(){
        lastInteraction=System.currentTimeMillis();composing=true;updateComposer();input.requestFocus();input.setSelection(input.length());
        input.post(()->{if(!closed&&composing&&input.hasFocus())((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);});
    }
    public void onKeyboard(boolean shown){
        boolean was=keyboard;keyboard=shown;
        if(shown)composing=true;
        // Initial false insets can arrive between tapping Write and IME appearance.
        // Only an actual visible -> hidden transition may collapse an empty editor.
        else if(was&&input.length()==0){composing=false;input.clearFocus();}
        user.setMaxLines(shown?2:3);assistant.setMaxLines(shown?3:4);result.setMaxLines(shown?1:2);updateComposer();
    }
    private void submitInput(){
        String text=input.getText().toString().trim();if(text.isEmpty())return;
        input.setText("");composing=false;input.clearFocus();
        ((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
        updateComposer();submit(text);
    }
    private void submit(String text){
        DecisionActions.cancelPending(activity);queued=true;failureText=null;
        lastInteraction=System.currentTimeMillis();prefs.edit().putLong("last_conversation_at",lastInteraction).apply();generation++;
        if(pendingGroup==null){pendingGroup=UUID.randomUUID().toString();if(inFlightGroup!=null)prefs.edit().putString("display:"+pendingGroup,prefs.getString("display:"+inFlightGroup,inFlightGroup)).apply();}
        // A request that has already been attempted must remain immutable on retry.
        boolean frozen=prefs.contains("attempted:"+pendingGroup);
        if(frozen){pendingGroup=UUID.randomUUID().toString();}
        store.addMessage("user",text,pendingGroup,"pending",null,System.currentTimeMillis());restore();
        reveal(user);
        conversation.post(()->conversation.fullScroll(View.FOCUS_DOWN));
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
                if(version==generation){restore();reveal(assistant);react.run();DecisionActions.open(activity,reply.clientAction);}else{prefs.edit().putBoolean("superseded:reply:"+group,true).apply();render();}
                if(pendingGroup!=null)dispatch();else syncInbox();
            });
        }catch(Exception e){store.setStatus(group,"failed");main.post(()->{if(closed)return;busy=false;queued=false;inFlightGroup=null;
            // Keep a failed older request available in history; the current turn includes the user's supplements.
            if(pendingGroup==null)pendingGroup=group;
            failureText="未能确认发送结果 · 点击重试";render();
        });}});
    }
    public void resume(){active=true;if(!busy)restore();else render();main.removeCallbacks(poll);main.post(poll);if(pausedAt>0&&System.currentTimeMillis()-pausedAt>30*60*1000L)greetingEvaluated=false;main.postDelayed(this::maybeGreet,1400);}
    public void pause(){active=false;updateFeedback();pausedAt=System.currentTimeMillis();main.removeCallbacks(poll);main.removeCallbacks(beginVoice);touching=false;if(voice!=null)voice.interrupt();}
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
                prefs.edit().putLong("last_greeting",System.currentTimeMillis()).putString("greeting_day",day).putInt("greeting_count",count+1).apply();store.addMessage("assistant",text);restore();react.run();
            });
        });
    }
    public void openIncoming(String id){if(id==null||id.isEmpty())return;AssistantStore.Message existing=store.externalMessage("inbox:"+id);if(existing!=null){showText("Greta 提醒你",existing.content);return;}requestedMessageId=id;syncInbox();}
    public void syncInbox(){
        if(syncing||closed)return;syncing=true;
        background.execute(()->{try{JSONArray items=AssistantApi.inbox(activity);main.post(()->{syncing=false;if(closed)return;
            long sequence=prefs.getLong("inbox_sequence",0);boolean changed=false;for(int i=0;i<items.length();i++){JSONObject m=items.optJSONObject(i);if(m==null)continue;
                sequence=Math.max(sequence,m.optLong("sequence",0));String id=m.optString("id"),body=m.optString("body");if(id.isEmpty()||body.isEmpty())continue;if(TopicRepository.blocksIncoming(activity,id))continue;
                long timestamp=System.currentTimeMillis();try{timestamp=java.time.Instant.parse(m.optString("created_at")).toEpochMilli();}catch(Exception ignored){}
                if(store.addMessage("assistant",body,"inbox:"+id,"sent","inbox:"+id,timestamp)!=-1){store.addMessage("tool",m.optString("title","提醒")+"\n"+body,"inbox:"+id,"sent","inbox-card:"+id,timestamp);resultText=m.optString("title","提醒")+" · "+body;changed=true;}
            }
            prefs.edit().putLong("inbox_sequence",sequence).apply();
            if(changed){prefs.edit().putString("result",resultText).apply();if(idleForProactive())restore();else{render();main.postDelayed(()->{if(idleForProactive())restore();},1500);}}
            if(items.length()==100)syncInbox();
            if(requestedMessageId!=null){for(int i=0;i<items.length();i++){JSONObject m=items.optJSONObject(i);if(m!=null&&requestedMessageId.equals(m.optString("id"))){requestedMessageId=null;showText(m.optString("title","Greta"),m.optString("body"));break;}}}
        });}catch(Exception ignored){main.post(()->syncing=false);}});
    }
    private void showText(String title,String text){if(text.isEmpty())return;lastInteraction=System.currentTimeMillis();TextView body=label(text,16);body.setTextIsSelectable(true);android.text.util.Linkify.addLinks(body,android.text.util.Linkify.WEB_URLS);body.setPadding(dp(22),dp(18),dp(22),dp(18));body.setTextColor(Color.parseColor(dark?"#E5EDE3":"#263C31"));ScrollView scroll=new ScrollView(activity);scroll.addView(body);showDialog(title,scroll);}
    private void showDialog(String title,View body){Context themed=new android.view.ContextThemeWrapper(activity,dark?android.R.style.Theme_Material_Dialog_Alert:android.R.style.Theme_Material_Light_Dialog_Alert);detail=new AlertDialog.Builder(themed).setTitle(title).setView(body).setPositiveButton("收起",null).create();detail.show();}
    public void showHistory(){lastInteraction=System.currentTimeMillis();Intent intent=new Intent(activity,HistoryActivity.class);intent.putExtra("dark",dark);activity.startActivity(intent);}
    public void showResults(){
        lastInteraction=System.currentTimeMillis();
        LinearLayout list=new LinearLayout(activity);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(dp(18),0,dp(18),0);
        TextView loading=label("正在读取提醒…",13);loading.setTextColor(Color.parseColor(dark?"#E5EDE3":"#263C31"));list.addView(loading);
        List<AssistantStore.Message> records=store.recentMessages(200);for(int i=records.size()-1;i>=0;i--){AssistantStore.Message m=records.get(i);if(!m.role.equals("tool"))continue;TextView row=label(m.content,15);row.setTextColor(Color.parseColor(dark?"#E5EDE3":"#263C31"));row.setPadding(0,dp(12),0,dp(16));row.setTextIsSelectable(true);android.text.util.Linkify.addLinks(row,android.text.util.Linkify.WEB_URLS);list.addView(row);}
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
    private void actOnReminder(String id,String action,View row){row.setEnabled(false);background.execute(()->{try{AssistantApi.reminderAction(activity,id,action);main.post(()->{if(closed)return;((android.view.ViewGroup)row.getParent()).removeView(row);String text=action.equals("complete")?"提醒已完成":"已延后 15 分钟";store.addMessage("tool",text);Toast.makeText(activity,text,Toast.LENGTH_SHORT).show();});}catch(Exception e){main.post(()->{row.setEnabled(true);Toast.makeText(activity,"操作未完成，请重试",Toast.LENGTH_SHORT).show();});}});}
    private boolean voiceTouch(android.view.MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){touching=true;speak.setScaleX(.94f);speak.setScaleY(.94f);speak.setAlpha(.72f);touchStartX=event.getRawX();touchStartY=event.getRawY();lastInteraction=System.currentTimeMillis();main.postDelayed(beginVoice,500);return true;}
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE){float dx=event.getRawX()-touchStartX,dy=event.getRawY()-touchStartY;if(voice==null&&Math.abs(dy)>dp(20))touching=false;if(voice!=null)voice.move(event.getRawX(),event.getRawY());return true;}
        if(event.getActionMasked()==MotionEvent.ACTION_UP){main.removeCallbacks(beginVoice);touching=false;speak.setScaleX(1f);speak.setScaleY(1f);speak.setAlpha(1f);if(voice!=null)voice.release(event.getRawX(),event.getRawY());else speak.performClick();return true;}
        if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){main.removeCallbacks(beginVoice);touching=false;speak.setScaleX(1f);speak.setScaleY(1f);speak.setAlpha(1f);if(voice!=null)voice.interrupt();return true;}return true;
    }
    private void startVoice(){if(voice!=null)return;
        if(activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){touching=false;activity.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},63);Toast.makeText(activity,"授权后，请重新按住说话",Toast.LENGTH_SHORT).show();return;}
        voice=new VoiceInputOverlay(activity,new VoiceInputOverlay.Listener(){
            public void completed(String text,boolean edit){voice=null;if(edit){input.setText(text);if(active)focusInput();}else submit(text);}
            public void dismissed(){voice=null;}
        });voice.start();
    }
    public void shutdown(){pause();closed=true;main.removeCallbacksAndMessages(null);if(detail!=null)detail.dismiss();background.shutdownNow();network.execute(store::close);network.shutdown();}
}
