package com.winlator.star.linux;

import android.content.Context;
import android.util.Log;

import com.winlator.star.container.Container;
import com.winlator.star.container.ContainerManager;
import com.winlator.star.core.FileUtils;

import org.json.JSONObject;

import java.io.File;

/**
 * The Linux runtime's own settings, global and container-free.
 *
 * <p>A Linux session - gamescope and Valve's native Steam client - runs no Wine, yet until now its
 * entry had to live inside a Wine container, because a container is two things at once: a prefix
 * on disk and the envelope the launch reads its settings from. Only the second is wanted here.
 * This is that envelope on its own: a {@link Container} with a reserved id whose root is
 * {@code files/linux/}, no prefix beneath it, its config in {@code files/linux/.container} like any
 * other. {@link ContainerManager#getContainerById} hands it out for {@link #CONTAINER_ID}, so the
 * activity, the editors and the launch intents need no special case, and the Games tab lists what
 * is in its desktop directory beside every container's.
 *
 * <p>Global on purpose: there is one Steam client in the runtime and one set of settings for it,
 * which is how Steam itself thinks. Entries written into Wine containers before this existed keep
 * working - they carry the runtime themselves - and the runtime tab moves the Steam entry here.
 */
public final class LinuxSettings {
    private static final String TAG = "LinuxSettings";
    /** Never a real container's id: those count up from 1. */
    public static final int CONTAINER_ID = -7;
    public static final String DIR = "linux";
    public static final String NAME = "Linux runtime";

    private LinuxSettings() {}

    /** Set once the settings have been seeded from a container; names the source. */
    public static final String EXTRA_SEEDED_FROM = "linuxSeededFrom";
    /** The container the Steam entry was moved out of, recorded at the move. */
    public static final String EXTRA_MOVED_FROM = "linuxMovedFrom";

    public static boolean isSeeded(Container linux) {
        return linux != null && !linux.getExtra(EXTRA_SEEDED_FROM, "").isEmpty();
    }

    /**
     * Copies every setting of {@code source} into the Linux settings - HUD config and its master
     * switch, frame generation and LSFG flags, audio, env vars, cpu lists, the lot - keeping the
     * settings container's own id, root, name and runtime. Done once, when the Steam entry leaves
     * the Wine container it used to live in: that container's settings ARE the user's settings for
     * the client, and starting from the app's defaults instead lost them (device-seen: the HUD went
     * missing, because showFPS defaulted to false). Returns false if nothing could be copied.
     */
    public static boolean seedFrom(Container linux, Container source) {
        if (linux == null || source == null || source.id == linux.id) return false;
        try {
            JSONObject data = source.getData();
            data.remove("id");
            data.remove("name");
            linux.loadData(data);
            linux.setName(NAME);
            linux.setRuntime(Container.RUNTIME_GAMESCOPE);
            linux.putExtra(EXTRA_SEEDED_FROM, String.valueOf(source.id));
            linux.saveData();
            Log.i(TAG, "Linux settings seeded from container " + source.id + " (" + source.getName() + ")");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "could not seed the Linux settings from container " + source.id, e);
            return false;
        }
    }

    public static boolean isLinuxContainer(int id) {
        return id == CONTAINER_ID;
    }

    public static boolean isLinuxContainer(Container container) {
        return container != null && container.id == CONTAINER_ID;
    }

    public static File rootDir(Context context) {
        return new File(context.getFilesDir(), DIR);
    }

    /**
     * The settings container, read from disk or created with the Linux runtime's defaults the
     * first time. Cheap enough to build on demand; {@link ContainerManager} keeps one per instance.
     */
    /**
     * The bundled driver a Wayland/gamescope session should start on when nothing was picked.
     * The compositor imports every frame as a dma-buf, and only Turnip (Mesa) advertises
     * {@code VK_EXT_external_memory_dma_buf} + {@code VK_EXT_image_drm_format_modifier} on
     * Adreno; the proprietary blob (v819 = vulkan.ad8191.so) has neither, so on it
     * {@code vkCreateDevice} fails and the session is sound over a black screen - exactly what a
     * fresh install got, because v819 is first in the picker's list. Prefer a Turnip entry the
     * GPU supports; fall back to the first supported entry at all.
     */
    public static String defaultDrawDriver(Context context) {
        String first = null;
        try {
            for (String id : context.getResources().getStringArray(com.winlator.star.R.array.wrapper_graphics_driver_version_entries)) {
                if (id == null || id.isEmpty() || id.equals("System")) continue;
                if (!com.winlator.star.core.GPUInformation.isDriverSupported(id, context)) continue;
                if (id.toLowerCase().contains("turnip")) return id;
                if (first == null) first = id;
            }
        } catch (Exception e) {
            Log.w(TAG, "could not pick a default draw driver: " + e.getMessage());
        }
        return first;
    }

    public static Container container(Context context, ContainerManager manager) {
        File root = rootDir(context);
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        Container container = new Container(CONTAINER_ID, manager);
        container.setRootDir(root);
        JSONObject data = null;
        File config = container.getConfigFile();
        if (config.isFile()) {
            try {
                data = new JSONObject(FileUtils.readString(config));
            } catch (Exception e) {
                Log.w(TAG, "linux settings unreadable, starting over: " + e.getMessage());
            }
        }
        boolean fresh = data == null;
        if (fresh) data = new JSONObject();
        try {
            // The app's own defaults for every key the container reads, then the runtime's.
            Container.checkObsoleteOrMissingProperties(data);
            if (fresh) {
                data.put("name", NAME);
                // gamescope is a Wayland client, and its output is sized by this: the client's own
                // interface is the most expensive thing a session draws, so 720p to start.
                data.put("screenSize", Container.DEFAULT_SCREEN_SIZE);
                // And a driver that can feed it (see defaultDrawDriver): stored here so the editor
                // shows the real default instead of an empty version the launch quietly replaces.
                String draw = defaultDrawDriver(context);
                if (draw != null) {
                    java.util.HashMap<String, String> gdc = com.winlator.star.contentdialog.GraphicsDriverConfigDialog
                            .parseGraphicsDriverConfig(Container.DEFAULT_GRAPHICSDRIVERCONFIG);
                    gdc.put("version", draw);
                    data.put("graphicsDriverConfig", com.winlator.star.contentdialog.GraphicsDriverConfigDialog.toGraphicsDriverConfig(gdc));
                    Log.i(TAG, "fresh Linux settings start on the " + draw + " driver");
                }
            }
            container.loadData(data);
            // Always the Linux runtime, whatever the stored data says. Both of these live in the
            // container's extras - getDisplayBackend() reads extraData, not a top-level key, and a
            // top-level "displayBackend" written here was silently ignored: the settings then
            // reported X11, which greyed frame generation in the editor (device-seen).
            container.setRuntime(Container.RUNTIME_GAMESCOPE);
            container.putExtra("displayBackend", Container.DISPLAY_BACKEND_WAYLAND);
            container.setName(NAME);
            if (fresh) {
                container.saveData();
                Log.i(TAG, "created the Linux runtime's settings at " + config);
            }
        } catch (Exception e) {
            Log.e(TAG, "could not build the Linux settings container", e);
        }
        return container;
    }
}
