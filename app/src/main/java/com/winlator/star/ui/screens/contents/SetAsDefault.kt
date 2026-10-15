package com.winlator.star.ui.screens.contents

import android.content.Context
import android.util.Log
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerLayerUpdater
import com.winlator.star.container.ContainerManager
import com.winlator.star.container.Shortcut
import com.winlator.star.contentdialog.DXVKConfigDialog
import com.winlator.star.contentdialog.GraphicsDriverConfigDialog
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.core.DefaultVersion
import com.winlator.star.core.DirectAudioSupport
import com.winlator.star.core.KeyValueSet
import com.winlator.star.core.NewContainerDefaults
import com.winlator.star.core.WineInfo
import com.winlator.star.core.WineWaylandSupport
import com.winlator.star.linux.LinuxShortcuts
import com.winlator.star.ui.screens.withGraphicsDriverVersion
import org.json.JSONObject

/**
 * "Set as default…" for an installed driver or component (Contents › Installed): writes ONE setting
 * for ONE architecture into any of three places —
 *  • New container defaults: the arch's profile when it has one, else its sparse picks
 *    (see [NewContainerDefaults]); the layer under its own per-arch key.
 *  • All existing containers of that arch: the container's own field (layers: an in-place swap
 *    through [ContainerLayerUpdater.switchLine], snapshot included, so Revert layer works).
 *  • All game shortcuts in those containers: the same per-game extra the game's settings screen
 *    writes (ShortcutsScreen save()), so the value becomes that game's own. Layers have no per-game
 *    setting.
 * Running containers (and their games) are skipped. Every value written is recorded first, so the
 * last apply can be undone exactly ([undo]) for as long as the process lives.
 */
object SetAsDefault {

    private const val TAG = "SetAsDefault"
    const val ARM64EC = NewContainerDefaults.ARCH_ARM64EC
    const val X86_64 = NewContainerDefaults.ARCH_X86_64

    /** What an item sets. [setting] reads in "sets the …" and "containers change their …". */
    enum class Kind(val setting: String) {
        DRIVER("graphics driver"),
        DXVK("DX wrapper"),
        VEGAS("DX wrapper"),
        VKD3D("VKD3D version"),
        BOX64("Box64 version"),
        WOWBOX64("WOWBox64 version"),
        FEXCORE("FEXCore version"),
        LAYER("compatibility layer"),
    }

    /** An installed item that can be made a default. [value] is exactly what gets written. */
    data class Item(val kind: Kind, val title: String, val value: String, val archs: Set<String>)

    fun archLabel(arch: String) = if (arch == X86_64) "x86-64" else "arm64ec"

    fun driverItem(id: String, name: String) =
        Item(Kind.DRIVER, name.ifEmpty { id }, id, setOf(ARM64EC, X86_64))

    /**
     * The item for an installed component profile, or null for a type with no default to set (D7VK).
     * Values mirror the editors' version lists: `<verName>-<verCode>` for DXVK/VKD3D/Box64/WOWBox64/
     * FEXCore (the entry name minus its type), the bare version for VEGAS ("vegas-2.7.3" → "2.7.3"),
     * the full entry name for a layer.
     */
    fun componentItem(context: Context, cm: ContentsManager, p: ContentProfile): Item? {
        val id = "${p.verName}-${p.verCode}"
        val both = setOf(ARM64EC, X86_64)
        return when (p.type) {
            // DXVK builds named *arm64ec* are only listed for arm64ec containers (loadDxvkVersionList).
            ContentProfile.ContentType.CONTENT_TYPE_DXVK ->
                Item(Kind.DXVK, p.verName, id, if (p.verName.contains("arm64ec")) setOf(ARM64EC) else both)
            ContentProfile.ContentType.CONTENT_TYPE_VKD3D -> Item(Kind.VKD3D, p.verName, id, both)
            ContentProfile.ContentType.CONTENT_TYPE_VEGAS ->
                Item(Kind.VEGAS, p.verName, p.verName.removePrefix("vegas-"), both)
            ContentProfile.ContentType.CONTENT_TYPE_BOX64 -> Item(Kind.BOX64, p.verName, id, setOf(X86_64))
            ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64 -> Item(Kind.WOWBOX64, p.verName, id, setOf(ARM64EC))
            ContentProfile.ContentType.CONTENT_TYPE_FEXCORE -> Item(Kind.FEXCORE, p.verName, id, setOf(ARM64EC))
            ContentProfile.ContentType.CONTENT_TYPE_WINE, ContentProfile.ContentType.CONTENT_TYPE_PROTON -> {
                val entry = ContentsManager.getEntryName(p)
                val arm = runCatching { WineInfo.fromIdentifier(context, cm, entry).isArm64EC }.getOrDefault(false)
                Item(Kind.LAYER, p.verName, entry, setOf(if (arm) ARM64EC else X86_64))
            }
            else -> null
        }
    }

