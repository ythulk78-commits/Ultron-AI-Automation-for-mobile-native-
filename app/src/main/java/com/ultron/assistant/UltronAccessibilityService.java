package com.ultron.assistant;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * PHASE 3 - The AccessibilityService that powers app automation.
 * Must be enabled manually by the user: Settings > Accessibility > Ultron > ON
 * (see res/xml/ultron_accessibility_service.xml for its declared capabilities).
 */
public class UltronAccessibilityService extends AccessibilityService {

    private static final String TAG = "Ultron.A11yService";

    private static UltronAccessibilityService instance;

    private AccessibilityNavigator navigator;
    private AppAutomationManager automationManager;
    private AgenticExecutor agenticExecutor;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        navigator = new AccessibilityNavigator(this);
        automationManager = new AppAutomationManager(this, navigator);
        // PHASE 9: shares this same navigator so the agent sees exactly what AppAutomationManager
        // would - built lazily off the shared SettingsManager so it always uses whatever
        // OpenRouter model/key the user has set in Ultron Heart, same as LlmIntentParser.
        agenticExecutor = new AgenticExecutor(this, navigator, new SettingsManager(this));
        Log.d(TAG, "Ultron AccessibilityService connected");
    }

    /** Lets other components (e.g. ActionExecutor) reach the running service instance. */
    public static UltronAccessibilityService getInstance() {
        return instance;
    }

    public AccessibilityNavigator getNavigator() {
        return navigator;
    }

    public AppAutomationManager getAutomationManager() {
        return automationManager;
    }

    public AgenticExecutor getAgenticExecutor() {
        return agenticExecutor;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Phase 3 doesn't need to react to every event - AppAutomationManager polls the
        // tree on demand instead. This is where you'd add passive listeners later
        // (e.g. auto-detecting incoming WhatsApp messages for notifications).
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Ultron AccessibilityService interrupted");
    }

    /**
     * Optional: true long-press detection for the volume-key wake trigger, now that we
     * have accessibility key interception available. Requires
     * android:canRequestFilterKeyEvents="true" in the service's XML config and the user
     * toggling "Use service to filter key events" if prompted.
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.isLongPress()) {
            Log.d(TAG, "Long-press volume-up detected via AccessibilityService");
            Intent wakeIntent = new Intent(this, UltronService.class);
            wakeIntent.setAction(UltronService.ACTION_WAKE);
            startService(wakeIntent);
            return true; // consume the event so it doesn't also change system volume
        }
        return super.onKeyEvent(event);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (instance == this) instance = null;
    }
}
