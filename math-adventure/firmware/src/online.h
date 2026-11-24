#pragma once
// Included in the UI namespace: only the main loop touches the screen or NVS.
namespace online {
Preferences config;
WebServer portal(80);
DNSServer captiveDns;
String configuredSsid;
bool wifiConfiguredOnline(){return adventure::configuredNetwork(configuredSsid.length(),WiFi.status()==WL_CONNECTED,WiFi.SSID()==configuredSsid,WiFi.localIP()!=IPAddress(0,0,0,0));}
struct Work { String url, token, path, body; };
struct Result { int status; String path, body; };
QueueHandle_t jobs, replies;
String base, credential, pendingPath, pendingBody, message="正在连接小伙伴", mode, day;
String apName, apPassword, csrf;
bool busy=false, ready=false, setupActive=false, refreshAfter=false;
uint32_t nextPoll=0, nextRetry=0, setupUntil=0;
DynamicJsonDocument state(24576);
int serverPage=0, mapOffset=0;
String exploreTaskId, mapMessage;
int selected=0, screen=0, contentPage=0; // home, map, question, method/hint, settings
DynamicJsonDocument outbox(24576);
String numberInput;
int detailKind=0; // method, hint, encouragement

String detail, resumeTask, pairSecret, pairBody, pairCode, pairVerification;
bool pairWaiting=false,backendReachable=false,pairOpenRequested=false;uint32_t pairPoll=0,backendChecked=0;
int xp=0, coins=0;

String escape(String s) {
  s.replace("&","&amp;");s.replace("<","&lt;");s.replace(">","&gt;");s.replace("\"","&quot;");return s;
}
bool validUrl(const String &s) {
  return (s.startsWith("https://")||s.startsWith("http://")) && s.length()<180 &&
    s.length()>8 && s.indexOf('@')<0 && s.indexOf('?')<0 && s.indexOf('#')<0 && s.indexOf(' ')<0;
}
void worker(void*) {
  for(;;) {
    Work *w=nullptr;
    if(xQueueReceive(jobs,&w,portMAX_DELAY)!=pdTRUE) continue;
    Result *r=new Result{-1,w->path,""};
    WiFiClient plain;WiFiClientSecure secure;
    secure.setCACert(kTrustedRoots);secure.setHandshakeTimeout(8);
    HTTPClient http;http.setConnectTimeout(4000);http.setTimeout(5000);
    bool tls=w->url.startsWith("https://");
    if(WiFi.status()==WL_CONNECTED && (!tls || time(nullptr)>1700000000) &&
       http.begin(tls?static_cast<WiFiClient&>(secure):plain,w->url+w->path)) {
      http.addHeader("Authorization","Bearer "+w->token);
      http.addHeader("Content-Type","application/json");
      r->status=w->body.isEmpty()?http.GET():http.POST(w->body);
      if(r->status>0) {
        // Bound response memory and require a complete JSON response before clearing a pending request.
        if(http.getSize()>24000) r->status=-2;
        else {r->body=http.getString();if(r->body.length()>24000) {r->body="";r->status=-2;}}
      }
      http.end();
    }
    delete w;
    xQueueSend(replies,&r,portMAX_DELAY);
  }
}
void statusHeader() {
  lcd.fillRect(244,0,76,28,pale);
  text(busy?"同步中":(pendingBody.length()?"待同步":(mode=="live"?"正式":(mode=="practice"?"试玩":"联网"))),260,20);
}
bool send(const String &path,const String &body="") {
  if(busy||base.isEmpty()||(credential.isEmpty()&&!path.startsWith("/api/math/pair/")&&path!="/health")||WiFi.status()!=WL_CONNECTED) return false;
  Work *w=new Work{base,path.startsWith("/api/math/pair/")?pairSecret:credential,path,body};
  if(xQueueSend(jobs,&w,0)!=pdTRUE) {delete w;return false;}
  busy=true;statusHeader();return true;
}
void sync() { send(String("/api/math/device/state?page=")+serverPage); }
JsonArray tasks() {return state["tasks"].as<JsonArray>();}
JsonObject task() {return tasks()[selected].as<JsonObject>();}
JsonObject question() {return task()["question"].as<JsonObject>();}
void cacheState() {String cached;serializeJson(state,cached);config.putString("cached-state",cached);}
bool saveOutbox(){String saved;serializeJson(outbox,saved);return config.putString("outbox",saved)==saved.length();}
void loadPending(){
  pendingPath="";pendingBody="";
  if(outbox.size()){pendingPath=outbox[0]["path"].as<String>();pendingBody=outbox[0]["body"].as<String>();}
}
void acknowledge(){outbox.remove(0);saveOutbox();loadPending();config.remove("pending");}
void submit(const char *path,int answer=-1,bool numeric=false) {
  if(!question()["solution"].is<JsonObject>()){message="请联网重新同步题目";refreshAfter=true;dirty=true;return;}
  if(!ready||!task()["available"].as<bool>()) {message="请先同步今日任务";dirty=true;return;}
  if(outbox.size()>=60){message="待同步记录已满，请先联网";dirty=true;return;}
  if(task()["state"]=="solved"&&String(path)=="/api/math/answers"){
    detail=question()["solution"]["explanation"].as<String>();detailKind=0;screen=3;contentPage=0;dirty=true;return;
  }
  DynamicJsonDocument body(512);
  char id[40];snprintf(id,sizeof(id),"%08lx%08lx%08lx%08lx",(unsigned long)esp_random(),(unsigned long)esp_random(),(unsigned long)esp_random(),(unsigned long)esp_random());
  body["request_id"]=id;body["task_id"]=task()["id"];
  double value=numeric?numberInput.toDouble():answer;
  if(numeric) body["answer"]=value;else if(answer>=0)body["answer"]=answer;
  String serialized;serializeJson(body,serialized);
  auto entry=outbox.createNestedObject();entry["path"]=path;entry["body"]=serialized;
  if(outbox.overflowed()||!saveOutbox()){outbox.remove(outbox.size()-1);message="保存失败，请稍后再试";dirty=true;return;}
  loadPending();
  auto solution=question()["solution"];
  if(String(path)=="/api/math/answers"){
    bool correct=value==solution["answer"].as<double>();
    detailKind=correct?0:2;
    detail=correct?solution["explanation"].as<String>():"没关系，换个办法再试试。可以先找一找题目里的条件，也可以请小向导给个提示。";
    if(correct){if(task()["state"]!="solved"&&state.containsKey("solved_total"))state["solved_total"]=state["solved_total"].as<int>()+1;task()["state"]="solved";task()["explanation"]=solution["explanation"];}
    screen=correct?9:3;
  }else if(String(path)=="/api/math/tasks/hint"){
    detailKind=1;int n=task()["hint_count"]|0;auto hints=solution["hints"].as<JsonArray>();
    detail=task()["state"]=="solved"?solution["explanation"].as<String>():hints[min(n,(int)hints.size()-1)].as<String>();
    task()["hint_count"]=n+1;screen=3;
  }else{task()["state"]="skipped";screen=1;}
  cacheState();contentPage=0;message="已保存，后台同步中";dirty=true;nextRetry=0;
}
void requestExploration(){
  for(auto entry:outbox.as<JsonArray>())if(entry["path"]=="/api/math/adventure/explore"){message="正在寻找新关卡";dirty=true;return;}
  if(outbox.size()>=60){message="请先联网同步记录";dirty=true;return;}
  char id[40];snprintf(id,sizeof(id),"%08lx%08lx%08lx%08lx",(unsigned long)esp_random(),(unsigned long)esp_random(),(unsigned long)esp_random(),(unsigned long)esp_random());
  DynamicJsonDocument body(256);body["request_id"]=id;String serialized;serializeJson(body,serialized);
  auto entry=outbox.createNestedObject();entry["path"]="/api/math/adventure/explore";entry["body"]=serialized;
  if(outbox.overflowed()||!saveOutbox()){outbox.remove(outbox.size()-1);message="保存失败，请稍后再试";dirty=true;return;}
  loadPending();nextRetry=0;message="正在寻找新关卡";mapMessage=message;dirty=true;
}
void startPortal() {
  if(setupActive){setupUntil=millis()+600000;screen=4;dirty=true;return;}
  if(apPassword.isEmpty()) {
    apPassword=config.getString("ap-password","");if(apPassword.length()<8){char pass[16];snprintf(pass,sizeof(pass),"%08lx%04lx",(unsigned long)esp_random(),(unsigned long)(esp_random()&0xffff));apPassword=pass;config.putString("ap-password",apPassword);}
    char name[32];snprintf(name,sizeof(name),"Math-Setup-%04lx",(unsigned long)(ESP.getEfuseMac()&0xffff));apName=name;
    char key[32];snprintf(key,sizeof(key),"%08lx%08lx",(unsigned long)esp_random(),(unsigned long)esp_random());csrf=key;
  }
  WiFi.setAutoReconnect(false);
  if(WiFi.status()!=WL_CONNECTED)WiFi.disconnect(false);
  WiFi.mode(WIFI_AP_STA);WiFi.softAPConfig(IPAddress(192,168,4,1),IPAddress(192,168,4,1),IPAddress(255,255,255,0));
  WiFi.softAP(apName.c_str(),apPassword.c_str());captiveDns.stop();captiveDns.start(53,"*",WiFi.softAPIP());setupActive=true;setupUntil=millis()+600000;
  screen=4;dirty=true;
}
void init() {
  config.begin("math-network",false);base=config.getString("base","http://192.168.0.160/math");credential=config.getString("device","");
  pairSecret=config.getString("pair-secret","");pairBody=config.getString("pair-body","");pairWaiting=pairSecret.length()&&!pairBody.length();
  DynamicJsonDocument envelope(1024);
  if(!deserializeJson(envelope,config.getString("pending",""))) {
    pendingPath=envelope["path"].as<String>();pendingBody=envelope["body"].as<String>();
  }
  if(deserializeJson(outbox,config.getString("outbox","[]")))outbox.to<JsonArray>();
  if(!outbox.size()&&pendingBody.length()){auto e=outbox.createNestedObject();e["path"]=pendingPath;e["body"]=pendingBody;saveOutbox();}
  loadPending();
  if(!deserializeJson(state,config.getString("cached-state",""))&&state["tasks"].is<JsonArray>()){
    ready=true;serverPage=state["page"]|0;mode=state["mode"].as<String>();day=state["day"].as<String>();xp=state["player"]["xp"]|0;coins=state["player"]["coins"]|0;
  }
  jobs=xQueueCreate(1,sizeof(Work*));replies=xQueueCreate(1,sizeof(Result*));
  if(!jobs||!replies||xTaskCreate(worker,"math-http",12288,nullptr,1,nullptr)!=pdPASS) {
    message="网络初始化失败，请重启";return;
  }
  WiFi.mode(WIFI_STA);WiFi.setAutoReconnect(true);
  String ssid=config.getString("ssid","");configuredSsid=ssid;
  if(ssid.length()) WiFi.begin(ssid.c_str(),config.getString("wifi","").c_str());else WiFi.begin();
  configTime(0,0,"pool.ntp.org","ntp.aliyun.com");
  portal.on("/networks",HTTP_GET,[](){
    if(!setupActive||portal.client().localIP()!=WiFi.softAPIP()) {portal.send(403);return;}
    int count=WiFi.scanComplete();
    if(count==WIFI_SCAN_FAILED || (portal.arg("refresh")=="1" && count!=WIFI_SCAN_RUNNING)) {
      WiFi.scanDelete();WiFi.scanNetworks(true,false);count=WIFI_SCAN_RUNNING;
    }
    DynamicJsonDocument response(8192);
    response["scanning"]=count==WIFI_SCAN_RUNNING;
    JsonArray networks=response.createNestedArray("networks");
    if(count>=0) {
      int order[64],n=count<64?count:64;for(int i=0;i<n;++i)order[i]=i;
      for(int i=0;i<n;++i)for(int j=i+1;j<n;++j)if(WiFi.RSSI(order[j])>WiFi.RSSI(order[i])) {int tmp=order[i];order[i]=order[j];order[j]=tmp;}
      for(int k=0;k<n&&networks.size()<30;++k) {
        int i=order[k];String name=WiFi.SSID(i);if(name.isEmpty())continue;
        bool duplicate=false;for(JsonObject entry:networks)if(entry["ssid"].as<String>()==name)duplicate=true;
        if(duplicate)continue;
        JsonObject entry=networks.createNestedObject();entry["ssid"]=name;entry["rssi"]=WiFi.RSSI(i);entry["open"]=WiFi.encryptionType(i)==WIFI_AUTH_OPEN;
      }
    }
    String json;serializeJson(response,json);portal.sendHeader("Cache-Control","no-store");portal.send(200,"application/json",json);
  });
  auto configPage=[](){
    if(!setupActive||portal.client().localIP()!=WiFi.softAPIP()) {portal.send(403);return;}
    String html="<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'><h2>数学冒险岛配网</h2><p>连接2.4GHz Wi-Fi。先连接家庭Wi-Fi，再在屏幕进行四位码配对。</p><form method=post action=/save>";
    html+="<input type=hidden name=csrf value='"+csrf+"'><p>附近Wi-Fi <select id=networks name=ssid required disabled><option value=''>正在扫描附近网络…</option></select> <button id=rescan type=button>重新扫描</button></p><p id=scanStatus role=status></p><p>Wi-Fi密码 <input name=wifi type=password maxlength=64></p><p>后端地址 <input name=base required value='"+escape(base)+"'></p><p>连接Wi-Fi后，在屏幕上输入家长网页的四位配对码即可绑定，无需复制令牌。</p><p>同一网络的密码留空保留原值。开放Wi-Fi请勾选<input type=checkbox name=open>无密码</p><button>保存并连接</button></form>";
    html+=R"HTML(<script>
const list=document.getElementById('networks'),statusText=document.getElementById('scanStatus'),rescan=document.getElementById('rescan'),openBox=document.querySelector('[name=open]');
let scanning=false;
list.addEventListener('change',()=>{openBox.checked=list.selectedOptions[0]?.dataset.open==='true';});
async function scan(){
 if(scanning)return;scanning=true;rescan.disabled=true;list.disabled=true;statusText.textContent='正在扫描2.4GHz Wi-Fi，请稍等…';
 const previous=list.value;
 try{
  for(let attempt=0;attempt<20;attempt++){
   const response=await fetch('/networks'+(attempt===0?'?refresh=1':''),{cache:'no-store',signal:AbortSignal.timeout(6000)});
   if(!response.ok)throw Error('读取网络列表失败');
   const data=await response.json();
   if(data.scanning){await new Promise(resolve=>setTimeout(resolve,1000));continue;}
   list.replaceChildren();const placeholder=document.createElement('option');placeholder.value='';placeholder.textContent='请选择Wi-Fi';list.append(placeholder);
   for(const network of data.networks){const option=document.createElement('option');option.value=network.ssid;option.dataset.open=String(network.open);option.textContent=network.ssid+' · '+(network.rssi>=-60?'信号强':network.rssi>=-75?'信号中':'信号弱')+(network.open?' · 无密码':'');list.append(option);}
   if([...list.options].some(option=>option.value===previous))list.value=previous;
   list.disabled=false;list.dispatchEvent(new Event('change'));
   statusText.textContent=data.networks.length?'选择网络后填写密码，再保存。':'没有找到网络，请靠近路由器并重新扫描；仅支持2.4GHz Wi-Fi。';
   return;
  }
  throw Error('扫描超时，请重新扫描');
 }catch(error){statusText.textContent=error.message+'，请点击重新扫描。';}
 finally{scanning=false;rescan.disabled=false;}
}
rescan.addEventListener('click',scan);scan();
</script>)HTML";
    portal.sendHeader("Cache-Control","no-store");portal.send(200,"text/html; charset=utf-8",html);
  };
  portal.on("/",HTTP_GET,configPage);
  portal.on("/hotspot-detect.html",HTTP_GET,configPage);
  portal.on("/library/test/success.html",HTTP_GET,configPage);
  portal.on("/generate_204",HTTP_GET,configPage);
  portal.on("/gen_204",HTTP_GET,configPage);
  portal.on("/connecttest.txt",HTTP_GET,configPage);
  portal.onNotFound([](){
    if(!setupActive||portal.client().localIP()!=WiFi.softAPIP()){portal.send(403);return;}
    portal.sendHeader("Location","http://192.168.4.1/",true);portal.sendHeader("Cache-Control","no-store");portal.send(302,"text/plain","");
  });
  portal.on("/save",HTTP_POST,[](){
    if(!setupActive||portal.client().localIP()!=WiFi.softAPIP()||portal.arg("csrf")!=csrf) {portal.send(403);return;}
    if(!pendingBody.isEmpty()||busy) {portal.send(409,"text/plain; charset=utf-8","有未确认提交，请先同步，避免更换设备归属。");return;}
    String url=portal.arg("base");url.trim();while(url.endsWith("/"))url.remove(url.length()-1);
    String tok=portal.arg("device");tok.trim();if(tok.isEmpty())tok=credential;
    String ssid=portal.arg("ssid"),pass=portal.arg("wifi");
    bool detected=false;int scanCount=WiFi.scanComplete();
    for(int i=0;i<scanCount;++i)if(WiFi.SSID(i)==ssid)detected=true;
    if(!detected) {portal.send(422,"text/plain; charset=utf-8","请选择扫描列表中的Wi-Fi；若列表过期，请重新扫描。");return;}
    if(pass.isEmpty()&&!portal.hasArg("open")&&ssid==config.getString("ssid",""))pass=config.getString("wifi","");
    if(pass.isEmpty()&&!portal.hasArg("open")) {portal.send(422,"text/plain; charset=utf-8","新选择的网络需要填写密码。");return;}
    if(!validUrl(url)||ssid.isEmpty()||ssid.length()>32||pass.length()>64) {portal.send(422,"text/plain; charset=utf-8","请检查地址、Wi-Fi与设备令牌");return;}
    config.putString("base",url);config.putString("device",tok);config.putString("ssid",ssid);config.putString("wifi",pass);
    base=url;credential=tok;configuredSsid=ssid;ready=false;state.clear();
    portal.send(200,"text/html; charset=utf-8","已保存，可以回到屏幕等待同步。热点将在十分钟后关闭。");
    WiFi.disconnect(false);WiFi.setAutoReconnect(true);WiFi.begin(ssid.c_str(),pass.c_str());screen=0;message="正在连接小伙伴";nextPoll=0;dirty=true;
  });
  portal.begin();
  if(base.isEmpty()||credential.isEmpty())startPortal();
}
void preparePair() {
  if(!wifiConfiguredOnline()){message="请先连接家庭Wi-Fi";screen=4;dirty=true;return;}
  if(busy||pendingBody.length())return;
  if(pairSecret.isEmpty()) {char secret[65];snprintf(secret,sizeof(secret),"%08lx%08lx%08lx%08lx%08lx%08lx%08lx%08lx",(unsigned long)esp_random(),(unsigned long)esp_random(),(unsigned long)esp_random(),(unsigned long)esp_random(),(unsigned long)esp_random(),(unsigned long)esp_random(),(unsigned long)esp_random(),(unsigned long)esp_random());pairSecret=secret;config.putString("pair-secret",pairSecret);}
  screen=5;pairCode="";dirty=true;
}
void beginPair() {
  pairOpenRequested=false;screen=7;dirty=true;
  if(!wifiConfiguredOnline()){backendReachable=false;message="Wi-Fi未连接，请先配网";return;}
  if(busy||pendingBody.length()){message="正在处理提交，请稍后再试";return;}
  message="正在检查服务器连接";
  pairOpenRequested=send("/health");
}
void claimPair() {
  if(pairCode.length()!=4)return;
  if(!adventure::pairingNetworkReady(wifiConfiguredOnline(),backendReachable,backendChecked,millis())) {
    message="网络未连通，请重新检查";screen=7;dirty=true;return;
  }
  DynamicJsonDocument body(1024);body["secret"]=pairSecret;body["name"]=apName.length()?apName:"Math-ESP32";body["code"]=pairCode;
  pairBody="";serializeJson(body,pairBody);config.putString("pair-body",pairBody);screen=6;pairPoll=0;dirty=true;
}
void tick() {
  if(setupActive)captiveDns.processNextRequest();
  portal.handleClient();
  if(screen==5&&!wifiConfiguredOnline()){backendReachable=false;screen=7;message="Wi-Fi断开，请先恢复连接";dirty=true;}
  if(setupActive && (int32_t)(millis()-setupUntil)>=0 && WiFi.softAPgetStationNum()==0) {captiveDns.stop();WiFi.softAPdisconnect(true);WiFi.setAutoReconnect(true);setupActive=false;dirty=true;}
  Result *r=nullptr;
  if(replies && xQueueReceive(replies,&r,0)==pdTRUE) {
    if(r->path=="/health") {
      busy=false;DynamicJsonDocument health(512);
      backendReachable=r->status==200&&!deserializeJson(health,r->body)&&health["ok"]==true&&health["service"]=="math-adventure";
      backendChecked=millis();
      if(pairOpenRequested){pairOpenRequested=false;if(backendReachable)preparePair();else{screen=7;message="服务器不可达，请检查网络";}}
      delete r;dirty=true;return;
    }
    if(r->path.startsWith("/api/math/pair/")) {
      busy=false;DynamicJsonDocument result(1024);bool valid=!deserializeJson(result,r->body)&&result["state"].is<const char*>();
      if(r->status==200&&valid) {
        String status=result["state"].as<String>();
        pairVerification=result["verification"]|"";
        if(status=="approved") {credential=pairSecret;config.putString("device",credential);pairSecret="";pairBody="";pairWaiting=false;config.remove("pair-secret");config.remove("pair-body");ready=false;screen=0;nextPoll=0;message="配对成功，正在同步";}
        else if(status=="claimed") {pairBody="";config.remove("pair-body");pairWaiting=true;if(screen==5||screen==6)screen=6;}
        else {pairBody="";pairSecret="";pairWaiting=false;config.remove("pair-secret");config.remove("pair-body");screen=4;message="配对已结束，请重新开启";}
      } else if(r->status==400||r->status==404||r->status==429) {pairBody="";config.remove("pair-body");pairWaiting=false;screen=4;message="配对码无效或操作太频繁";}
      pairPoll=millis()+3000;delete r;dirty=true;return;
    }
    busy=false;bool redraw=false;String previousMessage=message;
    DynamicJsonDocument data(24576);bool parsed=!deserializeJson(data,r->body);
    if(parsed) {
      parsed=data["player"]["xp"].is<int>()&&data["player"]["coins"].is<int>()&&data["mode"].is<const char*>();
      if(r->path.startsWith("/api/math/device/state")) parsed=parsed&&data["tasks"].is<JsonArray>()&&data["tasks"].size()<=5&&data["day"].is<const char*>();
      else if(r->path=="/api/math/adventure/explore") parsed=parsed&&data["found"].is<bool>();
      else {
        DynamicJsonDocument saved(512);deserializeJson(saved,pendingBody);
        parsed=parsed&&data["task_id"].as<String>()==saved["task_id"].as<String>()&&data["state"].is<const char*>();
      }
    }
    if(r->status==200&&parsed) {
      if(r->path=="/api/math/adventure/explore"){
        acknowledge();message=data["message"].as<String>();mapMessage=message;
        if(data["found"]==true){serverPage=data["page"]|0;mapOffset=((data["index"]|0)/2)*2;exploreTaskId=data["task_id"].as<String>();refreshAfter=true;}
        nextRetry=0;redraw=true;
      }else if(r->path.startsWith("/api/math/device/state")) {
        if(data["page"].as<int>()!=serverPage){refreshAfter=true;nextRetry=0;delete r;return;}
        if(!data["tasks"].size()&&serverPage>0){serverPage=0;mapOffset=0;refreshAfter=true;nextRetry=0;delete r;return;}
        String before,after;serializeJson(state,before);serializeJson(data,after);redraw=!ready||before!=after;
        String oldDay=day, oldId=resumeTask.length()?resumeTask:task()["id"].as<String>();resumeTask="";
        if(outbox.size()){delete r;return;}
        state=data;serverPage=state["page"]|0;if(mapOffset>=(int)tasks().size())mapOffset=0;cacheState();mode=state["mode"].as<String>();day=state["day"].as<String>();ready=true;
        xp=state["player"]["xp"]|0;coins=state["player"]["coins"]|0;
        selected=0;
        for(unsigned i=0;i<tasks().size();++i)if(tasks()[i]["id"].as<String>()==oldId) selected=i;
        if(oldDay.length()&&oldDay!=day){screen=0;contentPage=0;}
        message=tasks().size()?"冒险已同步":"请家长审核并安排题目";
        if(exploreTaskId.length()){
          for(unsigned i=0;i<tasks().size();++i)if(tasks()[i]["id"].as<String>()==exploreTaskId){selected=i;screen=2;contentPage=0;detailKind=0;}
          exploreTaskId="";mapMessage="";
        }
      } else {
        // Acknowledge uploads without interrupting the child's current screen.
        acknowledge();
        xp=data["player"]["xp"]|xp;coins=data["player"]["coins"]|coins;
        nextRetry=0;refreshAfter=!outbox.size();
        message=outbox.size()?"记录继续同步中":"记录已同步";

      }
    } else {
      if(r->status==401) {message="设备令牌无效，请检查配置";nextRetry=millis()+30000;}
      else if(r->status==409||r->status==404||r->status==422) {
        message="任务已变化，记录保留待处理";nextRetry=millis()+30000;
      } else message=WiFi.status()!=WL_CONNECTED?"网络断开，正在重新连接":"同步未成功，稍后自动重试";
      if(r->path=="/api/math/adventure/explore")mapMessage="网络暂时没连上，稍后再试";
      nextRetry=millis()+5000;
    }
    delete r;if(redraw||previousMessage!=message)dirty=true;statusHeader();
  }
  if(!busy&&WiFi.status()==WL_CONNECTED&&pairSecret.length()&&(pairBody.length()||pairWaiting)&&(int32_t)(millis()-pairPoll)>=0) {
    send(pairBody.length()?"/api/math/pair/claim":"/api/math/pair/status",pairBody);pairPoll=millis()+3000;return;
  }
  if(!busy && WiFi.status()==WL_CONNECTED && (int32_t)(millis()-nextRetry)>=0) {
    if(pendingBody.length()) {send(pendingPath,pendingBody);nextRetry=millis()+5000;}
    else if(refreshAfter||(!ready)||(screen==0&&(int32_t)(millis()-nextPoll)>=0)) {
      refreshAfter=false;nextRetry=millis()+5000;nextPoll=millis()+30000;sync();
    }
  }
}
#include "screen_ui.h"
}
