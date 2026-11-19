#pragma once
#include <stdint.h>
#include <string.h>
#ifdef ARDUINO
#include <lwip/sockets.h>
#else
#include <sys/socket.h>
#include <netinet/in.h>
#endif

namespace standalone {
inline bool mappedIPv4Equals(const uint8_t *ipv6,const uint8_t *ipv4) {
  static const uint8_t prefix[]={0,0,0,0,0,0,0,0,0,0,255,255};
  return !memcmp(ipv6,prefix,sizeof(prefix)) && !memcmp(ipv6+12,ipv4,4);
}
// ESP-IDF HTTPD uses a dual-stack listener when IPv6 is enabled. IPv4
// clients then have AF_INET6 local addresses such as ::ffff:192.168.4.1.
// Never read that sockaddr as sockaddr_in, and never trust the Host header.
inline bool socketUsesIPv4(int fd,const uint8_t *expected) {
  sockaddr_storage local={}; socklen_t len=sizeof(local);
  if(getsockname(fd,reinterpret_cast<sockaddr*>(&local),&len)!=0) return false;
  if(local.ss_family==AF_INET && len>=sizeof(sockaddr_in)) {
    auto *address=reinterpret_cast<const sockaddr_in*>(&local);
    return !memcmp(&address->sin_addr,expected,4);
  }
  if(local.ss_family==AF_INET6 && len>=sizeof(sockaddr_in6)) {
    auto *address=reinterpret_cast<const sockaddr_in6*>(&local);
    return mappedIPv4Equals(reinterpret_cast<const uint8_t*>(&address->sin6_addr),expected);
  }
  return false;
}
}
