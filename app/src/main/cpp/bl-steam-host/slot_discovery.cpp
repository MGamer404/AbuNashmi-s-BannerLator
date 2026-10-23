// bl-steam-host — build-independent discovery of the private Steam client slots.
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 The412Banner (Bannerlator). See slot_discovery.h for the method.
//
// Pure file analysis: this code reads bytes and decodes a handful of AArch64 instruction
// encodings. It never maps the library executable and never calls into it.

#include "slot_discovery.h"

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstring>
#include <map>
#include <set>
#include <unordered_map>

namespace blhost {
namespace {

// ── Reference fingerprints ──────────────────────────────────────────────────────────────────
// Recorded from Valve build 1788291500 (device-proven 2026-09-02) with `--check --dump-shapes`
// and re-checked to be identical on 1788652215 and 1791918634. A Valve build whose function
// for one of these methods has a different shape is refused (fail closed) until a human looks.
struct Expect { const char* key; uint64_t shape; };
constexpr Expect kExpected[] = {
    {"user.LogOn",                                  0x9bcee95de921ec2cULL},
    {"user.BLoggedOn",                              0x36a734ca3bfea341ULL},
    {"user.BHasCachedCredentials",                  0x0ded8dbe65d115e2ULL},
    {"user.SetAccountNameForCachedCredentialLogin", 0xf25cf8800de79b6aULL},
    {"user.SetLoginInformation",                    0xdfe980a8defa4c90ULL},
    {"user.SetLoginToken",                          0x55c602e3a7cc79afULL},
    {"user.BUpdateAppOwnershipTicket",              0x88037c6c62cc5910ULL},
    {"apps.RequestAppInfoUpdate",                   0x663c6eefc7899375ULL},
    {"friends.SetPersonaState",                     0xab9eaee49f6da1aeULL},
    {"engine.getter",                               0xc854ebd305738d7aULL},
};

uint64_t expected_shape(const std::string& key) {
    for (const auto& e : kExpected) if (key == e.key) return e.shape;
    return 0;
}

constexpr uint32_t R_AARCH64_JUMP_SLOT = 1026;
constexpr uint32_t R_AARCH64_RELATIVE  = 1027;

inline uint64_t fnv_mix(uint64_t h, uint32_t v) {
    for (int i = 0; i < 4; ++i) { h ^= (v >> (8 * i)) & 0xff; h *= 0x100000001b3ULL; }
    return h;
}
constexpr uint64_t kFnvBasis = 0xcbf29ce484222325ULL;
uint32_t fnv32_str(const char* s) {
    uint64_t h = kFnvBasis;
    for (; *s; ++s) { h ^= (unsigned char)*s; h *= 0x100000001b3ULL; }
    return (uint32_t)(h ^ (h >> 32));
}

struct Load { uint64_t vaddr, off, filesz, memsz; uint32_t flags; };

class Image {
public:
    std::vector<uint8_t> d;
    std::vector<Load> loads;
    uint64_t textLo = 0, textHi = 0;                         // executable PT_LOAD (vaddr)
    std::unordered_map<uint64_t, uint64_t> rel;              // reloc offset → addend (RELATIVE)
    std::vector<std::pair<uint64_t, uint64_t>> byAddend;     // (addend, offset), sorted
    std::vector<uint64_t> funcs;                             // sorted unique code targets of RELATIVE relocs
    std::unordered_map<uint64_t, std::string> gotName;       // GOT slot → imported symbol (JUMP_SLOT)
    std::string err;

    bool load(const std::string& path) {
        FILE* f = fopen(path.c_str(), "rb");
        if (!f) { err = "cannot open " + path; return false; }
        fseek(f, 0, SEEK_END);
        long n = ftell(f);
        fseek(f, 0, SEEK_SET);
        if (n < 0x1000 || n > (256L << 20)) { fclose(f); err = "implausible file size"; return false; }
        d.resize((size_t)n);
        size_t got = fread(d.data(), 1, d.size(), f);
        fclose(f);
        if (got != d.size()) { err = "short read"; return false; }
        return parse();
    }

