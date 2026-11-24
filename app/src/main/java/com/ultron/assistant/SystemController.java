package com.ultron.assistant;

import android.app.NotificationManager;
import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.util.Log;

/**
 * PHASE 2 - Direct system control via Android APIs (no Accessibility needed for these).
 * Covers volume, brightness, lock, torch, wifi, bluetooth, DND, ringer mode.
 */
public class SystemController {

    private static final String TAG = "Ultron.SystemControl";

    private final Context context;
    private final AudioManager audioManager;
    private final CameraManager cameraManager;
    private String torchCameraId;
    private boolean torchOn = false;

    public SystemController(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (cameraManager != null) {
                for (String id : cameraManager.getCameraIdList()) {
                    Boolean hasFlash = cameraManager.getCameraCharacteristics(id)
                            .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (hasFlash != null && hasFlash) {
                        torchCameraId = id;
                        break;
                    }
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Could not enumerate cameras for torch", e);
        }
    }

    // ---------- Volume ----------
    public void setVolumePercent(int percent) {
        if (audioManager == null) return;
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int target = Math.round(max * (Math.max(0, Math.min(100, percent)) / 100f));
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI);
    }

    public void volumeUp() {
        if (audioManager == null) return;
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
    }

    public void volumeDown() {
        if (audioManager == null) return;
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
    }

    public void setRingerMode(int mode) {
        // AudioManager.RINGER_MODE_NORMAL / SILENT / VIBRATE
        if (audioManager != null) audioManager.setRingerMode(mode);
    }

    // ---------- Brightness ----------
    // Requires Settings.System.canWrite(context) == true (user must grant "Modify system settings")
    public boolean setBrightnessPercent(int percent) {
        if (!Settings.System.canWrite(context)) {
            Log.w(TAG, "WRITE_SETTINGS not granted - can't change brightness");
            return false;
        }
        int value = Math.round(255 * (Math.max(0, Math.min(100, percent)) / 100f));
        Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, value);
        return true;
    }

    // ---------- Lock ----------
    // Requires Device Admin to be enabled (see DeviceAdminReceiver + activate in Settings).
    public boolean lockScreen() {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(context, DeviceAdminReceiver.class);
        if (dpm != null && dpm.isAdminActive(admin)) {
            dpm.lockNow();
            return true;
        }
        Log.w(TAG, "Device admin not active - can't lock screen");
        return false;
    }

    // ---------- Torch ----------
    public boolean setTorch(boolean on) {
        if (cameraManager == null || torchCameraId == null) return false;
        try {
            cameraManager.setTorchMode(torchCameraId, on);
            torchOn = on;
            return true;
        } catch (CameraAccessException e) {
            Log.e(TAG, "Torch toggle failed", e);
            return false;
        }
    }

    public boolean isTorchOn() {
        return torchOn;
    }

    // ---------- WiFi ----------
    @SuppressWarnings("deprecation")
    public boolean setWifiEnabled(boolean enabled) {
        // Note: WifiManager.setWifiEnabled() is restricted/deprecated on API 29+.
        // On modern Android, prefer opening quick settings / Settings panel instead.
        WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) return false;
        try {
            return wifiManager.setWifiEnabled(enabled);
        } catch (SecurityException e) {
            Log.w(TAG, "OS blocked programmatic WiFi toggle (API 29+). Open Settings panel instead.");
            return false;
        }
    }

    // ---------- Bluetooth ----------
    public boolean setBluetoothEnabled(boolean enabled) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return false;
        try {
            return enabled ? adapter.enable() : adapter.disable();
        } catch (SecurityException e) {
            Log.w(TAG, "Bluetooth permission missing", e);
            return false;
        }
    }

    // ---------- DND ----------
    // Requires "Notification policy access" granted in Settings.
    public boolean setDnd(boolean enabled) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;
        if (!nm.isNotificationPolicyAccessGranted()) {
            Log.w(TAG, "Notification policy access not granted");
            return false;
        }
        nm.setInterruptionFilter(enabled
                ? NotificationManager.INTERRUPTION_FILTER_PRIORITY
                : NotificationManager.INTERRUPTION_FILTER_ALL);
        return true;
    }

    // ---------- Info ----------
    public int getBatteryPercent() {
        android.os.BatteryManager bm = (android.os.BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (bm == null) return -1;
        return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }
}
