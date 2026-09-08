# Google Play Store Submission & Policy Compliance Guide

**Application ID:** `com.androiddex.host`  
**Target SDK:** `36` (Android 16 / Android 15 Ready)  
**NDK Version:** `30.0.14904198` (16 KB page-aligned native libraries)  
**Developer / Publisher:** [INSERT DEVELOPER / ORGANIZATION NAME]  
**Developer Contact Email:** [INSERT DEVELOPER CONTACT EMAIL]  
**Developer Physical Address:** [INSERT DEVELOPER POSTAL OR MAILING ADDRESS]  
**Privacy Policy URL:** [INSERT PUBLIC HOSTED URL OF PRIVACY.md]  

---

## 1. Pre-Submission Technical Checklist

- [ ] **Application ID:** Verified as `com.androiddex.host` (valid production namespace; `com.example.*` eliminated).
- [ ] **Target SDK Compliance:** `targetSdk = 36` in `android-host/app/build.gradle.kts`.
- [ ] **16 KB Page Alignment:** Verified via `llvm-readelf -l` that all `PT_LOAD` segments of `librust_quic_server.so` (for both `arm64-v8a` and `x86_64`) have `Align = 0x4000` (16,384 bytes).
- [ ] **Release Signing Keystore:** Generated personal release keystore via `keytool`:
  ```bash
  keytool -genkeypair -v -keystore release.jks -alias androiddex -keyalg RSA -keysize 2048 -validity 10000
  ```
- [ ] **Keystore Properties:** Configured `android-host/keystore.properties` (never committed to git):
  ```properties
  storeFile=release.jks
  storePassword=[INSERT STORE PASSWORD]
  keyAlias=androiddex
  keyPassword=[INSERT KEY PASSWORD]
  ```
- [ ] **Minification & Shrinking:** `isMinifyEnabled = true` and `isShrinkResources = true` verified with ProGuard rules in `proguard-rules.pro`.
- [ ] **Production Bundle Build:** Run the command:
  ```bash
  cd android-host
  ./gradlew :app:bundleRelease --no-daemon
  ```
  The generated `.aab` file will be located at `android-host/app/build/outputs/bundle/release/app-release.aab`.

---

## 2. Accessibility Service Declaration (CRITICAL REVIEW RISK)

> [!WARNING]
> **Primary Rejection Risk:** The Google Play Policy Review team enforces strict requirements on `AccessibilityService` declarations. If Google rejects the declaration or requests an appeal that cannot be resolved, **the recommended fallback is to remove `DesktopAccessibilityService` entirely from `AndroidManifest.xml` and ship without it.** AndroidDex is fully functional without it.

### A. Technical Assessment & Boundary
- **What it does:** AndroidDex provides optional desktop taskbar navigation buttons (**Back**, **Home**, and **Recent Apps**). When clicked by the user on the desktop interface, the service calls Android's `performGlobalAction()` (`GLOBAL_ACTION_BACK`, `GLOBAL_ACTION_HOME`, `GLOBAL_ACTION_RECENTS`).
- **What it does NOT do:**
  - Configured with `canRetrieveWindowContent="false"`: It cannot read screen elements, window content, text, or passwords.
  - Configured with `canPerformGestures="false"`: It cannot simulate touches, gestures, or tap coordinates outside the app.
  - Configured with `canRequestTouchExplorationMode="false"`: It does not alter system touch accessibility exploration.
  - Does NOT monitor keystrokes, input events, or user behavior.
- **Independence:** The app operates completely without the service. Mouse navigation, keyboard typing, display streaming, and audio playback do not use or require this service.

### B. In-App Prominent Disclosure Text
Before opening system accessibility settings, the app presents this prominent disclosure:
```
Accessibility Service Notice

AndroidDex uses the Accessibility Service solely to perform system navigation actions (Back, Home, and Recents) when you click the corresponding buttons on the desktop taskbar.

• AndroidDex does NOT inspect, read, or collect your screen content (canRetrieveWindowContent is false).
• AndroidDex does NOT log keystrokes, passwords, or personal data.
• AndroidDex does NOT perform automated gestures (canPerformGestures is false).
• Enabling this service is optional; full desktop streaming and input work without it.
```

