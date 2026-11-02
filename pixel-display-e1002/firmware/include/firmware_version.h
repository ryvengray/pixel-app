#pragma once
// Increment for every released build. Never reuse a build number.
#ifndef FIRMWARE_BUILD
#define FIRMWARE_BUILD 1
#endif
#define INK_STRINGIFY_INNER(x) #x
#define INK_STRINGIFY(x) INK_STRINGIFY_INNER(x)
static const char FIRMWARE_MARKER[] = "INK_E1002_BUILD=" INK_STRINGIFY(FIRMWARE_BUILD) ";";
