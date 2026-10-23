package dev.pixel.sync;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.os.Bundle;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Each widget instance owns its own display/privacy preferences. */
public final class CalendarWidgetSettingsActivity extends Activity {
    protected void onCreate(Bundle state){super.onCreate(state);setResult(RESULT_CANCELED);int id=getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID);if(id==AppWidgetManager.INVALID_APPWIDGET_ID||AppWidgetManager.getInstance(this).getAppWidgetInfo(id)==null||!getPackageName().equals(AppWidgetManager.getInstance(this).getAppWidgetInfo(id).provider.getPackageName())){finish();return;}
        android.content.SharedPreferences p=CalendarWidget.prefs(this,id);LinearLayout root=new LinearLayout(this);root.setOrientation(1);int pad=Math.round(24*getResources().getDisplayMetrics().density);root.setPadding(pad,pad,pad,pad);TextView title=new TextView(this);title.setText("桌面日历");title.setTextSize(24);root.addView(title);TextView explanation=new TextView(this);explanation.setText("每个组件可以独立选择主题和内容。");root.addView(explanation);Spinner theme=new Spinner(this);theme.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"跟随系统","浅色山岚","深色星夜"}));theme.setSelection(p.getInt("theme",0));root.addView(theme);Switch cycle=new Switch(this);cycle.setText("显示大姨妈内容");cycle.setChecked(p.getBoolean("cycle",false));root.addView(cycle);cycle.setVisibility(CalendarWidget.mode(this,id).equals("countdown")?android.view.View.GONE:android.view.View.VISIBLE);
        Spinner event=new Spinner(this);List<String> ids=new ArrayList<>(),names=new ArrayList<>();try(CalendarStore store=new CalendarStore(this)){for(JSONObject e:store.items("event")){ids.add(e.optString("id"));names.add(e.optString("title"));}}catch(Exception ignored){}
        if(ids.isEmpty()){ids.add("");names.add("请先在日历中添加一个重要日期");}event.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));int chosen=ids.indexOf(p.getString("event",""));if(chosen>=0)event.setSelection(chosen);event.setVisibility(CalendarWidget.mode(this,id).equals("countdown")?android.view.View.VISIBLE:android.view.View.GONE);root.addView(event);Button save=new Button(this);save.setText("添加到桌面");save.setOnClickListener(v->{synchronized(CalendarWidgetActions.class){p.edit().putInt("revision",p.getInt("revision",0)+1).putInt("theme",theme.getSelectedItemPosition()).putBoolean("cycle",cycle.isChecked()).putString("event",ids.get(event.getSelectedItemPosition())).apply();}CalendarWidget.update(this,id);CalendarUpdates.changed(this);setResult(RESULT_OK,new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id));finish();});root.addView(save);setContentView(root);
    }
}
