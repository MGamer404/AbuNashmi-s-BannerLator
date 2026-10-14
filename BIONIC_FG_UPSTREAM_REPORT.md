# bionic-fg on Android wrapper ICDs (Winlator/Turnip): investigation + fix

Internal record of the work behind our upstream PR to **xXJSONDeruloXx/bionic-fg**:
**https://github.com/xXJSONDeruloXx/bionic-fg/pull/6** — *Single-device mode + layer-dispatch
routing: make bionic-fg work on Android wrapper ICDs (Winlator/Turnip)*.

Context: integrating bionic-fg into **Bannerlator** (a Winlator-lineage Android app — Wine +
box64/arm64ec + DXVK, presenting through a **wrapper ICD → Turnip (Mesa) via adrenotools**). The
author granted permission to bundle the layer (submodule + README credit) and to bundle the built
`.so`; "PRs welcome." This file is the long-form companion to the PR.

## Outcome (TL;DR)

Frame generation now works end-to-end on the wrapper-ICD stack. On an **Adreno 750 / Turnip /
DXVK 2.4.1**, **2×/3×/4×** all produce the expected on-screen multiple of the base rate, and an
optional FPS limiter holds the base while generation scales on top — no hangs, multiple games. The
five fixes are in the PR; the path to them is below.

## Environment

- Game side: Wine/DXVK → `wrapper_icd.aarch64.json` ICD, `GALLIUM_DRIVER=zink`, real driver =
  Turnip (`libvulkan_freedreno.so`) via adrenotools. `VK_ANDROID_external_memory_android_hardware_buffer`
  present app-side; DRI3 creating pixmaps from `AHardwareBuffer`.
- The guest runs its **own glibc Khronos Vulkan loader** (it already loads glibc implicit layers
  such as MangoHud), so the layer is discovered guest-side, not by the Android loader.
- Device: Adreno 750, Android 14.

## The investigation (run by run)

1. **Manifest skip.** The implicit layer was found but skipped at parse time: the manifest declared
   only `enable_environment`, and the spec requires `disable_environment` too. Strict loaders reject
   it (`"... doesn't contain required layer object disable_environment ... skipping this layer"`).
   → **Fix 1:** add `disable_environment` (`BIONIC_FG_DISABLE=1`).

2. **Loads & initializes.** With the manifest fixed, our NDK/bionic `.so` loaded fine in the glibc
   loader (refuting our worry that it would need a glibc rebuild). `Device created`, all embedded
   SPIR-V loaded, and — the key unknown — a **real `VkSwapchainKHR` was hooked**
   (`SwapchainState ready: 1280x720 mult=2 provisionedOutputs=3`). So the layer is in the right place.

3. **First interpolated present hangs.** The process went silent right after `SwapchainState ready`,
   froze ~36 s, then ANR/SIGQUIT. Bounding the three present-path `vkWaitForFences(UINT64_MAX)` to a
   timeout did **not** fix it — none of those bounded waits fired, which localized the hang to an
   *unbounded* wait deeper in (`FramegenContext::present` fence + `vkQueueWaitIdle`, neither
   timeout-bounded).

4. **Root cause = the two-device architecture.** bionic-fg created its **own** `VkInstance`+`VkDevice`
   (the "first compute-capable GPU") and shared frames with the app's render device via AHB +
   `VK_QUEUE_FAMILY_EXTERNAL` ownership transfers. On this stack both devices are *independent*
   `wrapper_icd → Turnip` instances that don't share a real underlying queue/timeline, so the
   cross-instance compute submit / queue-wait never completes.
   → **Fix 2 (the core change):** *single-device mode* — wrap the application's own `VkDevice`
   (`vk::Device::wrap`) and run interpolation on it. One device ⇒ producer and consumer share a real
   queue, and the deadlock resolves. (The JNI standalone test path keeps `Device::create()`.)

5. **Single-device crashed at AHB import — dispatch hazard.** Single-device got much further but
   hard-crashed (SIGSEGV, not a thrown `VkError`) at the input AHB import. Cause: `vk_impl` called
   **global** `vkGetPhysicalDeviceMemoryProperties(dev.physical())` from *inside* the layer with a
   chain-level physical-device handle — the exact hazard bionic-fg's own `CreateDevice` already warns
   about and avoids with `nextGIPA`.
   → **Fix 3:** route physical-device queries through the layer dispatch (`memPropsFn` threaded into
   `vk::Device::wrap`); falls back to the global symbol for the standalone path.

6. **Works.** With single-device + dispatch routing, AHB import succeeded and frame gen ran: DXVK
   base ≈ 33 fps, on-screen ≈ 66 at mult=2 — visible doubling — then 3× and 4× confirmed the same way.

## Additional changes in the PR

- **Bounded fence waits (robustness):** all three present-path waits use a 250 ms timeout; on a
  timeout the real frame is presented, and after **2 consecutive** timeouts framegen is disabled for
  that swapchain — so an ICD whose present-sync model differs degrades to full-speed real frames
  instead of an ANR, rather than relying on every stack behaving identically.
- **Ext-list cleanup:** removed two *instance* extensions
  (`VK_KHR_external_memory_capabilities`, `VK_KHR_get_physical_device_properties2`, core since 1.1)
  that were listed in the device-ext array and always reported "Device ext not available" (harmless
  but misleading).
- **Optional `fps_limit` feature:** conf.toml key / `BIONIC_FG_FPS_LIMIT` env (0 = off, 10–200).
  Paces the application's *real* present calls (the `inPresent == false` ones) so on-screen FPS =
  `limit × multiplier`; generated-frame presents bypass the pacer. Works in pure passthrough too as a
  plain live cap. Capping the base feeds the interpolator a steady cadence (smoother output) and
  saves GPU/power.

## Device validation

Adreno 750 / Turnip / DXVK 2.4.1 / D3D11, multiple games: layer loads, `SwapchainState ready`, AHB
import OK; **2× / 3× / 4×** each produce the expected on-screen multiple of the base rate; the FPS
limiter holds the base (e.g. 30) while generation scales on top (60 / 90 / 120). No layer-side hangs
or crashes. Full logcats retained.

## Notes

- The compatibility fix and the `fps_limit` feature are independent; the PR offers to split them.
- bionic-fg has no `LICENSE` yet — not a blocker for us (explicit permission granted), but adding one
  would help others build on it.
