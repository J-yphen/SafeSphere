package com.android.safesphere.ml;

import android.graphics.Bitmap;
import android.util.Log;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import java.util.ArrayList;
import java.util.List;
import org.opencv.core.Scalar;

public class LightingAnalyzer {
    private static final String TAG = "LightingAnalyzer";

    public float analyzeLighting(Bitmap bitmap) {
        // Create an OpenCV Mat object to hold the image data
        Mat frame = new Mat();
        Utils.bitmapToMat(bitmap, frame);

        // Convert the image from BGR to HSV (Hue, Saturation, Value) color space.
        // The 'V' channel directly represents the brightness of each pixel.
        Mat hsvFrame = new Mat();
        Imgproc.cvtColor(frame, hsvFrame, Imgproc.COLOR_BGR2HSV);

        // Split the HSV image into its three separate channels (H, S, and V).
        List<Mat> hsvPlanes = new ArrayList<>();
        Core.split(hsvFrame, hsvPlanes);

        // Get the 'V' channel, which is the third channel (index 2).
        Mat valueChannel = hsvPlanes.get(2);

        // Calculate the mean (average) brightness of all pixels in the 'V' channel.
        // The result is a Scalar, but since it's a single-channel image, we only need the first value.
        Scalar meanBrightnessScalar = Core.mean(valueChannel);
        double meanBrightness = meanBrightnessScalar.val[0];

        // The meanBrightness is a value from 0 (black) to 255 (white).
        // We scale this to a 0-100 score.
        float lightingScore = (float) (meanBrightness / 255.0) * 100.0f;


        frame.release();
        hsvFrame.release();
        for (Mat mat : hsvPlanes) {
            mat.release();
        }

        // The final risk is the inverse of the lighting score.
        // Lower brightness (low score) means higher risk.
        Log.d(TAG, String.format("VERIFY - Anomaly Ratio: %.2f%%", 100.0f - lightingScore));

        return 100.0f - lightingScore;
    }
}