package dev.pixel.sync;

import android.app.*;
import android.os.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public class MemoryActivity extends Activity {
    private AssistantStore store;private LinearLayout list;private final ExecutorService network=Executors.newSingleThreadExecutor();
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle state){super.onCreate(state);store=new AssistantStore(this);ScrollView scroll=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(dp(20),dp(26),dp(20),dp(34));scroll.addView(list);setContentView(scroll);render();}
    private void render(){list.removeAllViews();TextView title=new TextView(this);title.setText("Gray 记得的你");title.setTextSize(25);list.addView(title);TextView help=new TextView(this);help.setText("这些是手机上的完整长期记忆。你可以修正或忘掉它们；删除会在服务端恢复连接后同步。\n");list.addView(help);Button add=new Button(this);add.setText("手动添加记忆");add.setOnClickListener(v->add());list.addView(add);List<AssistantStore.Memory> memories=store.memories(false);if(memories.isEmpty()){TextView empty=new TextView(this);empty.setText("还没有长期记忆。聊天中提到稳定偏好、目标或明确事实时，Gray 会谨慎记住。 ");list.addView(empty);return;}for(AssistantStore.Memory memory:memories)addMemory(memory);}
    private void add(){EditText input=new EditText(this);input.setHint("例如：我喜欢在晚上处理需要专注的事");new AlertDialog.Builder(this).setTitle("添加长期记忆").setView(input).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{String fact=input.getText().toString().trim();if(fact.isEmpty())return;AssistantStore.Memory created=store.updateMemory(java.util.UUID.randomUUID().toString(),"other",fact,1,"用户手动添加",false);sync(created);render();}).show();}
    private void addMemory(AssistantStore.Memory memory){LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(14),dp(12),dp(14),dp(12));TextView fact=new TextView(this);fact.setText(memory.fact);fact.setTextSize(17);card.addView(fact);TextView details=new TextView(this);details.setText(memory.category+" · 可信度 "+Math.round(memory.confidence*100)+"%\n"+(memory.source==null?"":memory.source));details.setTextSize(12);card.addView(details);LinearLayout actions=new LinearLayout(this);Button edit=new Button(this);edit.setText("修正");edit.setOnClickListener(v->edit(memory));actions.addView(edit);Button forget=new Button(this);forget.setText("忘掉");forget.setOnClickListener(v->forget(memory));actions.addView(forget);card.addView(actions);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(10);card.setLayoutParams(p);list.addView(card);}
    private void edit(AssistantStore.Memory memory){EditText input=new EditText(this);input.setText(memory.fact);new AlertDialog.Builder(this).setTitle("修正记忆").setView(input).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{String fact=input.getText().toString().trim();if(fact.isEmpty())return;AssistantStore.Memory updated=store.updateMemory(memory.id,memory.category,fact,memory.confidence,"用户修正",false);sync(updated);render();}).show();}
    private void forget(AssistantStore.Memory memory){new AlertDialog.Builder(this).setTitle("忘掉这条记忆？").setMessage(memory.fact).setNegativeButton("保留",null).setPositiveButton("忘掉",(d,w)->{AssistantStore.Memory removed=store.updateMemory(memory.id,memory.category,memory.fact,memory.confidence,"用户删除",true);sync(removed);render();}).show();}
    private void sync(AssistantStore.Memory memory){network.execute(()->{try{AssistantApi.syncMemory(this,memory);}catch(Exception ignored){}});}
    @Override protected void onDestroy(){network.shutdownNow();super.onDestroy();}
}
