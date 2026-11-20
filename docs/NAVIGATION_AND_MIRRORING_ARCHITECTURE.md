# Ampere Nexus Navigation & Screen Mirroring Architecture

This document provides a comprehensive reference of the reverse-engineered navigation and screen mirroring systems from the official Ampere Connect application (`com.greavesmobility.app v2.0.92`), and documents the implementation of **Option A (Turn-by-Turn via BLE)** and **Option B (Full Screen Mirroring via Wi-Fi Hotspot)** in `AmpereNexus`.

---

## 1. Official Ampere Connect Architecture

### 1.1 How Ampere Connect Shares Navigation to the Scooter
Ampere Connect does **not** mirror the Android phone screen. Instead, it embeds the MapMyIndia SDK (`com.mappls.sdk.maps.MapView`) directly into its application view hierarchy:
1. **Virtual Overlay Window:** The app creates a hidden layout (`fakeLayoutOverlay`) attached to Android's `WindowManager` (`MapSnapshotService`).
2. **Snapshot Loop:** A background executor captures an in-memory snapshot of the map view every **200 ms (5 FPS)** (`MapView.snapshot()`).
3. **Resolution & Compression:**
   - Bitmap is scaled to **800 x 360 pixels** (`Bitmap.createScaledBitmap(bmp, 800, 360, true)`), matching the cluster display resolution.
   - Iterative JPEG compression drops quality from 100 down by 5 until file size is $\le$ **50 KB** (51,200 bytes = `0xc800`).
   - Atomic file write: saved to `<filesDir>/www/stream_temp.jpeg` then renamed to `<filesDir>/www/stream.jpeg`.
4. **Local Wi-Fi Hotspot:**
   - `HotspotService` calls Android's `WifiManager.startLocalOnlyHotspot(...)`.
   - Android generates a randomized SSID and WPA2 passphrase.
5. **BLE Wi-Fi Provisioning:**
   - App writes the hotspot credentials to the scooter cluster over BLE (Characteristic `20012202-1234-1234-1234-220201042403`).
6. **Embedded HTTP Server:**
   - App launches an embedded web server (`react-native-static-server` / Lighttpd) on **Port 1880**, serving `<filesDir>/www`.
7. **Scooter Cluster Display:**
   - The scooter connects to the phone's Wi-Fi hotspot as a client.
   - It discovers the phone's gateway IP via DHCP (typically `192.168.43.1` or `192.168.49.1`).
   - The cluster periodically requests `http://<gateway_ip>:1880/stream.jpeg` and renders the frame on screen.

---

## 2. Protocol & BLE Packet Specifications

### 2.1 Wi-Fi Hotspot Provisioning Packet (Phone -> Scooter)
- **Characteristic UUID:** `20012202-1234-1234-1234-220201042403`
- **Structure:** `0x20` + SSID (64 hex chars) + Password (128 hex chars) + `FFFFFF` (100 bytes total).

| Field | Length | Description |
| :--- | :--- | :--- |
| **Opcode** | 1 byte (`0x20`) | Provision Wi-Fi Hotspot Credentials |
| **SSID** | 32 bytes (64 hex chars) | SSID in uppercase ASCII hex, padded on right with `F` to 64 chars |
| **Password** | 64 bytes (128 hex chars) | Passphrase in uppercase ASCII hex, padded on right with `F` to 128 chars |
| **Suffix** | 3 bytes (`FFFFFF`) | Packet end marker |
| **Total** | 100 bytes | Transmitted via Write Request (`0x12`) |

---

### 2.2 Unified 160-Byte Cluster Frame Specification (Phone -> Scooter)
The scooter cluster expects an exact **160-byte frame** (320 uppercase hexadecimal characters) written to Characteristic `20012202-1234-1234-1234-220201042403` using `ATT_WRITE_REQ` (`WRITE_TYPE_DEFAULT`). This unified packet updates the cluster clock, active navigation cues, caller alerts, and music track metadata atomically:

