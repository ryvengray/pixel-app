package dev.pixel.sync;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.*;
import android.widget.*;

/** Persistent first-party browser surface. Credentials stay in the WebView cookie store. */
public class WebPageActivity extends Activity {
    private WebView web;
    private Uri origin;
    private TextView error;
    private ProgressBar progress;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // Target SDK 35 draws edge to edge. Keep native controls and the WebView
        // inside system bars/cutouts, including when the keyboard is open.
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            Insets ime=insets.getInsets(WindowInsets.Type.ime());
            view.setPadding(safe.left,safe.top,safe.right,Math.max(safe.bottom,ime.bottom));
            return WindowInsets.CONSUMED;
        });
        LinearLayout toolbar=new LinearLayout(this);
        Button back=new Button(this);back.setText("返回");back.setOnClickListener(v->onBackPressed());toolbar.addView(back);
        TextView title=new TextView(this);title.setText("数学冒险岛");title.setTextSize(18);title.setGravity(android.view.Gravity.CENTER);toolbar.addView(title,new LinearLayout.LayoutParams(0,-1,1));
        Button reload=new Button(this);reload.setText("刷新");reload.setOnClickListener(v->{if(web!=null)web.reload();});toolbar.addView(reload);root.addView(toolbar);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);root.addView(progress,new LinearLayout.LayoutParams(-1,6));
        error=new TextView(this);error.setPadding(24,24,24,24);error.setVisibility(View.GONE);root.addView(error);
        setContentView(root);
        root.requestApplyInsets();
        WindowInsetsController bars=getWindow().getInsetsController();
        if(bars!=null)bars.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        String base=getSharedPreferences("gray_settings",0).getString("server_url","");
        Uri configured=Uri.parse(base);
        if(configured.getHost()==null||!("https".equals(configured.getScheme())||"http".equals(configured.getScheme()))){
            progress.setVisibility(View.GONE);error.setVisibility(View.VISIBLE);error.setText("请先在助理设置中配置服务地址");
            error.setOnClickListener(v->startActivity(new Intent(this,AssistantSettingsActivity.class)));return;
        }
        origin=configured.buildUpon().path("/math/").clearQuery().fragment(null).build();
        web=new WebView(this);root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);
        CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onProgressChanged(WebView view,int value){progress.setProgress(value);progress.setVisibility(value==100?View.GONE:View.VISIBLE);}
            @Override public boolean onJsConfirm(WebView view,String url,String message,JsResult result){new android.app.AlertDialog.Builder(WebPageActivity.this).setMessage(message).setPositiveButton("确定",(d,w)->result.confirm()).setNegativeButton("取消",(d,w)->result.cancel()).setOnCancelListener(d->result.cancel()).show();return true;}
            @Override public boolean onJsPrompt(WebView view,String url,String message,String value,JsPromptResult result){EditText input=new EditText(WebPageActivity.this);input.setText(value);new android.app.AlertDialog.Builder(WebPageActivity.this).setMessage(message).setView(input).setPositiveButton("确定",(d,w)->result.confirm(input.getText().toString())).setNegativeButton("取消",(d,w)->result.cancel()).setOnCancelListener(d->result.cancel()).show();return true;}
        });
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){Uri u=request.getUrl();if(origin.getScheme().equals(u.getScheme())&&origin.getEncodedAuthority().equals(u.getEncodedAuthority()))return false;return true;}
            @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap icon){error.setVisibility(View.GONE);}
            @Override public void onPageFinished(WebView view,String url){CookieManager.getInstance().flush();}
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError failure){if(request.isForMainFrame())showError();}
            @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse response){if(request.isForMainFrame())showError();}
        });
        if(state==null||web.restoreState(state)==null)web.loadUrl(origin.toString());
    }
    private void showError(){error.setText("页面暂时无法加载，请检查网络后点击刷新");error.setVisibility(View.VISIBLE);progress.setVisibility(View.GONE);}
    @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
    @Override protected void onSaveInstanceState(Bundle state){if(web!=null)web.saveState(state);super.onSaveInstanceState(state);}
    @Override protected void onPause(){if(web!=null){CookieManager.getInstance().flush();web.onPause();}super.onPause();}
    @Override protected void onResume(){super.onResume();if(web!=null)web.onResume();}
    @Override protected void onDestroy(){if(web!=null){((android.view.ViewGroup)web.getParent()).removeView(web);web.destroy();}super.onDestroy();}
}
