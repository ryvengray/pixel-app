package dev.pixel.sync;

import android.app.*;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Direct source subscriptions; blog feeds and X posts remain clearly distinguished. */
public final class NewsSourcesActivity extends ToolActivity {
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private JSONArray subscriptions=new JSONArray(),catalog=new JSONArray();
    private JSONObject capabilities=new JSONObject();
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
        TextView title=text("‹  新闻源与 X 账号",24);title.setPadding(0,dp(12),0,dp(14));title.setOnClickListener(v->finish());root.addView(title);
        root.addView(text("直接关注你信任的来源。博客文章和 X 动态分别订阅，每个来源每天一份简报。",15));
        status=text("正在读取新闻源…",13);status.setTextColor(muted);status.setPadding(0,dp(12),0,dp(12));status.setOnClickListener(v->load());root.addView(status);
        ScrollView scroll=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);render();load();
    }
    private String key(JSONObject row){return row.optString("kind")+":"+row.optString("locator");}
    private JSONObject find(JSONObject source){for(int i=0;i<subscriptions.length();i++){JSONObject r=subscriptions.optJSONObject(i);if(r!=null&&key(r).equals(key(source)))return r;}return null;}
    private void render(){list.removeAllViews();
        TextView info=text(!loaded?"连接服务后可查看内置来源":capabilities.optBoolean("x_ready")?("rss".equals(capabilities.optString("x_mode"))?"X 已连接 RSS 转换服务，依据转换后的标题整理；服务可用性取决于提供方。":"X 已配置官方接口；读取可能产生 X API 费用，正常每天获取一次，每次最多读取 10 条原创帖子。"):
            "X 尚未连接：可先保存关注账号；配置服务后才会获取。官方接口按用量收费，也可使用已有的 X RSS 转换服务。",13);info.setTextColor(muted);info.setPadding(0,0,0,dp(14));list.addView(info);
        Button phone=new Button(this);phone.setText("从手机 X 通知生成摘要 →");phone.setOnClickListener(v->startActivity(new android.content.Intent(this,XNotificationSettingsActivity.class)));list.addView(phone);
        Set<String> included=new HashSet<>();
        for(int i=0;i<catalog.length();i++){JSONObject source=catalog.optJSONObject(i);if(source!=null){included.add(key(source));card(source,find(source));}}
        for(int i=0;i<subscriptions.length();i++){JSONObject row=subscriptions.optJSONObject(i);if(row!=null&&!"topic".equals(row.optString("kind","topic"))&&!included.contains(key(row)))card(row,row);}
        Button rss=new Button(this);rss.setText("＋ 添加 RSS / Atom 地址");rss.setEnabled(loaded&&!busy);rss.setOnClickListener(v->add("rss"));list.addView(rss);
        Button x=new Button(this);x.setText("＋ 添加 X 账号");x.setEnabled(loaded&&!busy);x.setOnClickListener(v->add("x"));list.addView(x);
        TextView note=text("RSS 简报依据标题整理，X 简报依据获取到的帖子文本整理，均保留出处。首次只获取近 48 小时内容；X 官方接口不包含回复、转发，也不保证覆盖账号全部动态。RSS 地址需支持公网 HTTPS 访问。",13);note.setTextColor(muted);note.setPadding(0,dp(14),0,dp(20));list.addView(note);
    }
    private void card(JSONObject source,JSONObject row){
        String kind=source.optString("kind"),locator=source.optString("locator"),name=row==null?source.optString("topic"):row.optString("topic");boolean x="x".equals(kind);
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(16),dp(12),dp(16),dp(14));GradientDrawable bg=new GradientDrawable();bg.setColor(surface);bg.setCornerRadius(dp(20));card.setBackground(bg);
        Switch toggle=new Switch(this);toggle.setText(name);toggle.setTextSize(18);toggle.setTextColor(foreground);toggle.setChecked(row!=null);toggle.setEnabled(loaded&&!busy);toggle.setContentDescription(name+"订阅");card.addView(toggle);
        TextView desc=text(x?"X 动态 · @"+locator:"RSS / Atom · "+locator,13);desc.setTextColor(muted);card.addView(desc);
        int hour=row==null?9:row.optInt("hour",9);String zone=row==null?TimeZone.getDefault().getID():row.optString("timezone");
        String state=row==null?"尚未订阅":String.format(Locale.CHINA,"每天 %02d:00 · %s",hour,zone);
        if(row!=null&&x&&!capabilities.optBoolean("x_ready"))state+="\n已保存账号 · 等待连接 X";
        if(row!=null&&!row.optString("last_error").isEmpty())state+="\n"+row.optString("last_error");
        TextView delivery=text(state,13);delivery.setTextColor(muted);delivery.setPadding(0,dp(8),0,0);card.addView(delivery);
        toggle.setOnClickListener(v->{boolean checked=toggle.isChecked();toggle.setChecked(row!=null);if(checked)choose(name,kind,locator,hour,zone);else mutate(()->AssistantApi.cancelNews(this,row.optString("id")));});
        if(row!=null){Button time=new Button(this);time.setText("调整推送时间");time.setEnabled(loaded&&!busy);time.setOnClickListener(v->choose(name,kind,locator,hour,zone));card.addView(time);}
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(14);list.addView(card,params);
    }
    private void choose(String name,String kind,String locator,int hour,String zone){
        String[] hours=new String[15];for(int i=0;i<hours.length;i++)hours[i]=String.format(Locale.CHINA,"每天 %02d:00",i+8);
        final int[] chosen={Math.max(0,Math.min(14,hour-8))};new AlertDialog.Builder(this).setTitle(name+" · "+zone).setSingleChoiceItems(hours,chosen[0],(d,w)->chosen[0]=w).setNegativeButton("取消",null).setPositiveButton("保存订阅",(d,w)->mutate(()->AssistantApi.subscribeNewsSource(this,name,kind,locator,chosen[0]+8,zone))).show();
    }
    private void add(String kind){
        boolean x="x".equals(kind);LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(dp(20),0,dp(20),0);
        EditText name=new EditText(this);name.setSingleLine(true);name.setHint("来源名称（选填）");name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});fields.addView(name);
        EditText locator=new EditText(this);locator.setSingleLine(true);locator.setHint(x?"用户名，例如 @karpathy":"https://example.com/feed.xml");locator.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);fields.addView(locator);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(x?"添加 X 账号":"添加 RSS / Atom 来源").setView(fields).setNegativeButton("取消",null).setPositiveButton("选择时间",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String value=locator.getText().toString().trim();if(x)value=value.replaceFirst("^@","").toLowerCase(Locale.ROOT);
            if(x?!value.matches("[a-z0-9_]{1,15}"):(!value.startsWith("https://")||value.length()>2048)){locator.setError(x?"请输入用户名，不要填写主页链接":"请输入 HTTPS 的 RSS 或 Atom 地址");return;}
            String label=name.getText().toString().trim();if(label.isEmpty())label=x?"@"+value:android.net.Uri.parse(value).getHost();if(label==null||label.isEmpty()){locator.setError("地址无效");return;}
            dialog.dismiss();choose(label,kind,value,9,TimeZone.getDefault().getID());
        }));dialog.show();
    }
    private interface Action {void run()throws Exception;}
    private void load(){mutate(null);}
    private void mutate(Action action){if(busy||closed)return;busy=true;status.setText(action==null?"正在读取新闻源…":"正在保存…");render();network.execute(()->{
        boolean saved=false;try{if(action!=null){action.run();saved=true;}JSONObject config=AssistantApi.newsSources(this);JSONArray rows=AssistantApi.newsSubscriptions(this);runOnUiThread(()->{if(closed)return;capabilities=config;catalog=config.optJSONArray("catalog");if(catalog==null)catalog=new JSONArray();subscriptions=rows;loaded=true;busy=false;render();status.setText(action==null?"已同步 · 点击刷新":"订阅已保存 · 点击刷新");});}
        catch(Exception e){final boolean done=saved;runOnUiThread(()->{if(closed)return;busy=false;loaded=false;render();status.setText((done?"已保存，刷新失败":"暂时无法同步新闻源")+" · 点击重试\n"+e.getMessage());});}
    });}
    @Override protected void onResume(){super.onResume();if(list!=null)load();}
    @Override public void onDestroy(){closed=true;network.shutdown();super.onDestroy();}
}
