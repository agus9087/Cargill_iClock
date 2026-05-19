# Open Source Libraries - iClock Project

This document lists the primary open-source libraries and components used in the iClock project.

---

### 1. LiteRT (formerly TensorFlow Lite)
The inference engine used for running the face recognition model (`mobile_face_net.tflite`).
*   **Repository:** [GitHub - TensorFlow Lite](https://github.com/tensorflow/tensorflow/tree/master/tensorflow/lite)
*   **Official Site:** [LiteRT on Google AI](https://ai.google.dev/edge/litert)
*   **License:** Apache License 2.0

### 2. AndroidX & Jetpack
The core framework for the modern Android application, including:
*   **Jetpack Compose:** Modern toolkit for building native UI.
*   **CameraX:** Simplified camera integration.
*   **Lifecycle & ViewModel:** Management of app states and data persistence.
*   **Repository:** [AndroidX GitHub Mirror](https://github.com/androidx/androidx)
*   **License:** Apache License 2.0

### 3. Material Components for Android
Modular and customizable UI components implementing Google's Material Design.
*   **Repository:** [Material Components GitHub](https://github.com/material-components/material-components-android)
*   **License:** Apache License 2.0

### 4. GSON (Google)
A Java library that can be used to convert Java Objects into their JSON representation and vice versa.
*   **Repository:** [GitHub - GSON](https://github.com/google/gson)
*   **License:** Apache License 2.0

### 5. ML Kit (Face Detection)
Google's on-device machine learning SDK used for detecting faces in the camera stream.
*   **Documentation:** [ML Kit Face Detection](https://developers.google.com/ml-kit/vision/face-detection)
*   **License:** Google APIs Terms of Service

---

### Local Artifact Cache
The compiled versions of these libraries are stored locally in your Gradle cache:
`C:\Users\Usuario\.gradle\caches\modules-2\files-2.1\`

### How to open in Word
You can open this `.md` file directly in Microsoft Word (Word will convert the formatting) or copy-paste this content into a new Word document.
