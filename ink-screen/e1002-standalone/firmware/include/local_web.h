#pragma once
#include <esp_http_server.h>
#include <esp32s3/rom/miniz.h>
#include <ArduinoJson.h>
#include <atomic>
#include <lwip/sockets.h>
#include "album.h"
#include "web_assets.h"
#include "network_access.h"
#include "system_settings.h"

struct ConfigureRequest { char ssid[33],password[64]; int64_t epoch; int offset; };
class LocalWeb {
  httpd_handle_t setupServer=nullptr,uploadServer=nullptr;
  Album &album;
  String token;
  SemaphoreHandle_t statusMutex=xSemaphoreCreateMutex();
  String status="{}",networks="{\"networks\":[]}";
  QueueHandle_t configs=xQueueCreate(1,sizeof(ConfigureRequest));
  QueueHandle_t uploads=xQueueCreate(1,sizeof(uint32_t));
  QueueHandle_t systemRequests=xQueueCreate(1,sizeof(SystemRequest));
  uint32_t nextSystemId=0;
  std::atomic<bool> systemPending{false};
  static LocalWeb &self(httpd_req_t *r) { return *static_cast<LocalWeb*>(r->user_ctx); }
  static esp_err_t error(httpd_req_t *r,const char *code,const char *message) {
    httpd_resp_set_status(r,code); httpd_resp_set_type(r,"text/plain; charset=utf-8"); return httpd_resp_send(r,message,HTTPD_RESP_USE_STRLEN);
  }
  static esp_err_t json(httpd_req_t *r,const String &body) {
    httpd_resp_set_type(r,"application/json"); httpd_resp_set_hdr(r,"Cache-Control","no-store");
    return httpd_resp_send(r,body.c_str(),body.length());
  }
  bool onAP(httpd_req_t *r) {
    IPAddress ip=WiFi.softAPIP(); uint8_t expected[]={ip[0],ip[1],ip[2],ip[3]};
    return standalone::socketUsesIPv4(httpd_req_to_sockfd(r),expected);
  }
  bool authorized(httpd_req_t *r) {
    char header[96]={}; if(httpd_req_get_hdr_value_str(r,"Authorization",header,sizeof(header))!=ESP_OK) return false;
    String want="Bearer "+token; size_t len=strlen(header); unsigned diff=len^want.length();
    for(size_t i=0;i<want.length();i++) diff|=(i<len ? header[i] : 0)^want[i]; return diff==0;
  }
  static esp_err_t setupPage(httpd_req_t *r) {
    auto &s=self(r); if(!s.onAP(r)) return error(r,"403 Forbidden","配网页仅允许从设备热点打开");
    s.touch(); httpd_resp_set_type(r,"text/html; charset=utf-8"); httpd_resp_set_hdr(r,"Cache-Control","no-store");
    return httpd_resp_send(r,SETUP_HTML,HTTPD_RESP_USE_STRLEN);
  }
  static esp_err_t uploadPage(httpd_req_t *r) {
    // The page itself contains no capability. Reading the page is an explicit access.
    auto &s=self(r); s.touch(); httpd_resp_set_type(r,"text/html; charset=utf-8");
    httpd_resp_set_hdr(r,"Cache-Control","no-store"); httpd_resp_set_hdr(r,"Referrer-Policy","no-referrer");
    return httpd_resp_send(r,UPLOAD_HTML,HTTPD_RESP_USE_STRLEN);
  }
  static esp_err_t getStatus(httpd_req_t *r) {
    auto &s=self(r);
    if(r->handle==s.setupServer ? !s.onAP(r) : !s.authorized(r)) return error(r,"401 Unauthorized","请重新扫描设备二维码");
    xSemaphoreTake(s.statusMutex,portMAX_DELAY); String copy=s.status; xSemaphoreGive(s.statusMutex); return json(r,copy);
  }
  static esp_err_t getNetworks(httpd_req_t *r) {
    auto &s=self(r); if(!s.onAP(r)) return error(r,"403 Forbidden","请连接设备热点"); return json(r,s.networks);
  }
  static esp_err_t getSettings(httpd_req_t *r) {
    auto &s=self(r); if(!s.authorized(r)) return error(r,"401 Unauthorized","请重新扫描设备二维码");
    // An explicit tab visit extends the window; background status polls do not.
    s.touch();
    xSemaphoreTake(s.statusMutex,portMAX_DELAY); String copy=s.status; xSemaphoreGive(s.statusMutex);
    return json(r,copy);
  }
  static esp_err_t changeSettings(httpd_req_t *r) {
    auto &s=self(r); if(!s.authorized(r)) return error(r,"401 Unauthorized","请重新扫描设备二维码");
    if(r->content_len<2 || r->content_len>256) return error(r,"400 Bad Request","设置参数大小无效");
    char body[257]={}; size_t n=0;
    while(n<r->content_len) {
      int got=httpd_req_recv(r,body+n,r->content_len-n); if(got<=0) return ESP_FAIL; n+=got;
    }
    SystemRequest request={};
    if(!parseSystemRequest(body,n,request)) return error(r,"400 Bad Request","设置参数无效，请检查手机时间和时区");
    bool expected=false;
    if(s.transferring.load() || !s.systemPending.compare_exchange_strong(expected,true)) return error(r,"409 Conflict","设备正在处理操作，请稍后重试");
    request.id=++s.nextSystemId; request.queuedAt=millis();
    if(xQueueSend(s.systemRequests,&request,0)!=pdTRUE) {
      s.systemPending=false; return error(r,"409 Conflict","设备正在处理设置，请稍后重试");
    }
    s.touch(); httpd_resp_set_status(r,"202 Accepted");
    return json(r,String("{\"queued\":true,\"id\":")+request.id+"}");
  }
  static esp_err_t configure(httpd_req_t *r) {
    auto &s=self(r); if(!s.onAP(r)) return error(r,"403 Forbidden","请连接设备热点");
    if(r->content_len<2 || r->content_len>512) return error(r,"400 Bad Request","配网参数过长");
    char body[513]={}; size_t n=0;
    while(n<r->content_len) { int got=httpd_req_recv(r,body+n,r->content_len-n); if(got<=0) return ESP_FAIL; n+=got; }
    StaticJsonDocument<768> doc;
    if(deserializeJson(doc,body) || !doc["ssid"].is<const char*>() || !doc["password"].is<const char*>() || !doc["epoch"].is<int64_t>() || !doc["offset"].is<int>()) return error(r,"400 Bad Request","配网参数无效");
    String ssid=doc["ssid"].as<String>(),password=doc["password"].as<String>();
    int64_t epoch=doc["epoch"]; int offset=doc["offset"];
    if(!ssid.length() || ssid.length()>32 || password.length()>63 || (password.length()>0 && password.length()<8) || epoch<1577836800LL || epoch>=4102444800LL || offset< -720 || offset>840 || offset%15) return error(r,"400 Bad Request","请检查网络名、密码、手机时间或时区");
    ConfigureRequest request={}; ssid.toCharArray(request.ssid,sizeof(request.ssid)); password.toCharArray(request.password,sizeof(request.password)); request.epoch=epoch; request.offset=offset;
    if(s.connecting.load() || xQueueSend(s.configs,&request,0)!=pdTRUE) return error(r,"409 Conflict","设备正在连接，请稍候");
    s.touch(); return json(r,"{\"connecting\":true}");
  }
  static bool hex(const char *s,size_t n) { if(strlen(s)!=n) return false; for(size_t i=0;i<n;i++) if(!((s[i]>='0'&&s[i]<='9')||(s[i]>='a'&&s[i]<='f'))) return false; return true; }
  static esp_err_t photo(httpd_req_t *r) {
    auto &s=self(r); if(!s.authorized(r)) return error(r,"401 Unauthorized","上传码无效，请重新扫码");
    if(r->content_len<1 || r->content_len>standalone::BITMAP_BYTES) return error(r,"413 Payload Too Large","位图大小无效");
    if(!s.storageReady.load()) return error(r,"503 Service Unavailable","相册不可写，请在设备设置中恢复出厂以初始化存储");
    char encoding[16]={},crcHex[16]={},id[40]={};
    httpd_req_get_hdr_value_str(r,"X-Bitmap-Encoding",encoding,sizeof(encoding));
    httpd_req_get_hdr_value_str(r,"X-CRC32",crcHex,sizeof(crcHex));
    httpd_req_get_hdr_value_str(r,"X-Request-ID",id,sizeof(id));
    bool deflated=!strcmp(encoding,"deflate");
    if((!deflated && strcmp(encoding,"identity")) || !hex(crcHex,8) || !hex(id,32) || (!deflated && r->content_len!=standalone::BITMAP_BYTES)) return error(r,"400 Bad Request","位图参数无效");
    s.transferring=true;
    struct Guard { LocalWeb &s; ~Guard(){s.transferring=false;} } guard{s};
    uint8_t *input=(uint8_t*)ps_malloc(r->content_len),*output=deflated ? (uint8_t*)ps_malloc(standalone::BITMAP_BYTES) : input;
    if(!input || !output) { free(input); if(deflated) free(output); return error(r,"503 Service Unavailable","内存不足，请稍后重试"); }
    size_t n=0; uint32_t started=millis(); bool ok=true;
    while(n<r->content_len) {
      if(standalone::elapsed(millis(),started,120000)) { ok=false; break; }
      int got=httpd_req_recv(r,(char*)input+n,std::min(size_t(4096),r->content_len-n));
      if(got<=0) { ok=false; break; } n+=got;
    }
    if(ok && deflated) {
      auto *decoder=(tinfl_decompressor*)malloc(sizeof(tinfl_decompressor));
      if(!decoder) ok=false;
      else {
        tinfl_init(decoder); size_t inLen=n,outLen=standalone::BITMAP_BYTES;
        auto result=tinfl_decompress(decoder,input,&inLen,output,output,&outLen,TINFL_FLAG_PARSE_ZLIB_HEADER|TINFL_FLAG_USING_NON_WRAPPING_OUTPUT_BUF);
        ok=result==TINFL_STATUS_DONE && inLen==n && outLen==standalone::BITMAP_BYTES; free(decoder);
      }
    }
    uint32_t crc=strtoul(crcHex,nullptr,16);
    ok=ok && standalone::validPixels(output,standalone::BITMAP_BYTES) && standalone::crc32(output,standalone::BITMAP_BYTES)==crc;
    uint32_t seq=ok ? s.album.save(output,crc,id) : 0;
    free(input); if(deflated) free(output);
    if(!ok) return error(r,"422 Unprocessable Entity","位图长度、颜色、压缩数据或校验值不正确");
    if(!seq) return error(r,"507 Insufficient Storage","相册保存失败，旧照片已保留");
    xQueueOverwrite(s.uploads,&seq); s.touch(); return json(r,String("{\"saved\":true,\"seq\":")+seq+",\"refreshing\":true}");
  }
  void route(httpd_handle_t server,const char *uri,httpd_method_t method,esp_err_t (*handler)(httpd_req_t*)) {
    httpd_uri_t entry={}; entry.uri=uri; entry.method=method; entry.handler=handler; entry.user_ctx=this; httpd_register_uri_handler(server,&entry);
  }
public:
  std::atomic<uint32_t> accessed{0};
  std::atomic<bool> transferring{false},connecting{false},storageReady{false};
  explicit LocalWeb(Album &album):album(album) {}
  void setToken(const String &value) { token=value; }
  void touch() { accessed=millis(); }
  void updateStatus(const String &value) { xSemaphoreTake(statusMutex,portMAX_DELAY); status=value; xSemaphoreGive(statusMutex); }
  void setNetworks(const String &value) { networks=value; }
  bool takeConfig(ConfigureRequest &request) { return xQueueReceive(configs,&request,0)==pdTRUE; }
  bool takeUpload(uint32_t &id) { return xQueueReceive(uploads,&id,0)==pdTRUE; }
  bool takeSystemRequest(SystemRequest &request) { return xQueueReceive(systemRequests,&request,0)==pdTRUE; }
  void finishSystemRequest() { systemPending=false; }
  bool setupActive() { return setupServer!=nullptr; }
  bool uploadActive() { return uploadServer!=nullptr; }
  bool startSetup() {
    if(setupServer) { touch(); return true; }
    httpd_config_t cfg=HTTPD_DEFAULT_CONFIG(); cfg.server_port=80; cfg.ctrl_port=32768; cfg.stack_size=8192; cfg.recv_wait_timeout=5; cfg.send_wait_timeout=5; cfg.lru_purge_enable=true;
    if(httpd_start(&setupServer,&cfg)!=ESP_OK) { setupServer=nullptr; return false; }
    route(setupServer,"/api/configure",HTTP_POST,configure); route(setupServer,"/api/status",HTTP_GET,getStatus); route(setupServer,"/api/networks",HTTP_GET,getNetworks);
    route(setupServer,"/",HTTP_GET,setupPage);
    // Captive portal probes are deliberately not counted as user activity.
    httpd_register_err_handler(setupServer,HTTPD_404_NOT_FOUND,[](httpd_req_t *r,httpd_err_code_t){httpd_resp_set_status(r,"302 Found");httpd_resp_set_hdr(r,"Location","http://192.168.4.1/");return httpd_resp_send(r,"",0);});
    touch(); return true;
  }
  bool startUpload() {
    if(uploadServer) { touch(); return true; }
    httpd_config_t cfg=HTTPD_DEFAULT_CONFIG(); cfg.server_port=8080; cfg.ctrl_port=32769; cfg.stack_size=8192; cfg.recv_wait_timeout=5; cfg.send_wait_timeout=5; cfg.lru_purge_enable=true;
    if(httpd_start(&uploadServer,&cfg)!=ESP_OK) { uploadServer=nullptr; return false; }
    route(uploadServer,"/",HTTP_GET,uploadPage); route(uploadServer,"/api/photo",HTTP_POST,photo); route(uploadServer,"/api/status",HTTP_GET,getStatus);
    route(uploadServer,"/api/settings",HTTP_GET,getSettings); route(uploadServer,"/api/settings",HTTP_POST,changeSettings);
    touch(); return true;
  }
  void stop() {
    if(setupServer) { httpd_stop(setupServer); setupServer=nullptr; }
    if(uploadServer) { httpd_stop(uploadServer); uploadServer=nullptr; }
  }
};
