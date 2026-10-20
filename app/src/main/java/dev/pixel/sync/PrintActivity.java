package dev.pixel.sync;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
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
    public static final String EXTRA_DRAFT_TEXT = "dev.pixel.sync.extra.DRAFT_TEXT";
    public static final String EXTRA_FILE_NAME = "dev.pixel.sync.extra.FILE_NAME";
    private boolean dark;
    private int foreground, secondary, bg, cardBg, accent, accentText;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private FrameLayout loadingOverlay;
    private PetScene loadingPet;
    private TextView loadingStatus;
    private long loadingShownAt;
    private Runnable pendingLoadingHide;

    private LinearLayout printerContainer;
    private LinearLayout contentContainer;
    private TextView fileLabel, textInput, copiesLabel, fileTab, textTab;
    private View fileArea, textArea;
    private Button printBtn;
    private String selectedPrinterId;
    private Uri selectedFileUri;
    private String selectedFileName;
    private int copies = 1;
    private String paperSize = "a4", colorMode = "monochrome";
    private TextView[] paperChoices, colorChoices;
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
        body.addView(buildOptionsSection());
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

        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(bg);
        screen.addView(root, new FrameLayout.LayoutParams(-1, -1));
        loadingOverlay = buildLoadingOverlay();
        screen.addView(loadingOverlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(screen);
        loadIncomingFile(getIntent());
        String draft=getIntent().getStringExtra(EXTRA_DRAFT_TEXT);
        if(draft!=null&&!draft.isEmpty()&&draft.length()<=4000){switchTab(false);textInput.setText(draft);}
        showLoading("正在加载打印机…");
        loadPrinters();
    }

    private FrameLayout buildLoadingOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(bg);overlay.setClickable(true);overlay.setFocusable(true);
        loadingPet = new PetScene(this);loadingPet.setTheme(dark);loadingPet.setMoving(!prefs.getBoolean("reduceMotion", false));
        overlay.addView(loadingPet, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout message = new LinearLayout(this);message.setOrientation(LinearLayout.VERTICAL);message.setGravity(Gravity.CENTER);
        TextView title = new TextView(this);title.setText("Greta 正在准备");title.setTextSize(19);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));title.setTextColor(foreground);title.setGravity(Gravity.CENTER);message.addView(title);
        loadingStatus = new TextView(this);loadingStatus.setTextSize(13);loadingStatus.setTextColor(secondary);loadingStatus.setGravity(Gravity.CENTER);loadingStatus.setPadding(0,dp(8),0,0);message.addView(loadingStatus);
        FrameLayout.LayoutParams messageParams = new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);messageParams.leftMargin=dp(24);messageParams.rightMargin=dp(24);messageParams.bottomMargin=dp(82);overlay.addView(message,messageParams);
        overlay.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        return overlay;
    }

    private void showLoading(String status) {
        if (pendingLoadingHide != null) handler.removeCallbacks(pendingLoadingHide);
        loadingStatus.setText(status);loadingOverlay.animate().cancel();loadingOverlay.setAlpha(1f);loadingOverlay.setVisibility(View.VISIBLE);loadingOverlay.bringToFront();loadingShownAt=android.os.SystemClock.uptimeMillis();loadingPet.resumeScene();
    }

    private void hideLoading() {
        long wait=Math.max(0,500-(android.os.SystemClock.uptimeMillis()-loadingShownAt));
        pendingLoadingHide=()->loadingOverlay.animate().alpha(0f).setDuration(220).withEndAction(()->{loadingOverlay.setVisibility(View.GONE);loadingOverlay.setAlpha(1f);loadingPet.pauseScene();}).start();
        handler.postDelayed(pendingLoadingHide,wait);
    }

    private void loadIncomingFile(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        selectedFileUri = intent.getData();
        selectedFileName = intent.getStringExtra(EXTRA_FILE_NAME);
        if (selectedFileName == null || selectedFileName.isEmpty()) selectedFileName = getFileName(selectedFileUri);
        switchTab(true);
        fileLabel.setText(selectedFileName);
        fileLabel.setTextColor(accent);
        fileArea.setContentDescription("已选择文件：" + selectedFileName);
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
        header.setPadding(dp(20), dp(12), dp(20), dp(12));

        FrameLayout back = new FrameLayout(this);
        back.setBackground(toolbarSurface(true));back.setClickable(true);back.setFocusable(true);
        back.setContentDescription("返回");
        back.setOnClickListener(v -> finish());
        ToolbarGlyph backGlyph=new ToolbarGlyph(false);back.addView(backGlyph,new FrameLayout.LayoutParams(dp(26),dp(26),Gravity.CENTER));
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);title.setText("打印");title.setTextSize(22);title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));title.setTextColor(foreground);
        TextView subtitle = new TextView(this);subtitle.setText("选择内容并发送到打印机");subtitle.setTextSize(12);subtitle.setTextColor(secondary);subtitle.setPadding(0,dp(2),0,0);
        identity.addView(title);identity.addView(subtitle);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
        tp.leftMargin = dp(14);
        header.addView(identity, tp);

        TextView jobs = new TextView(this);jobs.setText("任务");jobs.setTextSize(14);jobs.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));jobs.setTextColor(foreground);jobs.setGravity(Gravity.CENTER);jobs.setIncludeFontPadding(false);
        jobs.setBackground(toolbarSurface(false));jobs.setClickable(true);jobs.setFocusable(true);jobs.setContentDescription("查看打印任务");
        jobs.setOnClickListener(v -> startActivity(new Intent(this, PrintJobsActivity.class)));
        LinearLayout.LayoutParams jobsParams=new LinearLayout.LayoutParams(dp(66),dp(48));jobsParams.rightMargin=dp(8);header.addView(jobs,jobsParams);

        FrameLayout refresh = new FrameLayout(this);
        refresh.setBackground(toolbarSurface(true));refresh.setClickable(true);refresh.setFocusable(true);
        refresh.setContentDescription("刷新打印机列表");
        refresh.setOnClickListener(v -> refreshPrinters());
        ToolbarGlyph refreshGlyph=new ToolbarGlyph(true);refresh.addView(refreshGlyph,new FrameLayout.LayoutParams(dp(25),dp(25),Gravity.CENTER));
        header.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(48)));

        return header;
    }

    private RippleDrawable toolbarSurface(boolean round) {
        int pressed=Color.parseColor(dark?"#416452":"#C9DDC8");
        GradientDrawable base=new GradientDrawable();base.setColor(dark?Color.parseColor("#284235"):Color.parseColor("#E4EEE0"));base.setCornerRadius(dp(round?24:16));
        return new RippleDrawable(ColorStateList.valueOf(pressed),base,null);
    }

    private final class ToolbarGlyph extends View {
        private final boolean refresh;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        ToolbarGlyph(boolean refresh){super(PrintActivity.this);this.refresh=refresh;paint.setColor(foreground);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2.4f));paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);float cx=getWidth()/2f,cy=getHeight()/2f;
            if(!refresh){
                canvas.drawLine(cx+dp(6),cy-dp(7),cx-dp(2),cy,paint);
                canvas.drawLine(cx-dp(2),cy,cx+dp(6),cy+dp(7),paint);
                canvas.drawLine(cx-dp(1),cy,cx+dp(9),cy,paint);
            }else{
                float radius=dp(7);RectF oval=new RectF(cx-radius,cy-radius,cx+radius,cy+radius);
                canvas.drawArc(oval,-55,285,false,paint);
                Path arrow=new Path();arrow.moveTo(cx+dp(3),cy-dp(8));arrow.lineTo(cx+dp(8),cy-dp(8));arrow.lineTo(cx+dp(8),cy-dp(3));canvas.drawPath(arrow,paint);
            }
        }
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

        LinearLayout.LayoutParams tabsParams=new LinearLayout.LayoutParams(-1,-2);tabsParams.topMargin=dp(14);section.addView(buildTabSwitcher(),tabsParams);

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
        tabs.setPadding(dp(4),dp(4),dp(4),dp(4));tabs.setBackground(cardBg());
        fileTab=buildTab("文件",true,v->switchTab(true));textTab=buildTab("文字",false,v->switchTab(false));
        tabs.addView(fileTab,new LinearLayout.LayoutParams(0,dp(42),1));tabs.addView(textTab,new LinearLayout.LayoutParams(0,dp(42),1));
        return tabs;
    }

    private TextView buildTab(String text, boolean selected, View.OnClickListener listener) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextSize(14);
        btn.setGravity(Gravity.CENTER);styleChoice(btn,selected);
        btn.setOnClickListener(listener);
        return btn;
    }

    private void styleChoice(TextView view,boolean selected){view.setTextColor(selected?accentText:foreground);GradientDrawable bg=new GradientDrawable();bg.setColor(selected?accent:cardBg);bg.setCornerRadius(dp(13));view.setBackground(bg);}

    private void switchTab(boolean isFile) {
        fileMode = isFile;
        styleChoice(fileTab,isFile);styleChoice(textTab,!isFile);
        fileArea.setVisibility(isFile ? View.VISIBLE : View.GONE);
        textArea.setVisibility(isFile ? View.GONE : View.VISIBLE);
    }

    private View buildFileArea() {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        area.setGravity(Gravity.CENTER);
        area.setPadding(dp(20), dp(22), dp(20), dp(22));
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setCornerRadius(dp(16));
        d.setStroke(dp(2), dark ? Color.parseColor("#3A5448") : Color.parseColor("#C8D8C0"));
        area.setBackground(d);
        area.setOnClickListener(v -> pickFile());

        TextView icon = new TextView(this);
        icon.setText("▤");
        icon.setTextColor(accent);icon.setTextSize(30);
        icon.setGravity(Gravity.CENTER);
        area.addView(icon);

        fileLabel = new TextView(this);
        fileLabel.setText("还没有选择文件");
        fileLabel.setTextSize(15);
        fileLabel.setTextColor(secondary);
        fileLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        area.addView(fileLabel, lp);

        TextView hint = new TextView(this);
        hint.setText("支持 PDF、图片、Word 和 Excel");
        hint.setTextSize(12);
        hint.setTextColor(dark ? Color.parseColor("#5A7A6A") : Color.parseColor("#9AB0A0"));
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(4);
        area.addView(hint, hp);

        TextView choose=new TextView(this);choose.setText("选择文件");choose.setTextSize(14);choose.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));choose.setTextColor(accentText);choose.setGravity(Gravity.CENTER);GradientDrawable button=new GradientDrawable();button.setColor(accent);button.setCornerRadius(dp(14));choose.setBackground(button);LinearLayout.LayoutParams chooseParams=new LinearLayout.LayoutParams(dp(132),dp(42));chooseParams.topMargin=dp(16);area.addView(choose,chooseParams);

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

    private View buildOptionsSection(){
        LinearLayout section=new LinearLayout(this);section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams outer=new LinearLayout.LayoutParams(-1,-2);outer.topMargin=dp(22);section.setLayoutParams(outer);
        TextView title=new TextView(this);title.setText("打印设置");title.setTextSize(16);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));title.setTextColor(foreground);section.addView(title);
        TextView paperLabel=new TextView(this);paperLabel.setText("纸张大小");paperLabel.setTextSize(13);paperLabel.setTextColor(secondary);paperLabel.setPadding(0,dp(14),0,dp(8));section.addView(paperLabel);
        LinearLayout paperRow=new LinearLayout(this);paperChoices=new TextView[3];String[] names={"A4","A5","Letter"};String[] values={"a4","a5","letter"};for(int i=0;i<3;i++){final String value=values[i];TextView choice=buildTab(names[i],i==0,v->{paperSize=value;refreshChoices();});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(42),1);p.rightMargin=i<2?dp(8):0;paperRow.addView(choice,p);paperChoices[i]=choice;}section.addView(paperRow);
        TextView colorLabel=new TextView(this);colorLabel.setText("颜色");colorLabel.setTextSize(13);colorLabel.setTextColor(secondary);colorLabel.setPadding(0,dp(16),0,dp(8));section.addView(colorLabel);
        LinearLayout colorRow=new LinearLayout(this);colorChoices=new TextView[2];String[] colors={"黑白","彩色"};String[] colorValues={"monochrome","color"};for(int i=0;i<2;i++){final String value=colorValues[i];TextView choice=buildTab(colors[i],i==0,v->{colorMode=value;refreshChoices();});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(42),1);p.rightMargin=i==0?dp(8):0;colorRow.addView(choice,p);colorChoices[i]=choice;}section.addView(colorRow);return section;
    }

    private void refreshChoices(){String[] papers={"a4","a5","letter"};for(int i=0;i<paperChoices.length;i++)styleChoice(paperChoices[i],paperSize.equals(papers[i]));String[] colors={"monochrome","color"};for(int i=0;i<colorChoices.length;i++)styleChoice(colorChoices[i],colorMode.equals(colors[i]));}

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
                handler.post(() -> {renderPrinters(printers);hideLoading();});
            } catch (Exception e) {
                handler.post(() -> {renderPrinters(java.util.Collections.emptyList());hideLoading();showToast(e.getMessage());});
            }
        });
    }

    private void refreshPrinters() {
        showLoading("正在扫描局域网打印机…");
        executor.execute(() -> {
            try {
                PrinterApi.discover(PrintActivity.this);
                List<PrinterApi.Printer> printers = PrinterApi.listPrinters(PrintActivity.this);
                handler.post(() -> {
                    renderPrinters(printers);
                    hideLoading();
                    showToast("已刷新打印机列表");
                });
            } catch (Exception e) {
                handler.post(() -> {hideLoading();showToast("刷新失败：" + e.getMessage());});
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
        showLoading("正在发送打印任务…");
        final String pid = selectedPrinterId;
        final int c = copies;
        final boolean fileJob=fileMode;final Uri fileUri=selectedFileUri;final String jobText=fileJob?null:((EditText)textInput).getText().toString().trim();final String paper=paperSize, color=colorMode;
        executor.execute(() -> {
            try {
                if (fileJob) {
                    PrinterApi.printFile(PrintActivity.this, pid, fileUri, c, paper, color);
                } else {
                    PrinterApi.printText(PrintActivity.this, pid, jobText, c, paper, color);
                }
                handler.post(() -> {
                    printing = false;
                    printBtn.setEnabled(true);
                    printBtn.setText("🖨  开始打印");
                    hideLoading();
                    showToast("打印任务已发送");
                    if (fileJob) {
                        selectedFileUri = null;
                        selectedFileName = null;
                        fileLabel.setText("还没有选择文件");
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
                    hideLoading();
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
        if (loadingOverlay != null && loadingOverlay.getVisibility() == View.VISIBLE) loadingPet.resumeScene();
    }

    @Override
    protected void onPause() {
        if (loadingPet != null) loadingPet.pauseScene();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (pendingLoadingHide != null) handler.removeCallbacks(pendingLoadingHide);
        super.onDestroy();
        executor.shutdownNow();
    }
}
