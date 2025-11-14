package com.android.safesphere.ml;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.tensorflow.lite.Interpreter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;

public class SceneClassifier {
    private static final String TAG = "SceneClassifier";
    private static final String MODEL_PATH = "clip_model.tflite";
    private static final String SCENE_DATA_PATH = "scene_data.json";
    private static final int INPUT_SIZE = 224;
    private static final int EMBEDDING_SIZE = 512;

    private Interpreter interpreter;
    private final Map<String, Float> sceneRiskMap;
    private final Map<String, float[]> textEmbeddings;
    private final Context context;

    private static final float SOFTMAX_TEMPERATURE = 0.015f;

    public SceneClassifier(Context context) {
        this.context = context;
        try {
            File modelFile = new File(context.getFilesDir(), MODEL_PATH);
            interpreter = new Interpreter(modelFile);
        } catch (Exception e) {
            Log.e(TAG, "Error initializing TensorFlow Lite interpreter.", e);
        }
        sceneRiskMap = new HashMap<>();
        textEmbeddings = new HashMap<>();
        initializeSceneRiskMap();
    }

    private ByteBuffer loadModelFile(Context context) throws IOException {
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(MODEL_PATH);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    private void initializeSceneRiskMap() {
        String jsonString;
        try {
            AssetManager assetManager = context.getAssets();
            InputStream is = assetManager.open(SCENE_DATA_PATH);
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            jsonString = new String(buffer, "UTF-8");

            JSONArray jsonArray = new JSONArray(jsonString);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);
                String label = obj.getString("label");
                float risk = (float) obj.getDouble("risk");

                JSONArray embeddingArray = obj.getJSONArray("embedding");
                float[] embedding = new float[EMBEDDING_SIZE];
                for (int j = 0; j < embeddingArray.length(); j++) {
                    embedding[j] = (float) embeddingArray.getDouble(j);
                }

                sceneRiskMap.put(label, risk);
                textEmbeddings.put(label, embedding);
            }
            Log.i(TAG, "Successfully loaded " + textEmbeddings.size() + " scene embeddings from JSON.");
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to load or parse scene_data.json!", e);
        }
    }

    public Object classifyScene(Bitmap bitmap) {
        if (interpreter == null) {
            Log.e(TAG, "Interpreter not initialized.");
            return 0.0f;
        }

        Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);
        ByteBuffer inputBuffer = convertBitmapToByteBuffer(scaledBitmap);

        float[][] imageEmbeddingOutput = new float[1][EMBEDDING_SIZE];
        interpreter.run(inputBuffer, imageEmbeddingOutput);
        float[] imageEmbedding = imageEmbeddingOutput[0];


        Map<String, Float> similarityScores = new HashMap<>();
        for (Map.Entry<String, float[]> entry : textEmbeddings.entrySet()) {
            String label = entry.getKey();
            float[] textEmbedding = entry.getValue();
            similarityScores.put(label, cosineSimilarity(imageEmbedding, textEmbedding));
        }

        // --- Step 3: Convert similarities to probabilities using Softmax ---
        Map<String, Float> confidenceScores = softmax(similarityScores, SOFTMAX_TEMPERATURE);

        // --- Step 4: Find the best match from the NEW confidence scores ---
        String bestMatchLabel = "unknown";
        float maxConfidence = -1.0f;

        for (Map.Entry<String, Float> entry : confidenceScores.entrySet()) {
            String label = entry.getKey();
            float confidence = entry.getValue();

//            Log.d(TAG, String.format("VERIFY - Confidence for '%s': %.2f%%", label, confidence * 100));

            if (confidence > maxConfidence) {
                maxConfidence = confidence;
                bestMatchLabel = label;
            }
        }

        Log.d(TAG, "Best match: '" + bestMatchLabel + "' with confidence: " + maxConfidence);
        float finalRiskScore = sceneRiskMap.getOrDefault(bestMatchLabel, 0.0f);
        return new ClassificationResult(finalRiskScore, bestMatchLabel, maxConfidence);
    }

    private Map<String, Float> softmax(Map<String, Float> scores, float temperature) {
        Map<String, Float> probabilities = new HashMap<>();
        float sumExp = 0.0f;

        // Calculate the sum of the exponentiated scores
        for (float score : scores.values()) {
            sumExp += (float) Math.exp(score / temperature);
        }

        // Calculate the softmax probability for each score
        for (Map.Entry<String, Float> entry : scores.entrySet()) {
            float probability = (float) (Math.exp(entry.getValue() / temperature) / sumExp);
            probabilities.put(entry.getKey(), probability);
        }

        return probabilities;
    }

    private float cosineSimilarity(float[] vec1, float[] vec2) {
        if (vec1 == null || vec2 == null || vec1.length != vec2.length) {
            return 0.0f;
        }
        float dotProduct = 0.0f;
        float norm1 = 0.0f;
        float norm2 = 0.0f;
        for (int i = 0; i < vec1.length; i++) {
            dotProduct += vec1[i] * vec2[i];
            norm1 += vec1[i] * vec1[i];
            norm2 += vec2[i] * vec2[i];
        }
        float magnitude1 = (float) Math.sqrt(norm1);
        float magnitude2 = (float) Math.sqrt(norm2);
        if (magnitude1 == 0 || magnitude2 == 0) {
            return 0.0f;
        }
        return dotProduct / (magnitude1 * magnitude2);
    }


    private ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3);
        byteBuffer.order(ByteOrder.nativeOrder());

        int[] intValues = new int[INPUT_SIZE * INPUT_SIZE];
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

        int pixel = 0;
        for (int i = 0; i < INPUT_SIZE; ++i) {
            for (int j = 0; j < INPUT_SIZE; ++j) {
                final int val = intValues[pixel++];
                // Normalization for FLOAT models. We'll use the [-1, 1] range.
                byteBuffer.putFloat((((val >> 16) & 0xFF) / 127.5f) - 1.0f); // Red
                byteBuffer.putFloat((((val >> 8) & 0xFF) / 127.5f) - 1.0f);  // Green
                byteBuffer.putFloat(((val & 0xFF) / 127.5f) - 1.0f);        // Blue
            }
        }
        return byteBuffer;
    }

    public void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
    }
}