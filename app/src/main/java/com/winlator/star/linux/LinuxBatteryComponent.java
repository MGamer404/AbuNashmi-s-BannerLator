package com.winlator.star.linux;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.util.Log;

import com.winlator.star.xenvironment.EnvironmentComponent;

import java.io.File;
import java.io.FileWriter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The battery the Steam client can see in a Linux session.
 *
 * <p>The client reads {@code /sys/class/power_supply/BAT<n>/...}, a laptop's or a Deck's naming,
 * files and {@code POWER_SUPPLY_*} uevent. Android names its supply {@code battery} and lays it out
 * differently, and on many phones an app may not read it at all, so the client showed no battery in
 * its top bar or its Quick Access Menu. This writes a {@code BAT0} and a {@code BAT1} (the Deck's
 * name) from Android's own battery API, which is the same on every device, into a directory the
 * session binds over {@code /sys/class/power_supply}, refreshed every few seconds while it runs.
 *
 * <p>Charge comes from the charge counter, energy from charge times voltage, power from the current
 * times voltage, and time to empty from charge over the average current while discharging. Where a
 * device reports no current the client still gets the status and the percentage.
 *
 * <p>Ported from The412Banner/SteamDeck (BatteryComponent), where it was proven on device.
 */
public class LinuxBatteryComponent extends EnvironmentComponent {
    private static final String TAG = "LinuxBattery";
    private static final long PERIOD_MS = 5_000L;

    private final File dir;
    private volatile boolean running;
    private Thread thread;

    public LinuxBatteryComponent(File dir) {
        this.dir = dir;
    }

    /** The directory the session binds over /sys/class/power_supply. */
    public File getDir() {
        return dir;
    }

