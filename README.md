# Nexus SmartRide 🛵⚡

**Nexus SmartRide** is an unofficial, open-source Android app that connects to your EV scooter via Bluetooth to provide reliable connectivity, real-time data insights, and enhanced ride information.

---

## 🚀 Features

* 📡 Stable Bluetooth Low Energy (BLE) connection
* 🔋 Real-time battery and vehicle telemetry (SoC, speed, odometer, trip distance, DTE range, voltage, current)
* 📊 Ride data and performance insights
* ⏱️ Sync vehicle display time with your device
* 📞 **Incoming & Missed Call Sync**: Real-time incoming caller ID and contact name on cluster; missed call count badge sync
* 🎛️ **Handlebar Call Controls**: Answer and reject incoming phone calls directly using scooter handlebar buttons
* 🎵 **Media & Song Info Sync**: Live playback metadata (track title, artist name, progress) streamed to vehicle display
* ⏯️ **Handlebar Media Controls**: Play, pause, skip next, and previous track controlled via scooter handlebar buttons
* 🧭 **Turn-by-Turn Navigation Cues**: Google Maps turn cues listener over BLE
* 🖥️ **Screen Mirroring / Hotspot Streaming**: Wi-Fi hotspot screen streaming architecture for scooter display
* 🔁 Automatic reconnection and session recovery

---

## 🧠 Why This App

This project focuses on improving Bluetooth connection stability and providing a smoother, more consistent experience when interacting with vehicle data.

Key improvements include:

* Reliable reconnection handling
* Optimized BLE communication intervals
* Structured and controlled data exchange
* Background-safe operation

---

## 🔧 Connectivity Improvements

### High Priority Connection

Uses optimized BLE connection parameters to maintain stable communication and reduce timeouts.

### Smart Auto-Reconnect

Automatically restores connection when the device is back in range.

### Watchdog Recovery

Detects stalled connections and safely reinitializes communication.

### Sequential Descriptor Handling

Prevents BLE congestion by managing descriptor writes in a controlled sequence.

### Foreground Service

Maintains persistent Bluetooth connection without interruption from system background limits.

---

## ⚙️ Setup

1. Open the project in **Android Studio**
2. Sync Gradle and build the project
3. Install the app on your device
4. Grant Bluetooth permissions when prompted
5. Tap **Scan & Connect** to discover your scooter
6. Once connected, the device is saved for automatic reconnection

---

## 📡 Device Communication

* Connects via Bluetooth Low Energy (BLE)
* Reads telemetry data exposed by the vehicle
* Maintains connection using optimized parameters

---

## 📊 Data Overview

| Field           | Description              |
| --------------- | ------------------------ |
| Speed           | Current vehicle speed    |
| Battery Voltage | Battery voltage level    |
| Current         | Charge/discharge current |
| State of Charge | Battery percentage       |
| Temperature     | System temperature       |
| Status          | Device state information |
| Error Code      | Diagnostic indicator     |

---

## 🔐 Privacy

* No user accounts required
* No data is sent to external servers
* All communication happens locally via Bluetooth

---

## ⚠️ Disclaimer

This is an unofficial, independent project and is not affiliated with, endorsed by, or associated with Ampere Vehicles or any of its subsidiaries.

This application provides access to certain Bluetooth-based features and telemetry data available from the vehicle. Some features may interact with non-critical vehicle settings (such as time synchronization or display-related information). The application does not modify firmware, bypass security mechanisms, or interfere with core vehicle safety systems.

This software is provided "as is", without any warranties or guarantees of any kind, express or implied, including but not limited to accuracy, reliability, or fitness for a particular purpose.

By using this application, you acknowledge and agree that you are doing so entirely at your own risk. The developer shall not be held responsible or liable for any direct, indirect, incidental, or consequential damages, including but not limited to vehicle issues, incorrect data display, navigation errors, device malfunction, data loss, personal injury, or any other consequences arising from the use or misuse of this application.

Users are solely responsible for ensuring safe usage of this application. Do not interact with the application while riding or in any situation that may cause distraction or unsafe conditions.

---

## 🏷️ Stable Releases & Tags

* **`v1.0.3`** (or **`1.0.3`**): **Verified Stable Release**
  * Fully tested and verified working features:
    * 📞 **Missed Call Counter**: Live counter syncs to the scooter dashboard.
    * 📲 **Incoming Calls**: Contact name & caller ID alert on the cluster; handlebar button actions to answer (`0x01`) and reject (`0x02`) calls.
    * 🎵 **Song & Media Info**: Track title, artist name, and playback progress streamed to cluster; handlebar media actions (play/pause/next/prev).
    * ⚡ **Telemetry & Time Sync**: Accurate SoC %, speed, trip meters, range, and system clock updates.
  * If any regressions or issues occur during future development, restore or checkout this tag:
    ```bash
    git checkout v1.0.3
    # or
    git checkout 1.0.3
    ```

---

## 🧩 Project Status

This is an experimental and actively evolving project. Features may change or improve over time.

---

## 🤝 Contributing

Contributions are welcome. Feel free to open issues or submit pull requests.

---

## 📄 License

MIT License

---

## ⭐ Support

If you find this project useful, consider giving it a star ⭐
