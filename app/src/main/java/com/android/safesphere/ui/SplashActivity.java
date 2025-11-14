package com.android.safesphere.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.android.safesphere.R;
import com.android.safesphere.utils.ModelDownloader;

import java.io.File;

public class SplashActivity extends AppCompatActivity {

    private static final String CLIP_MODEL_URL = "https://github.com/J-yphen/SafeSphere/releases/download/models-initial/clip_model.tflite";
    private static final String DETECTOR_MODEL_URL = "https://github.com/J-yphen/SafeSphere/releases/download/models-initial/detector_model.tflite";

    private static final String CLIP_MODEL_NAME = "clip_model.tflite";
    private static final String DETECTOR_MODEL_NAME = "detector_model.tflite";

    private ModelDownloader modelDownloader;
    private TextView statusText;
    private ProgressBar progressBar;
    private Button retryButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        statusText = findViewById(R.id.status_text);
        progressBar = findViewById(R.id.progress_bar);
        retryButton = findViewById(R.id.retry_button);

        modelDownloader = new ModelDownloader(this);

        retryButton.setOnClickListener(v -> checkForModels());

        checkForModels();
    }

    private void checkForModels() {
        retryButton.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);

        File clipModelFile = modelDownloader.getModelFile(CLIP_MODEL_NAME);
        File detectorModelFile = modelDownloader.getModelFile(DETECTOR_MODEL_NAME);

        if (clipModelFile.exists() && detectorModelFile.exists()) {
            statusText.setText("Models found. Starting app...");
            navigateToMain();
        } else {
            // We need to download at least one model
            downloadModels();
        }
    }

    private void downloadModels() {
        File clipModelFile = modelDownloader.getModelFile(CLIP_MODEL_NAME);
        File detectorModelFile = modelDownloader.getModelFile(DETECTOR_MODEL_NAME);

        // Chain the downloads
        if (!clipModelFile.exists()) {
            statusText.setText("Downloading required assets (1 of 2)...");
            modelDownloader.download(CLIP_MODEL_URL, CLIP_MODEL_NAME, new ModelDownloader.OnDownloadCompleteListener() {
                @Override
                public void onSuccess() {
                    // After the first succeeds, start the second
                    downloadModels();
                }
                @Override
                public void onFailure(Exception e) {
                    showError("Failed to download scene model.");
                }
            });
        } else if (!detectorModelFile.exists()) {
            statusText.setText("Downloading required assets (2 of 2)...");
            modelDownloader.download(DETECTOR_MODEL_URL, DETECTOR_MODEL_NAME, new ModelDownloader.OnDownloadCompleteListener() {
                @Override
                public void onSuccess() {
                    // Both are now downloaded, proceed
                    checkForModels();
                }
                @Override
                public void onFailure(Exception e) {
                    showError("Failed to download object model.");
                }
            });
        }
    }

    private void showError(String message) {
        statusText.setText(message);
        progressBar.setVisibility(View.GONE);
        retryButton.setVisibility(View.VISIBLE);
    }

    private void navigateToMain() {
        // Use a small delay to show the "Starting app" message
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent intent = new Intent(SplashActivity.this, MainActivity.class);
            startActivity(intent);
            finish(); // Finish SplashActivity so user can't go back to it
        }, 1000);
    }
}