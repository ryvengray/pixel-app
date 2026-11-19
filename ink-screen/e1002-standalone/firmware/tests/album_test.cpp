#include <assert.h>
#include <iostream>
#include "album.h"
using namespace standalone;
String path(int n){char b[24];snprintf(b,sizeof(b),"/p%010d.bin",n);return b;}
int main(){
  Album a;assert(a.begin());std::vector<uint8_t> pixels(BITMAP_BYTES,0x15),out(BITMAP_BYTES);
  uint32_t crc=crc32(pixels.data(),pixels.size());
  for(int n=1;n<=9;n++){char id[33];snprintf(id,sizeof(id),"%032x",n);assert(a.save(pixels.data(),crc,id)==uint32_t(n));}
  assert(a.count()==8&&a.latest()==9);assert(!LittleFS.files.count(path(1)));
  assert(a.adjacent(2,-1)==2&&a.adjacent(9,1)==9&&a.adjacent(5,-1)==4);
  assert(a.read(9,out.data())&&out==pixels);
  assert(a.save(pixels.data(),crc,"00000000000000000000000000000009")==9);assert(a.count()==8); // HTTP retry
  assert(a.save(pixels.data(),crc+1,"00000000000000000000000000000009")==0); // same id, different payload
  LittleFS.failRename=true;
  assert(!a.save(pixels.data(),crc,"0000000000000000000000000000000a"));assert(a.latest()==9&&a.count()==8);assert(a.read(2,out.data()));
  LittleFS.failRename=false;LittleFS.shortWrite=true;
  assert(!a.save(pixels.data(),crc,"0000000000000000000000000000000a"));assert(a.count()==8&&a.read(9,out.data()));LittleFS.shortWrite=false;
  // Simulate loss of power while writing: temporary data must not replace committed images.
  LittleFS.files["/incoming.tmp"]={1,2,3};
  Album recovered;assert(recovered.begin());assert(recovered.count()==8&&recovered.latest()==9);assert(!LittleFS.files.count("/incoming.tmp"));
  // A damaged latest record is excluded; previously committed photos remain readable.
  LittleFS.files[path(9)].back()^=0xff;Album damaged;assert(damaged.begin());assert(damaged.count()==7&&damaged.latest()==8);assert(damaged.read(8,out.data()));
  // Power loss after rename, before pruning: recovery keeps the latest eight, regardless of directory order.
  auto record=LittleFS.files[path(8)];
  for(uint32_t id: {9U,10U}) {memcpy(record.data()+4,&id,4);LittleFS.files[path(id)]=record;}
  Album afterCommit;assert(afterCommit.begin());assert(afterCommit.count()==8&&afterCommit.latest()==10);assert(!LittleFS.files.count(path(2)));
  assert(afterCommit.clear());assert(afterCommit.count()==0&&LittleFS.files.empty());
  std::cout<<"album commit, retry, eviction, I/O failure and restart recovery tests passed\n";
}