| Byte Offset | Hex Length | Marker / Field | Value Description |
| :--- | :--- | :--- | :--- |
| `0..1` | 4 | Header | `01 02` (Cluster control packet prefix) |
| `2..5` | 8 | Timestamp | 4-byte Little-Endian UNIX epoch timestamp (atomic cluster time sync) |
| `6..7` | 4 | `40` | Direction Maneuver Opcode (`01`=Right, `02`=Left, `03`=Straight, etc., `FF`=Clear) |
| `8..12` | 10 | `41` | Distance to next turn in meters (4-byte LE) |
| `13..17` | 10 | `42` | Total trip remaining distance in meters (4-byte LE) |
| `18..52` | 70 | `43` | Road Name: `FF02` + 19B (38 hex) + `03` + 12B (24 hex) |
| `53..54` | 4 | `49` | GPS Fix status (`01` = Fixed, `FF` / `00` = Searching) |
| `55..56` | 4 | `4F` | Suffix marker (`FF`) |
| `57..83` | 54 | `44` | Caller status & name: `[Status: 2B] + 04 + [Name P1: 38 hex] + 05 + [Name P2: 6 hex]` |
| `84..85` | 4 | `4D` | Missed call count (1 byte hex) |
| `86..87` | 4 | `4E` | Reserved (`00`) |
| `88..113` | 52 | `45` | Song Title: `[Title P1: 22 hex] + 06 + [Title P2: 24 hex] + 00` |
| `114..140` | 54 | `46` | Artist Name: `[Artist P1: 10 hex] + 07 + [Artist P2: 38 hex] + 08` |
| `141..143` | 6 | `4A` | Track playback progress in seconds (2-byte LE) |
| `144..146` | 6 | `4B` | Track total duration in seconds (2-byte LE) |
| `147..148` | 4 | `4C` | Playback State (`01` = Playing, `02` = Paused, `09` = Idle/Default) |
| `149..159` | 22 | Footer | Fixed padding bytes: `390000FFFFFFFFFFFFFFFF` |
| **Total** | **320** | **160 Bytes** | Fixed-length frame accepted by cluster firmware |

---

## 3. Implementation Modes in AmpereNexus

The user can choose between two navigation modes in the application dashboard:

### Option A: Google Maps Turn-by-Turn via BLE (Recommended)
- **Mechanism:** Implements `MapNotificationListenerService` (`NotificationListenerService`).
- **Data Flow:**
  1. Google Maps starts navigation and posts a foreground notification (`com.google.android.apps.maps`).
  2. `MapNotificationListenerService` intercepts the notification extras:
     - Title contains next maneuver direction and distance (e.g. *"In 200 m, Turn right onto MG Road"*).
     - Subtext contains ETA and trip remaining distance (e.g. *"15 min • 4.2 km"*).
  3. Distance is parsed into integer meters.
  4. Maneuver icon is mapped to the cluster's opcode (`0x01` through `0x07`).
  5. Road name is extracted and encoded with `02`/`03` delimiters.
  6. Packet is dispatched over BLE via `BleService`.
- **User Experience:**
  - **Phone can remain locked in rider's pocket.**
  - **Zero battery drain** from Wi-Fi hotspot or screen rendering.
  - No permission prompts per ride.

---

### Option B: Screen Mirroring via Wi-Fi Hotspot & MediaProjection
- **Mechanism:** Implements `ScreenMirroringService` + `MirroringHttpServer`.
- **Data Flow:**
  1. User toggles "Screen Mirroring" in the app.
  2. App requests `MediaProjectionManager.createScreenCaptureIntent()`.
  3. `ScreenMirroringService` starts:
     - Activates `WifiManager.startLocalOnlyHotspot(...)` (or fallback to Personal Hotspot).
     - Sends BLE Packet `0x20` with SSID and Password to scooter.
     - Launches `MirroringHttpServer` on port `1880`.
     - Creates `VirtualDisplay` (800x360 @ 160 dpi) rendering onto `ImageReader`.
  4. Background capture loop grabs frames at 5 FPS (200 ms), downscales/crops to 800x360, compresses JPEG to $\le 50$ KB, and updates `<filesDir>/www/stream.jpeg`.
  5. Scooter pulls the stream from `http://<phone_ip>:1880/stream.jpeg`.
- **User Experience:**
  - Mirrors whatever is on the phone screen (Google Maps app).
  - Best experienced when the phone is mounted on the handlebar in **landscape orientation**.
  - Requires user approval on the Android screen-record prompt.

---

## 4. File References & Architecture Map

- **Turn-by-Turn Notification Listener:** [`MapNotificationListenerService.kt`](file:///c:/Thiru/work/androidRep/AmpereNexus/app/src/main/java/com/thirutricks/nexussmartride/MapNotificationListenerService.kt)
- **Screen Mirroring Service:** [`ScreenMirroringService.kt`](file:///c:/Thiru/work/androidRep/AmpereNexus/app/src/main/java/com/thirutricks/nexussmartride/ScreenMirroringService.kt)
- **Embedded HTTP Streaming Server:** [`MirroringHttpServer.kt`](file:///c:/Thiru/work/androidRep/AmpereNexus/app/src/main/java/com/thirutricks/nexussmartride/MirroringHttpServer.kt)
- **Protocol Packet Builders & Parsers:** [`NexusParser.kt`](file:///c:/Thiru/work/androidRep/AmpereNexus/app/src/main/java/com/thirutricks/nexussmartride/NexusParser.kt)
- **BLE Service Dispatcher:** [`BleService.kt`](file:///c:/Thiru/work/androidRep/AmpereNexus/app/src/main/java/com/thirutricks/nexussmartride/BleService.kt)
- **Dashboard UI & Mode Controller:** [`MainActivity.kt`](file:///c:/Thiru/work/androidRep/AmpereNexus/app/src/main/java/com/thirutricks/nexussmartride/MainActivity.kt)
