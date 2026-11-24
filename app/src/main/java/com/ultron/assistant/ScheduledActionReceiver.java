package com.ultron.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * PHASE 6 - Fires when a Scheduler alarm goes off. Hands the stored command text back
 * to UltronService via ACTION_EXECUTE_TEXT, which runs it through the normal
 * parse -> execute -> speak pipeline without needing the wake word again.
 */
public class ScheduledActionReceiver extends BroadcastReceiver {

    private static final String TAG = "Ultron.ScheduledAction";

    public static final String EXTRA_COMMAND_TEXT = "com.ultron.assistant.extra.COMMAND_TEXT";
    public static final String EXTRA_REQUEST_CODE = "com.ultron.assistant.extra.REQUEST_CODE";

    @Override
    public void onReceive(Context context, Intent intent) {
        String commandText = intent.getStringExtra(EXTRA_COMMAND_TEXT);
        if (commandText == null || commandText.trim().isEmpty()) {
            Log.w(TAG, "Scheduled alarm fired with no command text, ignoring");
            return;
        }
        Log.d(TAG, "Scheduled action firing: " + commandText);

        Intent serviceIntent = new Intent(context, UltronService.class);
        serviceIntent.setAction(UltronService.ACTION_EXECUTE_TEXT);
        serviceIntent.putExtra(EXTRA_COMMAND_TEXT, commandText);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}
