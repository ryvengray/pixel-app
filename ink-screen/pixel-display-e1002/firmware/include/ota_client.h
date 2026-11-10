#pragma once
#include <esp_ota_ops.h>
#include "firmware_version.h"

void reportFirmware(const char *status) {
  HTTPClient h; h.setTimeout(5000); h.begin(base()+"/firmware/status"); headers(h);
  h.addHeader("Content-Type","application/json");
  h.POST(String("{\"build\":")+FIRMWARE_BUILD+",\"status\":\""+status+"\"}"); h.end();
}
bool installFirmware(int build, size_t size, const String &expected) {
  const esp_partition_t *partition=esp_ota_get_next_update_partition(nullptr);
  if(!partition || size>partition->size) return false;
  HTTPClient h; h.setTimeout(15000);
  h.begin(base()+"/firmware.bin?build="+String(build)); headers(h);
  if(h.GET()!=200 || h.getSize()!=static_cast<int>(size)) { h.end(); return false; }
  esp_ota_handle_t handle;
  if(esp_ota_begin(partition,size,&handle)!=ESP_OK) { h.end(); return false; }
  mbedtls_sha256_context hash; mbedtls_sha256_init(&hash); mbedtls_sha256_starts_ret(&hash,0);
  uint8_t buffer[2048]; size_t received=0;
  unsigned long started=millis(), lastData=started;
  WiFiClient *stream=h.getStreamPtr(); bool good=true;
  while(received<size && millis()-started<180000UL && millis()-lastData<15000UL) {
    int available=stream->available();
    if(available<=0) { if(!h.connected()) break; delay(5); continue; }
    size_t count=min(sizeof(buffer),min(static_cast<size_t>(available),size-received));
    int n=stream->read(buffer,count);
    if(n<=0) { delay(5); continue; }
    if(esp_ota_write(handle,buffer,n)!=ESP_OK || mbedtls_sha256_update_ret(&hash,buffer,n)!=0) { good=false; break; }
    received+=n; lastData=millis(); yield();
  }
  unsigned char digest[32]; mbedtls_sha256_finish_ret(&hash,digest); mbedtls_sha256_free(&hash); h.end();
  char hex[65]; for(int i=0;i<32;++i) sprintf(hex+i*2,"%02x",digest[i]);
  if(!good || received!=size || expected!=String(hex)) { esp_ota_abort(handle); return false; }
  // esp_ota_end validates the ESP image; only then change the boot partition.
  if(esp_ota_end(handle)!=ESP_OK) return false;
  return esp_ota_set_boot_partition(partition)==ESP_OK;
}
void checkFirmwareUpdate() {
  reportFirmware("running");
  HTTPClient h; h.setTimeout(10000); h.begin(base()+"/firmware"); headers(h);
  int code=h.GET(); String body=code==200 ? h.getString() : ""; h.end();
  DynamicJsonDocument doc(1024);
  if(code!=200 || deserializeJson(doc,body) || doc["target"].as<String>()!="e1002") return;
  JsonObject release=doc["release"].as<JsonObject>();
  if(!release["build"].is<int>() || !release["size"].is<unsigned int>()) return;
  int build=release["build"].as<int>(); size_t size=release["size"].as<unsigned int>();
  String digest=release["sha256"].as<String>();
  if(build<=FIRMWARE_BUILD || size<288 || size>0x330000 || digest.length()!=64) return;
  for(int i=0;i<64;++i) if(!((digest[i]>='0' && digest[i]<='9') || (digest[i]>='a' && digest[i]<='f'))) return;
  if(readBatteryPercent()<20) { Serial.println("OTA postponed: low battery"); return; }
  if(!installFirmware(build,size,digest)) { reportFirmware("failed"); Serial.println("OTA failed; keeping current firmware"); return; }
  Serial.println("OTA verified; restarting"); Serial.flush(); delay(100); ESP.restart();
}