    template <typename T> bool rd(uint64_t off, T& out) const {
        if (off + sizeof(T) > d.size()) return false;
        memcpy(&out, d.data() + off, sizeof(T));
        return true;
    }
    bool v2o(uint64_t va, uint64_t& off) const {
        for (const auto& l : loads)
            if (va >= l.vaddr && va < l.vaddr + l.filesz) { off = va - l.vaddr + l.off; return true; }
        return false;
    }
    bool o2v(uint64_t off, uint64_t& va) const {
        for (const auto& l : loads)
            if (off >= l.off && off < l.off + l.filesz) { va = off - l.off + l.vaddr; return true; }
        return false;
    }
    bool insn(uint64_t va, uint32_t& w) const {
        if (va < textLo || va + 4 > textHi) return false;
        uint64_t o;
        return v2o(va, o) && rd(o, w);
    }
    bool is_code(uint64_t va) const { return va >= textLo && va < textHi; }

    // All RELATIVE relocation offsets whose addend equals `a`.
    std::vector<uint64_t> refs_to(uint64_t a) const {
        std::vector<uint64_t> out;
        auto it = std::lower_bound(byAddend.begin(), byAddend.end(), std::make_pair(a, (uint64_t)0));
        for (; it != byAddend.end() && it->first == a; ++it) out.push_back(it->second);
        return out;
    }
    uint64_t func_end(uint64_t f) const {
        auto it = std::upper_bound(funcs.begin(), funcs.end(), f);
        uint64_t e = (it == funcs.end()) ? textHi : *it;
        return std::min(e, f + 4096);
    }
    // vaddr of the NUL-delimited string `s` (exact match, first occurrence), 0 if absent.
    uint64_t find_cstr(const std::string& s) const {
        std::string needle;
        needle.push_back('\0');
        needle += s;
        needle.push_back('\0');
        auto it = std::search(d.begin(), d.end(), needle.begin(), needle.end());
        if (it == d.end()) return 0;
        uint64_t va;
        return o2v((uint64_t)(it - d.begin()) + 1, va) ? va : 0;
    }

private:
    // The executable PT_LOAD also covers .rodata; prefer the .text section when the section
    // headers are present so string data is never decoded as code.
    void narrow_to_text_section() {
        uint64_t shoff = 0; uint16_t shentsize = 0, shnum = 0, shstrndx = 0;
        rd(0x28, shoff); rd(0x3a, shentsize); rd(0x3c, shnum); rd(0x3e, shstrndx);
        if (!shoff || shentsize < 64 || shstrndx >= shnum) return;
        uint64_t strOff = 0;
        if (!rd(shoff + (uint64_t)shstrndx * shentsize + 24, strOff)) return;
        for (uint16_t i = 0; i < shnum; ++i) {
            uint64_t h = shoff + (uint64_t)i * shentsize;
            uint32_t nameOff = 0; uint64_t addr = 0, size = 0;
            if (!rd(h, nameOff) || !rd(h + 16, addr) || !rd(h + 32, size)) return;
            uint64_t n = strOff + nameOff;
            if (n + 6 > d.size()) continue;
            if (memcmp(d.data() + n, ".text", 6) == 0 && addr >= textLo && addr + size <= textHi && size > 0) {
                textLo = addr; textHi = addr + size;
                return;
            }
        }
    }

