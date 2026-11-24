package com.ultron.assistant;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Original, generic sci-fi "arc reactor" HUD graphic (not a reproduction of any specific
 * copyrighted artwork) - concentric red/cyan rings that rotate continuously and pulse a little
 * faster while Ultron is actively listening/thinking. Drawn with Canvas instead of loaded
 * assets so it's instant to show (no bitmap decode) and cheap to animate every frame.
 */
public class HudRingsView extends View {

    private static final int CYAN = Color.parseColor("#00E5FF");
    private static final int RED = Color.parseColor("#FF1744");

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();

    private float rotationOuter = 0f;
    private float rotationInner = 0f;
    private ValueAnimator animator;
    private boolean active = false; // true while listening/thinking - spins faster + brighter core

    public HudRingsView(Context context) {
        super(context);
        init();
    }

    public HudRingsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        ringPaint.setStyle(Paint.Style.STROKE);
        corePaint.setStyle(Paint.Style.FILL);
    }

    /** Toggles the faster/brighter "listening" state - call true on wake, false once done. */
    public void setActive(boolean active) {
        this.active = active;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float size = Math.min(w, h) * 0.9f;
        float left = (w - size) / 2f;
        float top = (h - size) / 2f;
        bounds.set(left, top, left + size, top + size);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animator = ValueAnimator.ofFloat(0f, 360f);
        animator.setDuration(6000);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            float speedMultiplier = active ? 1.8f : 1f;
            rotationOuter = t * speedMultiplier;
            rotationInner = -t * 1.5f * speedMultiplier;
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) animator.cancel();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bounds.isEmpty()) return;

        float cx = bounds.centerX();
        float cy = bounds.centerY();
        float r = bounds.width() / 2f;

        // Outer cyan ring - dashed arcs
        ringPaint.setColor(CYAN);
        ringPaint.setStrokeWidth(r * 0.03f);
        ringPaint.setAlpha(active ? 255 : 160);
        canvas.save();
        canvas.rotate(rotationOuter, cx, cy);
        drawDashedRing(canvas, cx, cy, r * 0.95f, 10, 18f);
        canvas.restore();

        // Middle red segmented ring
        ringPaint.setColor(RED);
        ringPaint.setStrokeWidth(r * 0.05f);
        ringPaint.setAlpha(active ? 255 : 140);
        canvas.save();
        canvas.rotate(rotationInner, cx, cy);
        drawDashedRing(canvas, cx, cy, r * 0.75f, 14, 12f);
        canvas.restore();

        // Inner solid cyan ring
        ringPaint.setColor(CYAN);
        ringPaint.setStrokeWidth(r * 0.04f);
        ringPaint.setAlpha(255);
        canvas.drawCircle(cx, cy, r * 0.55f, ringPaint);

        // Glowing core - brighter/pulsing while active
        corePaint.setColor(RED);
        corePaint.setAlpha(active ? 230 : 170);
        canvas.drawCircle(cx, cy, r * (active ? 0.22f : 0.16f), corePaint);
        corePaint.setColor(Color.parseColor("#FFCDD2"));
        canvas.drawCircle(cx, cy, r * (active ? 0.10f : 0.07f), corePaint);
    }

    private void drawDashedRing(Canvas canvas, float cx, float cy, float radius, int segments, float gapDegrees) {
        RectF arcBounds = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        float sweep = (360f / segments) - gapDegrees;
        for (int i = 0; i < segments; i++) {
            float start = i * (360f / segments);
            canvas.drawArc(arcBounds, start, sweep, false, ringPaint);
        }
    }
}
