package dev.pixel.sync;

import android.app.*;
import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import org.json.*;
import java.util.*;

/** Real SQLite, fake SAF vault, actual HTTP boundary. No Termux or real LLM. */
final class RecordChecks {
    private final Instrumentation test;private int passed;private Context context;private LifeRecords store;private final List<String> ids=new ArrayList<>();
    RecordChecks(Instrumentation test){this.test=test;}
    private interface Check{void run()throws Exception;}
    private void check(String name,Check check)throws Exception{check.run();passed++;Bundle status=new Bundle();status.putString("test",name);status.putInt("current",passed);test.sendStatus(0,status);}
    private static void expect(boolean value,String message){if(!value)throw new AssertionError(message);}
    private String id(){String value="record-test-"+UUID.randomUUID();ids.add(value);return value;}
    private JSONObject args()throws Exception{return new JSONObject("{\"content\":\"今天加油、买水\",\"date\":\"2026-10-20\",\"items\":[{\"amount\":\"400\",\"currency\":\"CNY\",\"kind\":\"expense\",\"category\":\"加油\"},{\"amount\":\"5.50\",\"currency\":\"CNY\",\"kind\":\"expense\",\"category\":\"饮料\"}]}");}
    private static final class RecordAgent implements AutoCloseable {
        final java.net.ServerSocket server=new java.net.ServerSocket(0,10,java.net.InetAddress.getByName("127.0.0.1"));
        final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        RecordAgent(String id,JSONObject args)throws Exception{worker.execute(()->{while(!server.isClosed())try(java.net.Socket socket=server.accept()){
            socket.setSoTimeout(5000);java.io.InputStream in=socket.getInputStream();java.io.ByteArrayOutputStream head=new java.io.ByteArrayOutputStream();int tail=0;
            while(head.size()<16000){int n=in.read();if(n<0)throw new java.io.EOFException();head.write(n);tail=(tail<<8)|n;if(tail==0x0d0a0d0a)break;}
            int length=0;for(String line:head.toString("UTF-8").split("\r\n"))if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
            byte[] body=new byte[length];int at=0;while(at<length){int n=in.read(body,at,length-at);if(n<0)throw new java.io.EOFException();at+=n;}
            JSONObject request=new JSONObject(new String(body,java.nio.charset.StandardCharsets.UTF_8));boolean advertised=false;JSONArray tools=request.getJSONArray("client_tools");for(int i=0;i<tools.length();i++)if(tools.getString(i).equals("create_life_record"))advertised=true;
            if(!advertised)throw new AssertionError("record capability missing");calls.incrementAndGet();
            String response=new JSONObject().put("content","等待手机保存记录").put("client_action",new JSONObject().put("name","create_life_record").put("id",id).put("arguments",args)).toString();byte[] bytes=response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));socket.getOutputStream().write(bytes);
        }catch(Exception error){if(!server.isClosed())throw new RuntimeException(error);}});}
        public void close(){try{server.close();}catch(Exception ignored){}worker.shutdownNow();}
    }

    void run(){
        context=test.getTargetContext();Bundle result=new Bundle();int code=Activity.RESULT_OK;
        try{
            expect(Build.HARDWARE.equals("ranchu")||Build.HARDWARE.equals("goldfish"),"Use an independent emulator");store=new LifeRecords(context,"record-tests.db");
            check("schemaAndIntentGuards",()->{
                expect(LifeRecords.valid(args()),"multi-amount rejected");
                for(String amount:new String[]{"-1","0","1.001","1e3","NaN"}){JSONObject a=args();a.getJSONArray("items").getJSONObject(0).put("amount",amount);expect(!LifeRecords.valid(a),"invalid amount accepted "+amount);}
                JSONObject a=args();a.getJSONArray("items").getJSONObject(0).put("amount",400);expect(!LifeRecords.valid(a),"numeric float allowed");
                a=args().put("date","2026-02-30");expect(!LifeRecords.valid(a),"invalid date accepted");
                expect(!ClientTools.recordCommand("同步笔记"),"unrelated command allowed a write");
                expect(ClientTools.recordCommand("记一下我今天加了油400块"),"command rejected");expect(!ClientTools.explicitCommand("可以记一下加油400块吗"),"question accepted");expect(!ClientTools.explicitCommand("不要记一下加油400块"),"negation accepted");
            });
            check("versionThreeMigrationPreservesConversation",()->{
                String database="record-migration-tests.db";context.deleteDatabase(database);
                try{
                    try(AssistantStore old=new AssistantStore(context,database)){old.addMessage("user","迁移前的聊天");SQLiteDatabase db=old.getWritableDatabase();db.execSQL("DROP TABLE life_record");db.setVersion(3);}
                    try(AssistantStore upgraded=new AssistantStore(context,database)){expect(upgraded.recentMessages(1).get(0).content.equals("迁移前的聊天"),"migration lost conversation");try(android.database.Cursor c=upgraded.getReadableDatabase().rawQuery("SELECT count(*) FROM life_record",null)){expect(c.moveToFirst()&&c.getInt(0)==0,"record table missing");}}
                }finally{context.deleteDatabase(database);}
            });
            check("sqliteRestartRetryAndDeletion",()->{
                String id=id();store.save(id,args(),"记一下我今天加油400块买水5.50元");
                try(LifeRecords reopened=new LifeRecords(context,"record-tests.db")){LifeRecords.Record r=reopened.find(id);expect(r.payload.getJSONArray("items").length()==2,"amount lost");expect(r.original.contains("400"),"original lost");expect(r.day.equals("2026-10-20"),"date lost");reopened.update(id,r.payload,true);}
                store.save(id,args().put("content","duplicate"),"duplicate");expect(store.find(id).deleted,"retry resurrected deleted record");expect(!store.find(id).payload.getString("content").equals("duplicate"),"retry rewrote existing record");
            });
            check("diaryWithoutMoneyDefaultsToLocalToday",()->{String id=id();store.save(id,new JSONObject().put("content","今天很开心").put("items",new JSONArray()),"记日记今天很开心");expect(store.find(id).day.equals(java.time.LocalDate.now().toString()),"wrong local date");});
            check("safExportRetryAndConflict",()->{
                // Isolated db makes the fake vault independent of any prior emulator records.
                String id=id();store.save(id,args(),"original");Uri root=DocumentsContract.buildDocumentUriUsingTree(Uri.parse("content://dev.pixel.sync.records.testprovider/tree/root"),"root");
                VaultExport.exportToRoot(test.getContext(),store,root);LifeRecords.Record r=store.find(id);expect(r.exported==1,"export status missing");
                Uri file=DocumentsContract.buildDocumentUriUsingTree(root,"Greta 生活记录/"+VaultExport.hash(id).substring(0,32)+".md");String md=VaultExport.read(test.getContext(),file);expect(md.contains("400 CNY")&&md.contains("5.50 CNY"),"markdown amounts lost");VaultExport.exportToRoot(test.getContext(),store,root);expect(VaultExport.read(test.getContext(),file).equals(md),"retry changed file");
                store.beginSync();store.update(id,args().put("content","更新"),false);store.endSync(true);r=store.find(id);expect(r.synced==1&&r.revision==2,"sync acknowledged unexported edit");
                VaultExport.exportToRoot(test.getContext(),store,root);r=store.find(id);expect(r.exported==2&&r.synced==1,"edit not queued");
                try(java.io.OutputStream out=test.getContext().getContentResolver().openOutputStream(file,"wt")){out.write("Obsidian 手动修改".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
                store.update(id,args(),false);boolean blocked=false;try{VaultExport.exportToRoot(test.getContext(),store,root);}catch(java.io.IOException e){blocked=true;}expect(blocked,"external edit overwritten");expect(VaultExport.read(test.getContext(),file).equals("Obsidian 手动修改"),"external change lost");
            });
            check("httpActionCommitsBeforeSuccessAndRetryIsLocal",()->{
                String id=id();SharedPreferences settings=context.getSharedPreferences("gray_settings",0),secure=context.getSharedPreferences("gray_secure",0);
                String oldUrl=settings.getString("server_url",null),oldToken=secure.getString("device_token",null);
                try(RecordAgent agent=new RecordAgent(id,args())){
                    settings.edit().putString("server_url","http://127.0.0.1:"+agent.server.getLocalPort()).commit();new SecureStore(context).put("device_token","record-test-token");
                    List<AssistantStore.Message> turns=Collections.singletonList(new AssistantStore.Message(0,"user","记一下我今天加了油400块",System.currentTimeMillis(),id,"pending"));
                    AssistantApi.Reply reply=AssistantApi.chat(context,turns,Collections.emptyList(),id);
                    expect(reply.content.contains("已记录")&&reply.clientAction==null,"pending action leaked instead of committed reply");
                    try(LifeRecords local=new LifeRecords(context)){expect(local.find(id)!=null,"success before commit");}
                    AssistantApi.chat(context,turns,Collections.emptyList(),id);expect(agent.calls.get()==1,"retry called model again");
                }finally{
                    SharedPreferences.Editor se=settings.edit();if(oldUrl==null)se.remove("server_url");else se.putString("server_url",oldUrl);se.commit();
                    SharedPreferences.Editor token=secure.edit();if(oldToken==null)token.remove("device_token");else token.putString("device_token",oldToken);token.commit();
                    try(AssistantStore local=new AssistantStore(context)){local.getWritableDatabase().delete("life_record","id=?",new String[]{id});}
                    context.getSharedPreferences("chat_requests",0).edit().remove(id).commit();
                }
            });
            check("vaultPathAndToolPresentation",()->{
                expect(VaultExport.correctTree(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2Fobsidian")),"configured vault rejected");expect(!VaultExport.correctTree(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments")),"parent accepted");
                Activity activity=test.startActivitySync(new Intent(context,RecordsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));test.waitForIdleSync();test.runOnMainSync(()->{expect(activity.findViewById(android.R.id.content)!=null,"records UI missing");activity.finish();});
            });
            result.putString("stream","Record tests passed: "+passed+"\n");
        }catch(Throwable error){code=Activity.RESULT_CANCELED;result.putString("stream",android.util.Log.getStackTraceString(error));}
        finally{if(store!=null){store.close();context.deleteDatabase("record-tests.db");}}
        test.finish(code,result);
    }
}
