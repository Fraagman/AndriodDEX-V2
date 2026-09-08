# Privacy Policy for AndroidDex

**Effective Date:** [INSERT EFFECTIVE DATE - e.g. September 8, 2026]  
**Developer / Publisher:** [INSERT DEVELOPER / ORGANIZATION NAME]  
**Contact Email:** [INSERT DEVELOPER CONTACT EMAIL]  
**Physical / Mailing Address:** [INSERT DEVELOPER POSTAL OR MAILING ADDRESS]  

---

## 1. Introduction & Privacy Philosophy

AndroidDex ("the App", "we", "us") is an open-source desktop experience solution designed to provide a multi-window desktop interface streamed directly to a personal computer over a local USB tethering connection.

We operate under a strict, local-first privacy model:
- **Zero Telemetry:** The App contains no analytics, metrics, crash reporting, tracking SDKs, or advertising libraries.
- **Zero Cloud Servers:** We do not operate, connect to, or maintain any external servers. No data is ever uploaded to us or any third party.
- **Peer-to-Peer Local Operation:** All communication occurs strictly between the Android device and the physically connected PC over the local USB tethering network interface (RNDIS/CDC-ECM).

---

## 2. Information Handled by the Application

### A. Screen Display and Video Streaming
- **What is processed:** The App creates an internal Android `VirtualDisplay` and renders a desktop presentation interface. The composited display frames are encoded in real-time via the on-device hardware `MediaCodec` H.264 encoder.
- **Where it goes:** Video packets are streamed directly over an encrypted peer-to-peer QUIC connection to the paired PC.
- **Retention:** Video frames are processed purely in-memory and are immediately discarded. No video frames, screenshots, or display data are ever written to disk or transmitted to any remote server.

### B. Device Audio Playback
- **What is processed:** When enabled by the user, the App captures system audio playback using Android's standard `AudioPlaybackCaptureConfiguration` API (`RECORD_AUDIO` permission).
- **Where it goes:** Raw audio PCM frames are streamed directly over the local encrypted QUIC connection to the paired PC for playback through the PC's speakers or headphones.
- **Retention:** Audio streams are processed strictly in-memory and are never recorded, saved to storage, or transmitted off the local device.

### C. Mouse and Keyboard Input
- **What is processed:** The paired PC sends mouse movement, mouse clicks, keyboard keystrokes, and scroll events to the App over the local QUIC connection.
- **How it is used:** The App dispatches these input events directly into the AndroidDex desktop window manager (`LocalInputDispatcher`).
- **Retention:** Input events are processed instantaneously in real-time. No keystroke logging, credential harvesting, or input history is maintained or recorded.

### D. Cryptographic Pairing Credentials
- **What is stored:** To provide authenticated communication, the App generates and stores:
  1. An Ed25519 TLS identity and self-signed certificate for QUIC transport encryption.
  2. An authenticated pre-shared key (PSK) established during mutual pairing via X25519 Elliptic-Curve Diffie-Hellman (ECDH) and authenticated by user comparison of a 6-digit Short Authentication String (SAS).
- **Storage and Security:** Credentials are saved exclusively in the App's protected internal sandbox (`/data/data/com.androiddex.host/files/quic_server/`).
- **Backup Exclusion:** The App's manifest and backup configuration rules (`dataExtractionRules.xml` and `backup_rules.xml`) explicitly mark this directory as excluded from Android cloud backups and adb backups.
- **User Control:** Users can permanently delete stored pairing keys at any time by tapping **Forget paired PC** in the App's interface.

---

## 3. Sensitive Device Capabilities & Disclosures

### A. Terminal Shell Execution (SEC-15 Disclosure)
- **Deliberate Feature:** AndroidDex includes a built-in terminal utility (`TerminalWindow`) within the desktop interface that invokes the Android system shell (`/system/bin/sh`) via `ProcessBuilder`.
- **Capability:** This capability allows the user on the paired PC to execute command-line utilities within the App's Android sandbox under the App's Linux UID.
- **Security Implications:** This is a deliberate power-user feature. Users should understand that anyone with access to the paired PC has command execution capabilities equivalent to the App's permissions on the Android device. This access is restricted to the mutually authenticated local link and cannot be accessed over external networks.

### B. Accessibility Service (`DesktopAccessibilityService`)
- **Limited Purpose:** AndroidDex offers an optional Accessibility Service whose sole function is to execute system navigation actions (**Back**, **Home**, and **Recent Apps**) when clicked from the desktop taskbar.
- **Strictly Restricted Scope:**
  - `canRetrieveWindowContent="false"`: The service CANNOT inspect, read, or access text or contents of any window on screen.
  - `canPerformGestures="false"`: The service CANNOT simulate touch gestures or inject arbitrary touches into external apps.
  - `canRequestTouchExplorationMode="false"`: The service does NOT modify touch exploration.
- **Optional Nature:** The App operates with full display, audio, mouse, and keyboard capabilities even if this service is disabled.

---

## 4. Third-Party Data Sharing & Transfers

- **No Third-Party Sharing:** AndroidDex does not sell, rent, license, or transmit user information to any third parties, advertising networks, or analytics services.
- **No Cloud Services:** The App does not interact with any cloud backend or remote servers.

---

## 5. User Data Rights and Controls

- **Revoking Pairing:** Tapping **Forget paired PC** deletes all stored cryptographic credentials immediately from local storage.
- **Permissions Revocation:** Users may grant or revoke the `RECORD_AUDIO`, `POST_NOTIFICATIONS`, or Accessibility Service permissions at any time via Android System Settings.
- **Data Deletion:** Uninstalling AndroidDex permanently purges all local data, configuration files, and keys from the device.

---

## 6. Developer Contact Information

If you have questions, feedback, or inquiries regarding this Privacy Policy, please contact:

- **Entity / Developer:** [INSERT DEVELOPER / ORGANIZATION NAME]
- **Email:** [INSERT DEVELOPER CONTACT EMAIL]
- **Mailing Address:** [INSERT DEVELOPER POSTAL OR MAILING ADDRESS]
- **Source Repository:** [INSERT PUBLIC SOURCE CODE REPOSITORY URL]
