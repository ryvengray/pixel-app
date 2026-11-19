#include <assert.h>
#include <unistd.h>
#include <iostream>
#include "network_access.h"

void checkListener(bool dualStack) {
  int listener=socket(dualStack ? AF_INET6:AF_INET,SOCK_STREAM,0); assert(listener>=0);
  uint16_t port=0;
  if(dualStack) {
    int disabled=0; assert(setsockopt(listener,IPPROTO_IPV6,IPV6_V6ONLY,&disabled,sizeof(disabled))==0);
    sockaddr_in6 address={}; address.sin6_family=AF_INET6; address.sin6_addr=in6addr_any;
    assert(bind(listener,reinterpret_cast<sockaddr*>(&address),sizeof(address))==0);
    socklen_t length=sizeof(address); assert(getsockname(listener,reinterpret_cast<sockaddr*>(&address),&length)==0); port=address.sin6_port;
  } else {
    sockaddr_in address={}; address.sin_family=AF_INET; address.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
    assert(bind(listener,reinterpret_cast<sockaddr*>(&address),sizeof(address))==0);
    socklen_t length=sizeof(address); assert(getsockname(listener,reinterpret_cast<sockaddr*>(&address),&length)==0); port=address.sin_port;
  }
  assert(listen(listener,1)==0);
  int client=socket(AF_INET,SOCK_STREAM,0); assert(client>=0);
  sockaddr_in target={}; target.sin_family=AF_INET; target.sin_addr.s_addr=htonl(INADDR_LOOPBACK); target.sin_port=port;
  assert(connect(client,reinterpret_cast<sockaddr*>(&target),sizeof(target))==0);
  int accepted=accept(listener,nullptr,nullptr); assert(accepted>=0);
  const uint8_t expected[]={127,0,0,1},otherInterface[]={192,168,4,1};
  assert(standalone::socketUsesIPv4(accepted,expected));
  assert(!standalone::socketUsesIPv4(accepted,otherInterface));
  if(dualStack) {
    // Reproduce the original rejection: the truncated IPv6 sockaddr does not
    // put the IPv4 address in sockaddr_in::sin_addr.
    sockaddr_in old={}; socklen_t length=sizeof(old);
    assert(getsockname(accepted,reinterpret_cast<sockaddr*>(&old),&length)==0);
    assert(memcmp(&old.sin_addr,expected,4)!=0);
  }
  close(accepted);close(client);close(listener);
}
int main() {
  checkListener(false); checkListener(true);
  const uint8_t ip[]={192,168,4,1}; uint8_t address[16]={};
  memcpy(address+12,ip,4); assert(!standalone::mappedIPv4Equals(address,ip));
  address[10]=address[11]=255; assert(standalone::mappedIPv4Equals(address,ip));
  assert(!standalone::socketUsesIPv4(-1,ip));
  std::cout<<"IPv4, dual-stack IPv4-mapped IPv6, wrong-interface and invalid-socket tests passed\n";
}
