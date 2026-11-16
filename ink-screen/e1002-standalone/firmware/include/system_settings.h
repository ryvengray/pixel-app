#pragma once
#include <ArduinoJson.h>
#include <stdint.h>
#include <string.h>

enum class SystemAction { Invalid,SyncTime,Calendar,Photo,Reconfigure,FactoryReset };
struct SystemRequest { SystemAction action; int64_t epoch; int offset; uint32_t id,queuedAt; };
inline bool parseSystemRequest(const char *body,size_t length,SystemRequest &request) {
  StaticJsonDocument<384> doc;
  if(deserializeJson(doc,body,length) || !doc.is<JsonObject>() || !doc["action"].is<const char*>()) return false;
  const char *action=doc["action"];
  request={};
  if(!strcmp(action,"sync_time")) {
    if(!doc["epoch"].is<int64_t>() || !doc["offset"].is<int>()) return false;
    request.epoch=doc["epoch"]; request.offset=doc["offset"];
    if(request.epoch<1577836800LL || request.epoch>=4102444800LL || request.offset< -720 || request.offset>840 || request.offset%15) return false;
    request.action=SystemAction::SyncTime;
  } else if(!strcmp(action,"calendar")) request.action=SystemAction::Calendar;
  else if(!strcmp(action,"photo")) request.action=SystemAction::Photo;
  else if(!strcmp(action,"reconfigure")) request.action=SystemAction::Reconfigure;
  else if(!strcmp(action,"factory_reset")) request.action=SystemAction::FactoryReset;
  else return false;
  return true;
}
