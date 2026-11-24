package com.ultron.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * PHASE 1 - Restarts UltronService automatically after a phone reboot,
 * so the user doesn't have to manually reopen the app every time.
 * Requires RECEIVE_BOOT_COMPLETED permission (declared in AndroidManifest).
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Intent serviceIntent = new Intent(context, UltronService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }

            // Telegram bridge: only auto-restart if the user had it on and never explicitly hit
            // "Stop Bridge" (see TelegramBridgeService.ACTION_STOP / SettingsManager flag).
            SettingsManager settingsManager = new SettingsManager(context);
            if (settingsManager.isTelegramBridgeEnabled() && settingsManager.hasTelegramBotToken()) {
                Intent bridgeIntent = new Intent(context, TelegramBridgeService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(bridgeIntent);
                } else {
                    context.startService(bridgeIntent);
                }
            }
        }
    }
}