### C. Play Console Declaration Form Responses
When completing the Play Console **Accessibility Services Declaration**:

1. **Which core feature requires this service?**
   - *Selection:* Device management / System navigation.
   - *Response:* "AndroidDex provides a multi-window desktop workstation interface streamed to a connected PC. The Accessibility Service is used exclusively to dispatch system navigation actions (Back, Home, and Recent Apps) when the user clicks the corresponding navigation buttons in the desktop taskbar using their PC mouse. The service declares canRetrieveWindowContent='false' and canPerformGestures='false', meaning it cannot read screen content or simulate touch gestures. The service is entirely optional; users can decline enabling it and continue using the full remote desktop experience."

2. **Demonstration Video Link:**
   - URL: `[INSERT PUBLIC YOUTUBE OR DRIVE LINK SHOWING THE DESKTOP TASKBAR BACK/HOME/RECENTS BUTTONS IN ACTION AND THE IN-APP PROMINENT DISCLOSURE]`

---

## 3. Data Safety Form Responses

| Question / Section | Play Console Answer | Technical Justification |
|---|---|---|
| **Does your app collect or share any user data?** | **No** | All processing is ephemeral and strictly peer-to-peer over local USB. |
| **Is all user data encrypted in transit?** | **Yes** | Transport is encrypted using TLS 1.3 over QUIC with mutual key authentication. |
| **Do you provide a way for users to delete their data?** | **Yes** | Tapping "Forget paired PC" purges cryptographic keys; uninstalling removes all app data. |
| **Audio Data (`RECORD_AUDIO`)** | **Not collected / Not shared** | Android's `AudioPlaybackCaptureConfiguration` streams system audio in real-time over the local USB link. Audio is transiently processed in memory and never stored or uploaded. |
| **Personal Info / Device IDs** | **Not collected** | Zero analytics, zero ad tracking, zero telemetry SDKs. |

---

## 4. Foreground Service Declarations & Justifications

### A. `specialUse` (DisplayService) — API 34+ Mandatory Justification
In Google Play Console under **Policy > App content > Foreground service permissions**:

**Selected Subtype:**  
`Virtual display rendering and screen encoding for remote desktop streaming`  
(Matching `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" .../>` in the manifest).

**Play Console Justification Form Answers:**
1. **Describe the user-facing feature:**  
   "AndroidDex turns the Android phone into an interactive desktop environment when connected to an external PC over USB tethering. When active, the application programmatically instantiates an independent Android VirtualDisplay via DisplayManager, renders a multi-window desktop interface using Android's Presentation API, and encodes the resulting graphical surface at 60 FPS using an on-device hardware MediaCodec H.264 encoder for low-latency streaming to the paired PC."

2. **Why cannot a standard foreground service type be used?**  
   "- Not 'mediaProjection': The app does not mirror or capture the physical phone screen. It creates and manages an independent programmatic VirtualDisplay. Because no MediaProjection capture token exists or applies to an app-owned virtual display, mediaProjection is technically and conceptually inapplicable.  
   - Not 'connectedDevice': Under Android 15+ (targetSdk 35/36), connectedDevice requires system device association APIs (CompanionDeviceManager, Bluetooth, USB accessory). DisplayService is an internal graphics rendering and video encoding pipeline without an external hardware device association object.  
   - Not 'dataSync' or 'mediaPlayback': The service performs real-time graphics rendering and H.264 hardware encoding at 60 FPS, not background file synchronization or audio/video playback."

3. **What happens if the service is stopped?**  
   "If this foreground service is interrupted or terminated by the operating system, the VirtualDisplay is instantly destroyed by the Android framework, the hardware MediaCodec instance is torn down, and the user's active desktop session is abruptly aborted, causing loss of unsaved desktop work."

