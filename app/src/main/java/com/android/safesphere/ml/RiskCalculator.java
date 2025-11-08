package com.android.safesphere.ml;

import java.util.Calendar;

public class RiskCalculator {

    private static final float WEIGHT_SCENE = 0.5f;
    private static final float WEIGHT_MOTION = 0.3f;
    private static final float WEIGHT_LIGHTING = 0.2f;
    private static final float NIGHT_MULTIPLIER = 1.2f;

    public int calculateRiskScore(float sceneRisk, float motionAnomalyScore, float lightingRisk) {
        float timeMultiplier = isNightTime() ? NIGHT_MULTIPLIER : 1.0f;

        float combinedRisk = (sceneRisk * 100 * WEIGHT_SCENE) +
                (motionAnomalyScore * WEIGHT_MOTION) +
                (lightingRisk * WEIGHT_LIGHTING);

        int finalRiskScore = (int) (combinedRisk * timeMultiplier);

        return Math.min(100, Math.max(0, finalRiskScore));
    }

    private boolean isNightTime() {
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour < 6 || hour > 19; // 7 PM to 6 AM
    }
}