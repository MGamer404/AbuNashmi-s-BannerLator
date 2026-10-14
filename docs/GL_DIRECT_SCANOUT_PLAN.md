# GL Direct Scanout (Native Rendering) — Implementation Plan

**Status:** plan only, nothing built. Target: bring "Native Rendering" (direct scanout via
Android `SurfaceControl`/SurfaceFlinger) to the **OpenGL / GLES renderer**, which today is
Vulkan-only.

All paths below are absolute. Line numbers verified against the tree on 2026-06-28; where the
brief's numbers drifted I corrected them inline.

---

## 0. Ground truth (verified, do not re-derive)

### How the Vulkan path actually does scanout
The brief implied native C++ builds the SurfaceControls. It does **not**. The runtime path is:

1. `VulkanRenderer.setNativeMode(true)` (`/home/claude-user/bannerlators/app/src/main/java/com/winlator/star/renderer/vulkan/VulkanRenderer.java:614-679`)
   **builds the SurfaceControls in Java** on API ≥ 29:
   ```
   SurfaceControl xsc = (SurfaceControl) xServerView.getSurfaceControl();
   scanoutGameSC   = new SurfaceControl.Builder().setParent(xsc).setName("winlator_game").setOpaque(true).build();
   scanoutCursorSC = new SurfaceControl.Builder().setParent(xsc).setName("winlator_cursor").setFormat(1).build();
   // wrap each in a Surface, set layers 1/2 + visibility, optional setFrameRate, apply()
   nativeSetScanoutWindow(nativeHandle, scanoutGameSurface, scanoutCursorSurface);
   ```
   The same block is repeated in `onSurfaceCreated` (`:168-203`) for the surface-restore case.
2. `nativeSetScanoutWindow` (`vulkan_jni.cpp:200-213`) does `ANativeWindow_fromSurface` on each
   Surface and calls `VulkanRendererContext::initScanoutFromWindows(gw, cw)`
   (`/home/claude-user/bannerlators/app/src/main/cpp/winlator/VulkanRendererScanout.cpp:102`),
   which `ASurfaceControl_createFromWindow`s a *native* `ASurfaceControl` per child window.