### B. `connectedDevice` (TetheringService)
- **Justification:** Manages, configures, and monitors the physical USB tethering network interface (RNDIS/CDC-ECM) connecting the Android device to the PC.

### C. `mediaProjection` (AudioCaptureService)
- **Justification:** Manages the system `MediaProjection` token required by Android's `AudioPlaybackCaptureConfiguration` API to stream system audio playback to the PC.

---

## 5. Security & Sensitive Capabilities Disclosure (SEC-15)

### Built-in Terminal Shell Execution (`TerminalWindow`)
- **Deliberate Feature:** The desktop interface includes a terminal emulator window (`TerminalWindow`) that launches `/system/bin/sh` via `ProcessBuilder`.
- **Purpose:** Enables power users to run command-line tools within the application's Linux sandbox while using the desktop environment.
- **Security Boundary:** Execution is restricted to the Android application's assigned Linux UID and security context. Only the paired and mutually authenticated PC over the local USB link can send input to the terminal.
- **Documentation:** This capability is documented in `PRIVACY.md` and this submission guide as an intended product feature.

---

## 6. Merged Manifest Permissions Justification Table

| Permission | Source | User-Facing Justification |
|---|---|---|
| `android.permission.POST_NOTIFICATIONS` | App Manifest | Displays required ongoing notifications while desktop streaming and USB tethering foreground services are active (Android 13+). |
| `android.permission.WAKE_LOCK` | App Manifest | Prevents the CPU and display pipeline from sleeping while a remote desktop session is actively streaming over USB. |
| `android.permission.INTERNET` | App Manifest | Enables opening the local QUIC UDP listening socket (port 4433) for high-speed desktop streaming over USB tethering. |
| `android.permission.USE_BIOMETRIC` | App Manifest | Allows on-device biometric authentication (fingerprint/face) to confirm security-sensitive operations (e.g. desktop unlock or pairing reset). |
| `android.permission.FOREGROUND_SERVICE` | App Manifest | Base permission required to run foreground services keeping background streaming and tethering active without OS termination. |
| `android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION` | App Manifest | Required for `AudioCaptureService` to stream system audio playback via Android's `AudioPlaybackCaptureConfiguration`. |
| `android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE` | App Manifest | Required for `TetheringService` to monitor and manage communication over the physical USB tethering link with the connected PC. |
| `android.permission.FOREGROUND_SERVICE_SPECIAL_USE` | App Manifest | Required for `DisplayService` to render the virtual desktop surface and encode H.264 video at 60 FPS (Android 15+). |
| `android.permission.CHANGE_NETWORK_STATE` | App Manifest | Used to detect and adapt to network routing changes when USB tethering is enabled or disabled. |
| `android.permission.RECORD_AUDIO` | App Manifest | Required by Android to capture system audio playback via `AudioRecord` using `AudioPlaybackCaptureConfiguration`. |
| `android.permission.USE_FINGERPRINT` | AndroidX Biometric (Merged) | Legacy biometric compatibility permission for devices running Android 8.1 and earlier. |
| `com.androiddex.host.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX Core (Merged) | Enforces internal signature protection on dynamically registered broadcast receivers. |

---

## 7. Store Listing Placeholders

Before publishing the listing in Google Play Console, replace all bracketed items:

- **App Name:** `AndroidDex Host`
- **Short Description:** `Turn your Android device into a powerful desktop workstation streamed to your PC.`
- **Full Description:** `[INSERT COMPREHENSIVE APP STORE DESCRIPTION HIGHLIGHTING FEATURES, REQUIREMENTS, AND ZERO-CLOUD PRIVACY]`
- **Privacy Policy URL:** `[INSERT PUBLIC HOSTED URL TO PRIVACY.md]`
- **Support Contact Email:** `[INSERT DEVELOPER CONTACT EMAIL]`
- **Publisher Name:** `[INSERT DEVELOPER / ORGANIZATION NAME]`
- **Physical Address:** `[INSERT DEVELOPER POSTAL OR MAILING ADDRESS]`
