# Nexus SmartRide 🛵⚡

> **⚠️ EDUCATIONAL & RESEARCH STUDY ONLY**  
> This project was developed strictly for **educational purposes, reverse engineering (REA) analysis, and AI learning experiments**. It is an independent, non-commercial proof-of-concept exploring Bluetooth Low Energy (BLE) protocol reverse engineering, automotive cluster telemetry decoders, and AI-assisted companion application design.  
> **It is NOT an official product, is NOT affiliated with any vehicle manufacturer, and is NOT intended for commercial distribution or upload to public app stores.**

---

## 🔬 Educational Purpose, REA & AI Learning

This repository was created as an experimental learning laboratory centered around three core technical disciplines:

### 1. Reverse Engineering (REA) & Protocol Analysis
- **HCI Packet Snooping & Protocol Decoding**: Reverse-engineering Bluetooth Low Energy (BLE) GATT attributes, frame delimiters (`0xFF11`, `0xFF12`), byte-level bitmasks, and checksum validations used by modern digital two-wheeler TFT clusters.
- **Automotive Telemetry Reconstruction**: Studying how speed, battery state of charge (SoC), voltage, amp-hour current, odometer deltas, and turn-by-turn navigation packets are framed, synchronized, and serialized over radio interfaces.
- **Hardware Button Event Interception**: Analyzing how handlebar media and call button control bytes (`0x01`, `0x02`, etc.) are routed through the Bluetooth protocol stack to control smartphone media and telephony.

### 2. AI-Assisted Architecture & Neural Copilot Exploration
- **Agentic AI & REA Tooling**: Exploring modern AI-driven code generation, reverse-engineering decompilation analysis, and intelligent refactoring workflows.
- **On-Device Machine Learning & Heuristic Clustering**: Implementing intelligent trip clustering algorithms to group fragmented micro-rides, detect charging boundary events, and filter GPS/odometer drift.
- **Predictive Telemetry Coaching**: Exploring how multimodal and generative LLMs can consume raw vehicle time-series telemetry to provide real-time eco-riding advice, energy efficiency calculations, and range estimation.

### 3. Open Telemetry & Interoperability Research
- Investigating open standards for personal vehicle diagnostics, enabling vehicle owners to view and understand their own vehicle metrics without proprietary vendor lock-in.

---

## 🚀 Features

* 📡 **Stable Bluetooth Low Energy (BLE) Connection**: Optimized reconnection watchdog and sequential descriptor management.
* 🔋 **Real-Time Battery & Vehicle Telemetry**: State of Charge (SoC), speed, odometer delta, trip distance, DTE range, voltage, and current.
* 📈 **Dual-Axis Ride Cadence Analytics**: Visualizing daily travel distance (km bars) overlaid with daily battery consumption (SoC % cubic curve).
* ⏱️ **Cluster Clock Synchronization**: Accurate time sync with the vehicle's embedded dashboard.
* 📞 **Incoming & Missed Call Sync**: Real-time incoming caller ID and contact name on cluster; missed call count badge sync.
* 🎛️ **Handlebar Call Controls**: Answer and reject incoming phone calls directly using scooter handlebar buttons.
* 🎵 **Media & Song Info Sync**: Live playback metadata (track title, artist name, progress) streamed to vehicle display.
* ⏯️ **Handlebar Media Controls**: Play, pause, skip next, and previous track controlled via scooter handlebar buttons.
* 🧭 **Turn-by-Turn Navigation Cues**: Google Maps turn cues listener over BLE.
* 🖥️ **Screen Mirroring / Hotspot Streaming**: Wi-Fi hotspot screen streaming architecture for scooter display.
* 🛠️ **Service & Maintenance Lifecycle Tracker**: Interactive maintenance checklist and inspection intervals based on vehicle service documentation.

---

## 🔧 Connectivity & Architecture

### High Priority Connection
Uses optimized BLE connection parameters to maintain stable communication and reduce timeouts.

### Smart Auto-Reconnect & Watchdog Recovery
Automatically restores connection when the vehicle is in range and detects stalled sessions to safely reinitialize communication without manual app intervention.

### Sequential Descriptor Handling
Prevents BLE congestion by managing descriptor writes in a controlled queue.

### Foreground Service
Maintains persistent Bluetooth connection without interruption from system background execution limits.

---

## ⚙️ Building & Local Testing

1. Open the project in **Android Studio**
2. Sync Gradle dependencies (`Java 17` / Android Gradle Plugin)
3. Build the debug or release APK:
   ```bash
   ./gradlew assembleDebug
   ```
4. Install on your personal test device:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
5. Grant Bluetooth, Nearby Devices, and Notification listener permissions when prompted.

---

## 📊 Telemetry Data Model

| Field | Description | Protocol / Source |
|---|---|---|
| Speed | Current vehicle speed (km/h) | Live Frame (`0xFF11`) |
| Battery Voltage | Battery voltage level (V) | Live Frame (`0xFF11`) |
| Current | Charge / discharge current (A) | Battery Frame (`0xFF12`) |
| State of Charge | Battery percentage (0–100%) | Battery Frame (`0xFF12`) |
| Odometer | Lifetime distance traveled (km) | Cluster Odo Delta |
| Temperature | Controller & battery temperature | Telemetry Sensor Frame |
| Error Code | Cluster diagnostic indicators | Diagnostic Frame |

---

## 🔐 Privacy & Local-First Philosophy

* **No Mandatory Accounts**: The core Bluetooth connection, handlebar controls, call/media sync, and navigation mirror operate 100% locally on your phone.
* **Isolated Cloud Telemetry**: Optional cloud sync features isolate vehicle records using cryptographically hashed device identifiers (`SHA-256(MAC + SALT)`), ensuring zero plain-text hardware identifiers are exposed.
* **No Third-Party Analytics**: No third-party ad networks, trackers, or commercial monetization SDKs are embedded.

---

## ⚠️ Legal Notice & Disclaimers

1. **Non-Affiliation**: This project is completely independent and has **no affiliation, association, authorization, endorsement by, or any official connection with Greaves Electric Mobility Ltd., Ampere Vehicles, or any of their subsidiaries or affiliates**.
2. **Trademarks**: The names "Ampere", "Nexus", and related trademarks, trade names, emblems, and images are registered trademarks of their respective owners. Their mention in this repository is strictly for identification, nominative reference, and educational research purposes under fair use.
3. **No Commercial Intent**: This software is strictly non-commercial. It is **not sold, monetized, or offered as a commercial service, and it is not intended for distribution via the Google Play Store or any commercial marketplace**.
4. **Educational & Personal Use Only**: Intended solely for personal research by the individual vehicle owner on their own hardware. The software does not modify ECU firmware, disable safety mechanisms, or bypass vehicle security systems.
5. **No Warranty**: This software is provided "AS IS", without warranty of any kind, express or implied. Operating an electric vehicle carries inherent risks. The developers accept no liability for vehicle performance, warranty status, battery health, or safety outcomes. Never interact with the application while operating a vehicle.

---

## 📄 License

MIT License. See [LICENSE](LICENSE) for details.
