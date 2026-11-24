package com.ultron.assistant;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

/**
 * PHASE 6 - Battery polish. Android (and OEM skins like ColorOS/MIUI even more aggressively)
 * kill background foreground services to save battery, which is fatal for an always-listening
 * assistant. This asks the user to whitelist Ultron from standard Android battery optimization.
 * ColorOS-specific "Autostart" is a separate OEM setting with no public API - MainActivity
 * should just deep-link to app info settings and tell the user to enable it manually (see README).
 */
public class BatteryOptimizationHelper {

    private static final String TAG = "Ultron.Battery";

    public static boolean isIgnoringBatteryOptimizations(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /** Launches the system dialog that lets the user whitelist Ultron directly (best UX, needs
     *  REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission which is declared in the manifest). */
    public static void requestIgnoreBatteryOptimizations(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Direct request failed, falling back to battery settings screen", e);
            openBatteryOptimizationSettings(context);
        }
    }

    /** Fallback: the general battery-optimization list screen, in case the direct dialog is blocked by the OEM. */
    public static void openBatteryOptimizationSettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Couldn't open battery optimization settings", e);
        }
    }

    /** Deep-links to the app's own info page, where the user can find OEM-specific
     *  "Autostart"/"Startup manager" toggles that have no public Android API (ColorOS, MIUI, etc.). */
    public static void openAppInfoSettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Couldn't open app info settings", e);
        }
    }
}
