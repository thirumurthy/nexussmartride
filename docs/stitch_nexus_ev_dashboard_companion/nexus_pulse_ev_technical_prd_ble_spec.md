# Engineering Specification & Product Requirements Document (PRD)
**Project:** Nexus Pulse EV Mobile Application  
**Target Hardware:** Ampere Nexus Electric Scooter (EX v4.2 / 7" TFT Cluster)  
**Partner / Engineering Team:** Antigravity  
**Document Version:** 1.0.0  
**Status:** Ready for Engineering Review  

---

## 1. Executive Summary & Architecture Overview

The **Nexus Pulse EV** companion application is a high-performance, real-time telemetry and vehicle control suite designed for the Ampere Nexus electric scooter ecosystem. It interfaces with the vehicle through **Bluetooth Low Energy (BLE 5.2)** for real-time cluster telemetry, handlebar media control, and turn-by-turn navigation mirroring, supplemented by asynchronous **Cloud Telemetry APIs** for fleet analytics, trip logging, and firmware management.

### Recommended Technology Stack
- **Framework:** Flutter (or React Native with native C++/JNI BLE modules) / Kotlin Jetpack Compose (Android) & Swift/SwiftUI (iOS).
- **BLE Engine:** Reactive BLE / CoreBluetooth / Android BluetoothGatt with automated reconnect exponential backoff.
- **Design System Tokens:** `Nexus Cyber-Automotive` (Dark Slate `#10131a`, Cyber Cyan `#00e5ff`, Emerald `#10b981`, Amber `#f59e0b`, Space Grotesk / Inter typography).

---

## 2. Core Feature & Screen Breakdown

The mobile application architecture consists of a persistent native header, a 5-tab docked shell, and an interactive modal system.

### 2.1 Fixed App Shell
- **Header Chrome:**
  - Status bar mirroring phone battery and cellular state.
  - Scooter identifier tag: `NEX_B9ABF` | Hardware firmware release tag: `EX v4.2`.
  - Live vehicle status badge: `Connected` (Green pulse) / `Connecting...` (Amber pulse) / `Disconnected` (Muted red). Tap opens quick BLE scan modal.
- **Bottom Navigation Dock (5 Tabs):**
  1. `Home`: Hero HUD, today's ride telemetry, quick app launchers (YouTube/Music, Google Maps), energy pack & AI projection, ownership savings, TFT cluster controls, subsystem health.
  2. `Trips`: Cadence charts (3D / 7D / 30D / 1Y aggregation), latest ride velocity/elevation curves, recent journeys log with mode tag & efficiency.
  3. `Insights`: Nexus AI Copilot, Eco Score gauge (0–100), energy consumption vs. regen recapture breakdown, speed distribution, ride coaching tips.
  4. `Scooter`: Remote controls (BLE lock/unlock, boot release, horn chirp/locate, headlamp flash), LFP 16-cell voltage delta matrix, full diagnostics scan, service schedule tracker.
  5. `Settings`: Riding mode configuration (Eco / City / Power), regenerative braking tuning slider, reverse crawl & hill-hold toggles, BLE pairing manager, OTA firmware check, GPX/CSV export.

---

## 3. BLE (Bluetooth Low Energy) Specification

### 3.1 Connection Parameters
- **BLE Version:** 5.2
- **Device Advertisement Name:** `NEXUS-XXXX` (where `XXXX` is the last 4 characters of the vehicle MAC/ID, e.g. `NEXUS-9ABF`).
- **Connection Interval:** 15ms – 30ms (Active Ride Mode) / 100ms – 250ms (Idle / Background Mode).
- **Slave Latency:** 0 (during active navigation streaming).
- **Supervision Timeout:** 5000 ms.
- **Security:** Security Mode 1, Level 2 (Unauthenticated pairing with encryption / PIN passkey or Just Works).

### 3.2 Service & Characteristic UUID Matrix

```
Base Custom Service UUID: 0000FF00-0000-1000-8000-00805F9B34FB
```

| Service | UUID | Characteristic | UUID | Properties | Description & Payload Format |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Telemetry Service** | `0xFF10` | Vehicle Live State | `0xFF11` | `NOTIFY`, `READ` | 16-byte packet: Speed, Odometer, SoC, DTE, Mode, Status Flags |
| | | Battery & Thermal | `0xFF12` | `NOTIFY`, `READ` | 14-byte packet: Pack Voltage, Current (A), Temp, Cell Delta |
| **Control Service** | `0xFF20` | Remote Command | `0xFF21` | `WRITE` | Executable commands: Lock, Unlock, Trunk, Locate, Flash |
| | | Command Ack | `0xFF22` | `NOTIFY` | Command status, error codes, sequence acknowledgement |
| **Cluster & Nav** | `0xFF30` | TBT Navigation Packet| `0xFF31` | `WRITE_NO_RESP`| Maneuver icon ID, Distance to turn, Road name string (UTF-8) |
| | | Cluster Mirror State | `0xFF32` | `READ`, `WRITE`| Mirroring mode: 0=Off, 1=TBT BLE, 2=Wi-Fi Screen Cast |
| **Media & Audio** | `0xFF40` | Media Metadata | `0xFF41` | `WRITE` | Track Title (24B), Artist (16B), App Source ID (1B) |
| | | Handlebar Remote In | `0xFF42` | `NOTIFY` | 1-byte key events: Play/Pause (0x01), Next (0x02), Prev (0x03) |

---

### 3.3 Packet Structure & Byte Layout

#### 3.3.1 Vehicle Live State Packet (`0xFF11`) – Rate: 5 Hz (Active Ride)
```c
struct __attribute__((packed)) VehicleLivePacket {
    uint8_t  header;          // 0xAA (Packet sync)
    uint16_t speed_kmh_x10;   // Big-Endian: e.g. 245 = 24.5 km/h
    uint32_t odometer_m;      // Total distance in meters
    uint8_t  soc_percentage;  // 0 - 100%
    uint16_t dte_range_km;    // Cluster Estimated Distance to Empty
    uint8_t  ride_mode;       // 0x00=ECO, 0x01=CITY, 0x02=POWER, 0x03=REVERSE
    uint8_t  status_flags;    // Bit 0: Locked/Unlocked
                              // Bit 1: Side Stand Down
                              // Bit 2: Headlamp High Beam
                              // Bit 3: Brake Pressed
                              // Bit 4: Motor Cutoff Active
                              // Bit 5: Charging Cable Connected
    uint8_t  crc8;            // CRC-8 validation
};
```

#### 3.3.2 TBT Navigation Stream Packet (`0xFF31`) – Event-driven / 1 Hz
```c
struct __attribute__((packed)) TBTNavPacket {
    uint8_t  packet_seq;      // Monotonically increasing sequence number
    uint8_t  maneuver_code;   // 0x01=Straight, 0x02=Turn Left, 0x03=Turn Right,
                              // 0x04=Fork Left, 0x05=Fork Right, 0x06=Roundabout,
                              // 0x07=Destination
    uint16_t distance_meters; // Distance to next instruction
    uint8_t  eta_hours;       // Remaining travel time hours
    uint8_t  eta_minutes;     // Remaining travel time minutes
    char     road_name[28];   // Null-terminated UTF-8 road name string
    uint8_t  crc8;
};
```

#### 3.3.3 Remote Command Packet (`0xFF21`)
```c
struct __attribute__((packed)) RemoteCommandPacket {
    uint8_t  cmd_id;          // 0x10=LOCK, 0x11=UNLOCK, 0x20=BOOT_RELEASE,
                              // 0x30=LOCATE_CHIRP, 0x40=FLASH_HAZARDS
    uint32_t auth_token;      // Session rotating cryptographic token
    uint16_t duration_ms;     // e.g., chirp duration (default 3000ms)
};
```

---

## 4. Deep Linking & Third-Party App Integrations

### 4.1 Music Player Interfacing (Default: YouTube / YouTube Music)
1. **Installed Media Detection (Android / iOS):**
   - Query PackageManager on Android for:
     - `com.google.android.youtube` (YouTube)
     - `com.google.android.apps.youtube.music` (YouTube Music)
     - `com.spotify.music` (Spotify)
     - `com.apple.android.music` (Apple Music)
2. **Deep Launch Action:**
   - Launch default or selected player via native intents:
     - YouTube: `Intent.ACTION_VIEW, Uri.parse("https://youtube.com")` or package launch intent.
     - Spotify: `spotify:browse` or `Intent(ACTION_MAIN)`.
3. **Media Session Listener (Handlebar Media Controls):**
   - Register native `NotificationListenerService` / `MediaSessionManager` to read the active playing track, artist name, and playback state.
   - Forward track metadata packet to BLE Characteristic `0xFF41`.
   - On reception of Handlebar Key Event (`0xFF42`), dispatch `KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE`, `KEYCODE_MEDIA_NEXT`, or `KEYCODE_MEDIA_PREVIOUS`.

### 4.2 Google Maps Navigation & Real-time Location Sharing
1. **Google Maps Launcher:**
   - Deep link syntax:
     - Android: `google.navigation:q={destination_lat},{destination_lng}&mode=d`
     - iOS: `comgooglemaps://?daddr={destination_lat},{destination_lng}&directionsmode=driving`
     - Fallback: `https://maps.google.com/?daddr={destination}`
2. **Turn-by-Turn Maneuver Scraping / Google Navigation SDK:**
   - Option A (Native Notification Hook): Capture turn directions from active Google Maps foreground notification channel (`com.google.android.apps.maps`) to extract maneuver icons and distance.
   - Option B (Google Navigation SDK): Embedded turn-by-turn routing emitting structured maneuver updates directly to `0xFF31`.
3. **Live GPS Coordinate Sharing:**
   - Generate temporary trackable URL (e.g. `https://pulse.nexus.ev/track/{trip_id}?token={auth_key}`).
   - Utilize native Share Sheet (`Share.share(...)`) to send coordinates or live tracking link to WhatsApp, SMS, or Telegram.

---

## 5. UI/UX Asset & Token Inventory

All screens have been prototyped and validated in Stitch. The following items can be exported directly for engineering:

- **Source Code Base:** Standalone single-page responsive application with complete CSS glassmorphism, responsive flex-grid cards, and SVG icons.
- **Color Palette Variables:**
  - `surface-base`: `#10131a`
  - `surface-card`: `#191c22`
  - `surface-card-sub`: `#0b0e14`
  - `primary-cyan`: `#00e5ff` (glow: `rgba(0, 229, 255, 0.35)`)
  - `status-emerald`: `#10b981` (Eco / Connected / Battery optimal)
  - `status-amber`: `#f59e0b` (Caution / Low SoC warning)
- **High-Resolution Visual Render:**
  - Scooty Hero Graphic: Matte black Ampere Nexus cyber scooter with illuminated cyan LED headlights on dark asphalt.
- **Export Formats:** HTML5 / Tailwind source files and design system tokens can be extracted via the canvas inspection panel.

---

## 6. Implementation Phasing & Milestones

| Sprint | Focus Area | Deliverables |
| :--- | :--- | :--- |
| **Phase 1** | BLE Stack & GATT Setup | Device discovery, pairing handshake, Telemetry notification parsing (`0xFF11`, `0xFF12`). |
| **Phase 2** | UI Framework & Multi-tab Shell | Implementation of 5 tabs with design tokens, responsive cards, and offline telemetry caching. |
| **Phase 3** | Cluster TBT & Media Sync | Turn-by-Turn navigation packet forwarding to TFT cluster, notification listener for YouTube/Spotify, handlebar remote key handling. |
| **Phase 4** | Remote Controls & Diagnostics | Encrypted remote lock/unlock, LFP cell balance matrix visualizer, maintenance booking API integration. |
| **Phase 5** | Field Validation & QA | Range estimation validation (Cluster vs. AI model), BLE reconnect stress testing, low-battery fail-safes. |
