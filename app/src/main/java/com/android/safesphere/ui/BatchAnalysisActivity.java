package com.android.safesphere.ui;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import com.android.safesphere.R;
import com.android.safesphere.SafeSphereApp;
import com.android.safesphere.ml.ClassificationResult;
import com.android.safesphere.ml.LightingAnalyzer;
import com.android.safesphere.ml.MotionAnomalyDetector;
import com.android.safesphere.ml.RiskCalculator;
import com.android.safesphere.ml.SceneClassifier;
import java.io.IOException;
import java.io.InputStream;
import android.graphics.BitmapFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BatchAnalysisActivity extends AppCompatActivity {
    private static final String TAG = "BatchAnalysisActivity";

    // Data class to hold a result
    private static class AnalysisItem {
        Uri uri;
        String fileName;
        Bitmap thumbnail;
        int riskScore = -1; // -1 means not yet analyzed
        ClassificationResult result;
        AlertManager.AlertInfo alertInfo;
    }

    // UI Components
    private RecyclerView recyclerView;
    private Button startAnalysisButton;
    private ProgressBar progressBar;
    private TextView progressText;

    // ML Models (we need our own instances)
    private SceneClassifier sceneClassifier;
    private MotionAnomalyDetector motionAnomalyDetector;
    private LightingAnalyzer lightingAnalyzer;
    private RiskCalculator riskCalculator;
    private AlertManager alertManager;

    private List<AnalysisItem> analysisItems = new ArrayList<>();
    private ResultsAdapter adapter;
    private ExecutorService analysisExecutor;

    private static final int VIDEO_SAMPLING_INTERVAL_MS = SafeSphereApp.VIDEO_SAMPLING_INTERVAL_MS;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_batch_analysis);

        // Init UI
        recyclerView = findViewById(R.id.results_recycler_view);
        startAnalysisButton = findViewById(R.id.start_analysis_button);
        progressBar = findViewById(R.id.batch_progress_bar);
        progressText = findViewById(R.id.batch_progress_text);

        // Init ML models
        initDependencies();

        // Get URIs from MainActivity
        ArrayList<Uri> uris = getIntent().getParcelableArrayListExtra("file_uris");
        if (uris != null) {
            prepareItems(uris);
        }

        adapter = new ResultsAdapter(this, analysisItems);
        recyclerView.setAdapter(adapter);

        startAnalysisButton.setOnClickListener(v -> startBatchAnalysis());
    }

    private void initDependencies() {
        analysisExecutor = Executors.newSingleThreadExecutor();
        sceneClassifier = new SceneClassifier(this);
        motionAnomalyDetector = new MotionAnomalyDetector();
        lightingAnalyzer = new LightingAnalyzer();
        riskCalculator = new RiskCalculator();
        alertManager = new AlertManager(this);
    }

    // Prepares the list of items, extracting thumbnails and filenames
    private void prepareItems(List<Uri> uris) {
        analysisExecutor.execute(() -> {
            for (Uri uri : uris) {
                AnalysisItem item = new AnalysisItem();
                item.uri = uri;
                item.fileName = getFileName(uri);
                item.thumbnail = getThumbnail(uri);
                analysisItems.add(item);
            }
            runOnUiThread(() -> adapter.notifyDataSetChanged());
        });
    }

    private void startBatchAnalysis() {
        startAnalysisButton.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        progressText.setVisibility(View.VISIBLE);

        analysisExecutor.execute(() -> {
            for (int i = 0; i < analysisItems.size(); i++) {
                final int index = i;
                AnalysisItem item = analysisItems.get(index);

                runOnUiThread(() -> progressText.setText("Analyzing file " + (index + 1) + " of " + analysisItems.size() + "..."));

                String mimeType = getContentResolver().getType(item.uri);
                if (mimeType != null && mimeType.startsWith("video/")) {
                    analyzeVideo(item);
                } else {
                    analyzeImage(item);
                }

                runOnUiThread(() -> adapter.notifyItemChanged(index));
            }

            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                progressText.setText("Analysis Complete");
            });
        });
    }

    // --- ANALYSIS LOGIC (Adapted from DetectionActivity) ---
    private void analyzeImage(AnalysisItem item) {
        try (InputStream inputStream = getContentResolver().openInputStream(item.uri)) {
            Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
            if (bitmap != null) {
                item.result = (ClassificationResult) sceneClassifier.classifyScene(bitmap);
                item.riskScore = riskCalculator.calculateRiskScore(item.result.riskScore, 0, lightingAnalyzer.analyzeLighting(bitmap));
                item.alertInfo = alertManager.getAlertInfo(item.riskScore);
                bitmap.recycle();
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to analyze image", e);
        }
    }

    private void analyzeVideo(AnalysisItem item) {
        MotionAnomalyDetector videoMotionDetector = new MotionAnomalyDetector();
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();

        try {
            retriever.setDataSource(this, item.uri);

            // ... (cumulative risk logic is the same)
            float cumulativeRisk = 0.0f;
            float maxCumulativeRisk = -1.0f;
            ClassificationResult resultAtMaxRisk = null;
            final float alpha = 0.4f;

            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = Long.parseLong(durationStr);

            for (long timeMs = 0; timeMs < durationMs; timeMs += 500) {
                Bitmap frame = retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame != null) {
                    // Use the new, local instance of the detector
                    float motionScore = videoMotionDetector.detectAnomalies(frame, new float[3]);

                    ClassificationResult frameResult = (ClassificationResult) sceneClassifier.classifyScene(frame);
                    float lightingRisk = lightingAnalyzer.analyzeLighting(frame);
                    int finalFrameRisk = riskCalculator.calculateRiskScore(frameResult.riskScore, motionScore, lightingRisk);

                    cumulativeRisk = (alpha * finalFrameRisk) + ((1.0f - alpha) * cumulativeRisk);
                    if (cumulativeRisk > maxCumulativeRisk) {
                        maxCumulativeRisk = cumulativeRisk;
                        resultAtMaxRisk = frameResult;
                    }
                    frame.recycle();
                }
            }
            item.riskScore = Math.round(maxCumulativeRisk);
            item.result = resultAtMaxRisk;
            item.alertInfo = alertManager.getAlertInfo(item.riskScore);

        } catch (Exception e) {
            Log.e(TAG, "Failed to analyze video", e);
        } finally {
            try {
                retriever.release();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            videoMotionDetector.release();
        }
    }

    // --- UTILITY AND ADAPTER ---
    private String getFileName(Uri uri) {
        String result = "Unknown File";
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if(nameIndex >= 0)
                        result = cursor.getString(nameIndex);
                }
            }
        }
        return result;
    }

    private Bitmap getThumbnail(Uri uri) {
        String mimeType = getContentResolver().getType(uri);
        if (mimeType != null && mimeType.startsWith("video/")) {
            try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
                retriever.setDataSource(this, uri);
                return retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (Exception e) {
                return null;
            }
        } else {
            try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
                // Decode with a smaller sample size to create a thumbnail without using too much memory
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 8;
                return BitmapFactory.decodeStream(inputStream, null, options);
            } catch (IOException e) {
                return null;
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        analysisExecutor.shutdown();
        sceneClassifier.close();
        motionAnomalyDetector.release();
        alertManager.release();
    }

    // --- RecyclerView Adapter Inner Class ---
    private static class ResultsAdapter extends RecyclerView.Adapter<ResultsAdapter.ViewHolder> {
        private final Context context;
        private final List<AnalysisItem> items;

        ResultsAdapter(Context context, List<AnalysisItem> items) {
            this.context = context;
            this.items = items;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(context).inflate(R.layout.item_analysis_result, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AnalysisItem item = items.get(position);
            holder.fileNameText.setText(item.fileName);
            holder.thumbnailImage.setImageBitmap(item.thumbnail);

            if (item.riskScore == -1) {
                holder.resultDetailsText.setText("Pending analysis...");
                holder.riskColorBar.setBackgroundColor(context.getResources().getColor(android.R.color.darker_gray));
            } else {
                String details = String.format("Risk: %d%% (%s)\nScene: '%s' (%.1f%%)",
                        item.riskScore,
                        item.alertInfo.levelText,
                        item.result.bestMatchLabel,
                        item.result.confidence * 100);
                holder.resultDetailsText.setText(details);
                holder.riskColorBar.setBackgroundColor(item.alertInfo.color);
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            View riskColorBar;
            TextView fileNameText;
            TextView resultDetailsText;
            ImageView thumbnailImage;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                riskColorBar = itemView.findViewById(R.id.risk_color_bar);
                fileNameText = itemView.findViewById(R.id.file_name_text);
                resultDetailsText = itemView.findViewById(R.id.result_details_text);
                thumbnailImage = itemView.findViewById(R.id.thumbnail_image);
            }
        }
    }
}