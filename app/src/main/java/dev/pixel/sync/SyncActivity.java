package dev.pixel.sync;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.content.res.ColorStateList;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.res.Configuration;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

public class SyncActivity extends ToolActivity {
    private static final String PERMISSION = "com.termux.permission.RUN_COMMAND";
    private SharedPreferences prefs;
    private TextView status, log;
    private Button sync;
    private ProgressBar loading,countdownProgress;
    private LinearLayout countdownCard;
    private TextView countdownText;
    private boolean autoHandled,autoCancelled,starting,permissionPending,resumed,startAfterPermission;
    private long countdownDeadline;
    private static final String EXTRA_SKIP_AUTO = "dev.pixel.sync.extra.SKIP_AUTO_SYNC";
    private boolean dark;
    private int fg,muted,bg,card,accent;
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView label(String text,int size,int color){TextView view=new TextView(this);view.setText(text);view.setTextSize(size);view.setTextColor(color);return view;}
    private LinearLayout card(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);view.setPadding(dp(18),dp(17),dp(18),dp(17));view.setBackground(shape(card,20));return view;}
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences.OnSharedPreferenceChangeListener changes=(prefs,key)->render();
    private final Runnable countdown = new Runnable(){public void run(){
        if(countdownDeadline==0||!resumed||!hasWindowFocus())return;
        if(prefs.getBoolean("running",false)){cancelAutoStart(false);render();return;}
        if(SystemClock.elapsedRealtime()>=countdownDeadline){cancelAutoStart(false);startSync();return;}
        render();handler.postDelayed(this,100);
    }};
    private final Runnable refresh = new Runnable() {
        public void run() { render(); handler.postDelayed(this, 1000); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("sync", 0);
        autoHandled=state!=null||getIntent().getBooleanExtra(EXTRA_SKIP_AUTO,false);
        permissionPending=state!=null&&state.getBoolean("sync_permission_pending",false);
        int theme=getSharedPreferences("home",0).getInt("theme",0);
        dark=theme==2||(theme==0&&(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);
        fg=Color.parseColor(dark?"#E5EDE3":"#203B2A");muted=Color.parseColor(dark?"#9DB1A2":"#6C8271");bg=Color.parseColor(dark?"#102019":"#F2F6EE");card=Color.parseColor(dark?"#1C3326":"#FFFFFF");accent=Color.parseColor(dark?"#8BCB9A":"#327C4E");
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(bg);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;
        });
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(16),dp(12),dp(20),dp(12));TextView back=label("←",25,fg);back.setGravity(Gravity.CENTER);back.setOnClickListener(v->finish());header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));TextView title=label("Obsidian 同步",22,fg);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));header.addView(title);root.addView(header);
        ScrollView page=new ScrollView(this);page.setFillViewport(true);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(22),dp(10),dp(22),dp(30));page.addView(body);
        LinearLayout hero=card();hero.setBackground(shape(Color.parseColor(dark?"#244631":"#DFEFDE"),22));TextView icon=label("✦  笔记保持同步",21,fg);icon.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));hero.addView(icon);TextView intro=label("检查更改 → 提交 → 拉取 → 推送",13,muted);intro.setPadding(0,dp(10),0,dp(7));hero.addView(intro);TextView path=label("~/storage/shared/Documents/obsidian",13,fg);path.setTextIsSelectable(true);hero.addView(path);body.addView(hero);
        LinearLayout stateCard=card();LinearLayout.LayoutParams scp=new LinearLayout.LayoutParams(-1,-2);scp.topMargin=dp(14);body.addView(stateCard,scp);TextView stateTitle=label("当前状态",12,muted);stateCard.addView(stateTitle);status=label("准备就绪",19,fg);status.setTag("sync_status");status.setPadding(0,dp(8),0,dp(16));stateCard.addView(status);
        countdownCard=new LinearLayout(this);countdownCard.setTag("sync_countdown");countdownCard.setOrientation(LinearLayout.VERTICAL);countdownCard.setPadding(dp(12),0,dp(12),dp(12));
        countdownCard.setBackground(shape(Color.parseColor(dark?"#244631":"#EAF4E8"),14));
        LinearLayout countdownRow=new LinearLayout(this);countdownRow.setGravity(Gravity.CENTER_VERTICAL);
        countdownText=label("3 秒后自动同步",14,fg);countdownText.setTag("sync_countdown_text");countdownText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        countdownRow.addView(countdownText,new LinearLayout.LayoutParams(0,dp(48),1));countdownText.setGravity(Gravity.CENTER_VERTICAL);
        TextView cancel=label("取消",14,accent);cancel.setTag("sync_cancel_auto");cancel.setGravity(Gravity.CENTER);cancel.setContentDescription("取消自动同步");cancel.setOnClickListener(v->{cancelAutoStart(true);render();});countdownRow.addView(cancel,new LinearLayout.LayoutParams(dp(56),dp(48)));
        countdownCard.addView(countdownRow);
        countdownProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);countdownProgress.setMax(3000);countdownProgress.setProgressTintList(ColorStateList.valueOf(accent));countdownCard.addView(countdownProgress,new LinearLayout.LayoutParams(-1,dp(4)));
        LinearLayout.LayoutParams countdownParams=new LinearLayout.LayoutParams(-1,-2);countdownParams.bottomMargin=dp(12);stateCard.addView(countdownCard,countdownParams);countdownCard.setVisibility(View.GONE);
        FrameLayout action=new FrameLayout(this);
        sync = new Button(this);sync.setTag("sync_start");sync.setText("立即同步");sync.setAllCaps(false);sync.setTextColor(dark?bg:Color.WHITE);sync.setBackground(shape(accent,16));sync.setMinHeight(dp(52));sync.setOnClickListener(v -> startSync());action.addView(sync,new FrameLayout.LayoutParams(-1,-1));
        loading=new ProgressBar(this,null,android.R.attr.progressBarStyleSmall);loading.setTag("sync_loading");loading.setIndeterminateTintList(ColorStateList.valueOf(dark?bg:Color.WHITE));loading.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);loading.setVisibility(View.GONE);
        FrameLayout.LayoutParams spinner=new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.START|Gravity.CENTER_VERTICAL);spinner.leftMargin=dp(20);action.addView(loading,spinner);stateCard.addView(action,new LinearLayout.LayoutParams(-1,dp(52)));
        TextView logTitle=label("执行记录",16,fg);logTitle.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));logTitle.setPadding(dp(2),dp(24),0,dp(10));body.addView(logTitle);
        LinearLayout logCard=card();log=label("执行日志会在任务结束后显示。",13,muted);log.setTextIsSelectable(true);log.setLineSpacing(dp(4),1f);logCard.addView(log);body.addView(logCard);
        TextView toolTitle=label("需要帮助？",16,fg);toolTitle.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));toolTitle.setPadding(dp(2),dp(24),0,dp(10));body.addView(toolTitle);
        LinearLayout tools=card();TextView settings=label("应用权限设置  ›",15,fg);settings.setPadding(0,dp(5),0,dp(13));
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));tools.addView(settings);
        TextView termux=label("打开 Termux  ›",15,fg);termux.setPadding(0,dp(10),0,dp(8));termux.setOnClickListener(v -> {
            Intent launch = getPackageManager().getLaunchIntentForPackage("com.termux");
            if (launch != null) startActivity(launch); else Toast.makeText(this, "请先安装 Termux", Toast.LENGTH_LONG).show();
        });tools.addView(termux);body.addView(tools);
        TextView help=label("首次使用请配置 Termux、存储权限和 Git 认证。同步期间请暂停编辑笔记。若长时间没有结果，可在 Termux 查看任务状态。",12,muted);help.setLineSpacing(dp(3),1f);help.setPadding(dp(4),dp(14),dp(4),0);body.addView(help);
        root.addView(page,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);render();
    }
    @Override public void onResume() {
        super.onResume();resumed=true;prefs.registerOnSharedPreferenceChangeListener(changes);
        handler.removeCallbacks(refresh);handler.post(refresh);maybeStart();
    }
    @Override public void onWindowFocusChanged(boolean focused){
        super.onWindowFocusChanged(focused);
        if(focused)maybeStart();else if(countdownDeadline!=0){cancelAutoStart(true);render();}
    }
    @Override public void onPause() {
        resumed=false;cancelAutoStart(true);prefs.unregisterOnSharedPreferenceChangeListener(changes);
        handler.removeCallbacks(refresh);super.onPause();
    }
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle state){
        state.putBoolean("sync_permission_pending",permissionPending);super.onSaveInstanceState(state);
    }
    @Override protected void prepareFullPage(Intent intent){
        cancelAutoStart(true);intent.putExtra(EXTRA_SKIP_AUTO,true);
    }
    private void maybeStart(){
        if(!resumed||!hasWindowFocus()||isFinishing()||isDestroyed())return;
        if(startAfterPermission){startAfterPermission=false;startSync();return;}
        if(autoHandled||starting||permissionPending)return;
        autoHandled=true;
        if(!prefs.getBoolean("running",false)){countdownDeadline=SystemClock.elapsedRealtime()+3000;handler.post(countdown);}
    }
    private void cancelAutoStart(boolean cancelled){
        if(countdownDeadline!=0&&cancelled)autoCancelled=true;
        autoHandled=true;countdownDeadline=0;handler.removeCallbacks(countdown);
    }
    private void render() {
        boolean running=prefs.getBoolean("running",false);
        boolean stale=running&&System.currentTimeMillis()-prefs.getLong("started",0)>600000;
        boolean working=running||starting||permissionPending||startAfterPermission;
        if(running&&countdownDeadline!=0)cancelAutoStart(false);
        long remaining=Math.max(0,countdownDeadline-SystemClock.elapsedRealtime());
        countdownCard.setVisibility(countdownDeadline!=0?View.VISIBLE:View.GONE);
        String seconds=((remaining+999)/1000)+" 秒后自动同步";
        if(!seconds.contentEquals(countdownText.getText()))countdownText.setText(seconds);
        countdownProgress.setProgress((int)remaining);
        status.setText(stale?"同步仍未返回，请到 Termux 确认状态":running?prefs.getString("status","同步执行中…"):
            permissionPending||startAfterPermission?"等待运行命令授权…":starting?"正在准备同步…":countdownDeadline!=0?"准备同步":
            autoCancelled?"已取消自动同步，可手动开始":prefs.getString("status","准备就绪"));
        sync.setEnabled(!working);sync.setAlpha(working?.65f:1f);
        sync.setText(running?"同步中…":permissionPending||startAfterPermission?"等待授权…":starting?"正在准备…":"立即同步");
        loading.setVisibility(working?View.VISIBLE:View.GONE);
        log.setText(prefs.getString("log","执行日志会在任务结束后显示。"));
    }
    private void startSync() {
        if(prefs.getBoolean("running",false)||starting||permissionPending){cancelAutoStart(false);render();return;}
        cancelAutoStart(false);autoCancelled=false;starting=true;render();
        try {
            getPackageManager().getPackageInfo("com.termux", 0);
            if (checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                permissionPending=true;starting=false;render();requestPermissions(new String[]{PERMISSION}, 1);return;
            }
            // File export and Termux dispatch share the same lock as background jobs.
            new Thread(()->{
                try{SyncRunner.start(getApplicationContext());}
                catch(Exception error){prefs.edit().putString("status","启动失败").putString("log","请检查目录授权、Termux 配置。\n"+error).apply();}
                finally{handler.post(()->{starting=false;if(!isDestroyed())render();});}
            },"manual-obsidian-sync").start();
            return;

        } catch (Exception e) {
            starting=false;permissionPending=false;startAfterPermission=false;
            if(!prefs.getBoolean("running",false))prefs.edit().putString("status","启动失败")
                .putString("log","请确认已安装 Termux、授权运行命令并启用 allow-external-apps。\n"+e).apply();
        }finally{if(!starting||permissionPending)render();}
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if(code!=1)return;
        permissionPending=false;
        if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED){startAfterPermission=true;maybeStart();}
        else{startAfterPermission=false;autoCancelled=true;}
        render();
    }
}

