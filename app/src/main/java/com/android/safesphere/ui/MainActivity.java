package com.android.safesphere.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.Configuration;

import android.net.Uri;
import android.os.Bundle;

import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.android.safesphere.R;
import com.android.safesphere.utils.PermissionManager;
import com.android.safesphere.utils.ThemeHelper;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private Thread processingThread;
    private ActivityResultLauncher<Intent> filePickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyTheme(this);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Find views
        ImageView logo = findViewById(R.id.logo_image_view);
        TextView title = findViewById(R.id.app_title_text_view);
        TextView subtitle = findViewById(R.id.app_subtitle_text_view);
        Button liveDetectionButton = findViewById(R.id.live_detection_button);
        Button uploadMediaButton = findViewById(R.id.upload_media_button);
        FloatingActionButton themeSwitchFab = findViewById(R.id.theme_switch_fab);

        // Load animations
        Animation rotation = AnimationUtils.loadAnimation(this, R.anim.icon_rotate);
        Animation fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in);
        Animation slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up);

        // Apply animations
        logo.startAnimation(rotation); //        logo.startAnimation(fadeIn);
        title.startAnimation(fadeIn);
        subtitle.startAnimation(fadeIn);
        liveDetectionButton.startAnimation(slideUp);
        uploadMediaButton.startAnimation(slideUp);
        themeSwitchFab.startAnimation(fadeIn);


        // Setup Theme Switch FAB
        setupThemeSwitchButton(themeSwitchFab);

        // Setup Listeners
        uploadMediaButton.setOnClickListener(v -> openFilePicker());

        // Handle file picker result
        liveDetectionButton.setOnClickListener(v -> {
            if (PermissionManager.checkPermissions(this)) {
                startActivity(new Intent(this, DetectionActivity.class));
            } else {
                PermissionManager.requestPermissions(this);
            }
        });

        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        ArrayList<Uri> selectedUris = new ArrayList<>();
                        // TODO: Pass this URI to a new activity/fragment for processing
                        ClipData clipData = result.getData().getClipData();
                        if (clipData != null) {
                            // Multiple files selected
                            int numberOfFiles = clipData.getItemCount();
                            for (int i = 0; i < numberOfFiles; i++) {
                                selectedUris.add(clipData.getItemAt(i).getUri());
                            }
                            Toast.makeText(this, "Number of selected files: " + numberOfFiles, Toast.LENGTH_LONG).show();
                        } else {
                            // Only a single file selected
                            selectedUris.add(result.getData().getData());
                            Toast.makeText(this, "Number of selected files: 1", Toast.LENGTH_LONG).show();

                        }

                        if (!selectedUris.isEmpty()) {
                            // Use our new function to launch the batch analysis activity
                            launchBatchAnalysis(selectedUris);
                        }
                    }
                });
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*,video/*"); // Allows picking both images and videos
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/*", "video/*"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        filePickerLauncher.launch(intent);
    }

    private void launchBatchAnalysis(ArrayList<Uri> uris) {
        Intent intent = new Intent(this, BatchAnalysisActivity.class);
        // Pass the list of URIs to the new activity
        intent.putParcelableArrayListExtra("file_uris", uris);
        startActivity(intent);
    }

    private void setupThemeSwitchButton(FloatingActionButton fab) {
        // Set the correct icon based on the current theme
        int currentNightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (currentNightMode == Configuration.UI_MODE_NIGHT_YES) {
            fab.setImageResource(R.drawable.ic_light_mode); // Currently dark, show sun
        } else {
            fab.setImageResource(R.drawable.ic_dark_mode); // Currently light, show moon
        }

        // Set listener to toggle theme
        fab.setOnClickListener(view -> {
            if (currentNightMode == Configuration.UI_MODE_NIGHT_YES) {
                // Switch to light mode
                ThemeHelper.setTheme(MainActivity.this, AppCompatDelegate.MODE_NIGHT_NO);
            } else {
                // Switch to dark mode
                ThemeHelper.setTheme(MainActivity.this, AppCompatDelegate.MODE_NIGHT_YES);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String @NotNull [] permissions, int @NotNull [] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PermissionManager.REQUEST_CODE && PermissionManager.checkPermissions(this)) {
            startActivity(new Intent(this, DetectionActivity.class));
        } else {
            Toast.makeText(this, "Permissions are required to start detection.", Toast.LENGTH_SHORT).show();
        }
    }
}
