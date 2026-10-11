package dev.pixel.sync;

import android.content.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Network boundary for the private service, with direct DeepSeek fallback. */
public final class AssistantApi {
    public static final class Reply { public final String content; public final List<AssistantStore.Memory> memories; public final boolean directFallback; Reply(String c,List<AssistantStore.Memory> m,boolean d){content=c;memories=m;directFallback=d;} }
    private static String read(InputStream stream) throws IOException { ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=stream.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8"); }
    private static String post(String url,String bearer,JSONObject body) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();connection.setConnectTimeout(10000);connection.setReadTimeout(65000);connection.setRequestMethod("POST");connection.setRequestProperty("Content-Type","application/json");if(!bearer.isEmpty())connection.setRequestProperty("Authorization","Bearer "+bearer);connection.setDoOutput(true);
        try(OutputStream out=connection.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}
        int code=connection.getResponseCode();String result=read(code>=200&&code<300?connection.getInputStream():connection.getErrorStream());if(code<200||code>=300)throw new IOException("请求失败（"+code+"）："+result);return result;
    }
    private static String serverUrl(Context context){return context.getSharedPreferences("gray_settings",Context.MODE_PRIVATE).getString("server_url","").replaceAll("/+$","");}
    private static String secret(Context context,String name)throws Exception{return new SecureStore(context).get(name);}
    public static Reply chat(Context context,List<AssistantStore.Message> messages,List<AssistantStore.Memory> memories) throws Exception {
        JSONObject request=new JSONObject();JSONArray turns=new JSONArray();for(AssistantStore.Message message:messages)turns.put(new JSONObject().put("role",message.role).put("content",message.content));request.put("messages",turns);request.put("timezone",java.util.TimeZone.getDefault().getID());
        String url=serverUrl(context), token=secret(context,"device_token");
        if(!url.isEmpty()&&!token.isEmpty())try{return parseServer(post(url+"/v1/chat",token,request));}catch(Exception ignored){ /* direct fallback below */ }
        String key=secret(context,"deepseek_key");if(key.isEmpty())throw new IOException("服务端不可用，且尚未设置本地 DeepSeek 密钥。");return direct(key,messages,memories);
    }
    private static Reply parseServer(String value)throws Exception{ JSONObject json=new JSONObject(value);ArrayList<AssistantStore.Memory> changes=new ArrayList<>();JSONArray items=json.optJSONArray("memory_changes");if(items!=null)for(int i=0;i<items.length();i++)changes.add(memory(items.getJSONObject(i)));return new Reply(json.optString("content",""),changes,false); }
    private static AssistantStore.Memory memory(JSONObject item) throws JSONException {return new AssistantStore.Memory(item.getString("id"),item.getString("category"),item.getString("fact"),item.optDouble("confidence",.8),item.optString("source",""),item.optInt("revision",1),item.optBoolean("deleted",false),item.getString("updated_at"));}
    private static Reply direct(String key,List<AssistantStore.Message> messages,List<AssistantStore.Memory> memories)throws Exception{
        JSONArray turns=new JSONArray();StringBuilder context=new StringBuilder("You are Gray, a warm concise personal assistant. These are the user's saved memories; use them only if relevant.\\n");for(AssistantStore.Memory m:memories)context.append("- [").append(m.category).append("] ").append(m.fact).append("\\n");turns.put(new JSONObject().put("role","system").put("content",context.toString()));for(AssistantStore.Message m:messages)turns.put(new JSONObject().put("role",m.role).put("content",m.content));
        JSONObject body=new JSONObject().put("model","deepseek-chat").put("messages",turns).put("temperature",.7);JSONObject response=new JSONObject(post("https://api.deepseek.com/chat/completions",key,body));String content=response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content","");return new Reply(content,Collections.emptyList(),true);
    }
    public static void syncMemory(Context context,AssistantStore.Memory memory)throws Exception{
        String url=serverUrl(context),token=secret(context,"device_token");if(url.isEmpty()||token.isEmpty())return;JSONObject body=new JSONObject().put("id",memory.id).put("category",memory.category).put("fact",memory.fact).put("confidence",memory.confidence).put("source",memory.source).put("revision",memory.revision).put("deleted",memory.deleted).put("updated_at",memory.updatedAt);post(url+"/v1/memories/"+URLEncoder.encode(memory.id,"UTF-8"),token,body);
    }
    public static void claimPairing(Context context,String rawCode,String deviceName)throws Exception{
        JSONObject code=new JSONObject(rawCode);if(code.optInt("version")!=1)throw new IOException("这不是 Gray 的配对二维码。");String endpoint=code.optString("endpoint","");String secret=code.optString("secret","");if(!endpoint.startsWith("https://")||secret.isEmpty())throw new IOException("二维码内容无效。");
        JSONObject result=new JSONObject(post(endpoint,"",new JSONObject().put("secret",secret).put("device_name",deviceName)));String deviceToken=result.getString("device_token");int marker=endpoint.indexOf("/v1/pairings/");if(marker<0)throw new IOException("二维码服务地址无效。");String base=endpoint.substring(0,marker);context.getSharedPreferences("gray_settings",Context.MODE_PRIVATE).edit().putString("server_url",base).apply();new SecureStore(context).put("device_token",deviceToken);
    }
}
