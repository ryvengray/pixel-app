#include <assert.h>
#include <iostream>
#include "system_settings.h"
bool parse(const char *body,SystemRequest &out) { return parseSystemRequest(body,strlen(body),out); }
int main() {
  SystemRequest out={};
  assert(parse("{\"action\":\"calendar\"}",out) && out.action==SystemAction::Calendar);
  assert(parse("{\"action\":\"photo\"}",out) && out.action==SystemAction::Photo);
  assert(parse("{\"action\":\"reconfigure\"}",out) && out.action==SystemAction::Reconfigure);
  assert(parse("{\"action\":\"factory_reset\"}",out) && out.action==SystemAction::FactoryReset);
  assert(parse("{\"action\":\"sync_time\",\"epoch\":1794830400,\"offset\":480}",out));
  assert(out.action==SystemAction::SyncTime && out.epoch==1794830400LL && out.offset==480);
  assert(parse("{\"action\":\"sync_time\",\"epoch\":2208988800,\"offset\":345}",out)); // beyond 2038
  assert(parse("{\"action\":\"sync_time\",\"epoch\":1577836800,\"offset\":-720}",out));
  assert(parse("{\"action\":\"sync_time\",\"epoch\":4102444799,\"offset\":840}",out));
  const char *invalid[]={"", "{", "[]", "null", "{\"action\":null}", "{\"action\":1}", "{\"action\":\"erase_all\"}",
    "{\"action\":\"sync_time\"}",
    "{\"action\":\"sync_time\",\"epoch\":\"1794830400\",\"offset\":480}",
    "{\"action\":\"sync_time\",\"epoch\":1794830400,\"offset\":\"480\"}",
    "{\"action\":\"sync_time\",\"epoch\":false,\"offset\":480}",
    "{\"action\":\"sync_time\",\"epoch\":1577836799,\"offset\":480}",
    "{\"action\":\"sync_time\",\"epoch\":4102444800,\"offset\":480}",
    "{\"action\":\"sync_time\",\"epoch\":1794830400,\"offset\":481}",
    "{\"action\":\"sync_time\",\"epoch\":1794830400,\"offset\":-735}",
    "{\"action\":\"sync_time\",\"epoch\":1794830400,\"offset\":855}"};
  for(auto body:invalid) assert(!parse(body,out));
  std::cout<<"settings actions, time bounds, timezone bounds and invalid JSON tests passed\n";
}
