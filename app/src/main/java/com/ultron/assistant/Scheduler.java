package com.ultron.assistant;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PHASE 6 - "5 minute baad volume 0 karna" style deferred actions. Static tryParse()
 * is a cheap regex check the HybridCommandRouter runs before spending an LLM call;
 * the instance methods actually arm/cancel the AlarmManager alarm that fires
 * ScheduledActionReceiver, which re-enters the normal command pipeline later.
 */
public class Scheduler {

    private static final String TAG = "Ultron.Scheduler";

    private static final Pattern SCHEDULE_PATTERN = Pattern.compile(
            "(\\d+)\\s*(minute|min|minutes|ghante|ghanta|hour|hours)\\s*(ke\\s*)?(baad|later)\\s+(.+)",
            Pattern.CASE_INSENSITIVE);

    /** Cheap regex pre-check - returns a Type.SCHEDULE command if the text matches, else null. */
    public static UltronCommand tryParse(String text) {
        if (text == null) return null;
        Matcher m = SCHEDULE_PATTERN.matcher(text.trim());
        if (!m.find()) return null;

        int amount = Integer.parseInt(m.group(1));
        String unit = m.group(2).toLowerCase();
        String innerCommandText = m.group(5).trim();
        boolean isHours = unit.startsWith("hour") || unit.startsWith("ghant");

        long delayMs = isHours
                ? amount * 60L * 60L * 1000L
                : amount * 60L * 1000L;

        return new UltronCommand(UltronCommand.Type.SCHEDULE, "schedule")
                .with("delay_ms", String.valueOf(delayMs))
                .with("command_text", innerCommandText)
                .with("display_delay", amount + " " + unit);
    }

    private final Context context;

    public Scheduler(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Arms an alarm that re-runs commandText through the normal pipeline after delayMs. */
    public boolean schedule(String commandText, long delayMs) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return false;

        Intent intent = new Intent(context, ScheduledActionReceiver.class);
        intent.putExtra(ScheduledActionReceiver.EXTRA_COMMAND_TEXT, commandText);
        // Unique request code per scheduled item so multiple scheduled actions don't clobber each other.
        int requestCode = (int) System.currentTimeMillis();
        intent.putExtra(ScheduledActionReceiver.EXTRA_REQUEST_CODE, requestCode);

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, requestCode, intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT);

        long triggerAt = SystemClock.elapsedRealtime() + delayMs;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                // No SCHEDULE_EXACT_ALARM grant (Android 12+) - fall back to an inexact alarm
                // rather than crash; it'll still fire, just not to-the-second.
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
            }
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "No permission for exact alarms, falling back to inexact", e);
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
            return true;
        }
    }
}
