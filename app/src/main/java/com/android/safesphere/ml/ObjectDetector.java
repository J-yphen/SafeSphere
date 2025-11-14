package com.android.safesphere.ml;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ObjectDetector {
    private static final String TAG = "ObjectDetector";
    private static final String MODEL_PATH = "detector_model.tflite";
    private static final String LABELS_PATH = "detector_labels.txt";
    private static final float CONFIDENCE_THRESHOLD = 0.85f; // Threshold for object detection confidence

    private Interpreter tflite;
    private List<String> labels;
    private int inputWidth;
    private int inputHeight;
    private TensorImage inputImageBuffer;
    private ImageProcessor imageProcessor;

    public ObjectDetector(Context context) {
        try {
            File modelFile = new File(context.getFilesDir(), MODEL_PATH);
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(4);
            tflite = new Interpreter(modelFile, options);


            labels = FileUtil.loadLabels(context, LABELS_PATH);

            int[] inputShape = tflite.getInputTensor(0).shape();
            inputWidth = inputShape[1];
            inputHeight = inputShape[2];
            DataType inputDataType = tflite.getInputTensor(0).dataType();

            imageProcessor = new ImageProcessor.Builder()
                .add(new ResizeOp(inputHeight, inputWidth, ResizeOp.ResizeMethod.BILINEAR))
                .build();
            inputImageBuffer = new TensorImage(inputDataType);

        } catch (IOException e) {
            Log.e(TAG, "Error initializing TFLite Object Detector.", e);
        }
    }

    public boolean containsDangerousObject(Bitmap bitmap) {
        if (tflite == null) {
            Log.e(TAG, "Object detector is not initialized.");
            return false;
        }

        // 1. Preprocess the image
        inputImageBuffer.load(bitmap);
        TensorImage processedImage = imageProcessor.process(inputImageBuffer);
        ByteBuffer inputBuffer = processedImage.getBuffer();

        // 2. Prepare the output buffer
        int[] outputShape = tflite.getOutputTensor(0).shape();
        int numChannels = outputShape[1];
        int numDetections = outputShape[2];

        float[][][] outputArray = new float[1][numChannels][numDetections];
        Map<Integer, Object> outputs = new HashMap<>();
        outputs.put(0, outputArray);

        // 3. Run inference
        tflite.runForMultipleInputsOutputs(new Object[]{inputBuffer}, outputs);

        // 4. Post-process the YOLO-style output
        // The output is transposed, so we need to iterate through the 8400 detections
        for (int i = 0; i < numDetections; i++) {
            // Get the confidence that this detection is an object
            float confidence = outputArray[0][4][i];

            // Only process detections with a high enough initial confidence
            if (confidence >= CONFIDENCE_THRESHOLD) {
                // Find the class with the highest score among the class scores (channels 5 through 9)
                float maxClassScore = 0.0f;
                int maxClassId = -1;
                for (int j = 5; j < numChannels; j++) {
                    if (outputArray[0][j][i] > maxClassScore) {
                        maxClassScore = outputArray[0][j][i];
                        maxClassId = j - 5; // The class index is the channel index minus 5
                    }
                }

                // The final confidence is the object confidence multiplied by the class confidence
                float finalConfidence = confidence * maxClassScore;

                if (finalConfidence >= CONFIDENCE_THRESHOLD) {
                    String label = (maxClassId != -1 && maxClassId < labels.size()) ? labels.get(maxClassId) : "unknown";
                    Log.d(TAG, "Detected object: '" + label + "' with final confidence: " + finalConfidence);
                    if (label.equals("knife") || label.equals("pistol"))
                        return true;
                }
            }
        }

        return false; // No objects were detected above the threshold
    }

    public void close() {
        if (tflite != null) {
            tflite.close();
        }
    }
}