    // ── Writers shared by containers, games, profiles and the create form ────────────────────────

    private fun hasDxvk(w: String) = w.contains("dxvk")
    private fun hasVegas(w: String) = w.contains("vegas")

    /**
     * [dxwrapper]/[dxwrapperConfig] with [kind]'s version set. DXVK and VEGAS share the `version`
     * key, so picking one also switches the wrapper to it, keeping the VKD3D half ("…+vkd3d"); a
     * WineD3D container likewise moves to "dxvk+vkd3d" / "vegas+vkd3d". VKD3D only runs paired, so on
     * WineD3D it moves to "dxvk+vkd3d" too, keeping `version` when it names a DXVK this arch can use
     * ([dxvkVersions]) and otherwise taking the DXVK default.
     */
    fun withDx(
        kind: Kind, value: String, dxwrapper: String, dxwrapperConfig: String, dxvkVersions: () -> List<String>,
    ): Pair<String, String> {
        val cfg = KeyValueSet(dxwrapperConfig.ifEmpty { Container.DEFAULT_DXWRAPPERCONFIG })
        var w = dxwrapper
        when (kind) {
            Kind.DXVK -> { cfg.put("version", value); if (!hasDxvk(w)) w = "dxvk+vkd3d" }
            Kind.VEGAS -> { cfg.put("version", value); if (!hasVegas(w)) w = "vegas+vkd3d" }
            Kind.VKD3D -> {
                cfg.put("vkd3dVersion", value)
                when {
                    hasDxvk(w) -> if (!w.contains("vkd3d")) w = "dxvk+vkd3d"
                    hasVegas(w) -> if (!w.contains("vkd3d")) w = "vegas+vkd3d"
                    else -> {
                        w = "dxvk+vkd3d"
                        val list = dxvkVersions()
                        if (cfg.get("version") !in list)
                            cfg.put("version", DefaultVersion.getDxvkDefault().takeIf { it in list } ?: list.firstOrNull() ?: DefaultVersion.getDxvkDefault())
                    }
                }
            }
            else -> {}
        }
        return w to cfg.toString()
    }

    private fun dxvkList(context: Context, cm: ContentsManager, arch: String): () -> List<String> = {
        DXVKConfigDialog.loadDxvkVersionList(context, cm, arch == ARM64EC)
    }

    /** The New-Container-Defaults value of [kind] for [arch] (profile, else picks), or null. */
    fun currentDefault(context: Context, arch: String, kind: Kind): String? {
        if (kind == Kind.LAYER) return NewContainerDefaults.layer(context, arch)
        val profile = NewContainerDefaults.load(context, arch)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (profile == null) {
            val picks = NewContainerDefaults.loadPicks(context, arch) ?: return null
            return picks.optString(kind.name).ifEmpty { null }
        }
        val w = profile.optString("dxwrapper")
        val cfg = KeyValueSet(profile.optString("dxwrapperConfig"))
        return when (kind) {
            Kind.DRIVER -> GraphicsDriverConfigDialog.getVersion(profile.optString("graphicsDriverConfig"))
            Kind.DXVK -> if (hasDxvk(w)) cfg.get("version") else null
            Kind.VEGAS -> if (hasVegas(w)) cfg.get("version") else null
            Kind.VKD3D -> if (hasDxvk(w) || hasVegas(w)) cfg.get("vkd3dVersion") else null
            Kind.BOX64 -> if (arch == X86_64) profile.optString("box64Version") else null
            Kind.WOWBOX64 -> if (arch == ARM64EC) profile.optString("box64Version") else null
            Kind.FEXCORE -> profile.optString("fexcoreVersion")
            Kind.LAYER -> null
        }?.ifEmpty { null }
    }

