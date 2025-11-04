package com.android.safesphere.utils;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * A utility class to manage runtime permissions for the application.
 * This class handles checking and requesting necessary permissions like
 * CAMERA and ACCESS_FINE_LOCATION, which are critical for the app's functionality.
 */
public final class PermissionManager {

    /**
     * A constant integer code used to identify the permission request.
     * This code is returned in the onRequestPermissionsResult callback.
     */
    public static final int REQUEST_CODE = 101;

    // An array of all permissions required by the application.
    private static final String[] REQUIRED_PERMISSIONS = new String[]{
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO
    };

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private PermissionManager() {}

    /**
     * Checks if all the required permissions are already granted.
     *
     * @param activity The activity context from which the check is being made.
     * @return true if all required permissions are granted, false otherwise.
     */
    public static boolean checkPermissions(Activity activity) {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
                // If any one of the permissions is not granted, return false.
                return false;
            }
        }
        // If the loop completes, all permissions have been granted.
        return true;
    }

    /**
     * Requests the required permissions from the user.
     * This method will trigger the standard Android permission request dialog.
     * The result of this request is handled in the activity's
     * onRequestPermissionsResult() callback method.
     *
     * @param activity The activity context that is requesting the permissions.
     */
    public static void requestPermissions(Activity activity) {
        ActivityCompat.requestPermissions(
                activity,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE
        );
    }
}