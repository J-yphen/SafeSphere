# SafeSphere

**An AI-powered personal safety application for Android that uses on-device computer vision and sensor fusion to detect dangerous situations in real-time.**

[![Platform](https://img.shields.io/badge/Platform-Android-brightgreen.svg)](https://www.android.com/)
[![Language](https://img.shields.io/badge/Language-Java-orange.svg)](https://www.java.com/)

SafeSphere transforms a smartphone into a proactive safety companion. By analyzing the camera feed and environmental sensors, it provides a real-time, comprehensive risk score of the user's surroundings, empowering them with situational awareness. All processing is done locally on the device to ensure maximum user privacy and offline functionality.


---

## ✨ Features

*   **Multi-Modal Risk Analysis:** Generates a comprehensive 0-100 risk score by fusing data from four distinct AI modules.
*   **📸 Scene Classification:** Uses a TFLite-converted **CLIP** model for zero-shot detection of scenes based on natural language descriptions (e.g., "a dark alley," "a crowded plaza").
*   **🏃 Motion Anomaly Detection:** Employs **OpenCV Dense Optical Flow** to detect unusual, high-intensity motion patterns like punches or sudden running, while ignoring normal camera shake.
*   **🔪 Dangerous Object Detection:** A custom **YOLO-style** object detection model acts as a risk multiplier, drastically increasing the score if a dangerous object is detected.
*   **🤖 Sensor Fusion:** Integrates **Gyroscope** data to stabilize the video feed for the motion detector, significantly reducing false positives from user hand movements.
*   **💡 Lighting Analysis:** Assesses ambient brightness from the camera feed to factor visibility into the risk calculation.
*   **🎞️ Flexible Media Analysis:** Users can capture a new photo (tap) or video (hold-to-record) for on-demand analysis, or select multiple files from their device for batch processing.
*   **☁️ On-Demand Model Delivery:** Large ML model files are downloaded on the first app launch to keep the initial download size small.
*   **🔒 Privacy by Design:** All images, videos, and sensor data are processed **100% on the device**. Nothing is ever uploaded to a server.

---

## 🛠️ Technical Stack & Architecture

This project is built with a focus on modern Android development practices and efficient on-device machine learning.

*   **Language:** **Java**
*   **Architecture:** **MVVM (Model-View-ViewModel)**
*   **Core APIs:**
    *   **CameraX:** For a modern, robust, and lifecycle-aware camera implementation.
    *   **OpenCV for Android:** For high-performance image processing, optical flow, and lighting analysis.
    *   **TensorFlow Lite Interpreter API:** For running custom ML models for scene classification and object detection.
*   **Threading:** An `ExecutorService` manages all long-running ML inference tasks in the background to keep the UI smooth and responsive.
*   **UI:** Built with Material Design components, featuring a custom colored alert dialog and support for both light and dark themes.

---

## 🚀 Getting Started

Follow these instructions to get a copy of the project up and running on your local machine for development and testing purposes.

### Prerequisites

*   Android Studio (latest version recommended)
*   An Android device or emulator with API level 24+
*   **OpenCV Android SDK:** Download the latest 4.12.0 version from the [official OpenCV website](https://opencv.org/releases/) (e.g., `opencv-4.12.0-android-sdk.zip`).

### Installation & Setup

1.  **Clone the Repository**
    ```bash
    git clone https://github.com/J-yphen/SafeSphere.git
    ```

2.  **Unzip the OpenCV SDK**
    Unzip the downloaded OpenCV Android SDK to a memorable location on your computer (e.g., `/Users/YourName/SDKs/opencv-4.12.0-android-sdk/`).

3.  **Open the Project in Android Studio**
    Open the cloned `SafeSphere` project folder in Android Studio.

4.  **Import the OpenCV Module (Crucial Step)**
    The project is configured to use a local OpenCV module. You must import it:
    *   Go to **`File > New > Import Module...`**.
    *   In the "Source directory" field, navigate to the location where you unzipped the SDK and select the **`sdk/java`** folder. It should look like this: `.../opencv-4.12.0-android-sdk/sdk/java`.
    *   Android Studio will suggest a module name, which should default to `opencv`. **Do not change this name.**
    *   Click **"Finish"**. Android Studio will import the SDK and sync Gradle.

5.  **Model Hosting**
    This app uses on-demand model delivery. You must host the ML models on a cloud service.
    *   **Option A (Easy): GitHub Releases**
        1.  Create a new release in your forked GitHub repository.
        2.  Upload your `clip_model.tflite` and `detector_model.tflite` files as release assets.
        3.  Copy the direct download URLs for these assets.
    *   **Option B (Secure): Firebase Cloud Storage**
        1.  Create a Firebase project and connect it to the app (add `google-services.json`).
        2.  Upload the model files to a Firebase Storage bucket.
        3.  Set up security rules and modify the `ModelDownloader.java` to use the Firebase SDK.

6.  **Configure Model URLs**
    Open `app/src/main/java/com/android/safesphere/ui/SplashActivity.java` and replace the placeholder URLs with your actual download links from Step 5.
    ```java
    private static final String CLIP_MODEL_URL = "https://your-direct-download-url/clip_model.tflite";
    private static final String DETECTOR_MODEL_URL = "https://your-direct-download-url/detector_model.tflite";
    ```

7.  **Build and Run**
    Clean and rebuild the project (`Build > Clean Project`, then `Build > Rebuild Project`). Run the app on your connected device or emulator.
---

## Usage

1.  **First Launch:** The app will display a splash screen while it downloads the required ML models.
2.  **Grant Permissions:** Allow the app to access the Camera, Location, and Audio.
3.  **Choose an Option:**
    *   **Start Live Detection:** Opens the camera. Tap the capture button for a photo or press-and-hold to record a video. A preview will be shown.
    *   **Analyze Media File:** Opens a file picker. Select one or more photos/videos from your device.
4.  **Analyze:** Click the "Analyze" button. A processing overlay will appear while the app runs its ML pipeline.
5.  **View Results:** A custom dialog will appear, showing the final risk score and details about the detected scene and confidence. The dialog header is colored based on the risk level for immediate visual feedback.

