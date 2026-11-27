# Nexus Pulse EV — Production Export & Repository Zip Manifest
**Package:** `nexus-pulse-ev-release-v1.0.0.zip`  
**Target Hardware:** Ampere Nexus Electric Scooter (EX v4.2 / 7" TFT Cluster)  
**Target Engineering Team:** Antigravity / Mobile Core Team  
**Date of Generation:** 2026-11-22  
**Status:** Ready for Engineering Unpack & CI/CD Pipeline Integration  

---

## 🗂 Complete Archive File Tree & Layout

```text
nexus-pulse-ev-release-v1.0.0.zip
├── README.md                                  # Quickstart, project overview & build instructions
├── docs/                                      # Engineering specifications and BLE protocols
│   ├── PRD_BLE_SPECIFICATION.md               # Complete PRD & reverse-engineered BLE 5.2 GATT spec
│   ├── GATT_SERVICES_REFERENCE.json           # Raw JSON listing of all 0xFF10-0xFF40 BLE characteristics
│   └── NATIVE_INTENT_INTEGRATIONS.md          # Intent URI schemes for YouTube, Spotify & Google Maps TBT
├── design/                                    # UI tokens, color palettes & typographic scales
│   ├── tokens.json                            # W3C / Style Dictionary standard design tokens
│   ├── tailwind.config.js                     # Ready-to-use Tailwind theme extension file
│   └── DESIGN_SYSTEM.md                       # Nexus Cyber-Automotive system specification
├── web_prototype/                             # Interactive, standalone prototype reference
│   ├── index.html                             # Full 5-tab reactive application (HTML5 + vanilla JS tabs)
│   ├── css/
│   │   └── glassmorphism.css                  # Custom cyber HUD neon glow & backdrop blur styles
│   └── js/
│       └── app.js                             # Tab controller, cluster BLE mock & navigation handlers
├── assets/                                    # High-resolution production visual assets
│   ├── scooter_hero_cyber_twilight.png        # Photorealistic Ampere Nexus matte black scooter render
│   └── icons/                                 # Vector iconography set (SVG format)
│       ├── icon_battery.svg
│       ├── icon_navigation.svg
│       ├── icon_speedometer.svg
│       ├── icon_eco_leaf.svg
│       ├── icon_ble_pulse.svg
│       ├── icon_music_sync.svg
│       └── icon_settings.svg
└── schemas/                                   # Native C & Dart/TS data models
    ├── vehicle_packets.h                      # Packed C struct definitions for live telemetry & TBT
    └── VehicleModels.dart                     # Flutter data classes & binary parser helpers
```

---

## 📦 File-by-File Manifest & Checksums

| File Path in Zip | Format | Size Est. | Purpose & Contents |
| :--- | :--- | :--- | :--- |
| `/README.md` | Markdown | ~4.2 KB | Project introduction, directory guide, npm/flutter quickstart steps. |
| `/docs/PRD_BLE_SPECIFICATION.md` | Markdown | ~16.8 KB | Full engineering requirements, packet definitions, BLE 5.2 parameters, 5-sprint roadmap. |
| `/docs/GATT_SERVICES_REFERENCE.json` | JSON | ~3.5 KB | Raw UUID definitions for Telemetry (`0xFF10`), Remote Control (`0xFF20`), Cluster Nav (`0xFF30`), Media (`0xFF40`). |
| `/docs/NATIVE_INTENT_INTEGRATIONS.md` | Markdown | ~5.1 KB | Deep link syntax (`com.google.android.youtube`, `spotify:`, `google.navigation:q=`), NotificationListener hooks. |
| `/design/tokens.json` | JSON | ~8.4 KB | W3C / Style Dictionary tokens: Surfaces (`#10131a` to `#32353c`), Cyan (`#00e5ff`), Emerald (`#10b981`), Amber (`#f59e0b`), Space Grotesk type scale. |
| `/design/tailwind.config.js` | JavaScript | ~2.6 KB | Exported theme extension mapping custom colors, border radii, and cyber drop shadows for Tailwind CSS. |
| `/web_prototype/index.html` | HTML5 | ~42.0 KB | Complete self-contained single-page multi-tab prototype (`{{DATA:SCREEN:SCREEN_5}}`) showcasing all 5 screens. |
| `/assets/scooter_hero_cyber_twilight.png` | PNG | ~1.4 MB | High-res transparent render of the Ampere Nexus matte black cyber scooter (`{{DATA:IMAGE:IMAGE_23}}`). |
| `/schemas/vehicle_packets.h` | C Header | ~3.8 KB | Big-Endian byte-packed structs (`VehicleLivePacket`, `TBTNavPacket`, `RemoteCommandPacket`, CRC-8 helper). |
| `/schemas/VehicleModels.dart` | Dart | ~6.2 KB | Typed models for Flutter/React Native decoding raw BLE byte buffers into reactive state observables. |

---

## 🛠 Direct Extraction & Implementation Guide

### 1. Frontend / UI Team (Flutter / React Native / Web)
- Import `/design/tokens.json` into your design token transformer (Style Dictionary or custom Flutter Theme Builder).
- Use `/web_prototype/index.html` as the interactive visual reference for spacing, tab state toggles, and glassmorphic card layouts.
- Place `/assets/scooter_hero_cyber_twilight.png` in the app's `assets/images/` bundle.

### 2. Firmware & BLE Core Engineers (Antigravity)
- Drop `/schemas/vehicle_packets.h` directly into your C/C++ firmware or native Android JNI wrapper.
- Verify packet sequence headers (`0xAA`) and CRC-8 checksum calculation against characteristic `0xFF11`.
- Refer to `/docs/PRD_BLE_SPECIFICATION.md` for active ride connection intervals (15–30 ms) and navigation mirroring logic.

### 3. Native App Integrations (Android / iOS)
- Configure Android Manifest and iOS `Info.plist` queries as specified in `/docs/NATIVE_INTENT_INTEGRATIONS.md` to support deep linking to YouTube, Spotify, and Google Maps Turn-by-Turn broadcasting.

---

## 🏁 Archive Checksum & Signature
- **Archive File Name:** `nexus-pulse-ev-release-v1.0.0.zip`
- **Specification Version:** 1.0.0-final
- **Target Release:** Q1 Production Firmware (EX v4.2)
