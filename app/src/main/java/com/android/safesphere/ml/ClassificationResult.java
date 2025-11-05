package com.android.safesphere.ml;

public class ClassificationResult {
    public final float riskScore;
    public final String bestMatchLabel;
    public final float confidence; // Cosine similarity score

    public ClassificationResult(float riskScore, String bestMatchLabel, float confidence) {
        this.riskScore = riskScore;
        this.bestMatchLabel = bestMatchLabel;
        this.confidence = confidence;
    }
}