    // ── Scope: containers + games of an arch ─────────────────────────────────────────────────────

    class Scope(val containers: List<Container>, val shortcuts: List<Shortcut>)

    /** Wine containers of [arch] and their games (no Android apps, no Linux entries). Disk walk: IO only. */
    fun scope(context: Context, arch: String): Scope {
        val cm = ContentsManager(context).apply { syncContents() }
        val manager = ContainerManager(context)
        val containers = manager.containers.filter { archOf(context, cm, it) == arch }
        val ids = containers.map { it.id }.toSet()
        val shortcuts = runCatching { manager.loadShortcuts() }.getOrDefault(arrayListOf())
            .filter { it.container.id in ids && isGame(it) }
        return Scope(containers, shortcuts)
    }

    private fun isGame(s: Shortcut) =
        s.getExtra("storeSource") != "android" && !LinuxShortcuts.isLinuxEntry(s)

    private fun archOf(context: Context, cm: ContentsManager, c: Container): String =
        if (runCatching { WineInfo.fromIdentifier(context, cm, c.wineVersion).isArm64EC }.getOrDefault(false)) ARM64EC else X86_64

    // ── Apply + undo ─────────────────────────────────────────────────────────────────────────────

    private class ContainerUndo(val id: Int, val fields: Map<String, String?>, val layer: ContainerLayerUpdater.Snapshot?)
    private class ShortcutUndo(val containerId: Int, val path: String, val extras: Map<String, String?>)
    private class Undo(
        val item: Item, val arch: String,
        val defaults: NewContainerDefaults.Snapshot?,
        val containers: List<ContainerUndo>,
        val shortcuts: List<ShortcutUndo>,
    )

    /** The last apply, kept for the session so its Undo restores exactly what was there before. */
    @Volatile private var lastUndo: Undo? = null

    data class Outcome(val message: String, val canUndo: Boolean)

    /** Write [item] as the [arch] default into the ticked scopes. Blocking disk work: IO only. */
    fun apply(context: Context, item: Item, arch: String, toDefaults: Boolean, toContainers: Boolean, toGames: Boolean): Outcome {
        val cm = ContentsManager(context).apply { syncContents() }
        val updater = ContainerLayerUpdater(context)
        val dxvk = dxvkList(context, cm, arch)
        var defaultsSnap: NewContainerDefaults.Snapshot? = null
        val cUndo = mutableListOf<ContainerUndo>()
        val sUndo = mutableListOf<ShortcutUndo>()
        var skipped = 0
        val failures = mutableListOf<String>()

        if (toDefaults) {
            defaultsSnap = NewContainerDefaults.capture(context, arch)
            writeDefault(context, item, arch, dxvk)
        }

        val scope = if (toContainers || (toGames && item.kind != Kind.LAYER)) scope(context, arch) else Scope(emptyList(), emptyList())
        val running = scope.containers.filter { updater.isRunning(it) }.map { it.id }.toSet()
        skipped += running.size

        if (toContainers) {
            for (c in scope.containers) {
                if (c.id in running) continue
                runCatching {
                    if (item.kind == Kind.LAYER) {
                        if (c.wineVersion == item.value) return@runCatching
                        val before = mapOf("audioDriver" to c.getAudioDriver(), "displayBackend" to c.getDisplayBackend())
                        updater.switchLine(cm, c, item.value).onSuccess { snap ->
                            // What the editor would coerce on a layer change: DirectAudio only loads on
                            // its supported builds; Wayland only on a layer that ships winewayland.
                            var touched = false
                            if (c.getAudioDriver() == "directaudio" && !DirectAudioSupport.isSupported(item.value)) {
                                c.setAudioDriver(Container.DEFAULT_AUDIO_DRIVER); touched = true
                            }
                            if (c.getDisplayBackend() == Container.DISPLAY_BACKEND_WAYLAND &&
                                !WineWaylandSupport.isWaylandCapable(context, cm, item.value)) {
                                c.setDisplayBackend(Container.DISPLAY_BACKEND_X11); touched = true
                            }
                            if (touched) c.saveData()
                            cUndo += ContainerUndo(c.id, if (touched) before else emptyMap(), snap)
                        }.onFailure { failures += it.message ?: c.name }
                    } else {
                        cUndo += ContainerUndo(c.id, writeContainer(c, item, dxvk), null)
                        c.saveData()
                    }
                }.onFailure { failures += "${c.name}: ${it.message}"; Log.w(TAG, "container ${c.name}", it) }
            }
        }

        if (toGames && item.kind != Kind.LAYER) {
            for (s in scope.shortcuts) {
                if (s.container.id in running) continue
                runCatching {
                    sUndo += ShortcutUndo(s.container.id, s.file.absolutePath, writeShortcut(s, item, dxvk))
                    s.saveData()
                }.onFailure { failures += "${s.name}: ${it.message}"; Log.w(TAG, "shortcut ${s.name}", it) }
            }
        }

        lastUndo = Undo(item, arch, defaultsSnap, cUndo, sUndo)
        val parts = mutableListOf("${item.title} set as default (${archLabel(arch)})")
        if (skipped > 0) parts += "$skipped running skipped"
        if (failures.isNotEmpty()) {
            parts += "${failures.size} failed"
            Log.w(TAG, "failures: $failures")
        }
        return Outcome(parts.joinToString(" · "), true)
    }

