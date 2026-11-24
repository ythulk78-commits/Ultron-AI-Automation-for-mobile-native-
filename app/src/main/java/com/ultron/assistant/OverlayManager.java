package com.ultron.assistant;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

/**
 * PHASE 2 - JARVIS-style listening overlay. Requires SYSTEM_ALERT_WINDOW
 * ("Display over other apps") permission granted by the user first.
 */
public class OverlayManager {

    private final Context context;
    private final WindowManager windowManager;
    private View overlayView;
    private ObjectAnimator pulseAnimator;

    public OverlayManager(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean canDrawOverlay() {
        return android.provider.Settings.canDrawOverlays(context);
    }

    public void show() {
        if (!canDrawOverlay() || overlayView != null) return;

        overlayView = LayoutInflater.from(context).inflate(R.layout.overlay_listening, null);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = 150;

        windowManager.addView(overlayView, params);
        startPulseAnimation();
        setStatus("Listening...");
    }

    private void startPulseAnimation() {
        if (overlayView == null) return;
        View ring = overlayView.findViewById(R.id.pulse_ring);
        if (ring == null) return;
        pulseAnimator = ObjectAnimator.ofFloat(ring, "alpha", 1f, 0.2f, 1f);
        pulseAnimator.setDuration(1200);
        pulseAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        pulseAnimator.setInterpolator(new LinearInterpolator());
        pulseAnimator.start();
    }

    public void setStatus(String status) {
        if (overlayView == null) return;
        TextView statusText = overlayView.findViewById(R.id.status_text);
        if (statusText != null) statusText.setText(status);
    }

    public void hide() {
        if (overlayView != null && windowManager != null) {
            if (pulseAnimator != null) pulseAnimator.cancel();
            try {
                windowManager.removeView(overlayView);
            } catch (IllegalArgumentException ignored) {}
            overlayView = null;
        }
    }
}
