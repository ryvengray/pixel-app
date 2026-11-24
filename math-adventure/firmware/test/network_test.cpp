#include <cassert>
#include <string>
#include <ArduinoJson.h>
#include "../src/network_status.h"
using namespace adventure;
int main(){
  assert(connectionState(false,true,true,true)==Connection::Offline);
  assert(connectionState(true,false,false,true)==Connection::Checking);
  assert(connectionState(true,true,false,true)==Connection::Unreachable);
  assert(connectionState(true,true,true,true)==Connection::Unauthorized);
  assert(connectionState(true,true,true,false)==Connection::Online);
  assert(rejectedOperation(409)&&rejectedOperation(404)&&rejectedOperation(422));
  assert(!rejectedOperation(-1)&&!rejectedOperation(503)&&!rejectedOperation(401));
  assert(!resumePairPolling(true,false,false)); // A code has not been submitted yet.
  assert(!resumePairPolling(true,true,false)); // Retry the persisted claim first.
  assert(resumePairPolling(true,false,true)); // Resume phone confirmation after reboot.
  assert(!resumePairPolling(false,false,true));
  DynamicJsonDocument queue(2048);
  assert(!deserializeJson(queue,R"([{"body":"old skip","blocked":true,"http_status":409},{"body":"new answer"}])"));
  assert(pendingOperationIndex(queue)==1);
  queue.remove(pendingOperationIndex(queue));
  assert(queue.size()==1&&queue[0]["body"]=="old skip");
  assert(pendingOperationIndex(queue)==-1); // Retained failures cannot block state refresh or pairing.
  std::string persisted;serializeJson(queue,persisted);
  DynamicJsonDocument rebooted(2048);assert(!deserializeJson(rebooted,persisted));
  assert(pendingOperationIndex(rebooted)==-1);
  rebooted.createNestedObject()["body"]="fresh operation";
  assert(pendingOperationIndex(rebooted)==1);
}
