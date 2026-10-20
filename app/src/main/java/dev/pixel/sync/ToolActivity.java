package dev.pixel.sync;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Reuses each complete tool, permissions and lifecycle in a bottom drawer or full page. */
public abstract class ToolActivity extends Activity {
    public static final String EXTRA_PRESENTATION="dev.pixel.sync.extra.TOOL_PRESENTATION";
    private boolean drawer,dark;
    private LinearLayout panel;
    private float dragStart;
    private int px(float n){return Math.round(n*getResources().getDisplayMetrics().density);}

    @Override protected void onCreate(Bundle state){
        String mode=getIntent().getStringExtra(EXTRA_PRESENTATION);
        if(mode==null)mode=getSharedPreferences("gray_settings",0).getString("tool_presentation","drawer");
        drawer=!"page".equals(mode);
        int choice=getSharedPreferences("home",0).getInt("theme",0);
        dark=choice==2||(choice==0&&(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);
        setTheme(drawer?(dark?R.style.ToolDrawerDark:R.style.ToolDrawerLight):
            (dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar));
        super.onCreate(state);
        // Window theme alone does not update the task's occlusion state.
        setTranslucent(drawer);
        if(drawer)setFinishOnTouchOutside(true);
    }

    protected final boolean isToolDrawer(){return drawer;}

    @Override public void setContentView(View content){
        if(!drawer){super.setContentView(content);return;}
        panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background=new GradientDrawable();background.setColor(Color.parseColor(dark?"#102019":"#F2F6EE"));
        float radius=px(26);background.setCornerRadii(new float[]{radius,radius,radius,radius,0,0,0,0});
        panel.setBackground(background);panel.setClipToOutline(true);
        LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER_VERTICAL);controls.setPadding(px(14),0,px(8),0);
        FrameLayout drag=new FrameLayout(this);drag.setContentDescription("下拉关闭工具");
        View grip=new View(this);GradientDrawable gripBg=new GradientDrawable();gripBg.setColor(dark?0xFF799383:0xFFBACDBB);gripBg.setCornerRadius(px(3));grip.setBackground(gripBg);
        drag.addView(grip,new FrameLayout.LayoutParams(px(44),px(5),Gravity.CENTER));
        drag.setOnTouchListener((v,event)->drag(event));
        controls.addView(drag,new LinearLayout.LayoutParams(0,px(44),1));
        TextView expand=control("全屏","在新界面打开工具");
        expand.setOnClickListener(v->{Intent fullPage=new Intent(getIntent()).setFlags(getIntent().getFlags()&~Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_PRESENTATION,"page");prepareFullPage(fullPage);startActivity(fullPage);finish();});
        controls.addView(expand,new LinearLayout.LayoutParams(px(62),px(44)));
        TextView close=control("×","关闭工具");close.setTextSize(26);close.setOnClickListener(v->finish());
        controls.addView(close,new LinearLayout.LayoutParams(px(44),px(44)));
        panel.addView(controls);panel.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        super.setContentView(panel);
        configureWindow();
    }

    private TextView control(String label,String description){
        TextView v=new TextView(this);v.setText(label);v.setTextSize(14);v.setGravity(Gravity.CENTER);
        v.setTextColor(dark?0xFFE5EDE3:0xFF203B2A);v.setContentDescription(description);return v;
    }

    /** Tools can carry current form values when switching presentation. */
    protected void prepareFullPage(Intent intent){}

    private boolean drag(MotionEvent event){
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:dragStart=event.getRawY();return true;
            case MotionEvent.ACTION_MOVE:panel.setTranslationY(Math.max(0,event.getRawY()-dragStart));return true;
            case MotionEvent.ACTION_UP:
                if(panel.getTranslationY()>px(90)){finish();return true;}
                panel.animate().translationY(0).setDuration(150).start();return true;
            case MotionEvent.ACTION_CANCEL:panel.animate().translationY(0).setDuration(150).start();return true;
            default:return false;
        }
    }

    private void configureWindow(){
        Window window=getWindow();window.setGravity(Gravity.BOTTOM);window.setBackgroundDrawableResource(android.R.color.transparent);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.38f);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        android.graphics.Rect bounds=getWindowManager().getCurrentWindowMetrics().getBounds();
        android.graphics.Insets safe=getWindowManager().getCurrentWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
        int height=(int)((bounds.height()-safe.top-safe.bottom)*.88f);
        window.setLayout(Math.min(bounds.width()-safe.left-safe.right,px(720)),height);
        boolean reduced=getSharedPreferences("home",0).getBoolean("reduceMotion",false);
        window.setWindowAnimations(reduced?0:R.style.ToolDrawerAnimation);
    }
    @Override protected void onStart(){super.onStart();if(drawer)configureWindow();}
}
