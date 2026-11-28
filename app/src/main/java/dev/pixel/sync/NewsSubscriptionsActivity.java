package dev.pixel.sync;

import android.app.*;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit, server-backed morning briefing, AI/engineering radar and side-project inspiration cards. */
public final class NewsSubscriptionsActivity extends ToolActivity {
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final String[] presets={"个人晨报","AI 与技术动态","副业痛点周报"};
    private final int[] defaultHours={8,9,20};
    private final String[] descriptions={
            "今日日程提醒 + 2～3 天一次的精选技术动态；若当天无待办与精选，则推送一则历史典故故事",
            "聚焦大模型、开源工具、Agent 工程与技术热帖 · 重磅突破 08:00–22:00 实时推送，常规好内容每 2～3 天汇总",
            "挖掘海外垂直社区窄人群真痛点 · 每 2～3 天精选一张「目标人群 + 痛点原话 + 商业化思维」灵感卡，宁缺毋滥"
    };
    private JSONArray subscriptions=new JSONArray();
    private LinearLayout list;
    private TextView status;
    private boolean closed,busy,loaded;
    private int foreground,muted,surface;
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(foreground);return t;}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);
        int theme=getSharedPreferences("home",0).getInt("theme",0);
        boolean dark=theme==2||(theme==0&&(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);
        foreground=Color.parseColor(dark?"#E5EDE3":"#263C31");muted=Color.parseColor(dark?"#A5B9AA":"#59715F");surface=Color.parseColor(dark?"#243C2E":"#DEE9D8");
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(12),dp(20),dp(16));root.setBackgroundColor(Color.parseColor(dark?"#102019":"#F0F5E9"));
        TextView title=text("‹  晨报与灵感订阅",24);title.setPadding(0,dp(12),0,dp(14));title.setOnClickListener(v->finish());root.addView(title);
        TextView intro=text("告别低质水文播报：每天一份个人晨报，外部技术与副业灵感只推高含金量精选。",15);root.addView(intro);
        status=text("正在读取订阅…",13);status.setTextColor(muted);status.setPadding(0,dp(12),0,dp(12));status.setOnClickListener(v->load());root.addView(status);
        ScrollView scroll=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);render();load();
    }
    private boolean matchesPreset(String preset,String topic){
        if(preset.equals(topic))return true;
        if("AI 与技术动态".equals(preset))return "AI 新进展".equalsIgnoreCase(topic)||"AI新闻".equalsIgnoreCase(topic)||"AI 新闻".equalsIgnoreCase(topic);
        if("副业痛点周报".equals(preset))return "副业痛点灵感".equals(topic)||"副业痛点".equals(topic)||"痛点周报".equals(topic);
        return "个人晨报".equals(preset)&&"每日晨报".equals(topic);
    }
    private boolean isKnownPreset(String topic){
        for(String preset:presets)if(matchesPreset(preset,topic))return true;
        return "社会热点".equals(topic)||"国际大事".equals(topic);
    }
    private JSONObject find(String preset){
        for(int i=0;i<subscriptions.length();i++){
            JSONObject r=subscriptions.optJSONObject(i);
            if(r!=null&&"topic".equals(r.optString("kind","topic"))&&matchesPreset(preset,r.optString("topic")))return r;
        }
        return null;
    }
    private void render(){list.removeAllViews();
        for(int i=0;i<presets.length;i++)card(presets[i],descriptions[i],find(presets[i]),defaultHours[i]);
        for(int i=0;i<subscriptions.length();i++){JSONObject r=subscriptions.optJSONObject(i);if(r!=null&&"topic".equals(r.optString("kind","topic"))&&!isKnownPreset(r.optString("topic")))card(r.optString("topic"),"自定义关注方向",r,9);}
        Button add=new Button(this);add.setText("＋ 添加自定义主题");add.setEnabled(loaded&&!busy);add.setOnClickListener(v->addTopic());list.addView(add);
        Button sources=new Button(this);sources.setText("新闻源与 X 账号 →");sources.setOnClickListener(v->startActivity(new Intent(this,NewsSourcesActivity.class)));list.addView(sources);
        TextView note=text("推送时间可选 08:00–22:00。个人晨报仅同步普通近期日程与待办，不同步大姨妈等隐私记录；副业痛点周报每 2～3 天综合提炼一张最佳机会卡，无高分痛点时不凑数。",13);note.setTextColor(muted);note.setPadding(0,dp(14),0,dp(20));list.addView(note);
        Button settings=new Button(this);settings.setText("连接服务与通知权限");settings.setOnClickListener(v->startActivity(new Intent(this,AssistantSettingsActivity.class)));list.addView(settings);
    }
    private void card(String topic,String description,JSONObject row,int defaultHour){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(16),dp(12),dp(16),dp(14));GradientDrawable bg=new GradientDrawable();bg.setColor(surface);bg.setCornerRadius(dp(20));card.setBackground(bg);
        Switch toggle=new Switch(this);toggle.setText(topic);toggle.setTextSize(18);toggle.setTextColor(foreground);toggle.setChecked(row!=null);toggle.setEnabled(loaded&&!busy);toggle.setContentDescription(topic+"订阅");card.addView(toggle);
        TextView desc=text(description,14);desc.setTextColor(muted);card.addView(desc);
        int hour=row==null?defaultHour:row.optInt("hour",defaultHour);String zone=row==null?TimeZone.getDefault().getID():row.optString("timezone");
        String scheduleLabel="AI 与技术动态".equals(topic)
                ?String.format(Locale.CHINA,"重磅实时推 · 汇总时间 %02d:00 · %s",hour,zone)
                :"副业痛点周报".equals(topic)
                ?String.format(Locale.CHINA,"每 2～3 天 %02d:00 推一张灵感卡 · %s",hour,zone)
                :String.format(Locale.CHINA,"每天 %02d:00 · %s",hour,zone);
        TextView delivery=text(row==null?"尚未开启 · 开启后选择时间":scheduleLabel,13);delivery.setPadding(0,dp(8),0,0);card.addView(delivery);
        if(row!=null&&!row.optString("last_error").isEmpty()&&!row.isNull("last_error")){
            TextView check=text(row.optString("last_error"),13);check.setTextColor(muted);card.addView(check);
        }
        toggle.setOnCheckedChangeListener((button,checked)->{toggle.setChecked(row!=null);if(checked)choose(topic,hour,zone);else mutate(()->AssistantApi.cancelNews(this,row.optString("id")));});
        if(row!=null){Button time=new Button(this);time.setText("调整推送时间");time.setEnabled(!busy);time.setOnClickListener(v->choose(topic,hour,zone));card.addView(time);}
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(14);list.addView(card,params);
    }
    private void choose(String topic,int hour,String zone){
        String[] hours=new String[15];for(int i=0;i<hours.length;i++)hours[i]=String.format(Locale.CHINA,"%02d:00",i+8);
        final int[] chosen={Math.max(0,Math.min(14,hour-8))};new AlertDialog.Builder(this).setTitle(topic+" · "+zone).setSingleChoiceItems(hours,chosen[0],(d,w)->chosen[0]=w).setNegativeButton("取消",null).setPositiveButton("保存设置",(d,w)->mutate(()->AssistantApi.subscribeNews(this,topic,chosen[0]+8,zone))).show();
    }
    private void addTopic(){EditText edit=new EditText(this);edit.setSingleLine(true);edit.setHint("例如：机器人、端侧推理、ESP32");edit.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("添加关注主题").setView(edit).setNegativeButton("取消",null).setPositiveButton("选择时间",null).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String value=edit.getText().toString().trim();if(value.isEmpty()){edit.setError("请输入关注主题");return;}dialog.dismiss();JSONObject existing=find(value);choose(value,existing==null?9:existing.optInt("hour",9),existing==null?TimeZone.getDefault().getID():existing.optString("timezone"));}));dialog.show();
    }
    private interface Action {void run()throws Exception;}
    private void load(){mutate(null);}
    private void mutate(Action action){if(busy||closed)return;busy=true;status.setText(action==null?"正在读取订阅…":"正在保存…");render();network.execute(()->{
        boolean saved=false;try{if(action!=null){action.run();saved=true;}try{AssistantApi.syncCalendarBriefing(this);}catch(Exception ignored){}JSONArray rows=AssistantApi.newsSubscriptions(this);runOnUiThread(()->{if(closed)return;subscriptions=rows;loaded=true;busy=false;render();status.setText(action==null?"已同步 · 点击刷新":"设置已更新 · 点击刷新");});}
        catch(Exception e){final boolean done=saved;runOnUiThread(()->{if(closed)return;busy=false;loaded=false;render();status.setText((done?"已保存，刷新失败":"暂时无法同步订阅")+" · 点击重试\n"+e.getMessage());});}
    });}
    @Override protected void onResume(){super.onResume();if(list!=null)load();}
    @Override public void onDestroy(){closed=true;network.shutdown();super.onDestroy();}
}
