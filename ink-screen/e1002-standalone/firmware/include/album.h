#pragma once
#include <LittleFS.h>
#include "core.h"

class Album {
  struct Header { uint32_t magic,seq,crc; char request[33]; uint8_t reserved[3]; };
  static constexpr uint32_t MAGIC=0x45313032;
  uint32_t ids[9]={}; int count_=0;
  SemaphoreHandle_t mutex=xSemaphoreCreateMutex();
  String path(uint32_t seq) { char name[24]; snprintf(name,sizeof(name),"/p%010lu.bin",(unsigned long)seq); return name; }
  struct Lock {
    SemaphoreHandle_t m;
    explicit Lock(SemaphoreHandle_t m):m(m) { xSemaphoreTake(m,portMAX_DELAY); }
    ~Lock() { xSemaphoreGive(m); }
  };
  void prune() {
    while(count_>8) { if(!LittleFS.remove(path(ids[0]))) break; for(int i=1;i<count_;i++) ids[i-1]=ids[i]; --count_; }
  }
public:
  // Call only at boot, before either HTTP server starts. No auto-format on corruption.
  bool begin() {
    if(!LittleFS.begin(false)) return false;
    LittleFS.remove("/incoming.tmp");
    uint8_t *check=(uint8_t*)ps_malloc(standalone::BITMAP_BYTES);
    if(!check) return false;
    File root=LittleFS.open("/"); File f=root.openNextFile();
    while(f) {
      Header h={};
      bool valid=f.size()==sizeof(h)+standalone::BITMAP_BYTES && f.read((uint8_t*)&h,sizeof(h))==sizeof(h) && h.magic==MAGIC && h.seq>0 && h.request[32]==0;
      String name=f.path();
      valid=valid && name==path(h.seq) && f.read(check,standalone::BITMAP_BYTES)==standalone::BITMAP_BYTES &&
        standalone::validPixels(check,standalone::BITMAP_BYTES) && standalone::crc32(check,standalone::BITMAP_BYTES)==h.crc;
      f.close();
      if(valid) {
        if(count_==9) prune();
        if(count_<9) { ids[count_++]=h.seq; std::sort(ids,ids+count_); }
      } else if(name.startsWith("/p") && name.endsWith(".bin")) LittleFS.remove(name);
      f=root.openNextFile();
    }
    root.close(); free(check); prune(); return true;
  }
  int count() { Lock lock(mutex); return count_; }
  uint32_t latest() { Lock lock(mutex); return count_ ? ids[count_-1] : 0; }
  uint32_t adjacent(uint32_t current,int direction) {
    Lock lock(mutex); if(!count_) return 0;
    int i=0; while(i<count_-1 && ids[i]<current) ++i;
    i=std::max(0,std::min(count_-1,i+direction)); return ids[i];
  }
  bool read(uint32_t id,uint8_t *out) {
    Lock lock(mutex); File f=LittleFS.open(path(id)); Header h={};
    return f && f.read((uint8_t*)&h,sizeof(h))==sizeof(h) && h.magic==MAGIC && h.seq==id &&
      f.read(out,standalone::BITMAP_BYTES)==standalone::BITMAP_BYTES && standalone::crc32(out,standalone::BITMAP_BYTES)==h.crc;
  }
  uint32_t save(const uint8_t *pixels,uint32_t crc,const char *request) {
    Lock lock(mutex);
    // Retrying an ambiguous HTTP response must not create another photograph.
    for(int i=0;i<count_;i++) {
      File f=LittleFS.open(path(ids[i])); Header h={};
      if(f && f.read((uint8_t*)&h,sizeof(h))==sizeof(h) && !strcmp(h.request,request)) return h.crc==crc ? h.seq : 0;
    }
    prune(); if(count_>=9) return 0;
    Header h={}; h.magic=MAGIC; h.seq=count_ ? ids[count_-1]+1 : 1; h.crc=crc;
    if(!h.seq) return 0;
    memcpy(h.request,request,32);
    File f=LittleFS.open("/incoming.tmp","w");
    if(!f) return 0;
    bool ok=f.write((const uint8_t*)&h,sizeof(h))==sizeof(h) && f.write(pixels,standalone::BITMAP_BYTES)==standalone::BITMAP_BYTES;
    f.flush(); f.close();
    // Read back before the atomic rename publishes a valid photograph.
    File verify=LittleFS.open("/incoming.tmp");
    uint8_t chunk[1024]; uint32_t c=~0U;
    ok=ok && verify && verify.size()==sizeof(h)+standalone::BITMAP_BYTES && verify.seek(sizeof(h));
    if(ok) for(int n=0;n<standalone::BITMAP_BYTES;n+=sizeof(chunk)) {
      size_t len=std::min(size_t(sizeof(chunk)),size_t(standalone::BITMAP_BYTES-n));
      if(verify.read(chunk,len)!=len) { ok=false; break; }
      for(size_t k=0;k<len;k++) { c^=chunk[k]; for(int j=0;j<8;j++) c=(c>>1)^(0xedb88320U & (0U-(c&1))); }
    }
    verify.close(); ok=ok && ~c==crc;
    if(!ok || !LittleFS.rename("/incoming.tmp",path(h.seq))) { LittleFS.remove("/incoming.tmp"); return 0; }
    ids[count_++]=h.seq; prune(); return h.seq;
  }
  bool clear() {
    Lock lock(mutex); File root=LittleFS.open("/"); File f=root.openNextFile(); bool ok=true;
    while(f) { String name=f.path(); f.close(); ok=LittleFS.remove(name) && ok; f=root.openNextFile(); }
    root.close(); if(ok) count_=0; return ok;
  }
};
