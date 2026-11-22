#pragma once
// Included in the UI namespace: only the main loop touches the screen or NVS.
namespace online {
Preferences config;
WebServer portal(80);
struct Work { String url, token, path, body; };
struct Result { int status; String path, body; };
QueueHandle_t jobs, replies;
String base, credential, pendingPath, pendingBody, message="正在连接小伙伴", mode, day;
String apName, apPassword, csrf;
bool busy=false, ready=false, setupActive=false, refreshAfter=false;
uint32_t nextPoll=0, nextRetry=0, setupUntil=0;
DynamicJsonDocument state(24576);
int selected=0, screen=0, contentPage=0; // home, map, question, method/hint, settings
String detail, resumeTask;
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
  if(busy||base.isEmpty()||credential.isEmpty()||WiFi.status()!=WL_CONNECTED) return false;
  Work *w=new Work{base,credential,path,body};
  if(xQueueSend(jobs,&w,0)!=pdTRUE) {delete w;return false;}
  busy=true;statusHeader();return true;
}
void sync() { send("/api/math/device/state"); }
JsonArray tasks() {return state["tasks"].as<JsonArray>();}
JsonObject task() {return tasks()[selected].as<JsonObject>();}
JsonObject question() {return task()["question"].as<JsonObject>();}
void submit(const char *path,int answer=-1) {
  if(busy||!pendingBody.isEmpty()) return;
  if(!ready||!task()["available"].as<bool>()) {message="请先同步今日任务";dirty=true;return;}
  DynamicJsonDocument body(512);
  char id[40];snprintf(id,sizeof(id),"%08lx%08lx%08lx%08lx",(unsigned long)esp_random(),(unsigned long)esp_random(),(unsigned long)esp_random(),(unsigned long)esp_random());
  body["request_id"]=id;body["task_id"]=task()["id"];
  if(answer>=0) body["answer"]=answer;
  String serialized;serializeJson(body,serialized);
  // Store path and payload atomically in one blob before any network activity.
  DynamicJsonDocument envelope(1024);envelope["path"]=path;envelope["body"]=serialized;
  String saved;serializeJson(envelope,saved);
  if(config.putString("pending",saved)!=saved.length()) {message="保存失败，请稍后再试";dirty=true;return;}
  pendingPath=path;pendingBody=serialized;message="正在送出，请稍等";
  send(pendingPath,pendingBody);dirty=true;
}
void startPortal() {
  if(apPassword.isEmpty()) {
    char pass[16];snprintf(pass,sizeof(pass),"%08lx",(unsigned long)esp_random());apPassword=pass;
    char name[32];snprintf(name,sizeof(name),"Math-Setup-%04lx",(unsigned long)(ESP.getEfuseMac()&0xffff));apName=name;
    char key[32];snprintf(key,sizeof(key),"%08lx%08lx",(unsigned long)esp_random(),(unsigned long)esp_random());csrf=key;
  }
  WiFi.mode(WIFI_AP_STA);WiFi.softAP(apName.c_str(),apPassword.c_str());setupActive=true;setupUntil=millis()+600000;
  screen=4;dirty=true;
}
void init() {
  config.begin("math-network",false);base=config.getString("base","https://ryven.xin/math");credential=config.getString("device","");
  DynamicJsonDocument envelope(1024);
  if(!deserializeJson(envelope,config.getString("pending",""))) {
    pendingPath=envelope["path"].as<String>();pendingBody=envelope["body"].as<String>();
  }
  jobs=xQueueCreate(1,sizeof(Work*));replies=xQueueCreate(1,sizeof(Result*));
  if(!jobs||!replies||xTaskCreate(worker,"math-http",12288,nullptr,1,nullptr)!=pdPASS) {
    message="网络初始化失败，请重启";return;
  }
  WiFi.mode(WIFI_STA);WiFi.setAutoReconnect(true);
  String ssid=config.getString("ssid","");
  if(ssid.length()) WiFi.begin(ssid.c_str(),config.getString("wifi","").c_str());else WiFi.begin();
  configTime(0,0,"pool.ntp.org","ntp.aliyun.com");
  portal.on("/",HTTP_GET,[](){
    if(!setupActive||portal.client().localIP()!=WiFi.softAPIP()) {portal.send(403);return;}
    String html="<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'><h2>数学冒险岛配网</h2><p>连接2.4GHz Wi-Fi。填写设备专用令牌，勿填写家长令牌。</p><form method=post action=/save>";
    html+="<input type=hidden name=csrf value='"+csrf+"'><p>Wi-Fi名称 <input name=ssid required maxlength=32 value='"+escape(config.getString("ssid",""))+"'></p><p>Wi-Fi密码 <input name=wifi type=password maxlength=64></p><p>后端地址 <input name=base required value='"+escape(base)+"'></p><p>设备令牌 <input name=device type=password maxlength=180></p><p>密码和令牌留空保留原值。开放Wi-Fi请勾选<input type=checkbox name=open>无密码</p><button>保存并连接</button></form>";
    portal.sendHeader("Cache-Control","no-store");portal.send(200,"text/html; charset=utf-8",html);
  });
  portal.on("/save",HTTP_POST,[](){
    if(!setupActive||portal.client().localIP()!=WiFi.softAPIP()||portal.arg("csrf")!=csrf) {portal.send(403);return;}
    if(!pendingBody.isEmpty()||busy) {portal.send(409,"text/plain; charset=utf-8","有未确认提交，请先同步，避免更换设备归属。");return;}
    String url=portal.arg("base");url.trim();while(url.endsWith("/"))url.remove(url.length()-1);
    String tok=portal.arg("device");tok.trim();if(tok.isEmpty())tok=credential;
    String ssid=portal.arg("ssid"),pass=portal.arg("wifi");
    if(pass.isEmpty()&&!portal.hasArg("open"))pass=config.getString("wifi","");
    if(!validUrl(url)||tok.length()<24||tok.length()>180||ssid.isEmpty()||ssid.length()>32||pass.length()>64) {portal.send(422,"text/plain; charset=utf-8","请检查地址、Wi-Fi与设备令牌");return;}
    config.putString("base",url);config.putString("device",tok);config.putString("ssid",ssid);config.putString("wifi",pass);
    base=url;credential=tok;ready=false;state.clear();
    portal.send(200,"text/html; charset=utf-8","已保存，可以回到屏幕等待同步。热点将在十分钟后关闭。");
    WiFi.disconnect(false);WiFi.begin(ssid.c_str(),pass.c_str());screen=0;message="正在连接小伙伴";nextPoll=0;dirty=true;
  });
  portal.begin();
  if(base.isEmpty()||credential.isEmpty())startPortal();
}
void tick() {
  portal.handleClient();
  if(setupActive && (int32_t)(millis()-setupUntil)>=0) {WiFi.softAPdisconnect(true);setupActive=false;dirty=true;}
  Result *r=nullptr;
  if(replies && xQueueReceive(replies,&r,0)==pdTRUE) {
    busy=false;bool redraw=false;String previousMessage=message;
    DynamicJsonDocument data(24576);bool parsed=!deserializeJson(data,r->body);
    if(parsed) {
      parsed=data["player"]["xp"].is<int>()&&data["player"]["coins"].is<int>()&&data["mode"].is<const char*>();
      if(r->path=="/api/math/device/state") parsed=parsed&&data["tasks"].is<JsonArray>()&&data["tasks"].size()<=3&&data["day"].is<const char*>();
      else {
        DynamicJsonDocument saved(512);deserializeJson(saved,pendingBody);
        parsed=parsed&&data["task_id"].as<String>()==saved["task_id"].as<String>()&&data["state"].is<const char*>();
      }
    }
    if(r->status==200&&parsed) {
      if(r->path=="/api/math/device/state") {
        String before,after;serializeJson(state,before);serializeJson(data,after);redraw=!ready||before!=after;
        String oldDay=day, oldId=resumeTask.length()?resumeTask:task()["id"].as<String>();resumeTask="";
        state=data;mode=state["mode"].as<String>();day=state["day"].as<String>();ready=true;
        xp=state["player"]["xp"]|0;coins=state["player"]["coins"]|0;
        selected=0;
        for(unsigned i=0;i<tasks().size();++i)if(tasks()[i]["id"].as<String>()==oldId) selected=i;
        if(oldDay.length()&&oldDay!=day){screen=0;contentPage=0;}
        message=tasks().size()?"冒险已同步":"请家长审核并安排题目";
      } else {
        // A valid server response is the only event that acknowledges a saved operation.
        redraw=true;resumeTask=data["task_id"].as<String>();
        config.remove("pending");pendingPath="";pendingBody="";
        xp=data["player"]["xp"]|xp;coins=data["player"]["coins"]|coins;
        if(data.containsKey("hint")) {detail=data["hint"].as<String>();screen=3;}
        else if(data.containsKey("correct")) {
          detail=data["correct"].as<bool>()?data["explanation"].as<String>():"再想一想，你可以的";
          screen=3;
        } else screen=1;
        contentPage=0;message=data["message"]|"同步成功";refreshAfter=true;
      }
    } else {
      if(r->status==401) {message="设备令牌无效，请检查配置";config.remove("pending");pendingBody="";pendingPath="";ready=false;}
      else if(r->status==409||r->status==404||r->status==422) {
        message="任务已变化，请重新同步";config.remove("pending");pendingBody="";pendingPath="";screen=0;refreshAfter=true;
      } else message=WiFi.status()!=WL_CONNECTED?"网络断开，正在重新连接":"同步未成功，稍后自动重试";
      nextRetry=millis()+5000;
    }
    delete r;if(redraw||previousMessage!=message)dirty=true;statusHeader();
  }
  if(!busy && WiFi.status()==WL_CONNECTED && (int32_t)(millis()-nextRetry)>=0) {
    if(pendingBody.length()) {send(pendingPath,pendingBody);nextRetry=millis()+5000;}
    else if(refreshAfter||(!ready)||(screen==0&&(int32_t)(millis()-nextPoll)>=0)) {
      refreshAfter=false;nextRetry=millis()+5000;nextPoll=millis()+30000;sync();
    }
  }
}
// UTF-8 wrapping keeps all text available; never truncate the original question or method.
String lines[64];int lineCount=0;
void wrap(String s) {
  String line;
  for(unsigned i=0;i<s.length();) {
    unsigned char c=s[i];int n=c<128?1:(c<224?2:(c<240?3:4));
    String glyph=s.substring(i,i+n);i+=n;
    if(glyph=="\n") {if(lineCount<64)lines[lineCount++]=line;line="";continue;}
    if(chinese.getUTF8Width((line+glyph).c_str())>288) {if(lineCount<64)lines[lineCount++]=line;line="";}
    line+=glyph;
  }
  if(line.length()&&lineCount<64)lines[lineCount++]=line;
}
void render() {
  dirty=false;buttonCount=0;lcd.fillScreen(cream);lcd.fillRect(0,0,320,28,pale);
  text("小语冒险岛",8,20);if(busy||pendingBody.length())text("同步中",260,20);else text(mode=="live"?"正式":(mode=="practice"?"试玩":"联网"),276,20);
  if(screen==4) {
    text("用手机连接热点配网",8,52);
    lcd.setTextColor(ink,cream);lcd.drawString(apName,8,68,2);lcd.drawString("Pass: "+apPassword,8,90,2);
    lcd.drawString("http://192.168.4.1",8,112,2);
    button(8,136,148,"校准触摸",41,false);button(164,136,148,"开启热点",103,false);
    button(72,188,176,"返回首页",100);return;
  }
  if(screen==0) {
    face(54,107,true);
    text("每天一点数学魔法",100,64);
    char buf[50];snprintf(buf,sizeof(buf),"经验 %d  金币 %d",xp,coins);text(buf,100,94);
    text(ready?(mode=="practice"?"独立试玩任务":"正式学习任务"):"等待服务器同步",100,125);
    text(message.c_str(),8,171);
    button(8,188,96,"小设置",104,false);button(112,188,200,busy?"同步中":"今日冒险",101);return;
  }
  if(screen==1) {
    if(!tasks().size())text("暂无任务，请家长安排",8,76);
    for(unsigned i=0;i<tasks().size()&&i<3;++i) {
      auto t=tasks()[i];String title=t["question"]["title"]|"题目停用";
      String shortTitle;
      for(unsigned j=0;j<title.length();) {unsigned char c=title[j];int n=c<128?1:(c<224?2:3);String next=title.substring(j,j+n);if(chinese.getUTF8Width((shortTitle+next).c_str())>160)break;shortTitle+=next;j+=n;}
      text(shortTitle.c_str(),8,53+i*48);
      String st=t["state"]|"pending";
      button(180,32+i*48,132,st=="solved"?"看方法":(st=="skipped"?"再想想":"帮助朋友"),110+i);
    }
    button(8,188,148,"回家休息",100,false);button(164,188,148,"重新同步",102,false);return;
  }
  lineCount=0;
  wrap(question()["content"].as<String>());
  for(unsigned i=0;i<question()["options"].size();++i) wrap(String(i+1)+"号："+question()["options"][i].as<String>());
  if(screen==3) {wrap("—— 一起想办法 ——");wrap(detail);}
  int pages=(lineCount+3)/4;if(pages<1)pages=1;if(contentPage>=pages)contentPage=0;
  for(int j=0;j<4;++j)if(contentPage*4+j<lineCount)text(lines[contentPage*4+j].c_str(),12,51+j*21);
  if(screen==2) {
    auto opts=question()["options"].as<JsonArray>();
    // Option labels are paginated alongside the question if they don't fit a button.
    if(contentPage==pages-1) {
      for(unsigned i=0;i<opts.size()&&i<3;++i) {
        String label=opts[i].as<String>();
        if(chinese.getUTF8Width(label.c_str())>88) label=String(i+1)+"号答案";
        button(8+i*104,130,96,label.c_str(),120+i);
      }
    }
    button(8,188,72,"提示",130,false);button(88,188,72,"稍后",131,false);
    button(168,188,72,"翻页",136,false);button(248,188,64,"地图",132,false);
  } else {
    button(8,188,96,"回题目",133,false);button(112,188,96,"翻页",136,false);button(216,188,96,"地图",135,false);
  }
}
void handle(int id) {
  if(id==100) {screen=0;dirty=true;return;}
  if(id==104) {startPortal();return;}
  if(id==103) {startPortal();return;}
  if(id==101) {screen=1;dirty=true;sync();return;}
  if(id==102) {sync();return;}
  if(busy||pendingBody.length()) {message="正在送出，请稍等";return;}
  if(id>=110&&id<113) {
    selected=id-110;if(selected>=(int)tasks().size()||!task()["available"].as<bool>())return;
    contentPage=0;
    if(task()["state"].as<String>()=="solved") {detail=task()["explanation"].as<String>();screen=3;}else screen=2;
  }
  if(id>=120&&id<123)submit("/api/math/answers",id-120);
  if(id==130)submit("/api/math/tasks/hint");
  if(id==131)submit("/api/math/tasks/skip");
  if(id==132||id==135)screen=1;
  if(id==136)contentPage++;
  if(id==133){screen=2;contentPage=0;}
  if(id==134){if(lineCount>4)contentPage++;else screen=1;}
  dirty=true;
}
}
