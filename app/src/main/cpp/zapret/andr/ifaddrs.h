#pragma once

#include_next <ifaddrs.h>

#if __ANDROID_API__ < 24
void freeifaddrs(struct ifaddrs *);
int getifaddrs(struct ifaddrs **);
#endif
