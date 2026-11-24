#include <algorithm>
#include <cmath>
#include <cstdio>
#include <iostream>
#include <fstream>
#include <string>
#include <vector>
#include <ArduinoJson.h>
using std::min;
class String:public std::string {
public:
 using std::string::string;
 String():std::string(){} String(const std::string&s):std::string(s){}
 String(char c):std::string(1,c){} String(int n):std::string(std::to_string(n)){}
 String(unsigned n):std::string(std::to_string(n)){}
 String operator+(const String&s)const{return String(static_cast<const std::string&>(*this)+static_cast<const std::string&>(s));}
 String operator+(const char*s)const{return *this+String(s);}
 String operator+(int n)const{return *this+String(n);}
 String operator+(unsigned n)const{return *this+String(n);}
 String operator+(char c)const{return *this+String(c);}
 friend String operator+(const char*s,const String&other){return String(s)+other;}
 bool isEmpty()const{return empty();}
 bool startsWith(const char*s)const{return rfind(s,0)==0;}
 String substring(size_t a,size_t b)const{return substr(a,b-a);}
 void remove(size_t a){erase(a);} void remove(size_t a,size_t n){erase(a,n);}
 int indexOf(char c)const{auto n=find(c);return n==npos?-1:int(n);}
 double toDouble()const{try{return std::stod(*this);}catch(...){return 0;}}
};
namespace ArduinoJson {template<>struct Converter<String>{
 static void toJson(const String&s,JsonVariant dst){dst.set(std::string(s));}
 static String fromJson(JsonVariantConst src){return src.as<std::string>();}
 static bool checkJson(JsonVariantConst src){return src.is<const char*>();}
};}
#include "../src/ui_controls.h"
#include "../src/network_status.h"
DynamicJsonDocument commands(131072);
void cmd(const char*name,std::initializer_list<int> args){auto a=commands.as<JsonArray>().createNestedArray();a.add(name);for(int n:args)a.add(n);}
struct Canvas {
 void fillSprite(int c){cmd("fillRect",{0,0,320,240,c});}
 void fillRect(int x,int y,int w,int h,int c){cmd("fillRect",{x,y,w,h,c});}
 void fillRoundRect(int x,int y,int w,int h,int r,int c){cmd("roundRect",{x,y,w,h,r,c});}
 void fillCircle(int x,int y,int r,int c){cmd("circle",{x,y,r,c});}
 void fillTriangle(int x,int y,int xx,int yy,int xxx,int yyy,int c){cmd("triangle",{x,y,xx,yy,xxx,yyy,c});}
 void drawLine(int x,int y,int xx,int yy,int c){cmd("line",{x,y,xx,yy,c});}
 void drawFastHLine(int x,int y,int w,int c){drawLine(x,y,x+w,y,c);}
 void drawFastVLine(int x,int y,int h,int c){drawLine(x,y,x,y+h,c);}
 void setTextColor(int,int){}
 void drawString(const String&s,int x,int y,int){auto a=commands.as<JsonArray>().createNestedArray();a.add("text");a.add(x);a.add(y+16);a.add(0x2947);a.add(std::string(s));}
}lcd;
struct Font {
 int color=0,x=0,y=0;
 int getUTF8Width(const char*s){int width=0;for(const unsigned char*p=(const unsigned char*)s;*p;++p)if((*p&0xc0)!=0x80)width+=*p<128?8:16;return width;}
 void setForegroundColor(int c){color=c;}void setCursor(int xx,int yy){x=xx;y=yy;}
 void print(const char*s){auto a=commands.as<JsonArray>().createNestedArray();a.add("text");a.add(x);a.add(y);a.add(color);a.add(std::string(s));}
}chinese;
struct Logger {template<class...Args>void printf(const char*f,Args...args){std::fprintf(stderr,f,args...);}}Serial;
constexpr int TFT_WHITE=65535;
constexpr uint16_t cream=0xFF9A,green=0x346C,ink=0x2947,pale=0xD718,blue=0x8D9F,gold=0xFE48;
bool frameDirty=true,dirty=true;
using Button=adventure::ButtonRect;Button buttons[16];uint8_t buttonCount=0;
#include "../src/screen_primitives.h"
namespace online {
DynamicJsonDocument state(24576);
String base="本地预览，不连接服务器",credential="desktop-local",pendingBody,message="电脑本地试玩 · 不影响正式记录",mode="practice";
String pairCode,pairVerification,apName,apPassword,detail,numberInput,mapMessage;
bool busy=false,ready=true,setupActive=false,refreshAfter=false,pairOpenRequested=false,authInvalid=false;
int selected=0,screen=0,contentPage=0,detailKind=0,serverPage=0,mapOffset=0,xp=0,coins=0,nextRetry=0;
struct Config{void putString(const char*,const String&) {}}config;
JsonArray tasks(){return state["tasks"].as<JsonArray>();}
JsonObject task(){return tasks()[selected].as<JsonObject>();}
JsonObject question(){return task()["question"].as<JsonObject>();}
void sync(){message="本地预览，无需同步";dirty=true;}
void statusHeader(){connectionIcon(adventure::Connection::Online);}
String connectionLabel(){return "本地预览，网络图标示意";}
String networkDetails(){return "IP 192.168.0.123";}
bool wifiConfiguredOnline(){return true;}
void resetConnection(){refreshAfter=true;nextRetry=0;}
bool send(const String&){message="电脑预览无需连接";return true;}
void beginPair(){message="电脑预览不配对真实设备";screen=0;dirty=true;}
void claimPair(){}void startPortal(){message="电脑使用本地数据，无需配网";screen=0;dirty=true;}
void requestExploration(){mapMessage="本地题目已全部在地图中";dirty=true;}
void submit(const char*path,int answer=-1,bool numeric=false){
 auto solution=question()["solution"];String action=path;contentPage=0;
 if(action=="/api/math/answers"){
  bool correct=(numeric?numberInput.toDouble():answer)==solution["answer"].as<double>();
  detailKind=correct?0:2;
  detail=correct?solution["explanation"].as<String>():String("没关系，换个办法再试试。可以先找一找题目里的条件，也可以请小向导给个提示。");
  if(correct){if(task()["state"]!="solved"){xp+=20;coins+=5;}task()["state"]="solved";task()["explanation"]=solution["explanation"];}
  screen=correct?9:3;
 }else if(action=="/api/math/tasks/hint"){
  auto hints=solution["hints"].as<JsonArray>();int n=task()["hint_count"]|0;
  detail=hints[min(n,(int)hints.size()-1)].as<String>();task()["hint_count"]=n+1;detailKind=1;screen=3;
 }else{task()["state"]="skipped";screen=1;}
 dirty=true;
}
#include "../src/screen_ui.h"
}
int main(int argc,char**argv){
 if(argc!=2)return 2;
 std::ifstream fixture(argv[1]);if(deserializeJson(online::state,fixture))return 3;
 std::string line;
 while(std::getline(std::cin,line)){
  DynamicJsonDocument input(1024);if(deserializeJson(input,line))return 4;
  if(input["reset"]==true){fixture.clear();fixture.seekg(0);deserializeJson(online::state,fixture);online::screen=0;online::selected=0;online::xp=online::coins=0;online::mapOffset=online::contentPage=0;}
  if(input.containsKey("x")){int index=adventure::hitButton(buttons,buttonCount,input["x"]|0,input["y"]|0);if(index>=0)online::handle(buttons[index].action);}
  commands.to<JsonArray>();online::render();
  DynamicJsonDocument response(196608);response["commands"]=commands;auto controls=response.createNestedArray("buttons");
  for(int i=0;i<buttonCount;++i){auto b=controls.createNestedObject();b["x"]=buttons[i].x;b["y"]=buttons[i].y;b["w"]=buttons[i].w;b["h"]=buttons[i].h;b["action"]=buttons[i].action;}
  response["screen"]=online::screen;serializeJson(response,std::cout);std::cout<<std::endl;
 }
}
