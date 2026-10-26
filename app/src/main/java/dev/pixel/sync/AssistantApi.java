package dev.pixel.sync;

import android.content.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Network boundary for the private service, with direct DeepSeek fallback. */
public final class AssistantApi {
    public static final class Reply { public final String content; public final List<AssistantStore.Memory> memories; public final boolean directFallback; public final String toolResults; public JSONObject clientAction; public boolean pendingClient; Reply(String c,List<AssistantStore.Memory> m,boolean d){this(c,m,d,"");} Reply(String c,List<AssistantStore.Memory> m,boolean d,String tools){content=c;memories=m;directFallback=d;toolResults=tools;} }
    public static final class ApiException extends IOException {public final int status;ApiException(int code,String body){super("请求失败（"+code+"）："+body);status=code;}}
    private static String read(InputStream stream) throws IOException { ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=stream.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8"); }
    private static String post(String url,String bearer,JSONObject body) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();connection.setConnectTimeout(10000);connection.setReadTimeout(65000);connection.setRequestMethod("POST");connection.setRequestProperty("Content-Type","application/json");if(!bearer.isEmpty())connection.setRequestProperty("Authorization","Bearer "+bearer);connection.setDoOutput(true);
        try(OutputStream out=connection.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}
        int code=connection.getResponseCode();String result=read(code>=200&&code<300?connection.getInputStream():connection.getErrorStream());if(code<200||code>=300)throw new ApiException(code,result);return result;
    }
    private static String serverUrl(Context context){return context.getSharedPreferences("gray_settings",Context.MODE_PRIVATE).getString("server_url","").replaceAll("/+$","");}
    private static String secret(Context context,String name)throws Exception{return new SecureStore(context).get(name);}
    public static Reply chat(Context context,List<AssistantStore.Message> messages,List<AssistantStore.Memory> memories) throws Exception {return chat(context,messages,memories,UUID.randomUUID().toString());}
    public static Reply chat(Context context,List<AssistantStore.Message> messages,List<AssistantStore.Memory> memories,String messageId) throws Exception {
        try(LifeRecords records=new LifeRecords(context)){LifeRecords.Record saved=records.find(messageId);if(saved!=null){LifeSync.schedule(context);return new Reply(saved.deleted?"这条记录已保存，随后已删除。":"已记录。"+LifeRecords.summary(saved.payload),Collections.emptyList(),false);}}
        JSONObject request=new JSONObject();JSONArray turns=new JSONArray();for(AssistantStore.Message message:messages)turns.put(new JSONObject().put("role",message.role).put("content",message.content));request.put("messages",turns);request.put("client_message_id",messageId);request.put("decision_enabled",context.getSharedPreferences("gray_settings",0).getBoolean("decision_enabled",false));request.put("client_tools",clientCapabilities(context));request.put("timezone",java.util.TimeZone.getDefault().getID());try(TopicRepository topics=new TopicRepository(context)){request.put("suppressed_topic_ids",topics.suppressed());}
        String url=serverUrl(context), token=secret(context,"device_token");
        if(!url.isEmpty()&&!token.isEmpty()){
            SharedPreferences requests=context.getSharedPreferences("chat_requests",Context.MODE_PRIVATE);
            String saved=requests.getString(messageId,null);if(saved!=null)request=new JSONObject(saved);else requests.edit().putString(messageId,request.toString()).commit();
            Reply reply;
            try{reply=parseServer(post(url+"/v1/chat",token,request));}
            catch(ApiException incompatible){
                if(incompatible.status!=422||!incompatible.getMessage().contains("client_tools")||request.has("client_tool_result"))throw incompatible;
                String latest=messages.isEmpty()?"":messages.get(messages.size()-1).content;
                if(latest.matches("(?s).*(日历|待办|生日|大姨妈|农历|纪念|排卵|倒数|月经|经期).*"))throw new IOException("助理服务尚未更新日历能力，请更新服务端；日历页面和本地提醒仍可使用");
                request.put("client_tools",new JSONArray(Arrays.asList("open_sync","open_print","open_topics","create_life_record","open_records")));requests.edit().putString(messageId,request.toString()).commit();reply=parseServer(post(url+"/v1/chat",token,request));
            }
            for(int step=0;reply.pendingClient&&step<24;step++){
                JSONObject action=reply.clientAction;
                if(action==null||!messageId.equals(action.optString("id"))||!CalendarTools.isLocal(action.optString("name")))throw new IOException("无效的日历工具回传请求");
                String name=action.getString("name");String operation=action.getString("operation_id");
                JSONObject result=calendarResult(context,name,action.getJSONObject("arguments"),operation,messages);
                request.put("client_tool_result",new JSONObject().put("name",name).put("operation_id",operation).put("result",result));
                requests.edit().putString(messageId,request.toString()).commit();
                reply=parseServer(post(url+"/v1/chat",token,request));
            }
            if(reply.pendingClient)throw new IOException("日历工具调用较多，请重试以继续原操作");
            if(reply.clientAction!=null&&!messageId.equals(reply.clientAction.optString("id")))reply.clientAction=null;
            reply=commitRecord(context,reply,messages,messageId);
            requests.edit().remove(messageId).apply();return reply;
        }
        String key=secret(context,"deepseek_key");if(key.isEmpty())throw new IOException("服务端不可用，且尚未设置本地 DeepSeek 密钥。");return commitRecord(context,direct(context,key,messages,memories,messageId),messages,messageId);
    }
    private static Reply commitRecord(Context context,Reply reply,List<AssistantStore.Message> messages,String id)throws Exception{
        if(reply.clientAction==null||!"create_life_record".equals(reply.clientAction.optString("name")))return reply;
        String original=messages.isEmpty()?"":messages.get(messages.size()-1).content;
        if(!id.equals(reply.clientAction.optString("id"))||!ClientTools.recordCommand(original))throw new IOException("记录指令未通过校验");
        StringBuilder evidence=new StringBuilder();for(AssistantStore.Message m:messages)if(m.role.equals("user")&&m.groupId.equals(id)){if(evidence.length()>0)evidence.append("\n");evidence.append(m.content);}
        try(LifeRecords records=new LifeRecords(context)){records.save(id,reply.clientAction.getJSONObject("arguments"),evidence.length()>0?evidence.toString():original);}
        LifeSync.schedule(context);
        return new Reply("已记录。"+LifeRecords.summary(reply.clientAction.getJSONObject("arguments")),reply.memories,reply.directFallback,reply.toolResults);
    }
    private static Reply parseServer(String value)throws Exception{ JSONObject json=new JSONObject(value);ArrayList<AssistantStore.Memory> changes=new ArrayList<>();JSONArray items=json.optJSONArray("memory_changes");if(items!=null)for(int i=0;i<items.length();i++)changes.add(memory(items.getJSONObject(i)));StringBuilder tools=new StringBuilder();JSONArray results=json.optJSONArray("tool_results");if(results!=null)for(int i=0;i<results.length();i++){JSONObject item=results.getJSONObject(i);if(tools.length()>0)tools.append("\n\n");tools.append(item.optString("summary",item.toString()));}Reply reply=new Reply(json.optString("content",""),changes,false,tools.toString());reply.clientAction=json.optJSONObject("client_action");reply.pendingClient=json.optBoolean("pending_client");return reply; }
    private static AssistantStore.Memory memory(JSONObject item) throws JSONException {return new AssistantStore.Memory(item.getString("id"),item.getString("category"),item.getString("fact"),item.optDouble("confidence",.8),item.optString("source",""),item.optInt("revision",1),item.optBoolean("deleted",false),item.getString("updated_at"));}
    private static Reply direct(Context phone,String key,List<AssistantStore.Message> messages,List<AssistantStore.Memory> memories,String messageId)throws Exception{
        SharedPreferences sessions=phone.getSharedPreferences("direct_calendar_requests",0);
        JSONObject state;String snapshot=sessions.getString(messageId,null);
        if(snapshot!=null)state=new JSONObject(snapshot);
        else{
            JSONArray turns=new JSONArray();StringBuilder context=new StringBuilder("Current date/time: "+java.time.ZonedDateTime.now()+". You are Greta, a warm concise assistant. Only execute mutations explicitly requested by the user. Calendar facts live on the phone: query to obtain ids/revisions before patching. Never infer actual periods from predictions or save these facts to general memory. User-facing term is 大姨妈. Date-only cycle predictions cannot provide safe days or diagnosis. Do not guess a birth year. Never claim saved before a successful tool result.\n");
            for(AssistantStore.Memory m:memories)context.append("- [").append(m.category).append("] ").append(m.fact).append("\n");
            turns.put(new JSONObject().put("role","system").put("content",context.toString()));for(AssistantStore.Message m:messages)turns.put(new JSONObject().put("role",m.role).put("content",m.content));
            state=new JSONObject().put("messages",turns).put("step",0).put("pending",new JSONArray()).put("index",0);
        }
        String latest=messages.isEmpty()?"":messages.get(messages.size()-1).content;
        JSONArray definitions=ClientTools.definitions(phone),allowed=new JSONArray();
        for(int i=0;i<definitions.length();i++){JSONObject t=definitions.getJSONObject(i);String name=t.getJSONObject("function").getString("name");if(CalendarTools.isTool(name)||ClientTools.explicitCommand(latest))allowed.put(t);}
        while(state.getInt("step")<8){
            JSONArray turns=state.getJSONArray("messages"),calls=state.getJSONArray("pending");
            if(calls.length()==0){
                JSONObject body=new JSONObject().put("model","deepseek-chat").put("messages",turns).put("tools",allowed).put("temperature",.7);
                JSONObject message=new JSONObject(post("https://api.deepseek.com/chat/completions",key,body)).getJSONArray("choices").getJSONObject(0).getJSONObject("message");
                calls=message.optJSONArray("tool_calls");if(calls==null||calls.length()==0){sessions.edit().remove(messageId).apply();return new Reply(message.optString("content",""),Collections.emptyList(),true);}
                turns.put(message);state.put("pending",calls).put("index",0);sessions.edit().putString(messageId,state.toString()).commit();
            }
            for(int i=state.getInt("index");i<calls.length();i++){
                JSONObject call=calls.getJSONObject(i),f=call.getJSONObject("function");String name=f.getString("name");JSONObject args=new JSONObject(f.optString("arguments","{}"));
                if(CalendarTools.isLocal(name)){
                    String operation=messageId+":"+state.getInt("step")+":"+i+":"+name;
                    JSONObject result=calendarResult(phone,name,args,operation,messages);
                    turns.put(new JSONObject().put("role","tool").put("tool_call_id",call.getString("id")).put("content",result.toString()));state.put("index",i+1);sessions.edit().putString(messageId,state.toString()).commit();
                }else if(ClientTools.valid(name,args)&&(CalendarTools.isTool(name)||ClientTools.explicitCommand(latest))&&calls.length()==1){
                    sessions.edit().remove(messageId).apply();Reply reply=new Reply("已准备打开本地工具。",Collections.emptyList(),true);reply.clientAction=new JSONObject().put("name",name).put("arguments",args).put("id",messageId);return reply;
                }else throw new IOException("工具请求不完整，请说明要修改或查询的内容");
            }
            state.put("pending",new JSONArray()).put("index",0).put("step",state.getInt("step")+1);sessions.edit().putString(messageId,state.toString()).commit();
        }
        throw new IOException("日历操作已保留，请重试继续整理结果");
    }
    private static JSONArray clientCapabilities(Context context)throws Exception{
        JSONArray out=new JSONArray(),defs=ClientTools.definitions(context);for(int i=0;i<defs.length();i++)out.put(defs.getJSONObject(i).getJSONObject("function").getString("name"));return out;
    }
    private static JSONObject calendarResult(Context context,String name,JSONObject args,String operation,List<AssistantStore.Message> messages)throws Exception{
        boolean query=name.equals("query_calendar")||name.equals("get_cycle_summary")||name.equals("list_calendar_widgets");String latest=messages.isEmpty()?"":messages.get(messages.size()-1).content;
        if(!query&&(!latest.matches("(?s).*(记|保存|添加|创建|改|修正|删除|撤销|完成|恢复|开启|关闭|隐藏|显示|设置|提醒|来了|结束|开始|取消|停止).*" )||latest.matches("(?s).*(假如|假设|如果|如何|怎么|介绍|解释|是否支持|能不能支持).*")))return new JSONObject().put("ok",false).put("error","需要明确的新增或修改指令；普通问询不会写入记录");
        return CalendarTools.execute(context,name,args,operation);
    }
    private static JSONArray getArray(Context context,String path)throws Exception{
        String url=serverUrl(context),token=secret(context,"device_token");if(url.isEmpty()||token.isEmpty())return new JSONArray();
        HttpURLConnection connection=(HttpURLConnection)new URL(url+path).openConnection();connection.setConnectTimeout(10000);connection.setReadTimeout(15000);connection.setRequestProperty("Authorization","Bearer "+token);
        try{int code=connection.getResponseCode();if(code<200||code>=300)throw new IOException("请求失败（"+code+"）");return new JSONArray(read(connection.getInputStream()));}finally{connection.disconnect();}
    }
    public static String homeOpening(Context context,List<AssistantStore.Message> messages)throws Exception{
        String url=serverUrl(context),token=secret(context,"device_token");if(url.isEmpty()||token.isEmpty())return "";
        JSONArray turns=new JSONArray();for(AssistantStore.Message m:messages)turns.put(new JSONObject().put("role",m.role).put("content",m.content));
        return new JSONObject(post(url+"/v1/home",token,new JSONObject().put("messages",turns).put("timezone",java.util.TimeZone.getDefault().getID()))).optString("content","");
    }
    public static JSONArray topics(Context context)throws Exception{if(serverUrl(context).isEmpty()||secret(context,"device_token").isEmpty())throw new IOException("连接助理服务后才能整理重要话题");return getArray(context,"/v1/topics");}
    public static JSONObject updateTopic(Context context,String id,JSONObject change)throws Exception{
        String token=secret(context,"device_token");if(serverUrl(context).isEmpty()||token.isEmpty())throw new IOException("尚未配对服务，修改已保存在本机");
        return new JSONObject(post(serverUrl(context)+"/v1/topics/"+URLEncoder.encode(id,"UTF-8"),token,change));
    }
    public static JSONArray inbox(Context context)throws Exception{return getArray(context,"/v1/inbox?after_sequence="+context.getSharedPreferences("home_conversation",0).getLong("inbox_sequence",0));}
    public static JSONArray reminders(Context context)throws Exception{return getArray(context,"/v1/reminders");}
    public static void reminderAction(Context context,String id,String action)throws Exception{post(serverUrl(context)+"/v1/reminders/"+URLEncoder.encode(id,"UTF-8")+"/action",secret(context,"device_token"),new JSONObject().put("action",action));}
    public static void syncMemory(Context context,AssistantStore.Memory memory)throws Exception{
        String url=serverUrl(context),token=secret(context,"device_token");if(url.isEmpty()||token.isEmpty())return;JSONObject body=new JSONObject().put("id",memory.id).put("category",memory.category).put("fact",memory.fact).put("confidence",memory.confidence).put("source",memory.source).put("revision",memory.revision).put("deleted",memory.deleted).put("updated_at",memory.updatedAt);post(url+"/v1/memories/"+URLEncoder.encode(memory.id,"UTF-8"),token,body);
    }
    public static void registerDevice(Context context,String fcmToken)throws Exception{
        String url=serverUrl(context),token=secret(context,"device_token");if(url.isEmpty()||token.isEmpty()||fcmToken==null||fcmToken.isEmpty())return;
        post(url+"/v1/devices",token,new JSONObject().put("fcm_token",fcmToken));
    }
    public static void claimPairing(Context context,String rawCode,String deviceName)throws Exception{
        JSONObject code=new JSONObject(rawCode);if(code.optInt("version")!=1)throw new IOException("这不是 Greta 的配对二维码。");String endpoint=code.optString("endpoint","");String secret=code.optString("secret","");if(!(endpoint.startsWith("https://")||endpoint.startsWith("http://"))||secret.isEmpty())throw new IOException("二维码内容无效。");
        JSONObject result=new JSONObject(post(endpoint,"",new JSONObject().put("secret",secret).put("device_name",deviceName)));String deviceToken=result.getString("device_token");int marker=endpoint.indexOf("/v1/pairings/");if(marker<0)throw new IOException("二维码服务地址无效。");String base=endpoint.substring(0,marker);context.getSharedPreferences("gray_settings",Context.MODE_PRIVATE).edit().putString("server_url",base).apply();new SecureStore(context).put("device_token",deviceToken);
    }
}
