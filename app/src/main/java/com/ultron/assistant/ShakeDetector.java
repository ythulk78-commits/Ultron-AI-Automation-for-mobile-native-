package com.ultron.assistant;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * PHASE 1 - Secondary wake trigger: shake the phone to activate Ultron.
 * Uses the accelerometer and a simple threshold + debounce so normal walking/pocket
 * movement doesn't false-trigger it.
 */
public class ShakeDetector implements SensorEventListener {

    public interface TriggerListener {
        void onShakeDetected();
    }

    private static final float SHAKE_THRESHOLD_G = 2.7f; // in G's, tune if too sensitive
    private static final long DEBOUNCE_MS = 1000;

    private final SensorManager sensorManager;
    private final Sensor accelerometer;
    private final TriggerListener listener;
    private long lastShakeTime = 0;

    public ShakeDetector(Context context, TriggerListener listener) {
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.accelerometer = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
        this.listener = listener;
    }

    public void start() {
        if (sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }
    }

    public void stop() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float gX = event.values[0] / SensorManager.GRAVITY_EARTH;
        float gY = event.values[1] / SensorManager.GRAVITY_EARTH;
        float gZ = event.values[2] / SensorManager.GRAVITY_EARTH;

        float gForce = (float) Math.sqrt(gX * gX + gY * gY + gZ * gZ);

        if (gForce > SHAKE_THRESHOLD_G) {
            long now = System.currentTimeMillis();
            if (now - lastShakeTime > DEBOUNCE_MS) {
                lastShakeTime = now;
                if (listener != null) listener.onShakeDetected();
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
