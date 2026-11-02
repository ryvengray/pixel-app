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

/** Explicit, server-backed subscriptions. Switches reflect confirmed server state. */
public final class NewsSubscriptionsActivity extends ToolActivity {
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final String[] presets={"AI 新进展","国际大事","社会热点"};
    private final String[] descriptions={"模型发布、研究突破、AI 产品与应用进展","影响广泛的国际事件与全球局势变化","值得关注的公共事件、民生与社会议题"};
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
        TextView title=text("‹  新闻订阅",24);title.setPadding(0,dp(12),0,dp(14));title.setOnClickListener(v->finish());root.addView(title);
        TextView intro=text("选择你关心的方向，让 Greta 每天整理一份简报。没有新内容时不打扰。",15);root.addView(intro);
        status=text("正在读取订阅…",13);status.setTextColor(muted);status.setPadding(0,dp(12),0,dp(12));status.setOnClickListener(v->load());root.addView(status);
        ScrollView scroll=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);render();load();
    }
    private JSONObject find(String topic){for(int i=0;i<subscriptions.length();i++){JSONObject r=subscriptions.optJSONObject(i);if(r!=null&&topic.equals(r.optString("topic")))return r;}return null;}
    private void render(){list.removeAllViews();
        for(int i=0;i<presets.length;i++)card(presets[i],descriptions[i],find(presets[i]));
        for(int i=0;i<subscriptions.length();i++){JSONObject r=subscriptions.optJSONObject(i);if(r!=null&&!Arrays.asList(presets).contains(r.optString("topic")))card(r.optString("topic"),"自定义关注方向",r);}
        Button add=new Button(this);add.setText("＋ 添加自定义主题");add.setEnabled(loaded&&!busy);add.setOnClickListener(v->addTopic());list.addView(add);
        TextView note=text("每天可选 08:00–22:00，通常在所选时间后几分钟送达。简报基于新闻标题整理，附来源链接；暂不支持突发新闻实时提醒。系统通知需已配对服务并授予通知权限。",13);note.setTextColor(muted);note.setPadding(0,dp(14),0,dp(20));list.addView(note);
        Button settings=new Button(this);settings.setText("连接服务与通知权限");settings.setOnClickListener(v->startActivity(new Intent(this,AssistantSettingsActivity.class)));list.addView(settings);
    }
    private void card(String topic,String description,JSONObject row){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(16),dp(12),dp(16),dp(14));GradientDrawable bg=new GradientDrawable();bg.setColor(surface);bg.setCornerRadius(dp(20));card.setBackground(bg);
        Switch toggle=new Switch(this);toggle.setText(topic);toggle.setTextSize(18);toggle.setTextColor(foreground);toggle.setChecked(row!=null);toggle.setEnabled(loaded&&!busy);toggle.setContentDescription(topic+"订阅");card.addView(toggle);
        TextView desc=text(description,14);desc.setTextColor(muted);card.addView(desc);
        int hour=row==null?9:row.optInt("hour",9);String zone=row==null?TimeZone.getDefault().getID():row.optString("timezone");
        TextView delivery=text(row==null?"尚未订阅 · 开启后选择时间":String.format(Locale.CHINA,"每天 %02d:00 · %s",hour,zone),13);delivery.setPadding(0,dp(8),0,0);card.addView(delivery);
        toggle.setOnCheckedChangeListener((button,checked)->{toggle.setChecked(row!=null);if(checked)choose(topic,hour,zone);else mutate(()->AssistantApi.cancelNews(this,row.optString("id")));});
        if(row!=null){Button time=new Button(this);time.setText("调整推送时间");time.setEnabled(!busy);time.setOnClickListener(v->choose(topic,hour,zone));card.addView(time);}
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(14);list.addView(card,params);
    }
    private void choose(String topic,int hour,String zone){
        String[] hours=new String[15];for(int i=0;i<hours.length;i++)hours[i]=String.format(Locale.CHINA,"每天 %02d:00",i+8);
        final int[] chosen={hour-8};new AlertDialog.Builder(this).setTitle(topic+" · "+zone).setSingleChoiceItems(hours,chosen[0],(d,w)->chosen[0]=w).setNegativeButton("取消",null).setPositiveButton("保存订阅",(d,w)->mutate(()->AssistantApi.subscribeNews(this,topic,chosen[0]+8,zone))).show();
    }
    private void addTopic(){EditText edit=new EditText(this);edit.setSingleLine(true);edit.setHint("例如：机器人、航天进展");edit.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("添加关注主题").setView(edit).setNegativeButton("取消",null).setPositiveButton("选择时间",null).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String value=edit.getText().toString().trim();if(value.isEmpty()){edit.setError("请输入关注主题");return;}dialog.dismiss();JSONObject existing=find(value);choose(value,existing==null?9:existing.optInt("hour",9),existing==null?TimeZone.getDefault().getID():existing.optString("timezone"));}));dialog.show();
    }
    private interface Action {void run()throws Exception;}
    private void load(){mutate(null);}
    private void mutate(Action action){if(busy||closed)return;busy=true;status.setText(action==null?"正在读取订阅…":"正在保存…");render();network.execute(()->{
        boolean saved=false;try{if(action!=null){action.run();saved=true;}JSONArray rows=AssistantApi.newsSubscriptions(this);runOnUiThread(()->{if(closed)return;subscriptions=rows;loaded=true;busy=false;render();status.setText(action==null?"已同步 · 点击刷新":"订阅已更新 · 点击刷新");});}
        catch(Exception e){final boolean done=saved;runOnUiThread(()->{if(closed)return;busy=false;loaded=false;render();status.setText((done?"已保存，刷新失败":"暂时无法同步订阅")+" · 点击重试\n"+e.getMessage());});}
    });}
    @Override protected void onResume(){super.onResume();if(list!=null)load();}
    @Override public void onDestroy(){closed=true;network.shutdown();super.onDestroy();}
}