    @Override
    public void start() {
        running = true;
        write();
        thread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(PERIOD_MS);
                } catch (InterruptedException e) {
                    break;
                }
                if (running) write();
            }
        }, "linux-battery");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "BAT0/BAT1 written to " + dir + " every " + (PERIOD_MS / 1000) + " s");
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private void write() {
        Context ctx = environment != null ? environment.getContext() : null;
        if (ctx == null) return;
        try {
            BatteryManager bm = ctx.getSystemService(BatteryManager.class);
            Intent sticky = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int status = sticky != null ? sticky.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN) : BatteryManager.BATTERY_STATUS_UNKNOWN;
            int level = sticky != null ? sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
            int scale = sticky != null ? sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100) : 100;
            if (scale <= 0) scale = 100;
            int pct = level >= 0 ? level * 100 / scale : (bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : 50);
            long voltageUv = (sticky != null ? sticky.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) : 0) * 1000L;
            if (voltageUv <= 0) voltageUv = 3_800_000L;
            int tempDeci = sticky != null ? sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) : 0;
            String tech = sticky != null ? sticky.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) : null;
            if (tech == null || tech.trim().isEmpty()) tech = "Li-ion";
            long chargeNowUah = positive(bm, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            long currentNowUa = property(bm, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            long currentAvgUa = property(bm, BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);
            if (currentAvgUa == 0) currentAvgUa = currentNowUa;
            // Android's sign convention for the current varies by vendor; the status says which way it flows.
            boolean discharging = status == BatteryManager.BATTERY_STATUS_DISCHARGING || status == BatteryManager.BATTERY_STATUS_NOT_CHARGING;
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING;
            long chargeFullUah = chargeNowUah > 0 && pct > 0 ? chargeNowUah * 100 / pct : 0L;
            long energyNowUwh = chargeNowUah * voltageUv / 1_000_000L;
            long energyFullUwh = chargeFullUah * voltageUv / 1_000_000L;
            long powerNowUw = Math.abs(currentNowUa) * voltageUv / 1_000_000L;
            long absAvg = Math.abs(currentAvgUa);
            long timeToEmptyS = discharging && chargeNowUah > 0 && absAvg > 0 ? chargeNowUah * 3600L / absAvg : 0L;
            long timeToFullS = charging && chargeFullUah > chargeNowUah && absAvg > 0 ? (chargeFullUah - chargeNowUah) * 3600L / absAvg : 0L;
            String statusText;
            switch (status) {
                case BatteryManager.BATTERY_STATUS_CHARGING: statusText = "Charging"; break;
                case BatteryManager.BATTERY_STATUS_DISCHARGING: statusText = "Discharging"; break;
                case BatteryManager.BATTERY_STATUS_FULL: statusText = "Full"; break;
                case BatteryManager.BATTERY_STATUS_NOT_CHARGING: statusText = "Not charging"; break;
                default: statusText = "Unknown";
            }
            String capacityLevel = status == BatteryManager.BATTERY_STATUS_FULL || pct >= 100 ? "Full"
                    : pct <= 5 ? "Critical" : pct <= 15 ? "Low" : "Normal";

            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("type", "Battery");
            attrs.put("present", "1");
            attrs.put("status", statusText);
            attrs.put("capacity", Integer.toString(pct));
            attrs.put("capacity_level", capacityLevel);
            attrs.put("technology", tech);
            attrs.put("voltage_now", Long.toString(voltageUv));
            attrs.put("voltage_min_design", Long.toString(voltageUv));
            attrs.put("current_now", Long.toString(currentNowUa));
            attrs.put("current_avg", Long.toString(currentAvgUa));
            attrs.put("charge_now", Long.toString(chargeNowUah));
            attrs.put("charge_full", Long.toString(chargeFullUah));
            attrs.put("charge_full_design", Long.toString(chargeFullUah));
            attrs.put("energy_now", Long.toString(energyNowUwh));
            attrs.put("energy_full", Long.toString(energyFullUwh));
            attrs.put("energy_full_design", Long.toString(energyFullUwh));
            attrs.put("power_now", Long.toString(powerNowUw));
            attrs.put("time_to_empty_now", Long.toString(timeToEmptyS));
            attrs.put("time_to_empty_avg", Long.toString(timeToEmptyS));
            attrs.put("time_to_full_now", Long.toString(timeToFullS));
            attrs.put("time_to_full_avg", Long.toString(timeToFullS));
            attrs.put("temp", Integer.toString(tempDeci));
            attrs.put("model_name", android.os.Build.MODEL.replace(' ', '_'));
            attrs.put("manufacturer", android.os.Build.MANUFACTURER.replace(' ', '_'));
            attrs.put("scope", "System");

            for (String name : new String[]{"BAT0", "BAT1"}) {
                File bat = new File(dir, name);
                //noinspection ResultOfMethodCallIgnored
                bat.mkdirs();
                StringBuilder uevent = new StringBuilder("POWER_SUPPLY_NAME=").append(name).append('\n');
                for (Map.Entry<String, String> e : attrs.entrySet()) {
                    put(new File(bat, e.getKey()), e.getValue() + "\n");
                    uevent.append("POWER_SUPPLY_").append(e.getKey().toUpperCase(Locale.ROOT)).append('=').append(e.getValue()).append('\n');
                }
                put(new File(bat, "uevent"), uevent.toString());
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not write the battery", t);
        }
    }

    private static long property(BatteryManager bm, int id) {
        if (bm == null) return 0L;
        long v = bm.getLongProperty(id);
        return v == Long.MIN_VALUE ? 0L : v;
    }

    private static long positive(BatteryManager bm, int id) {
        long v = property(bm, id);
        return v > 0 ? v : 0L;
    }

    /** Atomic per file, so a reader in the guest never sees a half-written value. */
    private static void put(File target, String text) throws java.io.IOException {
        File tmp = new File(target.getParentFile(), "." + target.getName() + ".tmp");
        try (FileWriter w = new FileWriter(tmp)) {
            w.write(text);
        }
        if (!tmp.renameTo(target)) {
            try (FileWriter w = new FileWriter(target)) {
                w.write(text);
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
}
