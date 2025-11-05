package com.android.safesphere.ui;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import com.android.safesphere.R;
import com.google.android.material.card.MaterialCardView;

public class AlertManager {
    private final Context context;
    private final MediaPlayer mediaPlayer;
    private final Vibrator vibrator;

    /**
     * A simple data class to hold the text and color for a given risk level.
     */
    public static class AlertInfo {
        public final String levelText;
        public final int color;

        public AlertInfo(String text, int c) {
            this.levelText = text;
            this.color = c;
        }
    }

    public AlertManager(Context context) {
        this.context = context;
//        this.mediaPlayer = MediaPlayer.create(context, R.raw.high_risk_alert);
        this.mediaPlayer = null;
        this.vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    public AlertInfo getAlertInfo(int riskScore) {
        String alertLevel;
        int color;

        if (riskScore < 25) {
            alertLevel = "Low";
            color = ContextCompat.getColor(context, R.color.alert_green);
        } else if (riskScore < 50) {
            alertLevel = "Moderate";
            color = ContextCompat.getColor(context, R.color.alert_yellow);
        } else if (riskScore < 75) {
            alertLevel = "High";
            color = ContextCompat.getColor(context, R.color.alert_amber);
        } else {
            alertLevel = "Critical";
            color = ContextCompat.getColor(context, R.color.alert_red);
            triggerHighRiskAlert();
        }
        return new AlertInfo(alertLevel, color);
    }

    private void triggerHighRiskAlert() {
        if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
            mediaPlayer.start();
        }
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    public void release() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }
    }
}