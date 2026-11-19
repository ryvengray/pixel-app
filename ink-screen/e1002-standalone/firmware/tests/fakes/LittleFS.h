#pragma once
// Host-only fault-injection adapter. The production Album class is unchanged.
#include <string>
#include <map>
#include <vector>
#include <memory>
#include <mutex>
#include <cstdlib>
#include <cstring>
#include <algorithm>
class String:public std::string {
public:
  using std::string::string;
  String(const std::string &s):std::string(s) {}
  bool startsWith(const char *s)const{return rfind(s,0)==0;}
  bool endsWith(const char *s)const{size_t n=strlen(s);return size()>=n && compare(size()-n,n,s)==0;}
};
using SemaphoreHandle_t=std::mutex*;
constexpr int portMAX_DELAY=0;
inline auto xSemaphoreCreateMutex(){return new std::mutex;}
inline void xSemaphoreTake(SemaphoreHandle_t m,int){m->lock();}
inline void xSemaphoreGive(SemaphoreHandle_t m){m->unlock();}
inline void *ps_malloc(size_t n){return malloc(n);}
class File;
class FakeFS {
public:
  std::map<String,std::vector<uint8_t>> files;
  bool failRename=false,shortWrite=false,mountOK=true;
  bool begin(bool){return mountOK;}
  bool remove(const String &s){return files.erase(s)>0;}
  bool rename(const String &a,const String &b){if(failRename||!files.count(a)||files.count(b))return false;files[b]=files[a];files.erase(a);return true;}
  File open(const String &,const char *mode="r");
};
inline FakeFS LittleFS;
class File {
  String name;
  size_t pos=0,index=0;
  bool valid=false,root=false;
  std::vector<String> entries;
public:
  File()=default;
  File(String n,bool exists):name(n),valid(exists),root(n=="/") {if(root)for(auto &p:LittleFS.files)entries.push_back(p.first);}
  explicit operator bool() const{return valid;}
  size_t size(){return valid&&!root ? LittleFS.files[name].size():0;}
  String path(){return name;}
  size_t read(uint8_t *out,size_t n){if(!valid)return 0;auto &v=LittleFS.files[name];n=std::min(n,v.size()-std::min(pos,v.size()));memcpy(out,v.data()+pos,n);pos+=n;return n;}
  size_t write(const uint8_t *in,size_t n){if(!valid)return 0;if(LittleFS.shortWrite)n=std::min(n,size_t(4));auto &v=LittleFS.files[name];v.resize(std::max(v.size(),pos+n));memcpy(v.data()+pos,in,n);pos+=n;return n;}
  bool seek(size_t n){pos=n;return valid&&n<=size();}
  void flush(){}
  void close(){valid=false;}
  File openNextFile(){if(index==entries.size())return {};auto s=entries[index++];return File(s,LittleFS.files.count(s));}
};
inline File FakeFS::open(const String &s,const char *mode){if(s=="/")return File(s,true);if(mode[0]=='w')files[s].clear();return File(s,files.count(s));}
