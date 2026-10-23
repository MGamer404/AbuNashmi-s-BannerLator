// slotcheck — command-line front end of slot_discovery.cpp for CI and desk checks.
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 The412Banner (Bannerlator).
//
// Usage: slotcheck [--dump-shapes] <libsteamclient.so> [valve-version]
// Prints the discovery result as one JSON object; exit 0 = every slot found and every
// fingerprint matched, 1 = refused, 2 = usage. It only reads the file — nothing is executed.
// Not part of the APK: the app runs the same code through `libblsteamhost.so --check`.
#include <cstdio>
#include <cstring>
#include <string>

#include "slot_discovery.h"

int main(int argc, char** argv) {
    bool dump = false;
    int i = 1;
    if (i < argc && !strcmp(argv[i], "--dump-shapes")) { dump = true; ++i; }
    if (i >= argc) { fprintf(stderr, "usage: %s [--dump-shapes] <libsteamclient.so> [valve-version]\n", argv[0]); return 2; }
    std::string lib = argv[i++];
    std::string ver = i < argc ? argv[i] : "";
    blhost::DiscoveredSlots d = blhost::discover_slots(lib, dump);
    printf("%s\n", blhost::discovered_json(d, ver).c_str());
    return d.ok ? 0 : 1;
}
