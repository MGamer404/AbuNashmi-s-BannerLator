package com.winlator.star.contents;

import android.content.res.AssetManager;
import android.net.Uri;

import android.content.Context;
import android.util.Log;

import com.winlator.star.SettingsFragment;
import com.winlator.star.container.Container;
import com.winlator.star.container.Shortcut;
import com.winlator.star.container.ContainerManager;
import com.winlator.star.core.DefaultVersion;
import com.winlator.star.core.EnvVars;
import com.winlator.star.core.FileUtils;
import com.winlator.star.core.GPUInformation;
import com.winlator.star.contentdialog.GraphicsDriverConfigDialog;
import com.winlator.star.core.TarCompressorUtils;
import com.winlator.star.xenvironment.ImageFs;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONException;
import org.json.JSONObject;

public class AdrenotoolsManager {
    
    private File adrenotoolsContentDir;
    private Context mContext;
    
    public AdrenotoolsManager(Context context) {
        this.mContext = context;
        this.adrenotoolsContentDir = new File(mContext.getFilesDir(), "contents/adrenotools");
        if (!adrenotoolsContentDir.exists())
            adrenotoolsContentDir.mkdirs();
    }
        
    public String getLibraryName(String adrenoToolsDriverId) {
        String libraryName = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            // Guard: a missing/unknown driver id has no meta.json -> readString would NPE
            // (read() returns null). This runs inside a native JNI callback, so an uncaught
            // throw = SIGABRT. Degrade to "" instead of crashing the whole app.
            if (!metaProfile.exists()) return "";
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            libraryName = jsonObject.getString("libraryName");
        }
        catch (Exception e) {
        }
        return libraryName;
    }

    public String getDriverName(String adrenoToolsDriverId) {
        String driverName = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            if (!metaProfile.exists()) return "";
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            driverName = jsonObject.getString("name");
        }
        catch (Exception e) {
        }
        return driverName;
    }

    public String getDriverVersion(String adrenoToolsDriverId) {
        String driverVersion = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            if (!metaProfile.exists()) return "";
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            driverVersion = jsonObject.getString("driverVersion");
        }
        catch (Exception e) {
        }
        return driverVersion;
    }

    // meta.json "vendor" ("Mesa", "Qualcomm", ...), or "" when the package doesn't declare one
    // (the bundled turnip-sdk36 meta.json carries only libraryName).
    public String getDriverVendor(String adrenoToolsDriverId) {
        String vendor = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            if (!metaProfile.exists()) return "";
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            vendor = jsonObject.optString("vendor", "");
        }
        catch (Exception e) {
        }
        return vendor;
    }

    public String getDriverPath(String adrenotoolsDriverId) {
        return adrenotoolsContentDir.getAbsolutePath() + "/" + adrenotoolsDriverId + "/";
    }

    // Migrate every container/shortcut still pointing at the driver being removed onto the default
    // wrapper. Matching is by the driver's NAME, read from its meta.json.
    //
    // Two guards, because this is the one place that rewrites configuration the user never touched:
    //  • an absent/unreadable meta.json makes getDriverName() return "", and String.contains("") is
    //    true for EVERY container and EVERY shortcut — that reset the whole library's graphics
    //    driver on a single driver delete. Nothing to match against means nothing to migrate.
    //  • a shortcut with no graphicsDriverConfig of its own INHERITS the container's. Rewriting it
    //    pinned a per-game override onto a game that never had one; the container was already
    //    migrated above, so leaving it alone is both correct and lossless.
    private void reloadContainers(String adrenoToolsDriverId) {
        String driverName = getDriverName(adrenoToolsDriverId);
        if (driverName.isEmpty()) {
            Log.w("AdrenotoolsManager", "No driver name for " + adrenoToolsDriverId + " (missing meta.json?) - skipping container migration");
            return;
        }
        String fallback = GPUInformation.isDriverSupported(DefaultVersion.WRAPPER_ADRENO, mContext)
                ? DefaultVersion.WRAPPER_ADRENO : DefaultVersion.WRAPPER;

        ContainerManager containerManager = new ContainerManager(mContext);
        for (Container container : containerManager.getContainers()) {
            HashMap<String, String> config = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(container.getGraphicsDriverConfig());
            String version = config.get("version");
            Log.d("AdrenotoolsManager", "Checking if container driver version " + version + " matches " + driverName);
            if (version == null || !version.contains(driverName)) continue;
            Log.d("AdrenotoolsManager", "Found a match for container " + container.getName());
            config.put("version", fallback);
            container.setGraphicsDriverConfig(GraphicsDriverConfigDialog.toGraphicsDriverConfig(config));
            container.saveData();
        }
        for (Shortcut shortcut : containerManager.loadShortcuts()) {
            String own = shortcut.getExtra("graphicsDriverConfig", "");
            if (own.isEmpty()) continue;   // inherits the container, which was just migrated
            HashMap<String, String> config = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(own);
            String version = config.get("version");
            Log.d("AdrenotoolsManager", "Checking if shortcut driver version " + version + " matches " + driverName);
            if (version == null || !version.contains(driverName)) continue;
            Log.d("AdrenotoolsManager", "Found a match for shortcut " + shortcut.name);
            config.put("version", fallback);
            shortcut.putExtra("graphicsDriverConfig", GraphicsDriverConfigDialog.toGraphicsDriverConfig(config));
            shortcut.saveData();
        }
    }
    
    public void removeDriver(String adrenoToolsDriverId) {
        Log.d("AdrenotoolsManager", "Removing driver " + adrenoToolsDriverId);
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        reloadContainers(adrenoToolsDriverId);
        FileUtils.delete(driverPath);
    }

    public ArrayList<String> enumarateInstalledDrivers() {
        ArrayList<String> driversList = new ArrayList<>();
        
        for (File f : adrenotoolsContentDir.listFiles()) {
            boolean fromResources = isFromResources(f.getName());
            if (!fromResources && new File(f, "meta.json").exists())
                driversList.add(f.getName());
        }
        return driversList;
    }
    
    public boolean isFromResources(String adrenotoolsDriverId) {
        String driver = "graphics_driver/adrenotools-" + adrenotoolsDriverId + ".tzst";
        AssetManager am = mContext.getResources().getAssets();
        InputStream is = null;
        boolean isFromResources = true;
        
        try {
            is = am.open(driver);
            is.close();
        }
        catch (IOException e) {
            isFromResources = false;
        }
        
        return isFromResources;
    }
        
    public boolean extractDriverFromResources(String adrenotoolsDriverId) {
        String src = "graphics_driver/adrenotools-" + adrenotoolsDriverId + ".tzst";
        boolean hasExtracted;

        File dst = new File(adrenotoolsContentDir, adrenotoolsDriverId);
        if (dst.exists())
            return true;

        dst.mkdirs();
        Log.d("AdrenotoolsManager", "Extracting " + src + " to " + dst.getAbsolutePath());
        hasExtracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, mContext, src, dst);

        if (!hasExtracted)
            dst.delete();

        return hasExtracted;
    }
    
    public String installDriver(Uri driverUri) {
        File tmpDir = new File(adrenotoolsContentDir, "tmp");
        if (tmpDir.exists()) tmpDir.delete();
        tmpDir.mkdirs();
        ZipInputStream zis;
        InputStream is;
        String name = "";
        
        try {
            is = mContext.getContentResolver().openInputStream(driverUri);
            zis = new ZipInputStream(is);
            ZipEntry entry = zis.getNextEntry();
            while (entry != null) {
                File dstFile = new File(tmpDir, entry.getName());
                Files.copy(zis, dstFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                entry = zis.getNextEntry();
            }
            zis.close();
            // An AdrenoTools driver is one the Android Vulkan loader can be pointed at: meta.json
            // names the library in "libraryName" and that library is in the zip. Without this check
            // ANY zip carrying a meta.json installed here - including our own "-Linux" (glibc) and
            // "-Wayland" Turnip zips, which name no library on purpose. They then appeared in the
            // Android display-driver list, where setDriverById quietly sets nothing because
            // libraryName is empty, so picking one left the compositor on the system Vulkan: a
            // black screen wearing a driver's name. Refuse them here and say which list they belong in.
            String rejected = rejectionReason(tmpDir);
            if (rejected != null) {
                Log.d("AdrenotoolsManager", "not an AdrenoTools driver: " + rejected);
                FileUtils.delete(tmpDir);
                return "";
            }
            if (new File(tmpDir, "meta.json").exists()) {
                name = getDriverName(tmpDir.getName());
                File dst = new File(adrenotoolsContentDir, name);
                if (!dst.exists() && !name.equals(""))
                    tmpDir.renameTo(dst);
                else {
                    name = "";
                    FileUtils.delete(tmpDir);
                }
            }
            else {
                Log.d("AdrenotoolsManager", "Failed to install driver, a valid driver has not been selected");
                tmpDir.delete();
            }
        }
        catch (IOException e) {
            Log.d("AdrenotoolsManager", "Failed to install driver, a valid driver has not been selected");
            tmpDir.delete();
        }
        
        return name;
    }
    
    /**
     * Why this extracted zip is not an AdrenoTools driver, or null when it is one. Reads the zip's
     * own meta.json rather than the installed-driver accessors, which key off an installed id.
     */
    private static String rejectionReason(File dir) {
        File metaFile = new File(dir, "meta.json");
        if (!metaFile.isFile()) return "no meta.json";
        String libraryName = "", kind = "";
        try {
            JSONObject meta = new JSONObject(FileUtils.readString(metaFile));
            libraryName = meta.optString("libraryName", "");
            kind = meta.optString("kind", "");
        }
        catch (Exception e) {
            return "meta.json is unreadable (" + e.getMessage() + ")";
        }
        if ("linux-vulkan-icd".equals(kind))
            return "this is a Linux runtime driver (import it under \"Linux runtime drivers\")";
        if ("wayland-game-driver".equals(kind))
            return "this is a Wayland game driver (import it under \"Wayland game drivers\")";
        if (libraryName.isEmpty())
            return "meta.json names no libraryName, so nothing could be handed to AdrenoTools";
        if (!new File(dir, libraryName).isFile())
            return "meta.json names " + libraryName + ", which is not in the zip";
        return null;
    }

    public void setDriverById(EnvVars envVars, ImageFs imagefs, String adrenotoolsDriverId) {
        boolean isFromResources = isFromResources(adrenotoolsDriverId);

        if (isFromResources || enumarateInstalledDrivers().contains(adrenotoolsDriverId)) {
            String driverPath = getDriverPath(adrenotoolsDriverId);

            if (!getLibraryName(adrenotoolsDriverId).equals("")) {
                envVars.put("ADRENOTOOLS_DRIVER_PATH", driverPath);
                envVars.put("ADRENOTOOLS_HOOKS_PATH", imagefs.getLibDir());
                envVars.put("ADRENOTOOLS_DRIVER_NAME", getLibraryName(adrenotoolsDriverId));

                File winlatorDir = new File(SettingsFragment.DEFAULT_WINLATOR_PATH);
                File qglConfig = new File(winlatorDir, "qgl_config.txt");
                if (qglConfig.exists())
                    envVars.put("ADRENOTOOLS_REDIRECT_DIR", winlatorDir.getAbsolutePath() + "/");
            }
        }
    }
 }
