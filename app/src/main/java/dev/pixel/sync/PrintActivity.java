package dev.pixel.sync;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PrintActivity extends Activity {
    private boolean dark;
    private int foreground, secondary, bg, cardBg, accent, accentText;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private LinearLayout printerContainer;
    private LinearLayout contentContainer;
    private TextView fileLabel, textInput, copiesLabel;
    private View fileArea, textArea;
    private Button printBtn;
    private String selectedPrinterId;
    private Uri selectedFileUri;
    private String selectedFileName;
    private int copies = 1;
    private boolean fileMode = true;
    private boolean printing;
    private List<PrinterApi.Printer> cachedPrinters = new java.util.ArrayList<>();

    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    private boolean systemDark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("home", 0);
        int choice = prefs.getInt("theme", 0);
        dark = choice == 0 ? systemDark() : choice == 2;
        applyColors();

        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);

        root.addView(buildHeader());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(16), dp(24), dp(24));

        body.addView(buildPrinterSection());
        body.addView(buildDivider());
        body.addView(buildContentSection());
        body.addView(buildCopiesSection());
        body.addView(buildPrintButton());

        scroll.addView(body, new ScrollView.LayoutParams(-1, -1));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            root.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return insets;
        });

        setContentView(root);
        loadPrinters();
    }

    private void applyColors() {
        if (dark) {
            bg = Color.parseColor("#0F1F17");
            cardBg = Color.parseColor("#1A2E24");
            foreground = Color.parseColor("#E5EDE3");
            secondary = Color.parseColor("#7A9B8A");
            accent = Color.parseColor("#4ADE80");
            accentText = Color.parseColor("#1A2E24");
        } else {
            bg = Color.parseColor("#F5F7F0");
            cardBg = Color.parseColor("#FFFFFF");
            foreground = Color.parseColor("#1A2E24");
            secondary = Color.parseColor("#6B8F7A");
            accent = Color.parseColor("#2D8B4E");
            accentText = Color.parseColor("#FFFFFF");
        }
    }

    private GradientDrawable cardBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(cardBg);
        d.setCornerRadius(dp(16));
        return d;
    }

    private GradientDrawable selectedCardBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(cardBg);
        d.setCornerRadius(dp(16));
        d.setStroke(dp(2), accent);
        return d;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(12), dp(24), dp(12));

        Button back = new Button(this);
        back.setText("←");
        back.setTextSize(22);
        back.setTextColor(foreground);
        back.setAllCaps(false);
        back.setMinWidth(dp(48));
        back.setMinHeight(dp(48));
        back.setBackground(ripple());
        back.setOnClickListener(v -> finish());
        header.addView(back);

        TextView title = new TextView(this);
        title.setText("打印");
        title.setTextSize(22);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(foreground);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
        tp.leftMargin = dp(12);
        header.addView(title, tp);

        Button refresh = new Button(this);
        refresh.setText("⟳");
        refresh.setTextSize(22);
        refresh.setTextColor(foreground);
        refresh.setAllCaps(false);
        refresh.setMinWidth(dp(48));
        refresh.setMinHeight(dp(48));
        refresh.setBackground(ripple());
        refresh.setOnClickListener(v -> refreshPrinters());
        header.addView(refresh);

        return header;
    }

    private RippleDrawable ripple() {
        int rippleColor = dark ? Color.parseColor("#406651") : Color.parseColor("#CADBC7");
        int baseColor = dark ? Color.parseColor("#294536") : Color.parseColor("#E3ECDC");
        return new RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            cardBg(), null
        );
    }

    private View buildDivider() {
        View v = new View(this);
        v.setBackgroundColor(Color.parseColor(dark ? "#2A3F35" : "#E0E8D8"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.topMargin = dp(20);
        lp.bottomMargin = dp(20);
        return v;
    }

    private View buildPrinterSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);

        TextView label = new TextView(this);
        label.setText("打印机");
        label.setTextSize(16);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setTextColor(secondary);
        section.addView(label);

        printerContainer = new LinearLayout(this);
        printerContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = dp(12);
        section.addView(printerContainer, cp);

        return section;
    }

    private View buildContentSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);

        TextView label = new TextView(this);
        label.setText("打印内容");
        label.setTextSize(16);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setTextColor(secondary);
        section.addView(label);

        section.addView(buildTabSwitcher());

        fileArea = buildFileArea();
        textArea = buildTextArea();
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.topMargin = dp(12);
        section.addView(fileArea, fp);
        section.addView(textArea, fp);
        textArea.setVisibility(View.GONE);

        return section;
    }

    private View buildTabSwitcher() {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = dp(16);
        tabs.addView(buildTab("文件", true, v -> switchTab(true)));
        tabs.addView(buildTab("文字", false, v -> switchTab(false)));
        return tabs;
    }

    private View buildTab(String text, boolean selected, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(14);
        btn.setAllCaps(false);
        btn.setMinHeight(dp(40));
        btn.setPadding(dp(24), 0, dp(24), 0);
        if (selected) {
            btn.setTextColor(accentText);
            GradientDrawable d = new GradientDrawable();
            d.setColor(accent);
            d.setCornerRadius(dp(20));
            btn.setBackground(d);
        } else {
            btn.setTextColor(secondary);
            btn.setBackground(null);
        }
        btn.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return btn;
    }

    private void switchTab(boolean isFile) {
        fileMode = isFile;
        fileArea.setVisibility(isFile ? View.VISIBLE : View.GONE);
        textArea.setVisibility(isFile ? View.GONE : View.VISIBLE);
    }

    private View buildFileArea() {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        area.setGravity(Gravity.CENTER);
        area.setPadding(dp(24), dp(32), dp(24), dp(32));
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setCornerRadius(dp(16));
        d.setStroke(dp(2), dark ? Color.parseColor("#3A5448") : Color.parseColor("#C8D8C0"));
        area.setBackground(d);
        area.setOnClickListener(v -> pickFile());

        TextView icon = new TextView(this);
        icon.setText("📄");
        icon.setTextSize(32);
        icon.setGravity(Gravity.CENTER);
        area.addView(icon);

        fileLabel = new TextView(this);
        fileLabel.setText("点击选择文件");
        fileLabel.setTextSize(15);
        fileLabel.setTextColor(secondary);
        fileLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        area.addView(fileLabel, lp);

        TextView hint = new TextView(this);
        hint.setText("PDF · 图片 · Word · Excel");
        hint.setTextSize(12);
        hint.setTextColor(dark ? Color.parseColor("#5A7A6A") : Color.parseColor("#9AB0A0"));
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(4);
        area.addView(hint, hp);

        return area;
    }

    private View buildTextArea() {
        EditText et = new EditText(this);
        et.setHint("输入要打印的文字...");
        et.setTextSize(15);
        et.setTextColor(foreground);
        et.setHintTextColor(secondary);
        et.setMinHeight(dp(120));
        et.setGravity(Gravity.TOP | Gravity.LEFT);
        et.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setCornerRadius(dp(16));
        d.setStroke(dp(2), dark ? Color.parseColor("#3A5448") : Color.parseColor("#C8D8C0"));
        et.setBackground(d);
        textInput = et;
        return et;
    }

    private View buildCopiesSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.HORIZONTAL);
        section.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(24);
        section.setLayoutParams(sp);

        TextView label = new TextView(this);
        label.setText("份数");
        label.setTextSize(15);
        label.setTextColor(secondary);
        section.addView(label);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1));
        section.addView(spacer);

        Button minus = new Button(this);
        minus.setText("−");
        minus.setTextSize(18);
        minus.setTextColor(foreground);
        minus.setAllCaps(false);
        minus.setMinWidth(dp(44));
        minus.setMinHeight(dp(44));
        minus.setBackground(ripple());
        minus.setOnClickListener(v -> { if (copies > 1) { copies--; copiesLabel.setText(String.valueOf(copies)); } });
        section.addView(minus);

        copiesLabel = new TextView(this);
        copiesLabel.setText("1");
        copiesLabel.setTextSize(18);
        copiesLabel.setTextColor(foreground);
        copiesLabel.setGravity(Gravity.CENTER);
        copiesLabel.setPadding(dp(16), 0, dp(16), 0);
        section.addView(copiesLabel);

        Button plus = new Button(this);
        plus.setText("+");
        plus.setTextSize(18);
        plus.setTextColor(foreground);
        plus.setAllCaps(false);
        plus.setMinWidth(dp(44));
        plus.setMinHeight(dp(44));
        plus.setBackground(ripple());
        plus.setOnClickListener(v -> { if (copies < 99) { copies++; copiesLabel.setText(String.valueOf(copies)); } });
        section.addView(plus);

        return section;
    }

    private View buildPrintButton() {
        printBtn = new Button(this);
        printBtn.setText("🖨  开始打印");
        printBtn.setTextSize(17);
        printBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        printBtn.setTextColor(accentText);
        printBtn.setAllCaps(false);
        printBtn.setMinHeight(dp(56));
        GradientDrawable d = new GradientDrawable();
        d.setColor(accent);
        d.setCornerRadius(dp(28));
        printBtn.setBackground(d);
        printBtn.setElevation(dp(4));
        printBtn.setOnClickListener(v -> doPrint());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(56));
        lp.topMargin = dp(24);
        printBtn.setLayoutParams(lp);
        return printBtn;
    }

    private void loadPrinters() {
        executor.execute(() -> {
            try {
                List<PrinterApi.Printer> printers = PrinterApi.listPrinters(PrintActivity.this);
                handler.post(() -> renderPrinters(printers));
            } catch (Exception e) {
                handler.post(() -> showToast(e.getMessage()));
            }
        });
    }

    private void refreshPrinters() {
        executor.execute(() -> {
            try {
                PrinterApi.discover(PrintActivity.this);
                List<PrinterApi.Printer> printers = PrinterApi.listPrinters(PrintActivity.this);
                handler.post(() -> {
                    renderPrinters(printers);
                    showToast("已刷新打印机列表");
                });
            } catch (Exception e) {
                handler.post(() -> showToast("刷新失败：" + e.getMessage()));
            }
        });
    }

    private void renderPrinters(List<PrinterApi.Printer> printers) {
        cachedPrinters = printers;
        printerContainer.removeAllViews();
        if (printers.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无打印机，点击扫描发现局域网打印机");
            empty.setTextSize(14);
            empty.setTextColor(secondary);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(24), dp(16), dp(24));
            printerContainer.addView(empty);
            return;
        }
        for (PrinterApi.Printer p : printers) {
            printerContainer.addView(buildPrinterCard(p));
        }
    }

    private View buildPrinterCard(PrinterApi.Printer p) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(selectedPrinterId != null && selectedPrinterId.equals(p.id) ? selectedCardBg() : cardBg());
        card.setElevation(dp(2));
        card.setClickable(true);
        card.setOnClickListener(v -> {
            selectedPrinterId = p.id;
            renderPrinters(cachedPrinters);
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.bottomMargin = dp(8);
        card.setLayoutParams(cp);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        TextView icon = new TextView(this);
        icon.setText("🖨");
        icon.setTextSize(20);
        row1.addView(icon);

        TextView name = new TextView(this);
        name.setText(p.name);
        name.setTextSize(16);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setTextColor(foreground);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1);
        np.leftMargin = dp(10);
        row1.addView(name, np);

        if (p.online) {
            View dot = new View(this);
            dot.setLayoutParams(new LinearLayout.LayoutParams(dp(8), dp(8)));
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(Color.parseColor("#4ADE80"));
            dot.setBackground(gd);
            row1.addView(dot);
            TextView state = new TextView(this);
            state.setText("在线");
            state.setTextSize(12);
            state.setTextColor(Color.parseColor("#4ADE80"));
            LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-2, -2);
            stp.leftMargin = dp(4);
            row1.addView(state, stp);
        } else {
            View dot = new View(this);
            dot.setLayoutParams(new LinearLayout.LayoutParams(dp(8), dp(8)));
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(Color.parseColor("#9CA3AF"));
            dot.setBackground(gd);
            row1.addView(dot);
            TextView state = new TextView(this);
            state.setText("离线");
            state.setTextSize(12);
            state.setTextColor(Color.parseColor("#9CA3AF"));
            LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-2, -2);
            stp.leftMargin = dp(4);
            row1.addView(state, stp);
        }
        card.addView(row1);

        TextView host = new TextView(this);
        host.setText(p.host + ":" + p.port);
        host.setTextSize(13);
        host.setTextColor(secondary);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(4);
        hp.leftMargin = dp(30);
        card.addView(host, hp);

        return card;
    }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
            "application/pdf", "image/jpeg", "image/png",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/msword", "application/vnd.ms-excel"
        });
        startActivityForResult(intent, 1001);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            selectedFileUri = data.getData();
            selectedFileName = getFileName(selectedFileUri);
            fileLabel.setText(selectedFileName);
            fileLabel.setTextColor(accent);
        }
    }

    private String getFileName(Uri uri) {
        try (android.database.Cursor cur = getContentResolver().query(uri, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int idx = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return cur.getString(idx);
            }
        } catch (Exception ignored) {}
        return "文件";
    }

    private void doPrint() {
        if (printing) return;
        if (selectedPrinterId == null) { showToast("请先选择打印机"); return; }
        if (fileMode) {
            if (selectedFileUri == null) { showToast("请先选择文件"); return; }
        } else {
            String text = ((EditText) textInput).getText().toString().trim();
            if (text.isEmpty()) { showToast("请输入要打印的文字"); return; }
        }
        printing = true;
        printBtn.setEnabled(false);
        printBtn.setText("打印中...");
        final String pid = selectedPrinterId;
        final int c = copies;
        executor.execute(() -> {
            try {
                if (fileMode) {
                    PrinterApi.printFile(PrintActivity.this, pid, selectedFileUri, c);
                } else {
                    String text = ((EditText) textInput).getText().toString().trim();
                    PrinterApi.printText(PrintActivity.this, pid, text, c);
                }
                handler.post(() -> {
                    printing = false;
                    printBtn.setEnabled(true);
                    printBtn.setText("🖨  开始打印");
                    showToast("打印任务已发送");
                    if (fileMode) {
                        selectedFileUri = null;
                        selectedFileName = null;
                        fileLabel.setText("点击选择文件");
                        fileLabel.setTextColor(secondary);
                    } else {
                        ((EditText) textInput).setText("");
                    }
                });
            } catch (Exception e) {
                handler.post(() -> {
                    printing = false;
                    printBtn.setEnabled(true);
                    printBtn.setText("🖨  开始打印");
                    showToast("打印失败：" + e.getMessage());
                });
            }
        });
    }

    private void showToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyColors();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
