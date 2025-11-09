package com.android.safesphere;

import android.app.Application;
import android.util.Log;
import org.opencv.android.OpenCVLoader;

public class SafeSphereApp extends Application {

    private static final String TAG = "SafeSphereApp";
    public static final int VIDEO_SAMPLING_INTERVAL_MS = 1000;

    @Override
    public void onCreate() {
        super.onCreate();

        if (OpenCVLoader.initDebug()) {
            Log.i(TAG, "OpenCV library loaded successfully.");
        } else {
            Log.e(TAG, "OpenCV library not found!");
        }
    }
}