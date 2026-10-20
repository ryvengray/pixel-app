package dev.pixel.sync;

import android.content.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Network boundary for the private service, with direct DeepSeek fallback. */
public final class AssistantApi {
    public static final class Reply { public final String content; public final List<AssistantStore.Memory> memories; public final boolean directFallback; public final String toolResults; public JSONObject clientAction; Reply(String c,List<AssistantStore.Memory> m,boolean d){this(c,m,d,"");} Reply(String c,List<AssistantStore.Memory> m,boolean d,String tools){content=c;memories=m;directFallback=d;toolResults=tools;} }
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
        JSONObject request=new JSONObject();JSONArray turns=new JSONArray();for(AssistantStore.Message message:messages)turns.put(new JSONObject().put("role",message.role).put("content",message.content));request.put("messages",turns);request.put("client_message_id",messageId);request.put("decision_enabled",context.getSharedPreferences("gray_settings",0).getBoolean("decision_enabled",false));request.put("client_tools",new JSONArray(Arrays.asList("open_sync","open_print","open_topics")));request.put("timezone",java.util.TimeZone.getDefault().getID());try(TopicRepository topics=new TopicRepository(context)){request.put("suppressed_topic_ids",topics.suppressed());}
        String url=serverUrl(context), token=secret(context,"device_token");
        if(!url.isEmpty()&&!token.isEmpty()){
            SharedPreferences requests=context.getSharedPreferences("chat_requests",Context.MODE_PRIVATE);
            String saved=requests.getString(messageId,null);if(saved!=null)request=new JSONObject(saved);else requests.edit().putString(messageId,request.toString()).commit();
            Reply reply=parseServer(post(url+"/v1/chat",token,request));requests.edit().remove(messageId).apply();return reply;
        }
        String key=secret(context,"deepseek_key");if(key.isEmpty())throw new IOException("服务端不可用，且尚未设置本地 DeepSeek 密钥。");return direct(key,messages,memories);
    }
    private static Reply parseServer(String value)throws Exception{ JSONObject json=new JSONObject(value);ArrayList<AssistantStore.Memory> changes=new ArrayList<>();JSONArray items=json.optJSONArray("memory_changes");if(items!=null)for(int i=0;i<items.length();i++)changes.add(memory(items.getJSONObject(i)));StringBuilder tools=new StringBuilder();JSONArray results=json.optJSONArray("tool_results");if(results!=null)for(int i=0;i<results.length();i++){JSONObject item=results.getJSONObject(i);if(tools.length()>0)tools.append("\n\n");tools.append(item.optString("summary",item.toString()));}Reply reply=new Reply(json.optString("content",""),changes,false,tools.toString());reply.clientAction=json.optJSONObject("client_action");return reply; }
    private static AssistantStore.Memory memory(JSONObject item) throws JSONException {return new AssistantStore.Memory(item.getString("id"),item.getString("category"),item.getString("fact"),item.optDouble("confidence",.8),item.optString("source",""),item.optInt("revision",1),item.optBoolean("deleted",false),item.getString("updated_at"));}
    private static Reply direct(String key,List<AssistantStore.Message> messages,List<AssistantStore.Memory> memories)throws Exception{
        JSONArray turns=new JSONArray();StringBuilder context=new StringBuilder("You are Greta, a warm concise personal assistant. These are the user's saved memories; use them only if relevant.\\n");for(AssistantStore.Memory m:memories)context.append("- [").append(m.category).append("] ").append(m.fact).append("\\n");turns.put(new JSONObject().put("role","system").put("content",context.toString()));for(AssistantStore.Message m:messages)turns.put(new JSONObject().put("role",m.role).put("content",m.content));
        JSONObject body=new JSONObject().put("model","deepseek-chat").put("messages",turns).put("temperature",.7);JSONObject response=new JSONObject(post("https://api.deepseek.com/chat/completions",key,body));String content=response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content","");return new Reply(content,Collections.emptyList(),true);
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
