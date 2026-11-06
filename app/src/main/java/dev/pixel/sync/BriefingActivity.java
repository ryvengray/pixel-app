package dev.pixel.sync;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

/** Renders local briefing Markdown and delegates source links to the browser. */
public final class BriefingActivity extends Activity {
    private WebView web;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        int theme = getSharedPreferences("home", 0).getInt("theme", 0);
        boolean dark = theme == 2 || (theme == 0 &&
                (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                        == Configuration.UI_MODE_NIGHT_YES);
        String background = dark ? "#122019" : "#F6F8F1";
        String foreground = dark ? "#E5EDE3" : "#263C31";
        String accent = dark ? "#A8DCB5" : "#286849";
        String panel = dark ? "#21362B" : "#E7EFE3";
        String border = dark ? "#3A5646" : "#CEDCCB";
        String muted = dark ? "#AEC4B4" : "#526B59";

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.parseColor(background));
        layout.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets safe = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        Button back = new Button(this);
        back.setText("‹  返回");
        back.setTextColor(Color.parseColor(accent));
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setOnClickListener(v -> finish());
        layout.addView(back, new LinearLayout.LayoutParams(-2, -2));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor(background));
        web.getSettings().setJavaScriptEnabled(false);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setBlockNetworkLoads(true);
        web.getSettings().setTextZoom(Math.round(getResources().getConfiguration().fontScale * 100));
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request.isForMainFrame() && request.hasGesture()) openSource(request.getUrl());
                return true;
            }
        });
        layout.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
        int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        WindowInsetsController insets = getWindow().getInsetsController();
        if (insets != null) insets.setSystemBarsAppearance(dark ? 0 : lightBars, lightBars);

        String title = getIntent().getStringExtra("title");
        String text = getIntent().getStringExtra("body");
        String family = AppTypography.choice(this) == 1 ? "serif" : "sans-serif";
        String style = "html{background:" + background + ";color:" + foreground + "}"
                + "body{margin:0;font:16px/1.75 " + family + ";overflow-wrap:anywhere}"
                + "main{box-sizing:border-box;max-width:48rem;margin:auto;padding:12px 24px 48px}"
                + "header{padding:2px 0 18px;border-bottom:1px solid " + border + ";margin-bottom:24px}"
                + ".eyebrow{font-size:.75rem;letter-spacing:.12em;color:" + muted + "}"
                + "h1,h2,h3,h4{line-height:1.3;margin:1.45em 0 .5em}"
                + "header h1{font-size:1.9rem;margin:.2em 0 0}article h1,article h2{font-size:1.35rem}"
                + "h3,h4{font-size:1.1rem}p,ul,ol,pre,blockquote,table{margin:0 0 1em}"
                + "ul,ol{padding-left:1.55em}li{margin:.35em 0}li>p{margin:.25em 0}"
                + "a{color:" + accent + ";text-underline-offset:.18em}"
                + "blockquote,pre,th,article>h2:first-of-type+ul{background:" + panel + "}"
                + "article>h2:first-of-type+ul{padding:.65em 1em .65em 2em;border-radius:.75em}"
                + "blockquote{padding:.65em 1em;border-left:3px solid " + accent + ";border-radius:0 .5em .5em 0}"
                + "blockquote>:last-child{margin-bottom:0}pre{padding:1em;border-radius:.5em;overflow:auto}"
                + "code{font-size:.9em}p code,li code{background:" + panel + ";padding:.12em .3em;border-radius:.25em}"
                + "table{display:block;overflow:auto;border-collapse:collapse}"
                + "th,td{padding:.5em .7em;border:1px solid " + border + ";text-align:left}"
                + "hr{border:0;border-top:1px solid " + border + ";margin:1.5em 0}img{max-width:100%}";
        String html = "<!doctype html><html lang='zh-CN'><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; style-src 'unsafe-inline'\">"
                + "<style>" + style + "</style><main><header><div class='eyebrow'>GRETA</div><h1>"
                + TextUtils.htmlEncode(title == null ? "内容详情" : title)
                + "</h1></header><article>" + BriefingMarkdown.render(text == null ? "" : text)
                + "</article></main></html>";
        web.loadDataWithBaseURL("https://greta.invalid/", html, "text/html", "UTF-8", null);
    }

    private void openSource(Uri uri) {
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || "greta.invalid".equals(uri.getHost())) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "找不到可打开来源链接的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onDestroy() {
        if (web != null) {
            ((LinearLayout) web.getParent()).removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }
}
