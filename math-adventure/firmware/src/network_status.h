#pragma once
#include <stdint.h>
namespace adventure {
enum class Connection { Offline, Checking, Unreachable, Unauthorized, Online };
inline Connection connectionState(bool wifi,bool checked,bool reachable,bool unauthorized) {
  if(!wifi)return Connection::Offline;
  if(!checked)return Connection::Checking;
  if(!reachable)return Connection::Unreachable;
  return unauthorized?Connection::Unauthorized:Connection::Online;
}
inline bool rejectedOperation(int status) {return status==400||status==404||status==409||status==422;}
inline bool resumePairPolling(bool secret,bool claim,bool awaitingConfirmation) {
  return secret&&!claim&&awaitingConfirmation;
}
template<class Queue> int pendingOperationIndex(const Queue &queue) {
  for(unsigned i=0;i<queue.size();++i)if(queue[i]["blocked"]!=true)return int(i);
  return -1;
}
}