    /** Undo the last [apply]. Returns a one-line result. Blocking: IO only. */
    fun undo(context: Context): String {
        val u = lastUndo ?: return "Nothing to undo"
        lastUndo = null
        val cm = ContentsManager(context).apply { syncContents() }
        val manager = ContainerManager(context)
        val updater = ContainerLayerUpdater(context)
        val failures = mutableListOf<String>()
        u.defaults?.let { NewContainerDefaults.restore(context, it) }
        for (cu in u.containers) {
            val c = manager.getContainerById(cu.id) ?: continue
            runCatching {
                cu.layer?.let { snap ->
                    updater.revert(cm, c, snap).onFailure { failures += it.message ?: c.name }
                }
                if (cu.fields.isNotEmpty()) {
                    restoreContainerFields(c, cu.fields)
                    c.saveData()
                }
            }.onFailure { failures += "${c.name}: ${it.message}" }
        }
        for (su in u.shortcuts) {
            val c = manager.getContainerById(su.containerId) ?: continue
            runCatching {
                val s = Shortcut(c, java.io.File(su.path))
                su.extras.forEach { (k, v) -> s.putExtra(k, v) }
                s.saveData()
            }.onFailure { failures += "${su.path}: ${it.message}" }
        }
        return if (failures.isEmpty()) "Undone: ${u.item.title} (${archLabel(u.arch)})"
        else "Undone with ${failures.size} problem(s): ${failures.first()}"
    }

    private fun writeDefault(context: Context, item: Item, arch: String, dxvk: () -> List<String>) {
        if (item.kind == Kind.LAYER) { NewContainerDefaults.setLayer(context, arch, item.value); return }
        val profileJson = NewContainerDefaults.load(context, arch)
        if (profileJson != null) {
            val p = JSONObject(profileJson)
            when (item.kind) {
                Kind.DRIVER -> p.put("graphicsDriverConfig",
                    withGraphicsDriverVersion(p.optString("graphicsDriverConfig", Container.DEFAULT_GRAPHICSDRIVERCONFIG), item.value))
                Kind.DXVK, Kind.VEGAS, Kind.VKD3D -> {
                    val (w, cfg) = withDx(item.kind, item.value,
                        p.optString("dxwrapper", Container.DEFAULT_DXWRAPPER), p.optString("dxwrapperConfig"), dxvk)
                    p.put("dxwrapper", w); p.put("dxwrapperConfig", cfg)
                }
                Kind.BOX64, Kind.WOWBOX64 -> p.put("box64Version", item.value)
                Kind.FEXCORE -> p.put("fexcoreVersion", item.value)
                Kind.LAYER -> {}
            }
            NewContainerDefaults.save(context, arch, p.toString())
        } else {
            val picks = NewContainerDefaults.loadPicks(context, arch) ?: JSONObject()
            // DXVK and VEGAS both own `version`: the newer pick replaces the other.
            if (item.kind == Kind.DXVK) picks.remove(Kind.VEGAS.name)
            if (item.kind == Kind.VEGAS) picks.remove(Kind.DXVK.name)
            picks.put(item.kind.name, item.value)
            NewContainerDefaults.savePicks(context, arch, picks)
        }
    }

