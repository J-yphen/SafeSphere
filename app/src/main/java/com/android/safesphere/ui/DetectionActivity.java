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
import android.view.LayoutInflater;
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
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import com.android.safesphere.R;
import com.android.safesphere.SafeSphereApp;
import com.android.safesphere.ml.*;
import com.android.safesphere.utils.GyroscopeManager;
import com.google.common.util.concurrent.ListenableFuture;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    private ConstraintLayout processingOverlay;


    // ML Models and Managers
    private SceneClassifier sceneClassifier;
    private RiskCalculator riskCalculator;
    private AlertManager alertManager;
    private MotionAnomalyDetector motionAnomalyDetector;
    private LightingAnalyzer lightingAnalyzer;
    private ObjectDetector objectDetector;

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
    private final Handler animationHandler = new Handler(Looper.getMainLooper());
    private Runnable animationRunnable;

    // Add GyroscopeManager and timestamp tracking
    private GyroscopeManager gyroscopeManager;
    private long lastFrameTimestamp = 0;

    private static final int VIDEO_SAMPLING_INTERVAL_MS = SafeSphereApp.VIDEO_SAMPLING_INTERVAL_MS;

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
        processingOverlay = findViewById(R.id.processing_overlay);

        gyroscopeManager = new GyroscopeManager(this);

        initDependencies();
        startCamera();
        setupCaptureButtonListeners();

        analyzeButton.setOnClickListener(v -> analyzeCapturedMedia());
    }

    private void initDependencies() {
        cameraExecutor = Executors.newSingleThreadExecutor();
        sceneClassifier = new SceneClassifier(this);
        riskCalculator = new RiskCalculator();
        alertManager = new AlertManager(this);
        lightingAnalyzer = new LightingAnalyzer();
        objectDetector = new ObjectDetector(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        gyroscopeManager.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        gyroscopeManager.stop();
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
            public void onImageSaved(ImageCapture.@NotNull OutputFileResults outputFileResults) {
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
            public void onError(@NotNull ImageCaptureException exception) {
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
        // --- Show the processing overlay before starting analysis ---
        runOnUiThread(() -> processingOverlay.setVisibility(View.VISIBLE));

        // --- Reset gyroscope state before each analysis ---
        gyroscopeManager.reset();
        lastFrameTimestamp = 0;

        // Reset the motion detector's state before starting a new analysis
        motionAnomalyDetector = new MotionAnomalyDetector();

        if (capturedVideoUri != null) {
            analyzeVideo(capturedVideoUri);
        } else if (capturedPhotoFile != null) {
            analyzeImage(capturedBitmap);
        } else {
            Toast.makeText(this, "No media captured to analyze.", Toast.LENGTH_SHORT).show();
            runOnUiThread(() -> processingOverlay.setVisibility(View.GONE));
        }
    }

    private void analyzeImage(Bitmap bitmap) {
        cameraExecutor.execute(() -> {
            boolean objectFound = objectDetector.containsDangerousObject(bitmap);

            ClassificationResult sceneResult = (ClassificationResult) sceneClassifier.classifyScene(bitmap);
            float motionScore = 0.0f;
            float lightingRisk = lightingAnalyzer.analyzeLighting(bitmap);
            int riskScore = riskCalculator.calculateRiskScore(sceneResult.riskScore, motionScore, lightingRisk, objectFound);

            new Handler(Looper.getMainLooper()).post(() -> showAnalysisResultDialog(riskScore, sceneResult));
        });
    }

    private void analyzeVideo(Uri videoUri) {
        Toast.makeText(this, "Analyzing video... This may take a moment.", Toast.LENGTH_SHORT).show();

        cameraExecutor.execute(() -> {
            float cumulativeRisk = 0.0f;
            float maxCumulativeRisk = -1.0f; // Track the peak risk during the video
            ClassificationResult resultAtMaxRisk = null; // Store the details of the peak risk moment

            // Alpha determines how quickly the score adapts. 0.4 is a good starting point.
            final float alpha = 0.4f;

            MediaMetadataRetriever retriever = new MediaMetadataRetriever();

            try {
                retriever.setDataSource(this, videoUri);
                List<Long> frameTimestampsUs = new ArrayList<>(); // Timestamps in Microseconds
                String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                long durationMs = Long.parseLong(durationStr);

                // We still define an interval to avoid analyzing too many frames.
                long samplingIntervalUs = VIDEO_SAMPLING_INTERVAL_MS * 1000L;
                long lastAddedTimestampUs = -samplingIntervalUs; // Initialize to allow the first frame at t=0

                for (long timeUs = 0; timeUs < durationMs * 1000; timeUs += VIDEO_SAMPLING_INTERVAL_MS * 1000) {
                    Bitmap frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (frame != null) {
                        long actualTimestampUs = timeUs; // In a real scenario, API 30's getFramesAtTime would be better. This is a good approximation.
                        if (actualTimestampUs >= lastAddedTimestampUs + samplingIntervalUs) {
                            frameTimestampsUs.add(actualTimestampUs);
                            lastAddedTimestampUs = actualTimestampUs;
                        }
                        frame.recycle();
                    }
                }
                if (frameTimestampsUs.isEmpty()) { // Add first frame if none were found
                    frameTimestampsUs.add(0L);
                }

                for (int i = 0; i < frameTimestampsUs.size(); i++) {
                    long currentFrameTimestampUs = frameTimestampsUs.get(i);
                    Bitmap currentFrame = retriever.getFrameAtTime(currentFrameTimestampUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);

                    if (currentFrame != null) {
                        float motionScore = 0.0f;

                        if (i > 0) {
                            long lastFrameTimestampUs = frameTimestampsUs.get(i - 1);
                            long currentNanos = TimeUnit.MICROSECONDS.toNanos(currentFrameTimestampUs);
                            long lastNanos = TimeUnit.MICROSECONDS.toNanos(lastFrameTimestampUs);

                            float[] rotation = gyroscopeManager.getIntegratedRotation(lastNanos, currentNanos);
                            motionScore = motionAnomalyDetector.detectAnomalies(currentFrame, rotation);
                        } else {
                            // For the very first frame, just initialize the detector.
                            motionScore = motionAnomalyDetector.detectAnomalies(currentFrame, new float[3]);
                        }

                        boolean objectFound = objectDetector.containsDangerousObject(currentFrame);
                        ClassificationResult currentFrameResult = (ClassificationResult) sceneClassifier.classifyScene(currentFrame);

                        // Get other risk factors for the frame (e.g., lighting)
                        float lightingRisk = lightingAnalyzer.analyzeLighting(currentFrame);

                        int finalFrameRisk = riskCalculator.calculateRiskScore(currentFrameResult.riskScore, motionScore, lightingRisk, objectFound);

                        // --- Update cumulative score using EMA ---
                        cumulativeRisk = (alpha * finalFrameRisk) + ((1.0f - alpha) * cumulativeRisk);

                        Log.d(TAG, String.format("Frame Risk: %d%% and Cumulative Risk: %.2f%%", finalFrameRisk, cumulativeRisk));


                        // Check if this is the highest cumulative risk we've seen so far
                        if (cumulativeRisk > maxCumulativeRisk) {
                            maxCumulativeRisk = cumulativeRisk;
                            resultAtMaxRisk = currentFrameResult;
                        }

                        lastFrameTimestamp = currentFrameTimestampUs;
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

            int finalScoreToShowTemp = Math.round(maxCumulativeRisk);
            ClassificationResult finalResultToShowTemp = resultAtMaxRisk;

            if (finalResultToShowTemp == null) {
                finalScoreToShowTemp = 0;
                finalResultToShowTemp = new ClassificationResult(0.0f, "Analysis Failed", 0.0f);
            }

            int finalScoreToShow = finalScoreToShowTemp;
            ClassificationResult finalResultToShow = finalResultToShowTemp;

            // Post the final, most critical result back to the UI thread
            new Handler(Looper.getMainLooper()).post(() -> showAnalysisResultDialog(finalScoreToShow, finalResultToShow));
        });
    }


    private void showAnalysisResultDialog(int riskScore, ClassificationResult result) {
        processingOverlay.setVisibility(View.GONE);

        // Get the data object from the AlertManager
        AlertManager.AlertInfo info = alertManager.getAlertInfo(riskScore);

        // --- INFLATE AND PREPARE THE CUSTOM DIALOG VIEW ---
        // 1. Get the LayoutInflater service
        LayoutInflater inflater = this.getLayoutInflater();
        // 2. Inflate the custom layout. The second argument is null because the
        //    AlertDialog will attach it for us.
        View dialogView = inflater.inflate(R.layout.dialog_result_layout, null);

        // 3. Find the views inside our custom layout
        View dialogHeader = dialogView.findViewById(R.id.dialog_header);
        TextView dialogTitle = dialogView.findViewById(R.id.dialog_title);
        TextView dialogMessage = dialogView.findViewById(R.id.dialog_message);

        // --- POPULATE THE CUSTOM VIEW WITH OUR DATA ---
        // 4. *** THIS IS WHERE WE USE THE COLOR ***
        //    Set the background of the header to the color provided by AlertManager.
        dialogHeader.setBackgroundColor(info.color);

        // 5. Set the title (we can add the risk level here for more impact)
        dialogTitle.setText("Analysis Complete: " + info.levelText + " Risk");

        // 6. Build and set the detailed message
        String message = String.format(
                "Calculated Risk: %d%%\n\nDetected Scene: '%s'\nConfidence: %.2f%%",
                riskScore,
                result.bestMatchLabel,
                result.confidence * 100
        );
        dialogMessage.setText(message);

        // --- BUILD AND SHOW THE DIALOG ---
        new AlertDialog.Builder(this)
                // We no longer set the title or message here, as our custom layout handles it.
                // .setTitle("Analysis Complete")
                // .setMessage(message)

                // 7. Set the custom view as the content of the dialog.
                .setView(dialogView)

                .setPositiveButton("OK", (dialog, which) -> {
                    deleteCapturedMedia();
                    resetToPreviewState();
                })
                .setOnCancelListener(dialog -> {
                    deleteCapturedMedia();
                    resetToPreviewState();
                })
                .setCancelable(false)
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
        if (motionAnomalyDetector != null) motionAnomalyDetector.release();
        if (alertManager != null) alertManager.release();
        if (objectDetector != null) objectDetector.close();
    }
}