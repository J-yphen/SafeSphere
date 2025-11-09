package com.android.safesphere;

import android.app.Application;
import android.util.Log;
import org.opencv.android.OpenCVLoader;

public class SafeSphereApp extends Application {

    private static final String TAG = "SafeSphereApp";

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