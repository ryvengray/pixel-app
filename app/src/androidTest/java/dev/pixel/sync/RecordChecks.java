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
    private List<AssistantStore.Message> confirmed(String offer,String answer,String id){return Arrays.asList(new AssistantStore.Message(0,"user","今天加了340块钱的油，现在的里程是 61978 公里",1,"original-record","sent"),new AssistantStore.Message("assistant",offer,2),new AssistantStore.Message(0,"user",answer,3,id,"pending"));}
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
            check("contextualConfirmationGuardsAndOriginalEvidence",()->{
                String offer="要我把这笔记进生活记录吗？\n\n- 油费：340元\n- 当前里程：61978公里";
                for(String answer:new String[]{"好","好的。","可以","确认"}){String evidence=ClientTools.recordEvidence(confirmed(offer,answer,"confirm"));expect(evidence!=null&&evidence.contains("340")&&evidence.contains("61978"),"confirmation lost original facts "+answer);}
                for(String answer:new String[]{"不要","不用了","先不记","可以吗？","好，但别保存","好，340改成400","好，打印一下"})expect(ClientTools.recordEvidence(confirmed(offer,answer,"confirm"))==null,"ambiguous or negative confirmation accepted "+answer);
                for(String question:new String[]{"要我提醒你吗？","要记入生活记录还是打印？","已经记进生活记录了。要我打印吗？","要我把生日加到重要日期吗？"})expect(ClientTools.recordEvidence(confirmed(question,"好","confirm"))==null,"unrelated offer allowed write "+question);
                List<AssistantStore.Message> stale=new ArrayList<>(confirmed(offer,"换个话题","confirm"));stale.add(new AssistantStore.Message("assistant","今天过得好吗？",4));stale.add(new AssistantStore.Message("user","好",5));expect(ClientTools.recordEvidence(stale)==null,"stale offer allowed write");
                expect(ClientTools.recordEvidence(Arrays.asList(new AssistantStore.Message("user","记一下加油340元",1),new AssistantStore.Message("user","不要记了",2)))==null,"later refusal ignored");
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
            check("locationSnapshotPersistenceAndExport",()->{
                android.location.Location fix=new android.location.Location("gps");fix.setLatitude(31.2304);fix.setLongitude(121.4737);fix.setAccuracy(12);fix.setTime(System.currentTimeMillis());fix.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                JSONObject location=RecordLocation.snapshot(fix);expect(location!=null,"fresh fix rejected");String id=id();
                store.save(id,args(),"记录位置",location);
                try(LifeRecords reopened=new LifeRecords(context,"record-tests.db")){
                    LifeRecords.Record r=reopened.find(id);expect(r.location.getDouble("latitude")==31.2304,"location lost after restart");
                    expect(r.location.getLong("captured_at")==fix.getTime()&&r.location.getDouble("accuracy_m")==12,"fix metadata lost");
                    expect(LifeRecords.markdown(r).contains("31.230400, 121.473700"),"export lost coordinates");
                    reopened.update(id,args().put("content","修改后"),false);reopened.save(id,args(),"重试",null);
                    expect(reopened.find(id).location.toString().equals(location.toString()),"edit/retry replaced original location");
                    reopened.update(id,args(),true);expect(!LifeRecords.markdown(reopened.find(id)).contains("31.230400"),"deleted export retained location");
                    reopened.update(id,args(),false);expect(reopened.find(id).location!=null,"undo lost location");
                }
                fix.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos()-java.util.concurrent.TimeUnit.MINUTES.toNanos(3));expect(RecordLocation.snapshot(fix)==null,"stale fix accepted");
                expect(RecordLocation.snapshot(null)==null,"missing fix accepted");
                expect(!LifeRecords.valid(args().put("location",location)),"model supplied location accepted");
            });
            check("versionFiveLocationMigration",()->{
                String database="record-location-migration.db";context.deleteDatabase(database);
                try{
                    try(AssistantStore old=new AssistantStore(context,database)){
                        SQLiteDatabase db=old.getWritableDatabase();db.execSQL("ALTER TABLE life_record DROP COLUMN location");db.setVersion(5);
                        db.execSQL("INSERT INTO life_record(id,day,timezone,payload,original,created) VALUES(?,?,?,?,?,?)",new Object[]{"old","2026-10-20","Asia/Shanghai",args().toString(),"原文",1});
                    }
                    try(LifeRecords upgraded=new LifeRecords(context,database)){expect(upgraded.find("old").location==null,"old entry acquired location");expect(upgraded.find("old").original.equals("原文"),"migration lost record");upgraded.save("new",args(),"新记录",new JSONObject().put("latitude",1).put("longitude",2));expect(upgraded.find("new").location!=null,"new location not persisted");}
                }finally{context.deleteDatabase(database);}
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
            check("httpConfirmationPersistsAmountsMileageAndRetryIsLocal",()->{
                String id=id();SharedPreferences settings=context.getSharedPreferences("gray_settings",0),secure=context.getSharedPreferences("gray_secure",0);
                String oldUrl=settings.getString("server_url",null),oldToken=secure.getString("device_token",null);
                JSONObject payload=new JSONObject().put("content","今天加油340元，当前里程61978公里").put("items",new JSONArray().put(new JSONObject().put("amount","340").put("currency","CNY").put("kind","expense").put("category","油费")));
                try(RecordAgent agent=new RecordAgent(id,payload)){
                    settings.edit().putString("server_url","http://127.0.0.1:"+agent.server.getLocalPort()).commit();new SecureStore(context).put("device_token","record-test-token");
                    List<AssistantStore.Message> turns=confirmed("要我把这笔记进生活记录吗？\n\n- 油费：340元\n- 当前里程：61978公里","好",id);
                    AssistantApi.Reply reply=AssistantApi.chat(context,turns,Collections.emptyList(),id);expect(reply.content.contains("已记录")&&reply.clientAction==null,"confirmation did not commit");
                    try(LifeRecords local=new LifeRecords(context)){LifeRecords.Record saved=local.find(id);expect(saved!=null,"success before commit");expect(saved.original.contains("61978")&&saved.original.contains("340"),"only saved affirmative without original evidence");expect(saved.payload.getString("content").contains("61978"),"mileage dropped");expect(saved.payload.getJSONArray("items").getJSONObject(0).getString("amount").equals("340"),"fuel amount lost");}
                    AssistantApi.chat(context,turns,Collections.emptyList(),id);expect(agent.calls.get()==1,"confirmation retry called model again");
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
