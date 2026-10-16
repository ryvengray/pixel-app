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
    private final TextView assistant, user, state, result, write, speak, send;
    private final LinearLayout bottom, idle, editing;
    private final EditText input;
    private VoiceInputOverlay voice;
    private Dialog detail;
    private boolean dark, busy, active, closed, touching, keyboard, syncing;
    private long generation, lastInteraction;
    private int sceneHeight;
    private boolean greetingEvaluated;
    private String requestedMessageId;
    private String pendingGroup, inFlightGroup, assistantText = "", userText = "", resultText = "";
    private final Runnable dispatch = this::dispatch;
    private final Runnable beginVoice = () -> { if(touching) startVoice(); };
    private final Runnable poll = new Runnable(){public void run(){if(!active||closed)return;syncInbox();main.postDelayed(this,30000);}};

    public HomeConversationView(Activity activity, Runnable react) {
        super(activity); this.activity=activity;this.react=react;
        store=new AssistantStore(activity);prefs=activity.getSharedPreferences("home_conversation",0);
        setClipChildren(false);
        assistant=label("",16);assistant.setPadding(dp(17),dp(12),dp(17),dp(12));assistant.setMaxLines(4);assistant.setEllipsize(TextUtils.TruncateAt.END);
        assistant.setMaxWidth(dp(290));assistant.setContentDescription("Greta 最新发言，点击展开");assistant.setOnClickListener(v->showText("Greta",assistantText));
        FrameLayout.LayoutParams ap=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);ap.setMargins(dp(24),dp(88),dp(24),0);addView(assistant,ap);
        // A short tail makes the speech belong to the character without covering her face.
        TextView tail=label("▾",22);tail.setTextColor(Color.TRANSPARENT);tail.setTag("tail");
        FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(dp(28),dp(26),Gravity.TOP|Gravity.CENTER_HORIZONTAL);tp.topMargin=dp(146);addView(tail,tp);
        bottom=new LinearLayout(activity);bottom.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);bp.setMargins(dp(24),0,dp(24),dp(12));addView(bottom,bp);
        user=label("",15);user.setPadding(dp(15),dp(10),dp(15),dp(10));user.setMaxLines(3);user.setEllipsize(TextUtils.TruncateAt.END);
        user.setOnClickListener(v->showText("我",userText));
        LinearLayout.LayoutParams up=new LinearLayout.LayoutParams(-2,-2);up.gravity=Gravity.RIGHT;up.leftMargin=dp(52);up.bottomMargin=dp(8);bottom.addView(user,up);
        state=label("",12);state.setPadding(dp(4),dp(3),dp(4),dp(6));state.setOnClickListener(v->retry());bottom.addView(state);
        result=label("",13);result.setMaxLines(2);result.setEllipsize(TextUtils.TruncateAt.END);result.setPadding(dp(12),dp(9),dp(12),dp(9));
        result.setOnClickListener(v->showResults());result.setOnLongClickListener(v->{resultText="最近结果 · 点击查看";prefs.edit().putString("result",resultText).apply();render();return true;});
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.bottomMargin=dp(8);bottom.addView(result,rp);
        idle=new LinearLayout(activity);idle.setGravity(Gravity.CENTER_VERTICAL);
        write=label("写点什么…",14);write.setGravity(Gravity.CENTER);write.setMinHeight(dp(56));write.setOnClickListener(v->focusInput());
        idle.addView(write,new LinearLayout.LayoutParams(0,-2,1));
        speak=label("按住说话",14);speak.setGravity(Gravity.CENTER);speak.setMinHeight(dp(56));
        speak.setOnTouchListener((v,event)->voiceTouch(event));
        speak.setOnClickListener(v->Toast.makeText(activity,"按住说话，松开发送文字",Toast.LENGTH_SHORT).show());
        speak.setOnLongClickListener(v->{startVoice();return true;});
        idle.addView(speak,new LinearLayout.LayoutParams(0,-2,2));bottom.addView(idle);
        editing=new LinearLayout(activity);editing.setGravity(Gravity.CENTER_VERTICAL);editing.setPadding(dp(8),dp(4),dp(5),dp(4));
        input=new EditText(activity);input.setTextSize(15);input.setHint("跟 Greta 说说…");input.setBackgroundColor(Color.TRANSPARENT);input.setMaxLines(4);input.setMinHeight(dp(48));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);input.setText(prefs.getString("draft",""));
        input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){} public void afterTextChanged(Editable e){} public void onTextChanged(CharSequence s,int a,int b,int c){lastInteraction=System.currentTimeMillis();prefs.edit().putString("draft",s.toString()).apply();}});
        input.setOnEditorActionListener((v,a,e)->{if(a==EditorInfo.IME_ACTION_SEND){submitInput();return true;}return false;});
        editing.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        send=label("发送",14);send.setGravity(Gravity.CENTER);send.setPadding(dp(12),0,dp(12),0);send.setMinHeight(dp(48));send.setOnClickListener(v->submitInput());editing.addView(send);
        bottom.addView(editing);editing.setVisibility(GONE);
        resultText=prefs.getString("result","");restore();setTheme(false);
        assistant.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)tail.getLayoutParams();p.topMargin=b-dp(7);tail.setLayoutParams(p);});
    }
    @Override protected void onLayout(boolean changed,int left,int top,int right,int bottomEdge){
        super.onLayout(changed,left,top,right,bottomEdge);sceneHeight=Math.max(sceneHeight,getHeight());
        if(assistant.getVisibility()==VISIBLE){FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)assistant.getLayoutParams();int minimum=keyboard&&user.getVisibility()==VISIBLE?user.getBottom()+dp(10):dp(82);int margin=Math.max(minimum,Math.round(sceneHeight*.32f)-assistant.getMeasuredHeight()-dp(16));if(p.topMargin!=margin){p.topMargin=margin;assistant.setLayoutParams(p);}}
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView label(String text,int size){TextView v=new TextView(activity);v.setText(text);v.setTextSize(size);return v;}
    private GradientDrawable surface(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    public void setTheme(boolean value){dark=value;int fg=Color.parseColor(dark?"#E5EDE3":"#263C31"),muted=Color.parseColor(dark?"#A5B9AA":"#59715F");
        for(TextView v:new TextView[]{assistant,user,write,speak,send,result,input})v.setTextColor(fg);state.setTextColor(muted);input.setHintTextColor(muted);
        int bubble=Color.parseColor(dark?"#E62B4235":"#EBE0EBDD");assistant.setBackground(surface(bubble,22));((TextView)findViewWithTag("tail")).setTextColor(bubble);
        user.setBackground(surface(Color.parseColor(dark?"#DC365441":"#DBD3E3CE"),20));
        for(View v:new View[]{idle,editing})v.setBackground(surface(Color.parseColor(dark?"#CC263D30":"#CCDCE7D5"),28));
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
    private void render(){assistant.setText(assistantText);assistant.setVisibility(assistantText.isEmpty()?GONE:VISIBLE);findViewWithTag("tail").setVisibility(assistantText.isEmpty()?GONE:VISIBLE);
        user.setText(userText);user.setVisibility(userText.isEmpty()?GONE:VISIBLE);result.setText(resultText);result.setVisibility(resultText.isEmpty()?GONE:VISIBLE);
        if(!busy)state.setText(pendingGroup==null?"":"尚未发送 · 点击重试");state.setVisibility(state.length()==0?GONE:VISIBLE);
    }
    public void focusInput(){lastInteraction=System.currentTimeMillis();editing.setVisibility(VISIBLE);idle.setVisibility(GONE);input.requestFocus();input.setSelection(input.length());input.post(()->((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT));}
    public void onKeyboard(boolean shown){
        boolean was=keyboard;keyboard=shown;
        if(was!=shown){
            ((android.view.ViewGroup)user.getParent()).removeView(user);
            if(shown){FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.RIGHT);p.setMargins(dp(64),dp(82),dp(24),0);addView(user,p);user.setMaxLines(2);result.setMaxLines(1);}
            else{LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.gravity=Gravity.RIGHT;p.leftMargin=dp(52);p.bottomMargin=dp(8);bottom.addView(user,0,p);user.setMaxLines(3);result.setMaxLines(2);if(!busy)restore();}
        }
        if(!shown&&input.length()==0){input.clearFocus();editing.setVisibility(GONE);idle.setVisibility(VISIBLE);}
    }
    private void submitInput(){String text=input.getText().toString().trim();if(text.isEmpty())return;input.setText("");submit(text);}
    private void submit(String text){
        lastInteraction=System.currentTimeMillis();prefs.edit().putLong("last_conversation_at",lastInteraction).apply();generation++;
        if(pendingGroup==null){pendingGroup=UUID.randomUUID().toString();if(inFlightGroup!=null)prefs.edit().putString("display:"+pendingGroup,prefs.getString("display:"+inFlightGroup,inFlightGroup)).apply();}
        // A request that has already been attempted must remain immutable on retry.
        boolean frozen=prefs.contains("attempted:"+pendingGroup);
        if(frozen){pendingGroup=UUID.randomUUID().toString();}
        store.addMessage("user",text,pendingGroup,"pending",null,System.currentTimeMillis());restore();
        state.setText(busy?"收到补充，接着处理…":"正在听你说…");state.setVisibility(VISIBLE);
        main.removeCallbacks(dispatch);main.postDelayed(dispatch,800);
    }
    private void retry(){if(pendingGroup!=null&&!busy){main.removeCallbacks(dispatch);dispatch();}}
    private void dispatch(){
        if(closed||busy||pendingGroup==null)return;
        busy=true;inFlightGroup=pendingGroup;pendingGroup=null;final String group=inFlightGroup;final long version=generation;
        prefs.edit().putBoolean("attempted:"+group,true).apply();store.setStatus(group,"pending");state.setText("正在想…");state.setVisibility(VISIBLE);
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
                if(version==generation){restore();react.run();}else{prefs.edit().putBoolean("superseded:reply:"+group,true).apply();render();}
                if(pendingGroup!=null)dispatch();else syncInbox();
            });
        }catch(Exception e){store.setStatus(group,"failed");main.post(()->{if(closed)return;busy=false;inFlightGroup=null;
            // Keep a failed older request available in history; the current turn includes the user's supplements.
            if(pendingGroup==null)pendingGroup=group;
            state.setText("未能确认发送结果 · 点击重试");state.setVisibility(VISIBLE);
        });}});
    }
    public void resume(){active=true;if(!busy)restore();main.removeCallbacks(poll);main.post(poll);main.postDelayed(this::maybeGreet,1400);}
    public void pause(){active=false;main.removeCallbacks(poll);main.removeCallbacks(beginVoice);touching=false;if(voice!=null)voice.interrupt();}
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
                sequence=Math.max(sequence,m.optLong("sequence",0));String id=m.optString("id"),body=m.optString("body");if(id.isEmpty()||body.isEmpty())continue;
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
    private void showDialog(String title,View body){Context themed=new android.view.ContextThemeWrapper(activity,dark?android.R.style.Theme_Material_Dialog_Alert:android.R.style.Theme_Material_Light_Dialog_Alert);detail=new AlertDialog.Builder(themed).setTitle(title).setView(body).setPositiveButton("收起",null).setNeutralButton("历史记录",(d,w)->showHistory()).create();detail.show();}
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
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){touching=true;lastInteraction=System.currentTimeMillis();main.postDelayed(beginVoice,300);return true;}
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE){if(voice!=null)voice.move(event.getRawX(),event.getRawY());return true;}
        if(event.getActionMasked()==MotionEvent.ACTION_UP){main.removeCallbacks(beginVoice);touching=false;if(voice!=null)voice.release(event.getRawX(),event.getRawY());else speak.performClick();return true;}
        if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){main.removeCallbacks(beginVoice);touching=false;if(voice!=null)voice.interrupt();return true;}return true;
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
