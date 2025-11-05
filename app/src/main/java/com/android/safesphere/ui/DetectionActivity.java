package com.android.safesphere.ui;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.*;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.android.safesphere.R;
import com.android.safesphere.ml.ClassificationResult;
import com.android.safesphere.ml.RiskCalculator;
import com.android.safesphere.ml.SceneClassifier;
import com.google.common.util.concurrent.ListenableFuture;

import org.opencv.android.OpenCVLoader;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DetectionActivity extends AppCompatActivity {

    private static final String TAG = "DetectionActivity";

    // UI Components
    private PreviewView cameraPreview;
    private ImageButton captureButton;
    private Button analyzeButton;
    private ImageView capturedImagePreview;
    private VideoView capturedVideoPreview;
    private TextView captureInstructions;
    private ProgressBar recordingProgress;


    // ML Models and Managers
    private SceneClassifier sceneClassifier;
    private RiskCalculator riskCalculator;
    private AlertManager alertManager;

    // CameraX and Threading
    private ExecutorService cameraExecutor;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording currentRecording;

    // State
    private Bitmap capturedBitmap = null;
    private Uri capturedVideoUri = null;
    private File capturedPhotoFile = null;
    private boolean isRecording = false;

    // Animation Handler
    private Handler animationHandler = new Handler(Looper.getMainLooper());
    private Runnable animationRunnable;

    private static final int VIDEO_SAMPLING_INTERVAL_MS = 2000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detection);

        // Find views
        cameraPreview = findViewById(R.id.camera_preview);
        captureButton = findViewById(R.id.capture_button);
        analyzeButton = findViewById(R.id.analyze_button);
        capturedImagePreview = findViewById(R.id.captured_image_preview);
        capturedVideoPreview = findViewById(R.id.captured_video_preview);
        captureInstructions = findViewById(R.id.capture_instructions);
        recordingProgress = findViewById(R.id.recording_progress);

        initDependencies();
        startCamera();
        setupCaptureButtonListeners();

        analyzeButton.setOnClickListener(v -> analyzeCapturedMedia());
    }

    private void initDependencies() {
        if (!OpenCVLoader.initDebug()) {
            Toast.makeText(this, "OpenCV initialization failed!", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        cameraExecutor = Executors.newSingleThreadExecutor();
        sceneClassifier = new SceneClassifier(this);
        riskCalculator = new RiskCalculator();
        alertManager = new AlertManager(this);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder().build();

                // Setup VideoCapture use case
                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HD))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                // Bind all three use cases
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, videoCapture);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start camera.", e);
                Toast.makeText(this, "Failed to start camera.", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupCaptureButtonListeners() {
        Handler longPressHandler = new Handler(Looper.getMainLooper());
        Runnable longPressRunnable = this::startRecording;

        captureButton.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    // Schedule the long press runnable. If released before it runs, it's a tap.
                    longPressHandler.postDelayed(longPressRunnable, 500);
                    return true;
                case MotionEvent.ACTION_UP:
                    // User released the button.
                    longPressHandler.removeCallbacks(longPressRunnable); // Cancel long press check.
                    if (isRecording) {
                        stopRecording();
                    } else {
                        // If not recording, it was a short tap for a photo.
                        takePhoto();
                    }
                    return true;
            }
            return false;
        });
    }

    private void takePhoto() {
        if (imageCapture == null) return;
        capturedPhotoFile  = new File(getOutputDirectory(), UUID.randomUUID().toString() + ".jpg");
        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(capturedPhotoFile ).build();

        imageCapture.takePicture(outputOptions, cameraExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(ImageCapture.OutputFileResults outputFileResults) {
                capturedBitmap = BitmapFactory.decodeFile(capturedPhotoFile .getAbsolutePath());
                new Handler(Looper.getMainLooper()).post(() -> {
                    // Show ImageView for the photo
                    capturedVideoUri = null;
                    capturedVideoPreview.setVisibility(View.GONE);
                    capturedImagePreview.setVisibility(View.VISIBLE);
                    capturedImagePreview.setImageBitmap(capturedBitmap);
                    updateUiForAnalysis();
                });
            }

            @Override
            public void onError(ImageCaptureException exception) {
                Log.e(TAG, "Photo capture failed: " + exception.getMessage(), exception);
                resetToPreviewState();
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void startRecording() {
        if (videoCapture == null) return;
        isRecording = true;

        recordingProgress.setAlpha(1.0f); // Ensure it's fully visible initially
        recordingProgress.setVisibility(View.VISIBLE);
        animationRunnable = new Runnable() {
            private boolean isFaded = false;
            @Override
            public void run() {
                // Animate the alpha property to create a pulsing effect
                recordingProgress.animate()
                        .alpha(isFaded ? 1.0f : 0.5f)
                        .setDuration(700);
                isFaded = !isFaded; // Toggle the state
                animationHandler.postDelayed(this, 700); // Repeat the animation
            }
        };
        animationHandler.post(animationRunnable);

        String fileName = "VID_" + UUID.randomUUID().toString() + ".mp4";
        File videoFile = new File(getOutputDirectory(), fileName);

        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");

        MediaStoreOutputOptions outputOptions = new MediaStoreOutputOptions.Builder(
                getContentResolver(),
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(contentValues)
                .build();

        currentRecording = videoCapture.getOutput()
                .prepareRecording(this, outputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this), recordEvent -> {
                    if (recordEvent instanceof VideoRecordEvent.Finalize) {
                        VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) recordEvent;
                        if (!finalizeEvent.hasError()) {
                            capturedVideoUri = finalizeEvent.getOutputResults().getOutputUri();
                            capturedPhotoFile = null;
                            capturedBitmap = getVideoFrame(capturedVideoUri); // Get first frame for analysis thumbnail
                            runOnUiThread(() -> {
                                // Show VideoView for the video
                                capturedImagePreview.setVisibility(View.GONE);
                                capturedVideoPreview.setVisibility(View.VISIBLE);

                                // Setup video playback
                                MediaController mediaController = new MediaController(this);
                                capturedVideoPreview.setMediaController(mediaController);
                                capturedVideoPreview.setVideoURI(capturedVideoUri);
                                capturedVideoPreview.requestFocus();
                                capturedVideoPreview.start();

                                updateUiForAnalysis();
                            });
                        } else {
                            Log.e(TAG, "Video capture failed: " + finalizeEvent.getError());
                            resetToPreviewState();
                        }
                        isRecording = false;
                    }
                });

    }

    private void stopRecording() {
        if (currentRecording != null) {
            currentRecording.stop();
            currentRecording = null;
        }
        animationHandler.removeCallbacks(animationRunnable);
        recordingProgress.setVisibility(View.GONE);
    }

    private void analyzeCapturedMedia() {
        if (capturedVideoUri != null) {
            analyzeVideo(capturedVideoUri);
        } else if (capturedPhotoFile != null) {
            analyzeImage(capturedBitmap);
        } else {
            Toast.makeText(this, "No media captured to analyze.", Toast.LENGTH_SHORT).show();
        }
    }

    private void analyzeImage(Bitmap bitmap) {
        cameraExecutor.execute(() -> {
            ClassificationResult sceneResult = (ClassificationResult) sceneClassifier.classifyScene(bitmap);
            int riskScore = riskCalculator.calculateRiskScore(sceneResult.riskScore, 0, 0);

            new Handler(Looper.getMainLooper()).post(() -> showAnalysisResultDialog(riskScore, sceneResult));
        });
    }

    private void analyzeVideo(Uri videoUri) {
        Toast.makeText(this, "Analyzing video... This may take a moment.", Toast.LENGTH_SHORT).show();

        cameraExecutor.execute(() -> {
            // We need to track the highest score and the result object that produced it.
            int highestFinalRisk = -1;
            ClassificationResult highestRiskResult = null;

            MediaMetadataRetriever retriever = new MediaMetadataRetriever();

            try {
                retriever.setDataSource(this, videoUri);
                String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                long durationMs = Long.parseLong(durationStr);

                // Loop through the video, sampling one frame at each interval
                for (long timeMs = 0; timeMs < durationMs; timeMs += 2000) { // Using 2-second interval
                    Bitmap frame = retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);

                    if (frame != null) {
                        // 1. Get the detailed classification result for the current frame
                        ClassificationResult currentFrameResult = (ClassificationResult) sceneClassifier.classifyScene(frame);

                        // 2. Get other risk factors for the frame (e.g., lighting)
//                        float lightingRisk = lightingAnalyzer.analyzeLighting(frame);

                        // 3. Calculate the final, combined risk score for THIS frame
                        // We use 0 for motion, as we are analyzing static frames.
                        int finalFrameRisk = riskCalculator.calculateRiskScore(currentFrameResult.riskScore, 0, 0);

                        // 4. Check if this frame is the most dangerous one we've seen so far
                        if (finalFrameRisk > highestFinalRisk) {
                            highestFinalRisk = finalFrameRisk;
                            highestRiskResult = currentFrameResult; // Save the details of this specific frame
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed during video analysis", e);
            } finally {
                try {
                    retriever.release();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            // After the loop, if we never found a valid frame, create a default result.
            if (highestRiskResult == null) {
                highestFinalRisk = 0;
                highestRiskResult = new ClassificationResult(0.0f, "Analysis Failed", 0.0f);
            }

            // Make the variables effectively final for the lambda expression
            int finalScoreToShow = highestFinalRisk;
            ClassificationResult finalResultToShow = highestRiskResult;

            // Post the final, most critical result back to the UI thread
            new Handler(Looper.getMainLooper()).post(() -> showAnalysisResultDialog(finalScoreToShow, finalResultToShow));
        });
    }


    private void showAnalysisResultDialog(int riskScore, ClassificationResult result) {
        AlertManager.AlertInfo info = alertManager.getAlertInfo(riskScore);

        String message = String.format(
                "Calculated Risk: %d%%\nLevel: %s\n\nDetected Scene: '%s'\nConfidence: %.2f%%",
                riskScore,
                info.levelText,
                result.bestMatchLabel,
                result.confidence * 100 // Convert similarity to percentage
        );

        new AlertDialog.Builder(this)
                .setTitle("Analysis Complete")
                .setMessage(message)
                .setPositiveButton("OK", (dialog, which) -> {
                    // Delete media and reset state when user clicks OK
                    deleteCapturedMedia();
                    resetToPreviewState();
                })
                .setOnCancelListener(dialog -> {
                    // Also delete and reset if the user cancels the dialog
                    deleteCapturedMedia();
                    resetToPreviewState();
                })
                .show();
    }

    private void deleteCapturedMedia() {
        if (capturedPhotoFile != null && capturedPhotoFile.exists()) {
            if (capturedPhotoFile.delete()) {
                Log.d(TAG, "Photo deleted successfully: " + capturedPhotoFile.getAbsolutePath());
            } else {
                Log.e(TAG, "Failed to delete photo: " + capturedPhotoFile.getAbsolutePath());
            }
            capturedPhotoFile = null;
        }

        if (capturedVideoUri != null) {
            try {
                // Use ContentResolver to delete media store entries
                int rowsDeleted = getContentResolver().delete(capturedVideoUri, null, null);
                if (rowsDeleted > 0) {
                    Log.d(TAG, "Video deleted successfully: " + capturedVideoUri);
                } else {
                    Log.e(TAG, "Failed to delete video (no rows affected): " + capturedVideoUri);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error deleting video: " + capturedVideoUri, e);
            }
            capturedVideoUri = null;
        }
    }

    private File getOutputDirectory() {
        File mediaDir = new File(getExternalMediaDirs()[0], getString(R.string.app_name));
        mediaDir.mkdirs();
        return mediaDir;
    }

    private void updateUiForAnalysis() {
        captureButton.setVisibility(View.GONE);
        captureInstructions.setVisibility(View.GONE);
        capturedImagePreview.setVisibility(View.VISIBLE);
        analyzeButton.setVisibility(View.VISIBLE);
    }

    private void resetToPreviewState() {
        capturedBitmap = null;
        capturedVideoUri = null;
        capturedPhotoFile = null;

        // Hide both preview panes
        capturedImagePreview.setVisibility(View.GONE);
        if (capturedVideoPreview.isPlaying()) {
            capturedVideoPreview.stopPlayback();
        }
        capturedVideoPreview.setVisibility(View.GONE);

        // Show capture controls
        analyzeButton.setVisibility(View.GONE);
        captureButton.setVisibility(View.VISIBLE);
        captureInstructions.setVisibility(View.VISIBLE);
    }


    private Bitmap getVideoFrame(Uri videoUri) {
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(this, videoUri);
            return retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (Exception e) {
            Log.e(TAG, "Failed to retrieve frame from video", e);
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (sceneClassifier != null) sceneClassifier.close();
        if (alertManager != null) alertManager.release();
    }
}