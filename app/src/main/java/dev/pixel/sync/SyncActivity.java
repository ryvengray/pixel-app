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
import android.view.View;
import android.widget.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class SyncActivity extends Activity {
    private static final String PERMISSION = "com.termux.permission.RUN_COMMAND";
    private SharedPreferences prefs;
    private TextView status, log;
    private Button sync;
    private final Handler handler = new Handler();
    private final Runnable refresh = new Runnable() {
        public void run() { render(); handler.postDelayed(this, 1000); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("sync", 0);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(48, 48, 48, 48);
        body.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(48, bars.top + 32, 48, bars.bottom + 32); return insets;
        });
        TextView title = new TextView(this); title.setText("Obsidian 一键同步"); title.setTextSize(26); body.addView(title);
        TextView path = new TextView(this); path.setText("~/storage/shared/Documents/obsidian\n\n检查 → commit → pull → push\n"); body.addView(path);
        status = new TextView(this); status.setTextSize(20); body.addView(status);
        sync = new Button(this); sync.setText("立即同步"); sync.setOnClickListener(v -> startSync()); body.addView(sync);
        Button settings = new Button(this); settings.setText("调用权限设置");
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))); body.addView(settings);
        Button termux = new Button(this); termux.setText("打开 Termux"); termux.setOnClickListener(v -> {
            Intent launch = getPackageManager().getLaunchIntentForPackage("com.termux");
            if (launch != null) startActivity(launch); else Toast.makeText(this, "请先安装 Termux", Toast.LENGTH_LONG).show();
        }); body.addView(termux);
        TextView help = new TextView(this); help.setText("首次使用请按 README 配置 Termux、存储权限和 Git 认证。执行期间请暂停编辑笔记。若长时间没有结果，请到 Termux 检查任务；重新点击会由仓库锁防止并发执行。\n"); body.addView(help);
        ScrollView scroll = new ScrollView(this); log = new TextView(this); log.setTextIsSelectable(true); scroll.addView(log); body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(body);
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

