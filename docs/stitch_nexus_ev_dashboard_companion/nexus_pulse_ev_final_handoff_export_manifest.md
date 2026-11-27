# Nexus Pulse EV — Complete Handoff & Export Manifest (Final)
**Project:** Nexus Pulse EV Companion Application  
**Target Hardware:** Ampere Nexus Electric Scooter (EX v4.2 / 7" TFT Cluster)  
**Engineering Handover:** Ready for Production  
**Design System:** Nexus Cyber-Automotive (`DARK` mode / Cyan `#00e5ff`)

---

## 📦 Master Deliverables Package Index

### 1. Interactive Master Prototype
- **Master Screen:** `{{DATA:SCREEN:SCREEN_4}}` ("Nexus Pulse EV Interactive App Suite")
  - Complete single-page multi-tab reactive application with vanilla JavaScript tab controller.
  - Zero external UI library dependencies (built on standard Tailwind CSS utilities + custom glassmorphic styling).
  - Includes all 5 active tabs:
    1. **Home:** Scooter Hero with foreground live riding telemetry (18.6 km, avg speed 24 km/h, ride time 38 mins), YouTube / Music player launcher with 3-dot app selector, Google Maps launcher & GPS share card, 7-day cadence charts, Power Pack & AI prediction, Ownership savings, and Cluster TBT sync controls.
    2. **Trips:** Cadence trend bars (3D / 7D / 30D / 1Y), latest ride velocity/elevation profiles, recent trip log cards with mode tags & efficiency.
    3. **Insights:** Nexus AI Copilot, Eco Score gauge (95/100 Eco Master), energy dynamics vs. regen recapture, speed distribution breakdown.
    4. **Scooter:** BLE remote lock/unlock, boot release, headlamp flash, horn ping, LFP 16-cell voltage delta matrix, full diagnostics suite.
    5. **Settings:** Riding mode tuning (Eco / City / Power), regen braking slider, reverse crawl & hill-hold toggles, BLE pairing manager, OTA firmware updater.

---

### 2. Design Tokens & Styling Assets
- **Design Tokens (W3C / Style Dictionary JSON):** `{{DATA:DOCUMENT:DOCUMENT_2}}`
  - Tokenized color scale: Surface hierarchy (`#10131a` to `#32353c`), Cyber Cyan (`#00e5ff`), Emerald (`#10b981`), Amber (`#f59e0b`), Crimson (`#ef4444`).
  - Typography specs (Space Grotesk, Inter, JetBrains Mono).
  - Border radii (4px, 8px, 16px, 20px, 9999px), shadows, and backdrop blur filters.
- **Design System Definition:** `{{DATA:DESIGN_SYSTEM:DESIGN_SYSTEM_1}}` ("Nexus Cyber-Automotive")
  - Semantic component tokens for Bottom Dock (`76px`), Header Chrome (`64px`), and Telemetry gauges.

---

### 3. Engineering PRD & BLE 5.2 Technical Spec
- **Technical Specification Document:** `{{DATA:DOCUMENT:DOCUMENT_3}}`
  - **BLE GATT Table:**
    - Telemetry Service `0xFF10` (Live state `0xFF11`, Battery & thermal `0xFF12`)
    - Remote Command Service `0xFF20` (Executable command `0xFF21`, Ack `0xFF22`)
    - Cluster & TBT Navigation `0xFF30` (TBT route maneuver stream `0xFF31`, Mirroring state `0xFF32`)
    - Media & Handlebar Remote `0xFF40` (Metadata `0xFF41`, Handlebar buttons `0xFF42`)
  - **Binary Packet Definitions:** Exact C-struct definitions for `VehicleLivePacket`, `TBTNavPacket`, and `RemoteCommandPacket`.
  - **Native Intent Hooks:** YouTube / YouTube Music / Spotify detection and Google Maps Turn-by-Turn intent schemes.
  - **5-Sprint Delivery Roadmap:** Implementation phasing from GATT bring-up to field testing.

---

### 4. Graphic Assets
- **Scooter Hero Graphic:** `{{DATA:IMAGE:IMAGE_22}}`
  - High-resolution photorealistic render of the matte black Ampere Nexus electric scooter with illuminated cyan LED headlights on twilight cyber asphalt.

---

## 🚀 How to Export

1. **HTML/CSS Source Code:**
   - Select `Nexus Pulse EV Interactive App Suite` (`{{DATA:SCREEN:SCREEN_4}}`) on the canvas.
   - Click the **Code / Export** button in the canvas inspector panel to download the self-contained HTML/CSS source code.
2. **Design Tokens:**
   - Open `Nexus Pulse EV - Design Tokens (JSON)` (`{{DATA:DOCUMENT:DOCUMENT_2}}`) to copy or export raw JSON for Flutter, React Native, or Android/iOS tokens.
3. **PRD & BLE Specs:**
   - Open `Nexus Pulse EV - Technical PRD & BLE Spec` (`{{DATA:DOCUMENT:DOCUMENT_3}}`) to copy the markdown directly into GitHub Wiki, Notion, or Jira.
