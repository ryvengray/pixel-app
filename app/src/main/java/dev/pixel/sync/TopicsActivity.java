package dev.pixel.sync;

import android.app.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;

/** User-controlled, time-bounded topics; remembering never silently enables follow-up. */
public final class TopicsActivity extends ToolActivity {
    private TopicRepository topics;
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private LinearLayout list;
    private TextView status;
    private boolean closed,dark,syncing;
    private int foreground,muted,surface;
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(foreground);return v;}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);
        dark=getIntent().getBooleanExtra("dark",false);
        foreground=Color.parseColor(dark?"#E5EDE3":"#263C31");muted=Color.parseColor(dark?"#A5B9AA":"#59715F");surface=Color.parseColor(dark?"#243C2E":"#DEE9D8");topics=new TopicRepository(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(12),dp(20),dp(16));root.setBackgroundColor(Color.parseColor(dark?"#102019":"#F0F5E9"));
        TextView back=text("‹  重要话题",22);back.setPadding(0,dp(12),0,dp(14));back.setOnClickListener(v->finish());root.addView(back);
        TextView intro=text("她记住的具体事情，有原话和时间依据。记住后默认不追问；你可以约一个时间，让她关心一次。",14);intro.setTextColor(muted);root.addView(intro);
        status=text("点击这里同步",13);status.setTextColor(muted);status.setPadding(0,dp(12),0,dp(12));status.setOnClickListener(v->sync());root.addView(status);
        ScrollView scroll=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        WindowInsetsController bars=getWindow().getInsetsController();if(bars!=null)bars.setSystemBarsAppearance(dark?0:WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        render();sync();
    }
    private String when(String raw){try{return OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));}catch(Exception e){return "";}}
    private String state(JSONObject row){switch(row.optString("state")){case "paused":return "已暂停";case "resolved":return "已聊完";case "expired":return "话题已过期";case "forgotten":return "忘记待同步";default:
        if("declined".equals(row.optString("consent")))return "不再主动追问";
        if("allowed".equals(row.optString("consent")))return "sent".equals(row.optString("followup_status"))?"已经关心过一次":("下次关心 · "+when(row.optString("followup_at"))+((row.optInt("notify",0)!=0||row.optBoolean("notify"))?" · 系统通知":" · 仅首页"));return "已记住 · 不主动追问";}}
    private void render(){list.removeAllViews();try{
        JSONArray rows=topics.all();int visible=0;
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String id=row.getString("id");boolean pending=topics.pending(id);if("forgotten".equals(row.optString("state"))&&!pending)continue;visible++;
            LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(16),dp(14),dp(16),dp(12));GradientDrawable bg=new GradientDrawable();bg.setColor(surface);bg.setCornerRadius(dp(20));card.setBackground(bg);
            card.addView(text(row.optString("title"),18));TextView meta=text(state(row)+(pending?" · 待同步":""),12);meta.setTextColor(muted);meta.setPadding(0,dp(6),0,dp(8));card.addView(meta);
            if(!row.optString("source_quote").isEmpty()){TextView source=text("你说过：\n“"+row.optString("source_quote")+"”",14);source.setTextIsSelectable(true);card.addView(source);TextView time=text("记录于 "+when(row.optString("created_at")),12);time.setTextColor(muted);card.addView(time);}
            Button manage=new Button(this);manage.setText(topics.conflict(id)?"处理同步冲突":"管理这件事");manage.setOnClickListener(v->{if(topics.conflict(id))conflict(row);else manage(row);});card.addView(manage);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(14);list.addView(card,p);
        }
        if(visible==0){TextView empty=text("还没有重要话题。聊天里提到具体的重要事情后，她会在后台整理；普通寒暄和一时心情不会自动变成长期标签。",15);empty.setPadding(0,dp(24),0,0);list.addView(empty);}
    }catch(Exception e){status.setText("话题暂时无法读取");}}
    private void manage(JSONObject row){new AlertDialog.Builder(this).setTitle(row.optString("title")).setItems(new String[]{"修正话题名称","选个时间关心我一次","不再主动追问","先放一放","这件事聊完了","忘记这件事"},(d,which)->{
        try{switch(which){case 0:rename(row);break;case 1:chooseTime(row);break;case 2:save(row,new JSONObject().put("consent","declined"));break;case 3:save(row,new JSONObject().put("state","paused").put("consent","none"));break;case 4:save(row,new JSONObject().put("state","resolved").put("consent","none"));break;case 5:new AlertDialog.Builder(this).setTitle("忘记这件事？").setMessage("不再用于话题记忆和主动跟进。原聊天记录仍可在历史中查看。").setNegativeButton("保留",null).setPositiveButton("忘记",(x,w)->{try{save(row,new JSONObject().put("state","forgotten").put("consent","declined"));}catch(Exception e){status.setText("操作未保存，请重试");}}).show();}}
        catch(Exception e){status.setText("操作未保存，请重试");}
    }).show();}
    private void rename(JSONObject row){EditText edit=new EditText(this);edit.setText(row.optString("title"));new AlertDialog.Builder(this).setTitle("修正名称，原话仍保留").setView(edit).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{try{String value=edit.getText().toString().trim();if(value.isEmpty()||value.length()>100){status.setText("名称需为 1–100 个字");return;}save(row,new JSONObject().put("title",value));}catch(Exception e){status.setText("操作未保存");}}).show();}
    private void chooseTime(JSONObject row){java.time.ZonedDateTime now=java.time.ZonedDateTime.now();new DatePickerDialog(this,(view,y,m,day)->new TimePickerDialog(this,(time,hour,minute)->{
        ZonedDateTime due=LocalDateTime.of(y,m+1,day,hour,minute).atZone(ZoneId.systemDefault());if(!due.isAfter(ZonedDateTime.now())){status.setText("请选择未来的时间");return;}
        new AlertDialog.Builder(this).setTitle("允许她在这个时间关心一次").setMessage(due.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))+"\n可只在首页出现，也可以发送系统通知。").setNegativeButton("取消",null).setNeutralButton("仅首页",(d,w)->schedule(row,due,false)).setPositiveButton("系统通知",(d,w)->schedule(row,due,true)).show();
    },now.getHour(),now.getMinute(),true).show(),now.getYear(),now.getMonthValue()-1,now.getDayOfMonth()).show();}
    private void schedule(JSONObject row,ZonedDateTime due,boolean notify){try{save(row,new JSONObject().put("state","open").put("consent","allowed").put("followup_at",due.toOffsetDateTime().toString()).put("notify",notify));}catch(Exception e){status.setText("操作未保存");}}
    private void save(JSONObject row,JSONObject change)throws Exception{topics.edit(row.getString("id"),change);render();status.setText("已保存在本机，正在同步…");sync();}
    private void conflict(JSONObject row){new AlertDialog.Builder(this).setTitle("这件事有另一个版本").setMessage("本机修改仍然保留。请选择要采用的版本。").setNegativeButton("稍后",null).setNeutralButton("使用服务端版本",(d,w)->resolve(row,false)).setPositiveButton("保留本机修改",(d,w)->resolve(row,true)).show();}
    private void resolve(JSONObject row,boolean local){network.execute(()->{try{topics.resolve(row.getString("id"),local);topics.sync();runOnUiThread(()->{if(!closed){render();status.setText("已同步");}});}catch(Exception e){runOnUiThread(()->{if(!closed)status.setText(e.getMessage());});}});}
    private void sync(){if(syncing||closed)return;syncing=true;network.execute(()->{String error=null;try{topics.sync();}catch(Exception e){error=(e.getMessage()!=null&&e.getMessage().contains("连接助理服务")?e.getMessage():"修改已保存在本机，暂未同步；点击重试。")+ (e.getMessage()!=null&&e.getMessage().contains("冲突")?" 有同步冲突，请在话题中处理。":"");}final String value=error;runOnUiThread(()->{syncing=false;if(!closed){render();status.setText(value==null?"已同步 · 点击刷新":value);}});});}
    @Override public void onDestroy(){closed=true;network.execute(topics::close);network.shutdown();super.onDestroy();}
}
