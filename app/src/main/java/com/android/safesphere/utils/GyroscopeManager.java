package com.android.safesphere.utils;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.util.Map;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

public class GyroscopeManager implements SensorEventListener {

    private final SensorManager sensorManager;
    private final Sensor gyroscope;

    // A thread-safe, sorted map to store timestamped gyroscope readings.
    // Key: Timestamp (nanoseconds), Value: float[3] of rotation rates (rad/s)
    private final ConcurrentSkipListMap<Long, float[]> sensorReadings = new ConcurrentSkipListMap<>();

    // We need to remove the initial offset from the gyroscope data
    private boolean isCalibrated = false;
    private static final int CALIBRATION_COUNT = 100;
    private int calibrationCounter = 0;
    private float[] initialOffset = new float[3];

    public GyroscopeManager(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
    }

    public void start() {
        if (gyroscope != null) {
            // Register listener at a high frequency for accuracy
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST);
        }
    }

    public void stop() {
        sensorManager.unregisterListener(this);
        isCalibrated = false;
        calibrationCounter = 0;
        initialOffset = new float[3];
    }

    public void reset() {
        sensorReadings.clear();
        isCalibrated = false;
        calibrationCounter = 0;
        initialOffset = new float[3];
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            // Simple calibration: average the first few readings to find the stationary offset/drift.
            if (!isCalibrated) {
                if(calibrationCounter < CALIBRATION_COUNT) {
                    initialOffset[0] += event.values[0];
                    initialOffset[1] += event.values[1];
                    initialOffset[2] += event.values[2];
                    calibrationCounter++;
                } else {
                    initialOffset[0] /= CALIBRATION_COUNT;
                    initialOffset[1] /= CALIBRATION_COUNT;
                    initialOffset[2] /= CALIBRATION_COUNT;
                    isCalibrated = true;
                }
                return; // Don't record data during calibration
            }

            float[] calibratedValues = new float[3];
            calibratedValues[0] = event.values[0] - initialOffset[0];
            calibratedValues[1] = event.values[1] - initialOffset[1];
            calibratedValues[2] = event.values[2] - initialOffset[2];

            sensorReadings.put(event.timestamp, calibratedValues);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    public float[] getIntegratedRotation(long startTimestamp, long endTimestamp) {
        float[] totalRotation = new float[3];
        if (startTimestamp == 0 || !isCalibrated) {
            return totalRotation; // Return zero rotation if this is the first frame
        }

        // Get all sensor readings that occurred within the frame interval
        ConcurrentNavigableMap<Long, float[]> relevantReadings = sensorReadings.subMap(startTimestamp, true, endTimestamp, true);

        long lastTimestamp = startTimestamp;

        for (Map.Entry<Long, float[]> entry : relevantReadings.entrySet()) {
            long currentTimestamp = entry.getKey();
            float[] rotationRates = entry.getValue();

            // Calculate the time delta in seconds
            float dt = (currentTimestamp - lastTimestamp) / 1_000_000_000.0f; // Convert nanoseconds to seconds

            // Integrate: angle = angular_velocity * time_delta
            totalRotation[0] += rotationRates[0] * dt;
            totalRotation[1] += rotationRates[1] * dt;
            totalRotation[2] += rotationRates[2] * dt;

            lastTimestamp = currentTimestamp;
        }

        // Clean up old sensor readings to prevent memory leaks
        sensorReadings.headMap(endTimestamp).clear();

        return totalRotation;
    }
}