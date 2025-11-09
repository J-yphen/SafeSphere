package com.android.safesphere.ml;

import android.graphics.Bitmap;
import android.util.Log;
import org.opencv.android.Utils;
import org.opencv.core.*;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.Video;
import java.util.ArrayList;
import java.util.List;

public class MotionAnomalyDetector {
    private static final String TAG = "MotionAnomalyDetector";
    private Mat prevGrayFrame;
    private Mat opticalFlow;
    CLAHE clahe;

    public float detectAnomalies(Bitmap currentFrameBitmap) {
        Mat currentFrame = new Mat();
        Utils.bitmapToMat(currentFrameBitmap, currentFrame);
        Mat grayFrame = new Mat();
        Imgproc.cvtColor(currentFrame, grayFrame, Imgproc.COLOR_BGR2GRAY);

        if (clahe == null) {
            clahe = Imgproc.createCLAHE(2.0, new Size(8, 8));
        }
        clahe.apply(grayFrame, grayFrame);

        if (prevGrayFrame == null) {
            prevGrayFrame = grayFrame.clone();
            currentFrame.release();
            return 0.0f;
        }

        if (opticalFlow == null) {
            opticalFlow = new Mat();
        }

        Video.calcOpticalFlowFarneback(prevGrayFrame, grayFrame, opticalFlow, 0.5, 3, 15, 3, 5, 1.2, 0);

        Mat magnitude = new Mat();
        List<Mat> flowPlanes = new ArrayList<>();
        Core.split(opticalFlow, flowPlanes);
        Core.magnitude(flowPlanes.get(0), flowPlanes.get(1), magnitude);

        MatOfDouble meanMat = new MatOfDouble();
        MatOfDouble stdDevMat = new MatOfDouble();
        Core.meanStdDev(magnitude, meanMat, stdDevMat);
        double mean = meanMat.get(0, 0)[0];
        double stdDev = stdDevMat.get(0, 0)[0];

        double threshold = mean + (2.0 * stdDev);
        Mat anomalyMask = new Mat();
        Core.compare(magnitude, new Scalar(threshold), anomalyMask, Core.CMP_GT);

        int nonZeroPixels = Core.countNonZero(anomalyMask);
        if (nonZeroPixels == 0) {
            // No anomalies detected, release memory and return 0
            releaseMats(currentFrame, grayFrame, magnitude, anomalyMask, meanMat, stdDevMat, flowPlanes);
            grayFrame.copyTo(prevGrayFrame);
            return 0.0f;
        }

        // 1. Calculate Anomaly Area
        float anomalyAreaScore = ((float) nonZeroPixels / (float) anomalyMask.total()) * 100.0f; // Score from 0-100

        // 2. Calculate Anomaly Intensity
        Scalar meanIntensityScalar = Core.mean(magnitude, anomalyMask);
        double meanIntensityOfAnomalies = meanIntensityScalar.val[0];

        // Create an "intensity multiplier". Motion faster than 7 pixels/frame starts amplifying the score.
        float intensityMultiplier = (float) Math.max(1.0, meanIntensityOfAnomalies / 7.0);

        // 3. Amplify the area score by the intensity.
        float finalMotionScore = anomalyAreaScore * intensityMultiplier;

        Log.d(TAG, String.format("VERIFY - Anomaly Ratio: %.2f%%", finalMotionScore));

        releaseMats();

        return Math.min(100.0f, finalMotionScore); // Clamp score to a max of 100
    }

    private void releaseMats(Mat... mats) {
        for (Mat mat : mats) {
            if (mat != null) mat.release();
        }
    }

    private void releaseMats(Mat mat, Mat mat2, Mat mat3, Mat mat4, Mat mat5, Mat mat6, List<Mat> matList) {
        mat.release();
        mat2.release();
        mat3.release();
        mat4.release();
        mat5.release();
        mat6.release();
        for (Mat m : matList) {
            m.release();
        }
    }

    public void release() {
        if (prevGrayFrame != null) {
            prevGrayFrame.release();
            prevGrayFrame = null;
        }
        if (opticalFlow != null) {
            opticalFlow.release();
            opticalFlow = null;
        }
    }
}