package dev.pixel.sync;

import android.app.*;
import android.appwidget.AppWidgetManager;
import android.content.*;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Bundle;
import android.view.WindowInsets;
import android.widget.*;

public class QuickActionsWidgetSettingsActivity extends Activity {
    private int widgetId;
    private final String[] selected=new String[6];
    @Override public void onCreate(Bundle state){
        super.onCreate(state);setResult(RESULT_CANCELED);
        widgetId=getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID);
        if(!QuickActionsWidget.owned(this,widgetId)){finish();return;}
        getWindow().setDecorFitsSystemWindows(false);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(Color.rgb(241,246,236));
        scroll.setOnApplyWindowInsetsListener((view,insets)->{Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());view.setPadding(safe.left,safe.top,safe.right,safe.bottom);return WindowInsets.CONSUMED;});
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);int padding=Math.round(20*getResources().getDisplayMetrics().density);body.setPadding(padding,padding,padding,padding);scroll.addView(body);
        TextView title=new TextView(this);title.setText("你的 Greta 快捷面板");title.setTextSize(24);title.setTextColor(Color.rgb(35,77,59));body.addView(title);
        TextView intro=new TextView(this);intro.setText("为六个位置选择常用功能。点击微件右上角的「自定义」可随时更换，每个微件独立设置。");intro.setPadding(0,padding,0,padding);body.addView(intro);
        for(int i=0;i<selected.length;i++){
            final int slot=i;
            selected[i]=state!=null?state.getString("slot_"+i,QuickActionsWidget.DEFAULTS[i]):QuickActionsWidget.prefs(this,widgetId).getString("slot_"+i,QuickActionsWidget.DEFAULTS[i]);
            Button button=new Button(this);button.setAllCaps(false);button.setMinHeight(Math.round(56*getResources().getDisplayMetrics().density));
            Runnable label=()->button.setText("位置 "+(slot+1)+"  ·  "+QuickActionsWidget.LABELS[QuickActionsWidget.index(selected[slot])]);label.run();
            button.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("选择快捷功能").setSingleChoiceItems(QuickActionsWidget.LABELS,QuickActionsWidget.index(selected[slot]),(dialog,index)->{selected[slot]=QuickActionsWidget.KEYS[index];label.run();dialog.dismiss();}).setNegativeButton("取消",null).show());body.addView(button);
        }
        Button save=new Button(this);save.setText("保存快捷面板");save.setOnClickListener(v->{if(!QuickActionsWidget.owned(this,widgetId)){finish();return;}SharedPreferences.Editor edit=QuickActionsWidget.prefs(this,widgetId).edit();for(int i=0;i<selected.length;i++)edit.putString("slot_"+i,selected[i]);edit.apply();QuickActionsWidget.update(this,widgetId);setResult(RESULT_OK,new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widgetId));finish();});body.addView(save);
        setContentView(scroll);scroll.requestApplyInsets();
    }
    @Override protected void onSaveInstanceState(Bundle state){for(int i=0;i<selected.length;i++)state.putString("slot_"+i,selected[i]);super.onSaveInstanceState(state);}
}
