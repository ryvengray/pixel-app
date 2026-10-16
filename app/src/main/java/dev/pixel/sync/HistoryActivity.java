package dev.pixel.sync;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Read-only full history, paged without losing the original sends or tool outcomes. */
public final class HistoryActivity extends Activity {
    private AssistantStore store;
    private LinearLayout records;
    private TextView more;
    private long before=Long.MAX_VALUE;
    private int foreground,surface;
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);boolean dark=getIntent().getBooleanExtra("dark",false);
        foreground=Color.parseColor(dark?"#E5EDE3":"#263C31");surface=Color.parseColor(dark?"#243C2E":"#DEE9D8");store=new AssistantStore(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(12),dp(20),dp(16));root.setBackgroundColor(Color.parseColor(dark?"#102019":"#F0F5E9"));
        TextView back=new TextView(this);back.setText("‹  历史记录");back.setTextSize(20);back.setTextColor(foreground);back.setPadding(0,dp(12),0,dp(18));back.setOnClickListener(v->finish());root.addView(back);
        ScrollView scroll=new ScrollView(this);records=new LinearLayout(this);records.setOrientation(LinearLayout.VERTICAL);scroll.addView(records);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        more=new TextView(this);more.setText("查看更早的记录");more.setTextColor(foreground);more.setGravity(Gravity.CENTER);more.setPadding(0,dp(15),0,dp(15));more.setOnClickListener(v->load());records.addView(more);
        setContentView(root);load();scroll.post(()->scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }
    private void load(){List<AssistantStore.Message> page=store.messagesBefore(before,50);if(page.isEmpty()){more.setText(before==Long.MAX_VALUE?"还没有聊天记录":"已经是最早的记录");more.setEnabled(false);return;}
        before=page.get(0).id;int index=1;SimpleDateFormat date=new SimpleDateFormat("MM-dd HH:mm",Locale.getDefault());
        for(AssistantStore.Message m:page){TextView row=new TextView(this);row.setTextColor(foreground);row.setTextSize(15);row.setTextIsSelectable(true);row.setPadding(dp(15),dp(12),dp(15),dp(12));
            String who=m.role.equals("user")?"我":m.role.equals("tool")?"工具与结果":"Greta";
            row.setText(who+" · "+date.format(new Date(m.createdAt))+(m.status.equals("sent")?"":" · 未确认发送")+"\n\n"+m.content);
            GradientDrawable bg=new GradientDrawable();bg.setColor(surface);bg.setCornerRadius(dp(18));row.setBackground(bg);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(10);records.addView(row,index++,p);
        }
    }
    @Override public void onDestroy(){store.close();super.onDestroy();}
}