    bool parse() {
        if (d.size() < 64 || memcmp(d.data(), "\x7f" "ELF", 4) != 0) { err = "not an ELF file"; return false; }
        if (d[4] != 2 || d[5] != 1) { err = "not ELF64 little-endian"; return false; }
        uint16_t machine = 0;
        rd(18, machine);
        if (machine != 183) { err = "not AArch64"; return false; }
        uint64_t phoff = 0; uint16_t phentsize = 0, phnum = 0;
        rd(0x20, phoff); rd(0x36, phentsize); rd(0x38, phnum);
        uint64_t dynOff = 0, dynSize = 0;
        for (uint16_t i = 0; i < phnum; ++i) {
            uint64_t p = phoff + (uint64_t)i * phentsize;
            uint32_t type = 0, flags = 0; uint64_t off = 0, va = 0, fs = 0, ms = 0;
            rd(p, type); rd(p + 4, flags); rd(p + 8, off); rd(p + 16, va); rd(p + 32, fs); rd(p + 40, ms);
            if (type == 1) {
                loads.push_back({va, off, fs, ms, flags});
                if (flags & 1) { textLo = va; textHi = va + fs; }
            } else if (type == 2) { dynOff = off; dynSize = fs; }
        }
        if (loads.empty() || textHi == 0 || dynSize == 0) { err = "missing PT_LOAD/PT_DYNAMIC"; return false; }
        narrow_to_text_section();
        uint64_t rela = 0, relasz = 0, relaent = 24, jmprel = 0, pltrelsz = 0, symtab = 0, strtab = 0;
        for (uint64_t p = dynOff; p + 16 <= dynOff + dynSize; p += 16) {
            int64_t tag = 0; uint64_t val = 0;
            rd(p, tag); rd(p + 8, val);
            if (tag == 0) break;
            switch (tag) {
                case 7: rela = val; break;
                case 8: relasz = val; break;
                case 9: relaent = val; break;
                case 23: jmprel = val; break;
                case 2: pltrelsz = val; break;
                case 6: symtab = val; break;
                case 5: strtab = val; break;
                case 0x24: case 0x6fffe000: case 0x60000011: case 0x60000012:
                    // RELR / Android packed relocations: RELATIVE relocs we could not see.
                    err = "packed relocations (RELR/APS2) are not supported by the slot check";
                    return false;
                default: break;
            }
        }
        if (!rela || !relasz || relaent != 24) { err = "no plain RELA table"; return false; }
        uint64_t ro;
        if (!v2o(rela, ro)) { err = "RELA outside the file"; return false; }
        for (uint64_t p = ro; p + 24 <= ro + relasz; p += 24) {
            uint64_t off = 0, info = 0; int64_t add = 0;
            rd(p, off); rd(p + 8, info); rd(p + 16, add);
            if ((uint32_t)info == R_AARCH64_RELATIVE) {
                rel[off] = (uint64_t)add;
                byAddend.emplace_back((uint64_t)add, off);
                if (is_code((uint64_t)add)) funcs.push_back((uint64_t)add);
            }
        }
        std::sort(byAddend.begin(), byAddend.end());
        std::sort(funcs.begin(), funcs.end());
        funcs.erase(std::unique(funcs.begin(), funcs.end()), funcs.end());
        uint64_t jo, so, sto;
        if (jmprel && pltrelsz && symtab && strtab && v2o(jmprel, jo) && v2o(symtab, so) && v2o(strtab, sto)) {
            for (uint64_t p = jo; p + 24 <= jo + pltrelsz; p += 24) {
                uint64_t off = 0, info = 0;
                rd(p, off); rd(p + 8, info);
                if ((uint32_t)info != R_AARCH64_JUMP_SLOT) continue;
                uint32_t symIdx = (uint32_t)(info >> 32), nameOff = 0;
                if (!rd(so + (uint64_t)symIdx * 24, nameOff)) continue;
                uint64_t s = sto + nameOff;
                if (s >= d.size()) continue;
                const char* nm = reinterpret_cast<const char*>(d.data() + s);
                gotName[off] = std::string(nm, strnlen(nm, d.size() - s));
            }
        }
        return true;
    }
};

// ── AArch64 decoding helpers ────────────────────────────────────────────────────────────────
inline bool is_adrp(uint32_t w) { return (w & 0x9f000000u) == 0x90000000u; }
inline uint64_t adrp_page(uint32_t w, uint64_t pc) {
    int64_t imm = (int64_t)((((w >> 5) & 0x7ffffu) << 2) | ((w >> 29) & 3u));
    if (imm & (1LL << 20)) imm -= (1LL << 21);
    return (pc & ~0xfffULL) + (uint64_t)(imm << 12);
}
inline bool is_add_imm64(uint32_t w) { return (w & 0xffc00000u) == 0x91000000u; }
inline bool is_bl(uint32_t w) { return (w & 0xfc000000u) == 0x94000000u; }
inline bool is_b(uint32_t w) { return (w & 0xfc000000u) == 0x14000000u; }
inline uint64_t branch_target(uint32_t w, uint64_t pc) {
    int64_t imm = (int64_t)(w & 0x3ffffffu);
    if (imm & (1LL << 25)) imm -= (1LL << 26);
    return pc + (uint64_t)(imm * 4);
}
inline bool is_ldst_uimm(uint32_t w) { return (w & 0x3b000000u) == 0x39000000u; }
inline bool is_movz(uint32_t w) { return (w & 0x7f800000u) == 0x52800000u; }
inline bool is_movk(uint32_t w) { return (w & 0x7f800000u) == 0x72800000u; }
inline bool is_ldr_x0_uimm(uint32_t w) { return (w & 0xffc0001fu) == 0xf9400000u && ((w >> 5) & 31u) != 31u; }
inline bool is_str_x0_uimm(uint32_t w) { return (w & 0xffc0001fu) == 0xf9000000u; }

// Does the function [f, end) build the address `target` with ADRP (+ADD)?
bool refs_address(const Image& im, uint64_t f, uint64_t end, uint64_t target) {
    for (uint64_t pc = f; pc < end; pc += 4) {
        uint32_t w;
        if (!im.insn(pc, w) || !is_adrp(w)) continue;
        uint64_t page = adrp_page(w, pc);
        if ((target & ~0xfffULL) != page) continue;
        uint32_t reg = w & 31u;
        for (uint64_t q = pc + 4; q < end && q <= pc + 32; q += 4) {
            uint32_t w2;
            if (!im.insn(q, w2)) break;
            if (is_add_imm64(w2) && ((w2 >> 5) & 31u) == reg) {
                if (page + ((w2 >> 10) & 0xfffu) == target) return true;
                break;
            }
        }
    }
    return false;
}

// Normalise one instruction (see slot_discovery.h §3). `prev` is the previous instruction of
// the same function (0 for the first).
uint32_t normalise(uint32_t w, uint32_t prev, uint32_t next) {
    if (is_adrp(w)) return w & 0x9f00001fu;
    bool afterAdrp = is_adrp(prev) && ((w >> 5) & 31u) == (prev & 31u);
    if (afterAdrp && is_add_imm64(w)) return w & 0xffc003ffu;
    if (afterAdrp && is_ldst_uimm(w)) return w & 0xffc003ffu;
    if (is_movz(w) && is_movk(next) && (next & 31u) == (w & 31u)) return w & 0xffe0001fu;
    if (is_movk(w) && is_movz(prev) && (prev & 31u) == (w & 31u)) return w & 0xffe0001fu;
    return w;
}

// Token for a call target: an import's name for PLT stubs, else the callee's first (up to 6)
// normalised instructions, ending at its first call, branch or return so the token never runs
// past a noreturn call into whatever function the linker placed next.
uint32_t plt_token(const Image& im, uint64_t t, bool& isPlt) {
    uint32_t w0, w1;
    isPlt = false;
    if (im.insn(t, w0) && im.insn(t + 4, w1) && is_adrp(w0) && (w0 & 31u) == 16u &&
        (w1 & 0xffc003ffu) == 0xf9400211u) {
        isPlt = true;
        uint64_t got = adrp_page(w0, t) + (uint64_t)((w1 >> 10) & 0xfffu) * 8;
        auto it = im.gotName.find(got);
        if (it != im.gotName.end()) return fnv32_str(it->second.c_str()) | 1u;
        return 0xdeadbeefu;
    }
    return 0;
}

uint32_t callee_token(const Image& im, uint64_t t) {
    bool isPlt;
    uint32_t pt = plt_token(im, t, isPlt);
    if (isPlt) return pt;
    uint64_t h = kFnvBasis;
    uint32_t prev = 0;
    for (int i = 0; i < 6; ++i) {
        uint32_t w, next = 0;
        if (!im.insn(t + 4 * i, w)) break;
        im.insn(t + 4 * i + 4, next);
        if (is_bl(w) || is_b(w)) {
            bool inner;
            uint32_t it = plt_token(im, branch_target(w, t + 4 * i), inner);
            h = fnv_mix(h, w & 0xfc000000u);
            h = fnv_mix(h, inner ? it : 0u);
            break;
        }
        h = fnv_mix(h, normalise(w, prev, next));
        if (w == 0xd65f03c0u) break;   // ret
        prev = w;
    }
    return (uint32_t)(h ^ (h >> 32));
}

// Fingerprint of the function at f. When maskLdrX0 is set, the immediate of every
// `ldr x0, [xN, #imm]` is masked and the (single) field offset is returned in *field.
uint64_t shape_of(const Image& im, uint64_t f, bool maskLdrX0 = false, int* field = nullptr, int* ldrX0Count = nullptr) {
    uint64_t end = im.func_end(f);
    uint64_t h = kFnvBasis;
    uint32_t prev = 0;
    int count = 0;
    for (uint64_t pc = f; pc < end; pc += 4) {
        uint32_t w, next = 0;
        if (!im.insn(pc, w)) break;
        im.insn(pc + 4, next);
        if (is_bl(w)) {
            h = fnv_mix(h, 0x94000000u);
            h = fnv_mix(h, callee_token(im, branch_target(w, pc)));
        } else if (is_b(w)) {
            uint64_t t = branch_target(w, pc);
            if (t >= f && t < end) h = fnv_mix(h, w);
            else { h = fnv_mix(h, 0x14000000u); h = fnv_mix(h, callee_token(im, t)); }
        } else if (maskLdrX0 && is_ldr_x0_uimm(w)) {
            ++count;
            if (field) *field = (int)((w >> 10) & 0xfffu) * 8;
            h = fnv_mix(h, w & 0xffc003ffu);
        } else {
            h = fnv_mix(h, normalise(w, prev, next));
        }
        prev = w;
    }
    if (ldrX0Count) *ldrX0Count = count;
    h = fnv_mix(h, (uint32_t)((end - f) / 4));
    return h;
}

// Primary vtable (address point, offset-to-top 0) of the class whose typeinfo name is `mangled`.
uint64_t vtable_for(const Image& im, const std::string& mangled, std::string& why) {
    uint64_t nameVa = im.find_cstr(mangled);
    if (!nameVa) { why = "typeinfo name " + mangled + " not found"; return 0; }
    std::set<uint64_t> points;
    for (uint64_t nameRef : im.refs_to(nameVa)) {
        uint64_t ti = nameRef - 8;   // typeinfo = { vptr, name, ... }
        for (uint64_t r : im.refs_to(ti)) {
            uint64_t o;
            int64_t ott = 1;
            if (!im.v2o(r - 8, o) || !im.rd(o, ott) || ott != 0) continue;
            if (im.rel.count(r - 8)) continue;   // offset-to-top is plain data, never relocated
            uint64_t point = r + 8;
            auto it = im.rel.find(point);
            if (it != im.rel.end() && im.is_code(it->second)) points.insert(point);
        }
    }
    if (points.size() != 1) {
        why = mangled + ": expected exactly one primary vtable, found " + std::to_string(points.size());
        return 0;
    }
    return *points.begin();
}

int vtable_len(const Image& im, uint64_t vt) {
    int n = 0;
    while (n < 1024) {
        auto it = im.rel.find(vt + 8 * (uint64_t)n);
        if (it == im.rel.end() || !im.is_code(it->second)) break;
        ++n;
    }
    return n;
}

// vaddr of `method` inside the InterfaceMapBase<iface> name run, 0 when absent.
uint64_t method_name_va(const Image& im, const std::string& iface, const std::string& method, std::string& why) {
    std::string tn = "16InterfaceMapBaseI" + std::to_string(iface.size()) + iface + "E";
    uint64_t tnVa = im.find_cstr(tn);
    uint64_t o;
    if (!tnVa || !im.v2o(tnVa, o)) { why = tn + " not found"; return 0; }
    auto isIdent = [](const char* s, size_t n) {
        if (n == 0 || !(isalpha((unsigned char)s[0]) || s[0] == '_')) return false;
        for (size_t i = 1; i < n; ++i) if (!(isalnum((unsigned char)s[i]) || s[i] == '_')) return false;
        return true;
    };
    // Skip to the first run of >= 3 consecutive identifiers after the typeinfo name.
    uint64_t p = o + tn.size() + 1;
    for (int guard = 0; guard < 16 && p < im.d.size(); ++guard) {
        std::vector<std::pair<uint64_t, std::string>> run;
        uint64_t q = p;
        while (q < im.d.size()) {
            const char* s = reinterpret_cast<const char*>(im.d.data() + q);
            size_t n = strnlen(s, im.d.size() - q);
            if (!isIdent(s, n)) break;
            run.emplace_back(q, std::string(s, n));
            q += n + 1;
        }
        if (run.size() >= 3) {
            uint64_t hit = 0;
            int hits = 0;
            for (const auto& e : run) if (e.second == method) { hit = e.first; ++hits; }
            if (hits != 1) { why = iface + "::" + method + " appears " + std::to_string(hits) + "x in the name run"; return 0; }
            uint64_t va;
            return im.o2v(hit, va) ? va : 0;
        }
        const char* s = reinterpret_cast<const char*>(im.d.data() + p);
        p += strnlen(s, im.d.size() - p) + 1;
    }
    why = iface + " name run not found";
    return 0;
}

// The single vtable slot of `vt` whose code references the method-name string.
int slot_by_name(const Image& im, uint64_t vt, int n, uint64_t nameVa, std::string& why) {
    int found = -1, hits = 0;
    for (int k = 0; k < n; ++k) {
        uint64_t f = im.rel.at(vt + 8 * (uint64_t)k);
        if (refs_address(im, f, im.func_end(f), nameVa)) { found = k; ++hits; }
    }
    if (hits != 1) { why = "name referenced by " + std::to_string(hits) + " vtable entries"; return -1; }
    return found;
}

}  // namespace

DiscoveredSlots discover_slots(const std::string& path, bool dumpShapes) {
    DiscoveredSlots r;
    Image im;
    auto fail = [&](const std::string& e) { r.ok = false; if (r.error.empty()) r.error = e; return r; };
    if (!im.load(path)) return fail(im.err);
    char b[256];
    snprintf(b, sizeof(b), "elf: text 0x%llx-0x%llx, %zu RELATIVE relocs, %zu code targets, %zu imports",
             (unsigned long long)im.textLo, (unsigned long long)im.textHi, im.rel.size(), im.funcs.size(), im.gotName.size());
    r.notes.push_back(b);

    std::string why;
    r.engineVtable = vtable_for(im, "12CSteamClient", why);       if (!r.engineVtable) return fail(why);
    r.userMapVtable = vtable_for(im, "14IClientUserMap", why);     if (!r.userMapVtable) return fail(why);
    r.appsMapVtable = vtable_for(im, "14IClientAppsMap", why);     if (!r.appsMapVtable) return fail(why);
    r.friendsMapVtable = vtable_for(im, "17IClientFriendsMap", why); if (!r.friendsMapVtable) return fail(why);
    int nEngine = vtable_len(im, r.engineVtable), nUser = vtable_len(im, r.userMapVtable);
    int nApps = vtable_len(im, r.appsMapVtable), nFriends = vtable_len(im, r.friendsMapVtable);
    snprintf(b, sizeof(b), "vtables: CSteamClient 0x%llx (%d) IClientUserMap 0x%llx (%d) IClientAppsMap 0x%llx (%d) IClientFriendsMap 0x%llx (%d)",
             (unsigned long long)r.engineVtable, nEngine, (unsigned long long)r.userMapVtable, nUser,
             (unsigned long long)r.appsMapVtable, nApps, (unsigned long long)r.friendsMapVtable, nFriends);
    r.notes.push_back(b);

    bool shapesOk = true;
    auto check_shape = [&](const std::string& key, uint64_t f, uint64_t shape) {
        uint64_t want = expected_shape(key);
        bool match = want != 0 && want == shape;
        snprintf(b, sizeof(b), "%s: fn 0x%llx shape %016llx %s", key.c_str(), (unsigned long long)f,
                 (unsigned long long)shape, match ? "= reference" : "!= reference");
        if (dumpShapes || !match) r.notes.push_back(b);
        if (!match) { shapesOk = false; if (r.error.empty()) r.error = key + ": code shape differs from the verified reference"; }
    };

    struct M { const char* iface; const char* method; const char* key; uint64_t vt; int n; int* out; };
    M methods[] = {
        {"IClientUser", "LogOn", "user.LogOn", r.userMapVtable, nUser, &r.userLogOn},
        {"IClientUser", "BLoggedOn", "user.BLoggedOn", r.userMapVtable, nUser, &r.userBLoggedOn},
        {"IClientUser", "BHasCachedCredentials", "user.BHasCachedCredentials", r.userMapVtable, nUser, &r.userBHasCachedCredentials},
        {"IClientUser", "SetAccountNameForCachedCredentialLogin", "user.SetAccountNameForCachedCredentialLogin", r.userMapVtable, nUser, &r.userSetAccountNameForCachedCredentialLogin},
        {"IClientUser", "SetLoginInformation", "user.SetLoginInformation", r.userMapVtable, nUser, &r.userSetLoginInformation},
        {"IClientUser", "SetLoginToken", "user.SetLoginToken", r.userMapVtable, nUser, &r.userSetLoginToken},
        {"IClientUser", "BUpdateAppOwnershipTicket", "user.BUpdateAppOwnershipTicket", r.userMapVtable, nUser, &r.userBUpdateAppOwnershipTicket},
        {"IClientApps", "RequestAppInfoUpdate", "apps.RequestAppInfoUpdate", r.appsMapVtable, nApps, &r.appsRequestAppInfoUpdate},
        {"IClientFriends", "SetPersonaState", "friends.SetPersonaState", r.friendsMapVtable, nFriends, &r.friendsSetPersonaState},
    };
    for (auto& m : methods) {
        uint64_t nameVa = method_name_va(im, m.iface, m.method, why);
        if (!nameVa) return fail(why);
        int slot = slot_by_name(im, m.vt, m.n, nameVa, why);
        if (slot < 0) return fail(std::string(m.key) + ": " + why);
        *m.out = slot;
        uint64_t f = im.rel.at(m.vt + 8 * (uint64_t)slot);
        check_shape(m.key, f, shape_of(im, f));
    }
    r.userSlotsToCheck = 1 + std::max({r.userLogOn, r.userBLoggedOn, r.userBHasCachedCredentials,
                                       r.userSetAccountNameForCachedCredentialLogin, r.userSetLoginInformation,
                                       r.userSetLoginToken, r.userBUpdateAppOwnershipTicket});

    // ── engine getters ──────────────────────────────────────────────────────────────────────
    // (a) where each *Map vtable address is materialised (inside its factory)
    std::map<uint64_t, uint64_t> mapRef;   // vtable → instruction address
    {
        std::set<uint64_t> want = {r.userMapVtable, r.appsMapVtable, r.friendsMapVtable};
        uint32_t prevW = 0; uint64_t prevPc = 0;
        for (uint64_t pc = im.textLo; pc + 4 <= im.textHi; pc += 4) {
            uint32_t w;
            if (!im.insn(pc, w)) continue;
            if (is_add_imm64(w) && is_adrp(prevW) && prevPc + 4 == pc && ((w >> 5) & 31u) == (prevW & 31u)) {
                uint64_t t = adrp_page(prevW, prevPc) + ((w >> 10) & 0xfffu);
                if (want.count(t)) {
                    if (mapRef.count(t)) return fail("a *Map vtable is materialised in more than one place");
                    mapRef[t] = pc;
                }
            }
            prevW = w; prevPc = pc;
        }
        if (mapRef.size() != 3) return fail("could not find every *Map factory");
    }
    // (b) the factory = nearest preceding call target; (c) its result's field in the per-pipe object
    std::map<uint64_t, uint64_t> factory;  // vtable → factory entry
    for (uint64_t pc = im.textLo; pc + 4 <= im.textHi; pc += 4) {
        uint32_t w;
        if (!im.insn(pc, w) || !is_bl(w)) continue;
        uint64_t t = branch_target(w, pc);
        for (auto& kv : mapRef)
            if (t <= kv.second && kv.second - t < 0x400 && t > factory[kv.first]) factory[kv.first] = t;
    }
    std::map<uint64_t, std::set<int>> fieldOf;
    for (uint64_t pc = im.textLo; pc + 8 <= im.textHi; pc += 4) {
        uint32_t w, w2;
        if (!im.insn(pc, w) || !is_bl(w)) continue;
        uint64_t t = branch_target(w, pc);
        for (auto& kv : factory)
            if (kv.second == t && im.insn(pc + 4, w2) && is_str_x0_uimm(w2))
                fieldOf[kv.first].insert((int)((w2 >> 10) & 0xfffu) * 8);
    }
    auto field_for = [&](uint64_t vt, int& out) {
        auto it = fieldOf.find(vt);
        if (it == fieldOf.end() || it->second.size() != 1) return false;
        out = *it->second.begin();
        return true;
    };
    int fUser = -1, fApps = -1, fFriends = -1;
    if (!field_for(r.userMapVtable, fUser) || !field_for(r.appsMapVtable, fApps) || !field_for(r.friendsMapVtable, fFriends))
        return fail("could not tie every *Map to one per-pipe field");
    snprintf(b, sizeof(b), "per-pipe fields: IClientUserMap +%d IClientFriendsMap +%d IClientAppsMap +%d", fUser, fFriends, fApps);
    r.notes.push_back(b);

    // (d) engine slots whose code is the getter template, keyed by the field they return
    std::map<int, std::vector<int>> getterByField;
    uint64_t wantGetter = expected_shape("engine.getter");
    uint64_t firstGetterShape = 0;
    for (int k = 0; k < nEngine && k < 256; ++k) {
        uint64_t f = im.rel.at(r.engineVtable + 8 * (uint64_t)k);
        int field = -1, cnt = 0;
        uint64_t s = shape_of(im, f, true, &field, &cnt);
        if (cnt != 1) continue;
        if (dumpShapes && (field == fUser || field == fApps || field == fFriends)) {
            snprintf(b, sizeof(b), "engine slot %d: getter shape %016llx field +%d", k, (unsigned long long)s, field);
            r.notes.push_back(b);
        }
        if (field == fUser && !firstGetterShape) firstGetterShape = s;
        if (s == wantGetter) getterByField[field].push_back(k);
    }
    auto one = [&](int field, int& out, const char* what) {
        auto it = getterByField.find(field);
        if (it == getterByField.end() || it->second.size() != 1) {
            snprintf(b, sizeof(b), "engine.getter for %s: %zu matching slots", what,
                     it == getterByField.end() ? (size_t)0 : it->second.size());
            r.notes.push_back(b);
            return false;
        }
        out = it->second[0];
        return true;
    };
    if (!one(fUser, r.engineGetIClientUser, "IClientUser") || !one(fFriends, r.engineGetIClientFriends, "IClientFriends") ||
        !one(fApps, r.engineGetIClientApps, "IClientApps")) {
        snprintf(b, sizeof(b), "engine.getter: shape of the IClientUser getter candidate %016llx", (unsigned long long)firstGetterShape);
        r.notes.push_back(b);
        return fail("engine getters do not match the verified getter template");
    }
    if (!shapesOk) return fail(r.error);
    r.ok = true;
    r.notes.push_back("slots: " + format_slots(r));
    return r;
}

std::string format_slots(const DiscoveredSlots& d) {
    char b[256];
    snprintf(b, sizeof(b), "engine=%d,%d,%d user=%d,%d,%d,%d,%d,%d,%d apps=%d friends=%d",
             d.engineGetIClientUser, d.engineGetIClientFriends, d.engineGetIClientApps,
             d.userLogOn, d.userBLoggedOn, d.userBHasCachedCredentials, d.userSetAccountNameForCachedCredentialLogin,
             d.userSetLoginInformation, d.userSetLoginToken, d.userBUpdateAppOwnershipTicket,
             d.appsRequestAppInfoUpdate, d.friendsSetPersonaState);
    return b;
}

static std::string jesc(const std::string& s) {
    std::string o;
    for (char c : s) {
        if (c == '"' || c == '\\') { o += '\\'; o += c; }
        else if ((unsigned char)c < 0x20) o += ' ';
        else o += c;
    }
    return o;
}

std::string discovered_json(const DiscoveredSlots& d, const std::string& valveVersion) {
    char b[1024];
    snprintf(b, sizeof(b),
             "{\"version\":\"%s\",\"ok\":%s,\"error\":\"%s\",\"line\":\"%s\",\"slots\":{"
             "\"engineGetIClientUser\":%d,\"engineGetIClientFriends\":%d,\"engineGetIClientApps\":%d,"
             "\"userLogOn\":%d,\"userBLoggedOn\":%d,\"userBHasCachedCredentials\":%d,"
             "\"userSetAccountNameForCachedCredentialLogin\":%d,\"userSetLoginInformation\":%d,"
             "\"userSetLoginToken\":%d,\"userBUpdateAppOwnershipTicket\":%d,"
             "\"appsRequestAppInfoUpdate\":%d,\"friendsSetPersonaState\":%d},\"notes\":[",
             jesc(valveVersion).c_str(), d.ok ? "true" : "false", jesc(d.error).c_str(),
             d.ok ? format_slots(d).c_str() : "",
             d.engineGetIClientUser, d.engineGetIClientFriends, d.engineGetIClientApps,
             d.userLogOn, d.userBLoggedOn, d.userBHasCachedCredentials, d.userSetAccountNameForCachedCredentialLogin,
             d.userSetLoginInformation, d.userSetLoginToken, d.userBUpdateAppOwnershipTicket,
             d.appsRequestAppInfoUpdate, d.friendsSetPersonaState);
    std::string out = b;
    for (size_t i = 0; i < d.notes.size(); ++i) {
        if (i) out += ",";
        out += "\"" + jesc(d.notes[i]) + "\"";
    }
    out += "]}";
    return out;
}

}  // namespace blhost
