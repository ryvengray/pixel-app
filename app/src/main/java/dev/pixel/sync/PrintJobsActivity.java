package dev.pixel.sync;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PrintJobsActivity extends Activity {
    private boolean dark;
    private int foreground, secondary, bg, cardBg, accent;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LinearLayout jobsContainer;
    private FrameLayout loadingOverlay;
    private PetScene loadingPet;

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences prefs = getSharedPreferences("home", 0);
        int choice = prefs.getInt("theme", 0);
        dark = choice == 0 ? systemDark() : choice == 2;
        applyColors();

        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(bg);
        page.addView(buildHeader());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        jobsContainer = new LinearLayout(this);
        jobsContainer.setOrientation(LinearLayout.VERTICAL);
        jobsContainer.setPadding(dp(20), dp(10), dp(20), dp(28));
        scroll.addView(jobsContainer, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        page.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(bg);
        screen.addView(page, new FrameLayout.LayoutParams(-1, -1));
        loadingOverlay = buildLoadingOverlay(prefs);
        screen.addView(loadingOverlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(screen);
        loadJobs();
    }

    private boolean systemDark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyColors() {
        if (dark) {
            bg = Color.parseColor("#0F1F17"); cardBg = Color.parseColor("#1A2E24");
            foreground = Color.parseColor("#E5EDE3"); secondary = Color.parseColor("#7A9B8A"); accent = Color.parseColor("#4ADE80");
        } else {
            bg = Color.parseColor("#F5F7F0"); cardBg = Color.WHITE;
            foreground = Color.parseColor("#1A2E24"); secondary = Color.parseColor("#6B8F7A"); accent = Color.parseColor("#2D8B4E");
        }
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(12), dp(20), dp(12));

        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("打印任务", 22, foreground, true);
        TextView subtitle = text("最近 50 个任务", 12, secondary, false);
        subtitle.setPadding(0, dp(2), 0, 0);
        identity.addView(title); identity.addView(subtitle);
        header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));

        TextView done = text("完成", 14, foreground, true);
        done.setGravity(Gravity.CENTER);
        done.setIncludeFontPadding(false);
        done.setBackground(toolbarSurface());
        done.setOnClickListener(v -> finish());
        done.setContentDescription("返回打印页面");
        header.addView(done, new LinearLayout.LayoutParams(dp(66), dp(48)));
        return header;
    }

    private FrameLayout buildLoadingOverlay(SharedPreferences prefs) {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(bg); overlay.setClickable(true);
        loadingPet = new PetScene(this);
        loadingPet.setTheme(dark);
        loadingPet.setMoving(!prefs.getBoolean("reduceMotion", false));
        overlay.addView(loadingPet, new FrameLayout.LayoutParams(-1, -1));
        TextView label = text("正在读取打印任务…", 14, secondary, false);
        label.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.leftMargin = dp(24); lp.rightMargin = dp(24); lp.bottomMargin = dp(82);
        overlay.addView(label, lp);
        return overlay;
    }

    private void loadJobs() {
        loadingPet.resumeScene();
        executor.execute(() -> {
            try {
                List<PrinterApi.PrintJob> jobs = PrinterApi.listPrintJobs(PrintJobsActivity.this);
                handler.post(() -> { renderJobs(jobs); hideLoading(); });
            } catch (Exception error) {
                handler.post(() -> {
                    renderError(); hideLoading();
                    Toast.makeText(this, "读取失败：" + error.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void hideLoading() {
        loadingOverlay.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            loadingOverlay.setVisibility(View.GONE); loadingOverlay.setAlpha(1f); loadingPet.pauseScene();
        }).start();
    }

    private void renderJobs(List<PrinterApi.PrintJob> jobs) {
        jobsContainer.removeAllViews();
        if (jobs.isEmpty()) {
            TextView empty = text("还没有打印任务\n完成第一次打印后，记录会出现在这里", 15, secondary, false);
            empty.setGravity(Gravity.CENTER); empty.setLineSpacing(dp(5), 1f); empty.setPadding(dp(20), dp(96), dp(20), dp(30));
            jobsContainer.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        String previousDay = "";
        for (PrinterApi.PrintJob job : jobs) {
            String day = formatDate(job.createdAt, "M月d日");
            if (!day.equals(previousDay)) {
                TextView heading = text(day, 13, secondary, true);
                heading.setPadding(dp(2), previousDay.isEmpty() ? dp(8) : dp(18), 0, dp(9));
                jobsContainer.addView(heading);
                previousDay = day;
            }
            jobsContainer.addView(buildJobCard(job));
        }
    }

    private void renderError() {
        jobsContainer.removeAllViews();
        TextView error = text("暂时无法读取打印任务\n请检查服务连接后重试", 15, secondary, false);
        error.setGravity(Gravity.CENTER); error.setLineSpacing(dp(5), 1f); error.setPadding(dp(20), dp(96), dp(20), dp(30));
        error.setOnClickListener(v -> { loadingOverlay.setVisibility(View.VISIBLE); loadJobs(); });
        jobsContainer.addView(error, new LinearLayout.LayoutParams(-1, -2));
    }

    private View buildJobCard(PrinterApi.PrintJob job) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(14));
        card.setBackground(cardSurface());

        LinearLayout first = new LinearLayout(this);
        first.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(job.fileName, 16, foreground, true);
        name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        first.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView status = text(statusText(job.status), 12, statusColor(job.status), true);
        status.setGravity(Gravity.CENTER); status.setIncludeFontPadding(false); status.setPadding(dp(10), 0, dp(10), 0);
        status.setBackground(statusSurface(job.status));
        first.addView(status, new LinearLayout.LayoutParams(-2, dp(28)));
        card.addView(first);

        String details = job.printerName + "  ·  " + job.copies + " 份  ·  " + formatDate(job.createdAt, "HH:mm");
        TextView meta = text(details, 13, secondary, false);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(-1, -2); metaParams.topMargin = dp(8);
        card.addView(meta, metaParams);
        if (!job.error.isEmpty()) {
            TextView error = text(job.error, 12, Color.parseColor(dark ? "#FCA5A5" : "#B42318"), false);
            error.setMaxLines(2); error.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, -2); ep.topMargin = dp(7);
            card.addView(error, ep);
        }

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2); cp.bottomMargin = dp(9);
        card.setLayoutParams(cp);
        return card;
    }

    private TextView text(String value, float size, int color, boolean medium) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (medium) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private GradientDrawable cardSurface() {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(cardBg); shape.setCornerRadius(dp(17)); return shape;
    }

    private RippleDrawable toolbarSurface() {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(dark ? Color.parseColor("#284235") : Color.parseColor("#E4EEE0")); shape.setCornerRadius(dp(16));
        return new RippleDrawable(ColorStateList.valueOf(dark ? Color.parseColor("#416452") : Color.parseColor("#C9DDC8")), shape, null);
    }

    private GradientDrawable statusSurface(String status) {
        int color;
        if ("completed".equals(status)) color = dark ? Color.parseColor("#244A34") : Color.parseColor("#E0F2E4");
        else if ("failed".equals(status)) color = dark ? Color.parseColor("#4A2929") : Color.parseColor("#FDE7E5");
        else color = dark ? Color.parseColor("#3F3A24") : Color.parseColor("#FFF2CC");
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(14)); return shape;
    }

    private int statusColor(String status) {
        if ("completed".equals(status)) return accent;
        if ("failed".equals(status)) return Color.parseColor(dark ? "#FCA5A5" : "#B42318");
        return Color.parseColor(dark ? "#FACC15" : "#8A6200");
    }

    private String statusText(String status) {
        if ("completed".equals(status)) return "已完成";
        if ("failed".equals(status)) return "失败";
        if ("printing".equals(status)) return "打印中";
        return "等待中";
    }

    private String formatDate(String raw, String pattern) {
        try {
            return DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(Instant.parse(raw));
        } catch (Exception ignored) {
            return raw.length() >= 16 ? raw.substring(5, 16).replace('T', ' ') : raw;
        }
    }

    @Override protected void onResume() { super.onResume(); if (loadingOverlay != null && loadingOverlay.getVisibility() == View.VISIBLE) loadingPet.resumeScene(); }
    @Override protected void onPause() { if (loadingPet != null) loadingPet.pauseScene(); super.onPause(); }
    @Override protected void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
}
