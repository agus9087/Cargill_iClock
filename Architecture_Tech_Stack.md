# iClock - System Architecture & Tech Stack

This document outlines the architectural layers and technical components of the iClock application, a face-recognition based time clock system.

---

## 1. Architectural Overview

The application follows a **Layered Architecture** pattern, optimized for on-device machine learning and real-time camera processing.

### Layer 1: Presentation (UI) Layer
Responsible for interacting with the user, displaying the clock, handling PIN input, and rendering the camera preview.
*   **Components:** `MainActivity`, Compose Themes, XML Layouts (Bridge).
*   **Tech Stack:** 
    *   **Jetpack Compose:** For building modern, reactive UI components.
    *   **Material Design 3:** For standardized buttons, cards, and typography.
    *   **CameraX PreviewView:** For rendering the real-time camera feed.

### Layer 2: Domain (Business Logic) Layer
Handles the core rules of the application: face detection logic, PIN validation, and the orchestrator for user registration vs. punch-in.
*   **Components:** Face recognition workflow, distance calculation logic.
*   **Tech Stack:**
    *   **Kotlin Coroutines:** For managing background tasks without blocking the UI thread.
    *   **ML Kit Face Detection:** To identify face presence and bounding boxes in real-time.

### Layer 3: AI/Inference Layer
The "Intelligence" of the app. It transforms raw pixel data from detected faces into mathematical descriptors (embeddings).
*   **Components:** `MobileFaceNet` model integration.
*   **Tech Stack:**
    *   **LiteRT (TensorFlow Lite):** High-performance inference engine for on-device ML.
    *   **MobileFaceNet (.tflite):** Specialized neural network for extracting 128-dimensional facial features.

### Layer 4: Data Layer
Manages persistence of user profiles (PINs + Facial Descriptors) and the logging of attendance events.
*   **Components:** JSON File Repository, File-based Log system.
*   **Tech Stack:**
    *   **GSON (Google):** For serializing/deserializing user data to `usuarios_reloj.json`.
    *   **Android Internal Storage:** Private file storage for security and persistence.

---

## 2. Full Technology Stack Summary

| Category | Technology | Version |
| :--- | :--- | :--- |
| **Language** | Kotlin | 2.2.10 |
| **UI Framework** | Jetpack Compose (BOM) | 2026.02.01 |
| **Material Design** | Google Material 3 | 1.11.0 |
| **Camera** | CameraX | 1.5.0-alpha01 |
| **Face Detection** | ML Kit (Play Services) | 17.1.0 |
| **ML Engine** | LiteRT (Edge AI) | 2.1.4 |
| **Serialization** | GSON | 2.10.1 |
| **Build System** | Gradle (Kotlin DSL) | 9.4.1 |

---

## 4. Data Storage: Attributes Stored on Device

All data is stored locally within the application's private internal storage to ensure privacy and offline functionality.

### A. User Profiles (`usuarios_reloj.json`)
This file contains the enrolled users authorized to use the system.
*   **PIN:** A unique 4-6 digit string used as the primary identifier.
*   **Facial Descriptor (Embedding):** A 128-dimensional array of floating-point numbers (`List<Float>`) representing the unique mathematical signature of the user's face.

### B. Attendance Logs (`log_asistencia.txt`)
A plain-text audit trail of all successful authentication events.
*   **Timestamp:** The exact date and time of the event (Format: `yyyy-MM-dd HH:mm:ss`).
*   **User PIN:** The PIN associated with the person who clocked in.
*   **Status:** The result of the operation (e.g., `EXITOSO`).

### C. Assets & Configuration (Read-Only)
*   **ML Model:** `mobile_face_net.tflite` (The neural network weights).
*   **App Theme:** Primary/Secondary colors and UI shapes defined in resources.