    /** Writes [item] onto [c] (not saved); returns the previous values of the fields it wrote. */
    private fun writeContainer(c: Container, item: Item, dxvk: () -> List<String>): Map<String, String?> = when (item.kind) {
        Kind.DRIVER -> mapOf("graphicsDriverConfig" to c.graphicsDriverConfig).also {
            c.graphicsDriverConfig = withGraphicsDriverVersion(c.graphicsDriverConfig ?: Container.DEFAULT_GRAPHICSDRIVERCONFIG, item.value)
        }
        Kind.DXVK, Kind.VEGAS, Kind.VKD3D -> mapOf("dxwrapper" to c.getDXWrapper(), "dxwrapperConfig" to c.getDXWrapperConfig()).also {
            val (w, cfg) = withDx(item.kind, item.value, c.getDXWrapper(), c.getDXWrapperConfig(), dxvk)
            c.setDXWrapper(w); c.setDXWrapperConfig(cfg)
        }
        Kind.BOX64, Kind.WOWBOX64 -> mapOf("box64Version" to c.getBox64Version()).also { c.setBox64Version(item.value) }
        Kind.FEXCORE -> mapOf("fexcoreVersion" to c.getFEXCoreVersion()).also { c.setFEXCoreVersion(item.value) }
        Kind.LAYER -> emptyMap()
    }

    private fun restoreContainerFields(c: Container, fields: Map<String, String?>) {
        for ((k, v) in fields) when (k) {
            "graphicsDriverConfig" -> c.setGraphicsDriverConfig(v ?: Container.DEFAULT_GRAPHICSDRIVERCONFIG)
            "dxwrapper" -> c.setDXWrapper(v ?: Container.DEFAULT_DXWRAPPER)
            "dxwrapperConfig" -> c.setDXWrapperConfig(v)
            "box64Version" -> c.setBox64Version(v)
            "fexcoreVersion" -> c.setFEXCoreVersion(v)
            "audioDriver" -> c.setAudioDriver(v ?: Container.DEFAULT_AUDIO_DRIVER)
            "displayBackend" -> c.setDisplayBackend(v)
        }
    }

    /**
     * Writes [item] into [s]'s own extras — the same keys ShortcutsScreen's save() writes — starting
     * from what the game runs with today (its extra, else its container's value). Returns the previous
     * extras (null = was absent, so undo removes it again).
     */
    private fun writeShortcut(s: Shortcut, item: Item, dxvk: () -> List<String>): Map<String, String?> {
        val c = s.container
        fun prev(k: String) = if (s.hasExtra(k)) s.getExtra(k) else null
        return when (item.kind) {
            Kind.DRIVER -> mapOf("graphicsDriverConfig" to prev("graphicsDriverConfig")).also {
                val base = s.getExtra("graphicsDriverConfig", "").ifEmpty { c.graphicsDriverConfig ?: Container.DEFAULT_GRAPHICSDRIVERCONFIG }
                s.putExtra("graphicsDriverConfig", withGraphicsDriverVersion(base, item.value))
            }
            Kind.DXVK, Kind.VEGAS, Kind.VKD3D -> mapOf("dxwrapper" to prev("dxwrapper"), "dxwrapperConfig" to prev("dxwrapperConfig")).also {
                val (w, cfg) = withDx(item.kind, item.value,
                    s.getExtra("dxwrapper", "").ifEmpty { c.getDXWrapper() },
                    s.getExtra("dxwrapperConfig", "").ifEmpty { c.getDXWrapperConfig() }, dxvk)
                s.putExtra("dxwrapper", w); s.putExtra("dxwrapperConfig", cfg)
            }
            Kind.BOX64, Kind.WOWBOX64 -> mapOf("box64Version" to prev("box64Version")).also { s.putExtra("box64Version", item.value) }
            Kind.FEXCORE -> mapOf("fexcoreVersion" to prev("fexcoreVersion")).also { s.putExtra("fexcoreVersion", item.value) }
            Kind.LAYER -> emptyMap()
        }
    }
}