3. Native `initScanout()` (`VulkanRendererScanout.cpp:68`, `ASurfaceControl_createFromWindow` on
   the renderer's **own** `window`) is only the **fallback** for API < 29 or a Java-side exception.

So the SurfaceControl-hosting question reduces to: **what does `xServerView.getSurfaceControl()`
return, and can a GLSurfaceView provide one?**

### The actual blocker
`/home/claude-user/bannerlators/app/src/main/java/com/winlator/star/widget/XServerView.java:126-131`:
```java
public Object getSurfaceControl() {
    if (Build.VERSION.SDK_INT >= 29 && vulkanSurfaceView != null)
        return vulkanSurfaceView.getSurfaceControl();
    return null;                       // <-- GL path: vulkanSurfaceView is null -> null
}
```
GL containers use `glSurfaceView` (`XServerView.java:86-96`), and `getSurfaceControl()` returns
**null** for them. **But `android.opengl.GLSurfaceView extends android.view.SurfaceView`**, and
`SurfaceView.getSurfaceControl()` exists since API 29. So a GLSurfaceView *can* host child
SurfaceControls — the method is simply never reached today. This is a one-line gap, not an
architectural wall.

### Per-frame present routing (where the AHB enters the renderer)
GL is **pull-based** (`GLSurfaceView.Renderer.onDrawFrame` → `renderWindows` → `renderDrawable` →
`glDrawArrays`, `/home/claude-user/bannerlators/app/src/main/java/com/winlator/star/renderer/GLRenderer.java:322-345`).
`GLRenderer.onUpdateWindowContent` (`:317`) just calls `requestRender()`; it never touches the AHB.

The real game-frame entry point is **`PresentExtension`**
(`/home/claude-user/bannerlators/app/src/main/java/com/winlator/star/xserver/extensions/PresentExtension.java:267-305`),
which already has a per-renderer switch:
- ASR → `asr.presentWindow(...)` (`:282`)
- Vulkan native + `isDirectScanout()` → `vr.onUpdateWindowContent(window)` (`:288-293`) which runs
  the scanout push (`VulkanRenderer.java:487-528`)
- Vulkan non-native AHB → `vr.onUpdateWindowContentDirect(...)` (`:298`)
- **everything else incl. GL → `content.copyArea(...)`** (`:300-304`) → CPU/GL texture upload

GL today lands in the final `else`. The GL scanout push must be added here as a **new branch**,
mirroring the ASR branch (ASR is the closest precedent: a non-Vulkan HostRenderer that scans out
through PresentExtension).

### The native scanout impl is already renderer-neutral
`VulkanRendererScanout.cpp` (310 lines) has **zero** Vulkan calls (grep confirms 0 `Vk*`/`vkDevice`/
`EGL`). It is pure NDK: `dlsym`'d `ASurfaceControl_*` + `ASurfaceTransaction_*` + `AHardwareBuffer_*`.
It is only *organizationally* a set of `VulkanRendererContext::` methods, and its state lives in
`VulkanRendererContext.h:303-340` (`scanoutGameSC`, `scanoutCursorSC`, the 9 `fn*` ptrs, the
`scanout*` rects/flags, cursor buffer). **One genuine coupling exists:** the cursor path
(`scanoutSetCursorImage`/`scanoutSetCursorPos`, `VulkanRendererScanout.cpp:260-309`) sets dirty
flags then signals the Vulkan render thread (`needsRender.store(true); dirtyCV.notify_one();`) and
the actual cursor transaction is applied later by `applyScanoutBuffer()` (`:204`) **from the Vulkan
render loop**. The game-buffer path (`scanoutSetBuffer`, `:165`) already applies its transaction
**inline** (`ST_APPLY(t)` at `:198`), so it is loop-independent. ASR proves cursor-inline-apply is
fine (`ASurfaceRenderer.onPointerMove` applies directly off the epoll thread). GL has no render loop,
so it must apply the cursor inline.

### Lib layout
`/home/claude-user/bannerlators/app/src/main/cpp/CMakeLists.txt`:
- `libwinlator.so` (`:20`) — owns `gpu_image.c`; loaded by `GPUImage` (Java).
- `libvulkan_renderer.so` (`:61`) — `vulkan_jni.cpp` + `VulkanRendererContext.cpp` +
  `VulkanRendererScanout.cpp`; **links `vulkan`, `adrenotools`**. A GL container must NOT have to
  load this (it would drag Vulkan/adreno deps onto the GL path).
- `libasurface_renderer.so` (`:84`) — `asurface_jni.cpp` + `ASurfaceRendererContext.cpp`; links only
  `log android dl atomic`. Note ASR already **re-implements** the same SurfaceControl/AHB logic in
  its own `ASurfaceRendererContext.cpp` — i.e. the scanout code is **already duplicated** between
  Vulkan and ASR today. Extracting once will pay down that debt too (optional follow-up to also fold
  ASR onto `ScanoutContext`).

### Container / toggle plumbing (reuse as-is)
- `Container.isRendererNative()` / `setRendererNative()` (`Container.java:491-492`, persisted `:564`,
  `:680`) — renderer-agnostic boolean; already works for GL containers.
- `Drawable.isDirectScanout()` / `setDirectScanout()` (`Drawable.java:94-99`) — renderer-agnostic;
  set by `DRI3Extension.java:162` and `PresentExtension.java:280/290`.
- `onNativeRenderingToggle` (`XServerDisplayActivity.java:503-519`) — currently casts to
  `VulkanRenderer` only.
- Preset mutual-exclusion: `disableNativeRenderingForPreset()` (`:1907`), `resetVulkanPresets()`
  (`:1919`), `initInlineTabStates()` preset-apply wiring (`:1929-1983`).

---

## 1. Architecture recommendation

### Native: extract a standalone `ScanoutContext`
Create `/home/claude-user/bannerlators/app/src/main/cpp/scanout/ScanoutContext.h` and
`ScanoutContext.cpp`:

- **Mechanical move** of the bodies in `VulkanRendererScanout.cpp` and the scanout members from
  `VulkanRendererContext.h:303-340` into a self-contained class `ScanoutContext`. Replace
  `VulkanRendererContext::` with `ScanoutContext::`. The macros (`SC_CREATE`, `ST_*`) and the 9
  `fn*` dlsym pointers move with it.
- **Threading stays out of ScanoutContext.** It must not reference `needsRender`, `dirtyCV`, or any
  render thread. Split the cursor methods into pure state-setters plus an explicit
  `applyPendingCursor()` (the body of today's `applyScanoutBuffer()`). The owner decides when to
  apply:
  - GL calls `setCursorImage()/setCursorPos()` then `applyPendingCursor()` **inline** (epoll thread;
    a SurfaceControl transaction apply is thread-safe and ASR already does this).
  - Vulkan keeps its existing deferral: `VulkanRendererContext` sets the dirty flags through the
    context, signals its loop (`needsRender`/`dirtyCV` remain in `VulkanRendererContext`), and the
    render loop calls `scanout.applyPendingCursor()`. **Vulkan behavior is preserved exactly.**
- `ScanoutContext` public surface (the renderer-neutral contract):
  `loadApi()`, `initFromWindows(ANativeWindow* game, ANativeWindow* cursor)`, `initFromWindow(...)`
  (fallback), `destroy()`, `setBuffer(AHardwareBuffer*, x,y,w,h, fenceFd)`,
  `setCursorImage(pixels,w,h,stride)`, `setCursorPos(x,y,hotX,hotY)`, `applyPendingCursor()`,
  `setDst(x,y,w,h)`, `setContainerSize(w,h)` / `setSurfaceSize(w,h)`,
  `isActive()`, `isGameFrameDelivered()`. (`containerWidth/Height`/`surfaceWidth/Height` are read by
  `setBuffer`/`applyPendingCursor` today via the enclosing context — they become explicit
  `ScanoutContext` fields set by the owner.)

### Native: keep Vulkan working, add a GL-facing lib
- `VulkanRendererContext` **owns a `ScanoutContext scanout;` member** and its existing scanout
  methods (`initScanout`, `scanoutSetBuffer`, …) become thin forwarders. **`vulkan_jni.cpp`
  signatures are unchanged → libvulkan_renderer ABI preserved.** Remove the moved bodies from
  `VulkanRendererScanout.cpp` (it becomes the forwarders, or is deleted and the forwarders inline in
  `VulkanRendererContext.cpp`).
- New `libdirect_scanout.so`: `scanout/directscanout_jni.cpp` + `scanout/ScanoutContext.cpp`. Links
  only `log android dl atomic` (same as asurface_renderer — **no Vulkan**). The GL container loads
  this small lib, never libvulkan_renderer.
- **CMake:** add `scanout/ScanoutContext.cpp` to the `vulkan_renderer` source list (replacing the
  emptied `VulkanRendererScanout.cpp`) AND to the new `direct_scanout` library. Compiling the ~310
  lines into both `.so`s (object duplication) is simpler and safer than introducing a third shared
  lib both must link/version against; it has no global state.

### Java: a renderer-neutral `DirectScanout`
New `/home/claude-user/bannerlators/app/src/main/java/com/winlator/star/renderer/DirectScanout.java`
(mirrors GameHub's `DirectRendering` singleton, but instance-per-renderer and pointer-fed, not
socket-fed):
- `static { System.loadLibrary("direct_scanout"); }`
- Owns a `long nativeHandle`, the two `SurfaceControl`s + `Surface`s, and the
  build/teardown/`setColorTransform` logic. **This is the lift-and-share of
  `VulkanRenderer.java:168-300` and `:614-679`** (SC builder, layers, `setFrameRate`,
  `applyScanoutSwapTransform`, `releaseScanoutSurfaces`).
- JNI: `nativeInit()`, `nativeSetWindows(Surface game, Surface cursor)`, `nativeSetBuffer(long ahbPtr,
  int x,int y,int w,int h,int fenceFd)`, `nativeSetCursorImage(ByteBuffer,short,short,short)`,
  `nativeSetCursorPos(short,short,short,short)`, `nativeSetDst(int,int,int,int)`,
  `nativeSetSurfaceSize`/`nativeSetContainerSize`, `nativeIsActive()`,
  `nativeIsGameFrameDelivered()`, `nativeDestroy()`.
- Public Java API: `enable(SurfaceControl parent, int containerW, int containerH, float targetFps,
  boolean swapRB)`, `disable()`, `present(long ahbPtr,int x,int y,int w,int h,int fence)`,
  `setCursorImage(...)`, `setCursorPos(...)`, `setDst(...)`, `isActive()`, `isGameFrameDelivered()`.

**Optional clean-up (defer unless free):** refactor `VulkanRenderer` to delegate to
`DirectScanout` too, deleting its duplicate SC-builder + native methods. This is *not required* for
the GL feature and adds Vulkan-regression risk, so keep it as a separate later pass. For this
project the Vulkan path stays byte-for-byte; only the native scanout *implementation* is shared.

---

## 2. GL renderer wiring (`GLRenderer.java`)

Mirror `VulkanRenderer`'s native-mode lifecycle, adapted to GL's pull model.

- **Fields/state:** `private DirectScanout scanout;`, `private boolean nativeMode;`,
  `private boolean xRenderingPausedForScanout;`, `setInitialNativeMode(boolean)`,
  `setNativeMode(boolean)`, `isNativeMode()`. (`nativeMode` lift from `VulkanRenderer.java:45/614-687`.)
- **Enable (`setNativeMode(true)` / `setInitialNativeMode` honored at surface-create):** on the UI
  thread (`xServerView.post`), get `(SurfaceControl) xServerView.getSurfaceControl()` (needs the
  XServerView fix in §3), `scanout.enable(parentSC, screenW, screenH, targetFps, swapRB)`. Build the
  child SCs **as a sibling/child of the GLSurfaceView's SC** with the **game layer z-order > 0** so
  it composites **on top of** the GL surface content.
- **Pause GL drawing on first delivered frame:** GL's `setRenderingEnabled` is currently a **no-op**
  (`GLRenderer.java:503`). Implement it to forward to `xServer.setRenderingEnabled(enabled)` (GL holds
  `xServer`). On first `isGameFrameDelivered()` transition, call `xServer.setRenderingEnabled(false)`
  + set `xRenderingPausedForScanout` — exactly the Vulkan logic at `VulkanRenderer.java:513-516`.
  With X rendering paused there are no content updates → no `requestRender` → GLSurfaceView idles and
  the opaque top game SC occludes the stale GL frame (SurfaceFlinger can promote it to an overlay —
  the whole point).
- **Per-frame push — primary integration in `PresentExtension`:** add a GL-native branch in
  `PresentExtension.java:267-305` **before** the final `else`:
  ```
  else if (xr instanceof GLRenderer && ((GLRenderer)xr).isNativeMode()
           && pixmap.drawable.getTexture() instanceof GPUImage
           && ((GPUImage)pixmap.drawable.getTexture()).getHardwareBufferPtr() != 0) {
      content.setTexture(pixmap.drawable.getTexture());
      content.setDirectScanout(true);
      sendCompleteNotify(window, serial, Kind.PIXMAP, Mode.FLIP, ust, msc);
      glr.presentScanout(window, content);          // new
      emitIdleNotify(window, pixmap, serial, idleFence, targetFps, null);
  }
  ```
  New `GLRenderer.presentScanout(Window, Drawable)`: lift the AHB-push body of
  `VulkanRenderer.onUpdateWindowContent` (`:497-528`) — `g.unlock()` → `scanout.present(ahbPtr, rx,
  ry, w, h, fence)` → `g.lock()` → `refreshDataFromTexture()` → first-delivery pause → HUD tick.
- **Cursor:** in scanout mode, set GLRenderer's own `cursorVisible=false` for its GL pass and route
  the cursor through `scanout.setCursorImage(...)` / `setCursorPos(...)` (which apply inline). Mirror
  `VulkanRenderer.sendCursorToNative` (`:422-441`) + the `onPointerMove` native branch (`:555-559`).
  Drive `setCursorPos` from `GLRenderer.onPointerMove` (`:320`, currently just `requestRender`).
- **Dst rect:** compute from `viewTransformation` (`viewOffsetX/Y`, `viewWidth/Height`) and call
  `scanout.setDst(...)` in `onSurfaceChanged` (`:210-225`) and on `toggleFullscreen`
  (`:398-401`) — same data the Vulkan `updateTransform` (`:302-338`) feeds `nativeScanoutSetDst`.
- **Effect chain bypass:** scanout skips `onDrawFrame`/`EffectComposer` entirely. When native mode is
  on, ensure `effectComposer` is inactive (it already gates on `isActive()` at `:235`, and with X
  rendering paused `onDrawFrame` stops firing). The drawer must also hard-disable the GL effects UI
  in native mode (see §4) so no effect appears to be "on" while bypassed.
- **Teardown:** `setNativeMode(false)` / `onSurfaceDestroyed` / `forceCleanup` → `scanout.disable()`
  (hide + release SCs), `xServer.setRenderingEnabled(true)`, `requestRender()`. Mirror
  `VulkanRenderer.java:663-672` + `:225-271`.

---

## 3. THE KEY UNKNOWN — SurfaceControl hosting on GL

**Question:** can a `GLSurfaceView` host the sibling game/cursor `SurfaceControl`s, or must we host
from a plain `SurfaceView` / reparent under the activity root (GameHub)?

**Finding (code-reading, high confidence):** `GLSurfaceView extends SurfaceView`, so it inherits
`getSurfaceControl()` (API 29+). The only reason GL has no SC today is that `XServerView`
short-circuits to null for the GL branch (`XServerView.java:127`). The Vulkan path already builds
**child** SCs under its SurfaceView's SC and that is the production path. There is no API reason a
GLSurfaceView's SC cannot parent the same children.

**Recommendation:** reuse the Vulkan child-SC model verbatim. The minimal enabler:
```java
// XServerView.getSurfaceControl()
if (Build.VERSION.SDK_INT >= 29) {
    if (vulkanSurfaceView != null) return vulkanSurfaceView.getSurfaceControl();
    if (glSurfaceView != null)     return glSurfaceView.getSurfaceControl();
}
return null;
```
Then `DirectScanout.enable` builds child SCs under it with the game layer above the GL content.

**What MUST be validated on-device (cannot be proven by reading code):**
1. `glSurfaceView.getSurfaceControl()` returns a **valid, non-null** SC after the GL surface is
   created (timing: must be called after `surfaceCreated`, on the UI thread — same as Vulkan).
2. A child SC with positive z-order **composites above** the GLSurfaceView's EGL content (the GL
   surface is the parent layer; expected to render beneath, but confirm — GLSurfaceView surfaces are
   opaque by default).
3. The opaque full-screen game SC actually lets SurfaceFlinger **skip/overlay** the GL layer (the
   power/latency win) — confirm via `dumpsys SurfaceFlinger` HWC layer state.
4. On surface destroy/recreate (rotation, app switch), GL SC teardown/rebuild doesn't leak or
   black-screen (GLSurfaceView manages its own EGL across pause/resume, `XServerView.onPause/onResume`).

**Fallback A (if child-under-GLSurfaceView fails to composite):** reparent the game/cursor SCs under
the **activity root SurfaceControl** (GameHub's model — `setParent(activityRootSC)` with z-order
above the content view). Higher reach across the view tree but must be torn down on activity
lifecycle.

**Fallback B (if GLSurfaceView SC is unusable at all):** swap the GL container's view from
`GLSurfaceView` to a plain `SurfaceView` + an EGL context we manage (this is what ASR effectively
does for its non-GL path), or simply **gate GL-native to "unsupported, use Vulkan/ASR"** on devices
where (1)–(2) fail. Given Adreno is the primary target and the Vulkan path already works there,
Fallback B is an acceptable last resort, not a blocker.

---

## 4. Toggle / UI wiring

- **`onNativeRenderingToggle` (`XServerDisplayActivity.java:503-519`):** add a `GLRenderer` arm:
  ```
  if (r instanceof VulkanRenderer)   { ...existing... }
  else if (r instanceof GLRenderer)  { ((GLRenderer)r).setNativeMode(next);
                                       if (next) resetGlEffectsForNative(); }
  ```
- **Launch path (`XServerDisplayActivity.java:1766-1768`):** the Vulkan block sets
  `setInitialNativeMode(container.isRendererNative())`. Add the equivalent in the `GLRenderer`
  block (`:1784-1786`): `glr.setInitialNativeMode(container.isRendererNative())` +
  `XServerDrawerState.INSTANCE.setNativeRenderingEnabled(nativeOn)`. Also wire
  `glr.setHudFrameTick(...)` like the ASR block (`:1790-1798`) so the perf HUD ticks per present in
  GL native mode (the FLIP path bypasses copyArea's HUD driver — same problem ASR/Vulkan solved).
- **Preset mutual-exclusion (`:1907-1983`):** GL's "presets" are the EffectComposer effects (the
  GL-only screen effects + GL upscalers), which are exactly the things scanout bypasses. Mirror the
  Vulkan logic:
  - `disableNativeRenderingForPreset()` (`:1907`): generalize the cast so it also turns GL native off
    (`if (r instanceof GLRenderer) ((GLRenderer)r).setNativeMode(false);`).
  - Add `resetGlEffectsForNative()` analogous to `resetVulkanPresets()` (`:1919`): when GL native is
    enabled, reset the GL EffectComposer toggles/sliders + their `XServerDialogState` flows to
    neutral so the drawer is truthful.
  - In `initInlineTabStates` (`:1929-1983`): when GL-native is active, the GL effect apply callbacks
    should `disableNativeRenderingForPreset()` on enable (Direction A), and enabling GL native should
    reset the effects (Direction B) — symmetric to the Vulkan wiring. `setEffectsSupported(...)`
    (`:1933`) may stay true for GL but the drawer should **gray out / disable** the GL effects while
    native is on (they are bypassed), matching how Vulkan greys its post effects.
- **Drawer:** the "Native Rendering" toggle already exists and is renderer-independent
  (`XServerDrawerState.nativeRenderingEnabled`); no new control needed. Just ensure it is **shown for
  GL containers** (if currently Vulkan-gated in the drawer, ungate it) and that the GL effects
  section disables while it is on.

---

## 5. Risks & unknowns (ordered; confidence labelled)

1. **GLSurfaceView child-SC composites correctly above GL content** — *device-only, highest risk.*
   §3 (2)/(3). If a positive-z child SC does not occlude the GL EGL surface, GL native shows the
   stale GL frame under/over the game. Mitigation: Fallback A (activity-root reparent) then B.
2. **Power/latency actually improves** — *device-only.* The feature's reason to exist. Must confirm
   SurfaceFlinger promotes the game SC to an HWC overlay and skips GL composition
   (`dumpsys SurfaceFlinger`); otherwise it is strictly worse than the GL compositor.
3. **Cursor inline-apply from the epoll thread** — *code-reading + device.* Dropping the Vulkan
   render-loop deferral for GL (and, if the optional Vulkan delegation is done, for Vulkan too).
   ASR already applies cursor transactions off-thread successfully, so confidence is medium-high, but
   transaction-apply rate under heavy pointer motion must be sanity-checked for jank.
4. **`ScanoutContext` extraction is behavior-preserving for Vulkan** — *compiles + CI, then device.*
   The cursor threading split (§1) is the only semantic change to the Vulkan path; everything else is
   a mechanical method move with identical JNI ABI. Must device-re-test Vulkan native after the
   refactor lands (regression guard) **before** building any GL code on top.
5. **API-level gates** — *code-reading, high confidence.* `ASurfaceControl`/`ASurfaceTransaction`
   need **API 29** (already gated, `VulkanRendererScanout.cpp:24-25`; `ASurfaceRenderer.isSupported`
   = API 29). `SurfaceControl.Transaction.setFrameRate` needs **API 30** (already guarded,
   `VulkanRenderer.java:637`). GameHub's `setPosition`/geometry strings imply some geometry ops want
   **API 31** — we use `ASurfaceTransaction_setGeometry` (API 29, present in the dlsym set) and
   `setZOrder` is optional-guarded (`ST_SETZORDER` no-ops if missing). GL native must hard-gate to
   API ≥ 29 and fall back to the GL compositor below that.
6. **Adreno / GLES specifics** — *device.* Brief flags ASR is reboot-risk on non-Adreno/old SoCs;
   GL-native via SurfaceControl shares that exposure. Keep GL native **experimental/gated** like ASR.
   The AHB the GL path scans out is the same DXVK/DRI3 buffer Vulkan scans out, so format/modifier is
   identical to the proven Vulkan path (low risk on that axis).
7. **Orientation / scaling / fence** — *code-reading + device.* `setDst`/src-rect geometry is reused
   verbatim from the Vulkan path; the fence from `GPUImage.unlock()` is passed straight to
   `ASurfaceTransaction_setBuffer` (same as Vulkan `VulkanRenderer.java:506-508`). Letterbox/fullscreen
   must be confirmed to match the GL compositor's `viewTransformation` mapping.
8. **`swapRB` / color** — *device.* `applyScanoutSwapTransform` (`VulkanRenderer.java:280-300`) uses a
   reflection `setColorTransform`; carry it into `DirectScanout`. Confirm R/B ordering on GL matches
   (cf. open ASR fix GN #1622 R/B swap — same hazard class).

---

## 6. Phased steps (small, independently testable)

**P0 — Native extraction (no behavior change).**
- Create `scanout/ScanoutContext.{h,cpp}`; move the scanout bodies + state out of
  `VulkanRendererContext`/`VulkanRendererScanout.cpp`. Split cursor into setters +
  `applyPendingCursor()`. `VulkanRendererContext` owns a `ScanoutContext` and forwards.
- CMake: `ScanoutContext.cpp` into `vulkan_renderer`. Build green (CI). **Device-re-test Vulkan
  native** (regression gate — must pass before P1).

**P1 — New GL-facing native lib + Java class (dormant).**
- New `libdirect_scanout.so` (`directscanout_jni.cpp` + `ScanoutContext.cpp`) and
  `DirectScanout.java`. Not wired into any renderer yet. CI green, lib loads.

**P2 — XServerView SC enabler.**
- `XServerView.getSurfaceControl()` returns `glSurfaceView.getSurfaceControl()` for GL. Trivial;
  CI green. **Device-validate** §3 (1): non-null SC on GL.

**P3 — GLRenderer scanout lifecycle (no per-frame yet).**
- `nativeMode`/`setNativeMode`/`setInitialNativeMode`, `DirectScanout.enable/disable`, dst, cursor,
  implement `setRenderingEnabled` to forward to `xServer`. Toggle wiring §4 (activity + drawer).
  At this point enabling GL native should build the SCs and show the cursor SC; game still composited
  by GL. **Device-validate** §3 (2): cursor SC composites above GL.

**P4 — Per-frame game push.**
- PresentExtension GL-native branch + `GLRenderer.presentScanout` + first-delivery X-rendering pause
  + HUD tick. **Device-test the full feature** (§7).

**P5 — Effect mutual-exclusion + drawer polish.**
- `resetGlEffectsForNative`, grey-out GL effects under native, symmetric Direction A/B.

**P6 (optional, separate).** Fold `VulkanRenderer` and/or `ASurfaceRenderer` onto `DirectScanout`/
`ScanoutContext` to delete the duplicate SC code. Pure cleanup; gated on its own Vulkan/ASR device
re-test.

---

## 7. Device test procedure

Same device that runs this session → **flush memory + PROGRESS_LOG + commit before testing**
(hard rule). Build is `release-device-engineer` territory.

**Container setup:** Renderer = **GL** (`container.renderer = gl`); Native Rendering = **on** (set
`rendererNative` true, or flip the in-game drawer toggle). A DXVK/DRI3 game that produces an
AHB-backed FLIP present (the AIO DX11 SPACE scene is a known good high-frequency source).

**Pass criteria:**
1. **Frame still presents** — game renders correctly (no black screen, correct colors → checks
   swapRB §5.8), correct letterbox/fullscreen mapping vs the GL compositor.
2. **Cursor** correct (position, hotspot, scaling) and not double-drawn (GL cursor pass off).
3. **Lower overhead** — `dumpsys SurfaceFlinger` shows the game layer on an **HWC overlay** (GL layer
   skipped); GPU/compositor load and/or power lower than GL-compositor mode at the same FPS. This is
   the feature's justification.
4. **GL effects correctly unavailable** — EffectComposer effects (and GL upscalers) are greyed/disabled
   in the drawer while native is on; enabling one turns native off with the existing toast.
5. **HUD ticks** in GL native mode (FPS not frozen).
6. **Lifecycle** — rotate / background+foreground: no black screen, no SC leak, clean teardown on
   exit. Toggle native off → GL compositor resumes cleanly.
7. **Regression** — Vulkan native (after P0) unchanged; ASR unchanged.

**Report axis discipline:** always note GL vs Vulkan vs ASR; distinguish CI-green from device-proven.
GL-native must ship **experimental/gated** (like ASR) until proven on Adreno + at least one non-Adreno
SoC.
