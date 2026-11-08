package dev.pixel.sync;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;

/** Explains Android's broad access separately from Greta's narrow collection rule. */
public final class XNotificationSettingsActivity extends ToolActivity {
    private TextView state,delivery;
    private Switch enabled;
    private EditText authors;
    private int hour,foreground,muted;
    private boolean building;
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(foreground);t.setPadding(0,dp(8),0,dp(8));return t;}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);
        int theme=getSharedPreferences("home",0).getInt("theme",0);boolean dark=theme==2||(theme==0&&(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);
        foreground=Color.parseColor(dark?"#E5EDE3":"#263C31");muted=Color.parseColor(dark?"#A5B9AA":"#59715F");
        ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(12),dp(20),dp(24));root.setBackgroundColor(Color.parseColor(dark?"#102019":"#F0F5E9"));scroll.addView(root);setContentView(scroll);
        TextView back=text("‹  手机 X 通知摘要",24);back.setOnClickListener(v->finish());root.addView(back);
        root.addView(text("收集手机实际收到的指定作者发帖通知，每天合成一份摘要。无需 X API；仅产生模型总结用量。每天最多整理50条待处理通知。此开关不会关闭已有的官方 API 账号订阅。",15));
        enabled=new Switch(this);enabled.setText("收集 X 发帖通知");enabled.setTextColor(foreground);enabled.setChecked(XNotificationStore.enabled(this));root.addView(enabled);
        state=text("",13);state.setTextColor(muted);root.addView(state);
        Button permission=new Button(this);permission.setText("前往系统授予通知读取权限");permission.setOnClickListener(v->explainPermission());root.addView(permission);
        root.addView(text("关注作者（每行：用户名|通知显示名称）",15));authors=new EditText(this);authors.setTextColor(foreground);authors.setMinLines(3);authors.setText(XNotificationStore.prefs(this).getString("authors",XNotificationStore.DEFAULT_AUTHORS));root.addView(authors);
        root.addView(text("已预填马斯克、Karpathy。填写 X 通知显示的作者名称。支持常见的发帖标题与专用发帖频道，跳过无法识别作者、疑似私信、带回复输入框和汇总通知。不会读取旧通知、图片或打开 X 补取全文。",13));
        hour=XNotificationStore.prefs(this).getInt("hour",9);delivery=text("",15);root.addView(delivery);updateHour();Button time=new Button(this);time.setText("选择每日摘要时间");time.setOnClickListener(v->{String[] values=new String[15];for(int i=0;i<15;i++)values[i]=String.format(java.util.Locale.CHINA,"每天 %02d:00",i+8);new AlertDialog.Builder(this).setTitle("摘要时间").setSingleChoiceItems(values,hour-8,(d,w)->{hour=w+8;updateHour();d.dismiss();}).setNegativeButton("取消",null).show();});root.addView(time);
        Button save=new Button(this);save.setText("保存作者与时间");save.setOnClickListener(v->save(enabled.isChecked(),false));root.addView(save);
        Button sync=new Button(this);sync.setText("重试同步到助理服务");sync.setOnClickListener(v->{XNotificationSync.schedule(this,true);refresh();Toast.makeText(this,"已安排后台同步",Toast.LENGTH_SHORT).show();});root.addView(sync);
        Button preview=new Button(this);preview.setText("查看最近收集的通知");preview.setOnClickListener(v->{try(XNotificationStore store=new XNotificationStore(this)){TextView body=text(store.preview(),14);body.setTextIsSelectable(true);ScrollView view=new ScrollView(this);view.setPadding(dp(20),0,dp(20),0);view.addView(body);new AlertDialog.Builder(this).setTitle("最近30条 · 本机保留7天").setView(view).setPositiveButton("收起",null).show();}});root.addView(preview);
        Button clear=new Button(this);clear.setText("清除待处理通知");clear.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("清除待处理通知？").setMessage("清除本机原始通知，并在联网后清除服务端原始通知。已生成的摘要仍保留。").setNegativeButton("取消",null).setPositiveButton("清除",(d,w)->save(enabled.isChecked(),true)).show());root.addView(clear);
        root.addView(text("Android 的通知读取权限涵盖其他应用；Greta 仅处理 X 应用（com.twitter.android）中符合上述筛选规则的新通知。被接受的作者、通知文字和时间会发送到已配对的私人服务，再交给 DeepSeek 总结。原始通知最多保留7天；关闭后立即停止本机收集，联网后停止服务端摘要并清除原始通知。通知格式变化可能导致遗漏。",13));
        enabled.setOnClickListener(v->{if(building)return;boolean wanted=enabled.isChecked();enabled.setChecked(XNotificationStore.enabled(this));if(wanted)new AlertDialog.Builder(this).setTitle("开启 X 通知摘要？").setMessage("将指定作者的新 X 通知文字同步到私人服务，并交给 DeepSeek 生成每日摘要。Android 需要你另行授予通知读取权限。").setNegativeButton("取消",null).setPositiveButton("开启",(d,w)->save(true,false)).show();else save(false,false);});
        refresh();
    }
    private void updateHour(){delivery.setText(String.format(java.util.Locale.CHINA,"每天 %02d:00 · %s（保存后生效）",hour,java.util.TimeZone.getDefault().getID()));}
    private void save(boolean collect,boolean purge){try{String raw=authors.getText().toString().trim();if(!collect)raw=XNotificationStore.prefs(this).getString("authors",XNotificationStore.DEFAULT_AUTHORS);XNotificationStore.configure(this,collect,hour,raw,purge);refresh();Toast.makeText(this,collect?"已保存，等待同步到助理服务":"已停止本机收集，等待同步关闭服务端摘要",Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}}
    private void explainPermission(){new AlertDialog.Builder(this).setTitle("通知读取权限").setMessage("系统会允许 Greta 访问通知。Greta 只保留你开启后、来自 X 且符合作者和公开发帖筛选规则的通知。你可以随时在系统设置中撤销权限。").setNegativeButton("取消",null).setPositiveButton("前往授权",(d,w)->{try{startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,new ComponentName(this,XNotificationListener.class).flattenToString()));}catch(Exception e){startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));}}).show();}
    private String localTime(long timestamp){
        return timestamp==0?"暂无":android.text.format.DateFormat.format("MM-dd HH:mm",timestamp).toString();
    }
    private void refresh(){
        if(state==null)return;
        XNotificationStore.refreshPermission(this);
        building=true;enabled.setChecked(XNotificationStore.enabled(this));building=false;
        SharedPreferences p=XNotificationStore.prefs(this);
        boolean allowed=XNotificationStore.access(this);
        String value=!enabled.isChecked()?"收集已关闭":!allowed?"已开启 · 等待系统通知读取授权":
                XNotificationListener.connected()?"已开启 · 系统监听已连接":"已开启 · 正在连接系统监听";
        if(enabled.isChecked()&&allowed){
            value+="\n最近收到 X 通知："+localTime(p.getLong("last_seen",0));
            String capture=p.getString("capture_state","");
            if(!capture.isEmpty())value+=" · "+capture;
            value+="\n最近匹配发帖："+localTime(p.getLong("last_match",0));
        }
        String error=p.getString("error","");
        state.setText(value+"\n最近服务端同步："+localTime(p.getLong("last_sync",0))
                +(error.isEmpty()?"":"\n"+error));
        XNotificationListener.reconnect(this);
        XNotificationSync.schedule(this,true);
    }
    @Override protected void onResume(){super.onResume();refresh();}
}
