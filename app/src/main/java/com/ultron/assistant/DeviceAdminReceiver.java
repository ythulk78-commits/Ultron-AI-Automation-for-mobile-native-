package com.ultron.assistant;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * PHASE 2 - Required so SystemController.lockScreen() can call DevicePolicyManager.lockNow().
 * User must manually activate this under Settings > Security > Device Admin apps > Ultron.
 */
public class DeviceAdminReceiver extends android.app.admin.DeviceAdminReceiver {

    @Override
    public void onEnabled(Context context, Intent intent) {
        Toast.makeText(context, "Ultron device admin enabled", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Toast.makeText(context, "Ultron device admin disabled - lock command won't work", Toast.LENGTH_SHORT).show();
    }
}
