package dev.pixel.sync;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.webkit.*;
import android.widget.*;
import android.text.TextUtils;

/** Local Markdown reader; remote pages run without script or local-file access. */
public final class BriefingActivity extends Activity {
    private WebView web;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);
        Button back=new Button(this);back.setText("返回 · "+getIntent().getStringExtra("title"));back.setOnClickListener(v->finish());layout.addView(back);
        web=new WebView(this);web.getSettings().setJavaScriptEnabled(false);web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                String scheme=request.getUrl().getScheme();return !"https".equals(scheme)&&!"http".equals(scheme);
            }
        });
        layout.addView(web,new LinearLayout.LayoutParams(-1,0,1));setContentView(layout);
        String text=getIntent().getStringExtra("body");if(text==null)text="";
        StringBuilder html=new StringBuilder("<html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>body{font:17px sans-serif;line-height:1.7;padding:16px;color:#263c31;background:#fafbf7;overflow-wrap:anywhere}a{color:#287c60}h2{font-size:21px}</style></head><body>");
        for(String line:text.split("\n")){
            String safe=TextUtils.htmlEncode(line);
            if(line.matches("https?://[^\\s]+"))html.append("<p><a href=\"").append(safe).append("\">查看来源原文 ↗</a></p>");
            else if(line.startsWith("## "))html.append("<h2>").append(TextUtils.htmlEncode(line.substring(3))).append("</h2>");
            else if(line.startsWith("# "))html.append("<h1>").append(TextUtils.htmlEncode(line.substring(2))).append("</h1>");
            else html.append("<p>").append(safe.replaceAll("\\*\\*(.+?)\\*\\*","<strong>$1</strong>")).append("</p>");
        }
        web.loadDataWithBaseURL("https://greta.invalid/",html.append("</body></html>").toString(),"text/html","UTF-8",null);
    }
    @Override public void onBackPressed(){if(web.canGoBack())web.goBack();else super.onBackPressed();}
    @Override protected void onDestroy(){web.destroy();super.onDestroy();}
}
