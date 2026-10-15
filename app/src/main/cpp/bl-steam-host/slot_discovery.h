// bl-steam-host — build-independent discovery of the private Steam client slots.
//
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 The412Banner (Bannerlator).
//
// Reads Valve's androidarm64 libsteamclient.so as a FILE (never executes it) and finds, by
// structure rather than by fixed index, every private vtable slot bl-steam-host calls.
// The same code runs in three places:
//   • inside the host before dlopen (runtime self-check, the gate for every build),
//   • `libblsteamhost.so --check <libsteamclient.so>` (the app checks a staged download
//     before it replaces a working install),
//   • the CI watcher (slotcheck_main.cpp built for the runner) that vets new Valve builds.
// A disagreement anywhere fails closed: the host refuses to drive the build.
//
// How each slot is found (all from data the library carries for itself):
//   1. RTTI: the typeinfo-name strings "14IClientUserMap", "14IClientAppsMap",
//      "17IClientFriendsMap" and "12CSteamClient" lead (through R_AARCH64_RELATIVE
//      relocations) to the typeinfo objects and from there to each class's primary vtable.
//      The *Map classes are the IPC proxies the engine hands out; CSteamClient is the
//      IClientEngine implementation.
//   2. Method names: the library keeps, per interface, a run of method-name strings right
//      after the "InterfaceMapBaseI<n>IClientXxxE" typeinfo name. Every proxy method loads
//      its own name (ADRP+ADD) for its error paths, so the vtable entry whose code references
//      the string "SetLoginToken" IS SetLoginToken — whatever its index.
//   3. Code shape: each function found is fingerprinted (FNV-1a over its instructions with
//      addresses, PC-relative immediates and the per-build IPC method ids masked out; calls
//      into the PLT are named by their import). The fingerprint must equal the one recorded
//      from the device-proven build 1788291500. A changed argument list changes the
//      marshalling code and therefore the fingerprint, so a method that kept its name but
//      changed its signature is refused instead of being called with the wrong arguments.
//   4. Engine getters: the per-pipe constructor stores each new *Map proxy into a fixed field
//      (`bl factory; str x0, [xN, #field]`); the IClientEngine slot whose getter code returns
//      that field (template fingerprint + `ldr x0, [xM, #field]`) is GetIClientXxx.
//   5. At runtime the host additionally checks that the objects it gets back really are
//      those classes (vptr == load base + the vtable found here) before calling into them.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace blhost {

struct DiscoveredSlots {
    bool ok = false;
    std::string error;                 // first hard failure (empty when ok)
    std::vector<std::string> notes;    // human-readable trace for the host log / CI report

    // Library-relative virtual addresses (add the load base at runtime).
    uint64_t engineVtable = 0, userMapVtable = 0, appsMapVtable = 0, friendsMapVtable = 0;

    int engineGetIClientUser = -1, engineGetIClientFriends = -1, engineGetIClientApps = -1;
    int userLogOn = -1, userBLoggedOn = -1, userBHasCachedCredentials = -1;
    int userSetAccountNameForCachedCredentialLogin = -1, userSetLoginInformation = -1;
    int userSetLoginToken = -1, userBUpdateAppOwnershipTicket = -1;
    int appsRequestAppInfoUpdate = -1;
    int friendsSetPersonaState = -1;

    // Highest discovered IClientUser slot + 1 (for the dladdr sanity walk).
    int userSlotsToCheck = 0;
};

// Analyse the library at `path`. Never throws; result.ok tells whether every slot was found
// and every fingerprint matched. `dumpShapes` adds each function's fingerprint to notes
// (used once to record the reference values).
DiscoveredSlots discover_slots(const std::string& path, bool dumpShapes = false);

// One-line summary "engine=8,13,17 user=1,4,49,50,54,56,69 apps=7 friends=5".
std::string format_slots(const DiscoveredSlots& d);

// JSON object for --check / CI (no secrets involved).
std::string discovered_json(const DiscoveredSlots& d, const std::string& valveVersion);

}  // namespace blhost
