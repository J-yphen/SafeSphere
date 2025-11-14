package com.android.safesphere.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import javax.net.ssl.HttpsURLConnection;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ModelDownloader {

    private static final String TAG = "ModelDownloader";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Context context;

    // Interface to report back the result of the download
    public interface OnDownloadCompleteListener {
        void onSuccess();
        void onFailure(Exception e);
    }

    public ModelDownloader(Context context) {
        this.context = context;
    }

    public File getModelFile(String modelName) {
        // Models will be stored in the app's private internal storage
        return new File(context.getFilesDir(), modelName);
    }

    public void download(String modelUrl, String modelName, OnDownloadCompleteListener listener) {
        executor.execute(() -> {
            File modelFile = getModelFile(modelName);
            try {
                // Open connection
                URL url = new URL(modelUrl);
                HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
                connection.connect();

                if (connection.getResponseCode() != HttpsURLConnection.HTTP_OK) {
                    throw new IOException("Server returned HTTP " + connection.getResponseCode()
                            + " " + connection.getResponseMessage());
                }

                // Download the file
                InputStream input = connection.getInputStream();
                FileOutputStream output = new FileOutputStream(modelFile);

                byte[] data = new byte[4096];
                int count;
                while ((count = input.read(data)) != -1) {
                    output.write(data, 0, count);
                }

                output.close();
                input.close();
                connection.disconnect();

                Log.d(TAG, "Model downloaded successfully: " + modelName);
                // Report success on the main thread
                handler.post(listener::onSuccess);

            } catch (Exception e) {
                Log.e(TAG, "Error downloading model: " + modelName, e);
                // Delete partial file on failure
                if (modelFile.exists()) {
                    modelFile.delete();
                }
                // Report failure on the main thread
                handler.post(() -> listener.onFailure(e));
            }
        });
    }
}