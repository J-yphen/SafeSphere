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
    private CLAHE clahe;

    public MotionAnomalyDetector() {
    }

    public float detectAnomalies(Bitmap currentFrameBitmap, float[] rotationVector) {
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

        Mat stabilizedPrevFrame = correctForCameraShake(prevGrayFrame, rotationVector);

        if (!stabilizedPrevFrame.size().equals(grayFrame.size())) {
            Log.w(TAG, "Frame size mismatch detected! Resizing current frame to match previous frame.");
            Imgproc.resize(grayFrame, grayFrame, stabilizedPrevFrame.size());
        }

        if (opticalFlow == null) {
            opticalFlow = new Mat();
        }

        Video.calcOpticalFlowFarneback(stabilizedPrevFrame, grayFrame, opticalFlow, 0.5, 3, 15, 3, 5, 1.2, 0);

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
            releaseMats(currentFrame, grayFrame, stabilizedPrevFrame, magnitude, anomalyMask, meanMat, stdDevMat, flowPlanes);
            grayFrame.copyTo(prevGrayFrame);
            return 0.0f;
        }

        float anomalyAreaScore = ((float) nonZeroPixels / (float) anomalyMask.total()) * 100.0f;
        Scalar meanIntensityScalar = Core.mean(magnitude, anomalyMask);
        double meanIntensityOfAnomalies = meanIntensityScalar.val[0];
        float intensityMultiplier = (float) Math.max(1.0, meanIntensityOfAnomalies / 7.0);
        float finalMotionScore = anomalyAreaScore * intensityMultiplier;

        grayFrame.copyTo(prevGrayFrame);

        // Release all Mats
        releaseMats(currentFrame, grayFrame, stabilizedPrevFrame, magnitude, anomalyMask, meanMat, stdDevMat, flowPlanes);

        Log.d(TAG, String.format("VERIFY - Anomaly Ratio: %.2f%%", finalMotionScore));
        return Math.min(100.0f, finalMotionScore);
    }

    private Mat correctForCameraShake(Mat frameToWarp, float[] rotationVector) {
        double angleZ = Math.toDegrees(rotationVector[2]);
        if (Math.abs(angleZ) < 0.1) {
            return frameToWarp.clone();
        }
        Size size = frameToWarp.size();
        Point center = new Point(size.width / 2, size.height / 2);
        Mat rotationMatrix = Imgproc.getRotationMatrix2D(center, angleZ, 1.0);
        Mat warpedFrame = new Mat();
        Imgproc.warpAffine(frameToWarp, warpedFrame, rotationMatrix, size);
        rotationMatrix.release();
        return warpedFrame;
    }

    private void releaseMats(Mat... mats) {
        for (Mat mat : mats) {
            if (mat != null) mat.release();
        }
    }

    private void releaseMats(Mat mat, Mat mat2, Mat mat3, Mat mat4, Mat mat5, Mat mat6, Mat mat7, List<Mat> matList) {
        releaseMats(mat, mat2, mat3, mat4, mat5, mat6, mat7);
        for (Mat m : matList) {
            if (m != null) m.release();
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