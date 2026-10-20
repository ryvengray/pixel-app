package dev.pixel.sync;

import android.app.AlertDialog;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.time.LocalDate;
import java.util.*;

/** One book for diary entries and optional multiple income/expense items. */
public final class RecordsActivity extends ToolActivity {
    private final Map<String,TextView> rowStates=new HashMap<>();
    private LinearLayout list;private TextView state;private LifeRecords records;private boolean dark;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable(){public void run(){renderState();for(LifeRecords.Record r:records.all(false)){TextView label=rowStates.get(r.id);if(label!=null)label.setText(r.state()+" · 点击修改，长按删除");}handler.postDelayed(this,1000);}};
    private int fg,muted,accent;
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(fg);v.setPadding(0,dp(7),0,dp(7));return v;}
    private Button button(String value,Runnable action){Button v=new Button(this);v.setText(value);v.setAllCaps(false);v.setTextColor(accent);v.setTextSize(15);v.setMinHeight(dp(48));v.setPadding(dp(12),dp(8),dp(12),dp(8));v.setBackground(shape(Color.parseColor(dark?"#244631":"#DFEFDE")));v.setOnClickListener(w->action.run());return v;}
    private GradientDrawable shape(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(18));return d;}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);records=new LifeRecords(this);
        int theme=getSharedPreferences("home",0).getInt("theme",0);dark=theme==2||(theme==0&&(getResources().getConfiguration().uiMode&48)==32);
        fg=Color.parseColor(dark?"#E5EDE3":"#203B2A");muted=Color.parseColor(dark?"#A7BBAA":"#6C8271");accent=Color.parseColor(dark?"#8BCB9A":"#327C4E");
        ScrollView scroll=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(1);body.setPadding(dp(22),dp(14),dp(22),dp(28));body.setBackgroundColor(Color.parseColor(dark?"#102019":"#F2F6EE"));scroll.addView(body);
        TextView title=text("生活记录",26);body.addView(title);TextView intro=text("把今天留在这里，也把每一笔记清楚。",14);intro.setTextColor(muted);body.addView(intro);
        state=text("",13);state.setTag("records_sync_state");state.setTextColor(muted);body.addView(state);
        LinearLayout actions=new LinearLayout(this);actions.addView(button("写一条",()->edit(null)),new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams settingsPosition=new LinearLayout.LayoutParams(0,-2,1);settingsPosition.leftMargin=dp(8);actions.addView(button("Obsidian 设置",this::settings),settingsPosition);body.addView(actions);
        list=new LinearLayout(this);list.setOrientation(1);body.addView(list);setContentView(scroll);render();
    }
    private void renderState(){
        String tree=getSharedPreferences("life_sync",0).getString("tree",""),error=getSharedPreferences("life_sync",0).getString("error","");
        state.setText(tree.isEmpty()?"保存在手机 · 可连接 Obsidian 自动导出":getSharedPreferences("sync",0).getBoolean("running",false)?"保存在手机 · Obsidian 正在同步":!error.isEmpty()?error:records.pendingExport()?"保存在手机 · 等待导出到 Obsidian":records.pendingSync()?"保存在手机 · 等待同步到 Obsidian":"保存在手机 · Obsidian 已同步");
    }
    private void render(){
        renderState();rowStates.clear();list.removeAllViews();List<LifeRecords.Record> rows=records.all(false);
        if(rows.isEmpty())list.addView(text("试着对 Greta 说：\n记一下我今天加了油 400 块\n\n也可以只记日记，不填金额。",16));
        for(LifeRecords.Record r:rows){
            LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.setPadding(dp(16),dp(12),dp(16),dp(10));card.setBackground(shape(Color.parseColor(dark?"#1C3326":"#FFFFFF")));
            TextView day=text(r.day,13);day.setTextColor(muted);card.addView(day);card.addView(text(r.payload.optString("content"),17));String summary=LifeRecords.summary(r.payload);if(!summary.isEmpty()){TextView money=text(summary,14);money.setTextColor(accent);card.addView(money);}
            TextView status=text(r.state()+" · 点击修改，长按删除",12);status.setTextColor(muted);card.addView(status);rowStates.put(r.id,status);
            card.setOnClickListener(v->edit(r));card.setOnLongClickListener(v->{new AlertDialog.Builder(this).setTitle("删除这条记录？").setMessage("手机保留撤销机会，Obsidian 中会标记为已删除。").setNegativeButton("保留",null).setPositiveButton("删除",(d,w)->{try{records.update(r.id,r.payload,true);LifeSync.schedule(this);render();new AlertDialog.Builder(this).setMessage("记录已删除").setPositiveButton("撤销",(dd,ww)->{try{records.update(r.id,r.payload,false);LifeSync.schedule(this);render();}catch(Exception e){error(e);}}).setNegativeButton("关闭",null).show();}catch(Exception e){error(e);}}).show();return true;});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(12);list.addView(card,lp);
        }
    }
    private void settings(){
        boolean configured=!getSharedPreferences("life_sync",0).getString("tree","").isEmpty();
        new AlertDialog.Builder(this).setTitle("Obsidian 自动同步")
            .setMessage("先保存到手机，再后台导出并同步。请选择与 Termux 相同的 Documents/obsidian 目录。\n\n文件保存在「Greta 生活记录」中。请在 App 修改记录；Obsidian 中的手动改动会保留并暂停覆盖。\n\n首次需在同步工具中授予 Termux 运行权限。现有 Git 同步会提交仓库的所有更改。")
            .setPositiveButton(configured?"重新选择目录":"选择仓库目录",(d,w)->chooseVault())
            .setNeutralButton("同步 / 授权",(d,w)->{LifeSync.schedule(this);startActivity(DecisionActions.intent(this,"open_sync",new JSONObject()));})
            .setNegativeButton(configured?"断开自动同步":"稍后",(d,w)->{if(configured){getSharedPreferences("life_sync",0).edit().remove("tree").remove("error").commit();getSystemService(android.app.job.JobScheduler.class).cancel(LifeSync.EXPORT_JOB);getSystemService(android.app.job.JobScheduler.class).cancel(LifeSync.NETWORK_JOB);render();}}).show();
    }
    private void chooseVault(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADocuments%2Fobsidian"));startActivityForResult(intent,41);
    }
    @Override public void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=41||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri tree=data.getData();if(!VaultExport.correctTree(tree)){Toast.makeText(this,"请选择 Documents/obsidian，与现有同步脚本保持一致",Toast.LENGTH_LONG).show();return;}
        try{
            int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if(flags!=(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION))throw new SecurityException("需要读写权限");
            getContentResolver().takePersistableUriPermission(tree,flags);getSharedPreferences("life_sync",0).edit().putString("tree",tree.toString()).remove("error").commit();LifeSync.schedule(this);render();
        }catch(Exception e){error(e);}
    }
    private EditText field(LinearLayout body,String hint,String value,boolean multiline){
        EditText input=new EditText(this);input.setTextColor(fg);input.setHintTextColor(muted);input.setHint(hint);input.setText(value);input.setTextSize(16);input.setContentDescription(hint);input.setPadding(dp(12),dp(12),dp(12),dp(12));GradientDrawable border=shape(Color.parseColor(dark?"#1C3326":"#FFFFFF"));border.setStroke(dp(1),Color.parseColor(dark?"#406651":"#CADBC7"));input.setBackground(border);input.setSingleLine(!multiline);if(multiline){input.setMinLines(3);input.setMaxLines(8);input.setGravity(Gravity.TOP);}LinearLayout.LayoutParams fieldParams=new LinearLayout.LayoutParams(-1,-2);fieldParams.bottomMargin=dp(10);body.addView(input,fieldParams);return input;
    }
    private void edit(LifeRecords.Record record){
        LinearLayout body=new LinearLayout(this);body.setPadding(dp(16),dp(8),dp(16),dp(12));body.setOrientation(1);ScrollView scroll=new ScrollView(this);scroll.addView(body);
        EditText day=field(body,"日期，例如 2026-10-20",record==null?LocalDate.now().toString():record.day,false);
        EditText content=field(body,"今天发生了什么…",record==null?"":record.payload.optString("content"),true);
        List<MoneyRow> rows=new ArrayList<>();LinearLayout money=new LinearLayout(this);money.setOrientation(1);body.addView(money);
        JSONArray existing=record==null?new JSONArray():record.payload.optJSONArray("items");if(existing!=null)for(int i=0;i<existing.length();i++)addMoney(money,rows,existing.optJSONObject(i));
        body.addView(button("＋ 添加一笔收支",()->{if(rows.size()<30)addMoney(money,rows,null);}));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(record==null?"写一条生活记录":"修改记录").setView(scroll).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            JSONArray items=new JSONArray();for(MoneyRow row:rows)items.put(new JSONObject().put("category",row.category.getText().toString().trim()).put("amount",row.amount.getText().toString().trim()).put("kind",row.kind.getSelectedItemPosition()==0?"expense":"income").put("currency",row.currency.getSelectedItem().toString()));
            JSONObject payload=new JSONObject().put("content",content.getText().toString().trim()).put("date",day.getText().toString().trim()).put("items",items);
            if(record==null)records.save(UUID.randomUUID().toString(),payload,content.getText().toString());else records.update(record.id,payload,false);
            LifeSync.schedule(this);dialog.dismiss();render();
        }catch(Exception e){error(e);}}));dialog.show();
    }
    private static final class MoneyRow {EditText category,amount;Spinner kind,currency;}
    private void addMoney(LinearLayout parent,List<MoneyRow> rows,JSONObject value){
        MoneyRow row=new MoneyRow();LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(0,dp(10),0,dp(10));parent.addView(box);
        row.category=field(box,"用途，例如 加油",value==null?"":value.optString("category"),false);row.amount=field(box,"金额",value==null?"":value.optString("amount"),false);row.amount.setInputType(8194);
        LinearLayout controls=new LinearLayout(this);row.kind=new Spinner(this);row.kind.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"支出","收入"}));row.kind.setSelection(value!=null&&value.optString("kind").equals("income")?1:0);controls.addView(row.kind,new LinearLayout.LayoutParams(0,dp(48),1));
        row.currency=new Spinner(this);String[] currencies={"CNY","USD","EUR","JPY","HKD","GBP"};row.currency.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,currencies));if(value!=null)row.currency.setSelection(Math.max(0,Arrays.asList(currencies).indexOf(value.optString("currency"))));controls.addView(row.currency,new LinearLayout.LayoutParams(0,dp(48),1));
        controls.addView(button("移除",()->{rows.remove(row);parent.removeView(box);}));box.addView(controls);rows.add(row);
    }
    private void error(Exception e){Toast.makeText(this,e.getMessage()==null?"操作未完成，请重试":e.getMessage(),Toast.LENGTH_LONG).show();}
    @Override public void onResume(){super.onResume();render();LifeSync.schedule(this);handler.post(refresh);}
    @Override public void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    @Override public void onDestroy(){handler.removeCallbacks(refresh);records.close();super.onDestroy();}
}
