package dev.pixel.sync;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.res.Configuration;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class SyncActivity extends Activity {
    private static final String PERMISSION = "com.termux.permission.RUN_COMMAND";
    private SharedPreferences prefs;
    private TextView status, log;
    private Button sync;
    private boolean dark;
    private int fg,muted,bg,card,accent;
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView label(String text,int size,int color){TextView view=new TextView(this);view.setText(text);view.setTextSize(size);view.setTextColor(color);return view;}
    private LinearLayout card(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);view.setPadding(dp(18),dp(17),dp(18),dp(17));view.setBackground(shape(card,20));return view;}
    private final Handler handler = new Handler();
    private final Runnable refresh = new Runnable() {
        public void run() { render(); handler.postDelayed(this, 1000); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("sync", 0);
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
        LinearLayout stateCard=card();LinearLayout.LayoutParams scp=new LinearLayout.LayoutParams(-1,-2);scp.topMargin=dp(14);body.addView(stateCard,scp);TextView stateTitle=label("当前状态",12,muted);stateCard.addView(stateTitle);status=label("准备就绪",19,fg);status.setPadding(0,dp(8),0,dp(16));stateCard.addView(status);
        sync = new Button(this); sync.setText("立即同步");sync.setAllCaps(false);sync.setTextColor(dark?bg:Color.WHITE);sync.setBackground(shape(accent,16));sync.setMinHeight(dp(52));sync.setOnClickListener(v -> startSync());stateCard.addView(sync,new LinearLayout.LayoutParams(-1,dp(52)));
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
        root.addView(page,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
    }
    @Override public void onResume() { super.onResume(); handler.post(refresh); }
    @Override public void onPause() { super.onPause(); handler.removeCallbacks(refresh); }
    private void render() {
        boolean running = prefs.getBoolean("running", false);
        boolean stale = running && System.currentTimeMillis() - prefs.getLong("started", 0) > 600000;
        status.setText(stale ? "结果未知：请检查 Termux 后重试" : prefs.getString("status", "准备就绪"));
        sync.setEnabled(!running || stale);
        log.setText(prefs.getString("log", "执行日志会在任务结束后显示。"));
    }
    private void startSync() {
        try {
            getPackageManager().getPackageInfo("com.termux", 0);
            if (checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{PERMISSION}, 1); return;
            }
            String script;
            try (java.io.InputStream stream = getAssets().open("sync.sh")) {
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] bytes = new byte[4096]; int count;
                while ((count = stream.read(bytes)) != -1) buffer.write(bytes, 0, count);
                script = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            }
            String id = UUID.randomUUID().toString();
            Intent callback = new Intent(this, ResultReceiver.class).setData(Uri.parse("pixelsync://result/" + id)).putExtra("runId", id);
            PendingIntent result = PendingIntent.getBroadcast(this, 0, callback, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_MUTABLE);
            Intent command = new Intent("com.termux.RUN_COMMAND").setClassName("com.termux", "com.termux.app.RunCommandService");
            command.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
            command.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-c", script});
            command.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
            command.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            command.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", result);
            prefs.edit().putString("runId", id).putBoolean("running", true).putLong("started", System.currentTimeMillis())
                    .putString("status", "同步执行中…").putString("log", "等待 Termux 返回结果。最多等待网络步骤各 180 秒。").commit();
            if (startService(command) == null) throw new IllegalStateException("无法启动 Termux 服务");
        } catch (Exception e) {
            prefs.edit().putBoolean("running", false).putString("status", "启动失败")
                    .putString("log", "请确认已安装 Termux、授权运行命令并启用 allow-external-apps。\n" + e).apply();
        }
        render();
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == 1 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) startSync();
    }
}

