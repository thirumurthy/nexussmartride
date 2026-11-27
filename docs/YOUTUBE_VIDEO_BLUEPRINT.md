# 🎬 YouTube Video Blueprint: Reverse Engineering My EV Scooter & Building an AI Dashboard

> **Document Type:** Production Script, Chapter Breakdown & Content Guide  
> **Target Audience:** Developers, IoT Engineers, EV Enthusiasts, Hardware Hackers & AI Builders  
> **Tone:** Curious, Technical, Engineering-Centric, Professional & Educational  

---

## 📌 Video Title & Thumbnail Strategy

### Title Options (Choose one based on your channel style):
* **Option A (High-CTR / Maker & Engineering):**  
  *How I Reverse Engineered My EV Scooter’s Bluetooth Protocol & Built a Custom Cyberpunk Dashboard*
* **Option B (AI & Tech Focus):**  
  *Building an AI-Powered Companion App for an Electric Scooter using REA & Android Canvas*
* **Option C (IoT & Protocol Analysis):**  
  *Reverse Engineering Bluetooth Telemetry on an EV Scooter: From Raw Packets to Live Analytics*
* **Option D (Short & Punchy):**  
  *I Hacked My Electric Scooter’s Bluetooth Protocol (Educational Case Study)*

### Thumbnail Concept:
* **Left Side:** Close-up of your electric scooter TFT screen or handlebar with a glowing cyan neon overlay.
* **Middle:** A stylized split graphic of raw Wireshark/HCI Hex bytes (`0xFF 11 00 24 3F...`) transitioning into clean, polished code.
* **Right Side:** The glowing dark-glass Android Dashboard on a phone displaying the Dual-Axis Cadence chart and 16-cell battery matrix.
* **Badge / Bold Text:** `REA PROTOCOL DEEP DIVE` or `AI + EV TELEMETRY`.

---

## ⚖️ Pre-Recording Privacy & Legal Checklist

Before publishing, ensure your screen captures and footage follow these rules:

- [ ] **Blur the Scooter's License Plate & VIN / Chassis Number** in any physical footage.
- [ ] **Mask the Bluetooth MAC Address** in app screenshots and packet dumps (use `AA:BB:CC:**:**:**` or mock address).
- [ ] **Hide Sensitive Backend Credentials**: Mask your `MONGODB_URI`, `GROQ_API_KEY`, `MISTRAL_API_KEY`, and `GEMINI_API_KEY` in terminal/VSCode captures.
- [ ] **Use the On-Screen Disclaimer Card** in the first 5 seconds of the video and in the description.
- [ ] **Avoid "Exploit / Jailbreak" Terminology**: Frame the video as *telemetry analysis, protocol decoding, and interoperability research*.

---

## 📋 Full Script & Chapter Breakdown

```
Estimated Total Duration: 10 to 12 minutes
```

---

### ⏱️ 0:00 - 1:15 | Chapter 1: The Hook & Motivation

#### 🎥 Visuals:
- B-roll of your electric scooter starting up, headlight turning on, digital cluster lighting up.
- Quick high-energy montage: phone screen recording showing the custom cyber dashboard with glowing gauges, dynamic graphs, and handlebar button interactions.
- Talking head or voiceover introducing the project.

#### 🎙️ Talking Points:
1. **The Core Motivation**: *"When you buy a modern electric scooter, you expect the companion app to just work. But like many EV owners, I faced Bluetooth connection drops, missed call alerts that didn't sync, and most importantly: I wanted deeper insights into my battery health, daily charge usage, and ride cadence that the stock app never showed."*
2. **The Question**: *"Since the scooter communicates over Bluetooth Low Energy (BLE), could I analyze the protocol myself and build a customized companion app tailored to what I actually need?"*
3. **The Project Overview**: *"In this video, I'll walk you through how I used Reverse Engineering Anything (REA) techniques—from packet snooping to frame decoding—and how I used AI tools to rapidly build a production-grade Android companion app and cloud telemetry server from scratch."*

> **[ON-SCREEN DISCLAIMER POPUP (0:05 - 0:10)]**  
> *"Educational and engineering research project only. Not affiliated with or endorsed by Greaves Electric Mobility / Ampere. All trademarks belong to their respective owners."*

---

### ⏱️ 1:15 - 3:45 | Chapter 2: The REA Process (Bluetooth Protocol Decoding)

#### 🎥 Visuals:
- Screen capture showing Android Developer Options: **"Enable Bluetooth HCI Snoop Log"**.
- Wireshark interface filtering packets by `btsnoop` and showing Bluetooth ATT/GATT exchanges.
- Clean diagram or slide illustrating the packet structure: Header, Frame Type, Payload Bytes, and Checksum.

#### 🎙️ Talking Points:
1. **Capturing the Traffic**:
   - Explain how Android lets developers record Bluetooth packets directly using the **Bluetooth HCI Snoop Log**.
   - Pulling the log file via ADB: `adb bugreport` or pulling `btsnoop_hci.log`.
2. **Finding the Service & Characteristics**:
   - Locating the custom GATT Service UUIDs used by the scooter cluster.
   - Identifying the Notification Characteristic (scooter to phone) and Write Characteristic (phone to scooter).
3. **Decoding the Packet Structure**:
   - **Live Telemetry Frame (`0xFF11`)**:
     - Byte offsets for Speed, Voltage (in tenths of a volt), Trip Meter, and Odometer deltas.
   - **Battery & BMS Frame (`0xFF12`)**:
     - Decoding State of Charge (SoC %), charging/discharging current in Amps, and cell temperature.
   - **Handlebar Button Events**:
     - Discovering the event bytes emitted when you press handlebar switches:
       - `0x01`: Handlebar call answer button pressed.
       - `0x02`: Handlebar call reject/end button pressed.
       - Media control packets: Play, Pause, Next Track, Previous Track.
4. **Sending Data Back to the Cluster**:
   - Time synchronization packet (sending phone timestamp to cluster).
   - Caller ID packet (formatting UTF-8 contact name and number for cluster screen).
   - Song info streaming (track title, artist name, and duration progress).

---

### ⏱️ 3:45 - 6:30 | Chapter 3: Android App Architecture & Cyber-Automotive UI

#### 🎥 Visuals:
- Screen capture of Android Studio and the running app on a physical device.
- Zoom in on the **Ride Cadence** card showing the dual-axis chart.
- Demo of the **Recent Journeys** bottom sheet with 3-column telemetry strips.
- Demo of the **Maintenance Timeline** showing past service milestones and future target checklist.

#### 🎙️ Talking Points:
1. **The Cyber-Automotive Aesthetic**:
   - Why a dark glass substrate (`#0B1017`) with electric cyan (`#00E5FF`) and kinetic emerald (`#01F5A0`) was chosen.
2. **Building the Custom Dual-Axis Combo Chart**:
   - Standard MPAndroidChart or WebViews were too heavy and couldn't deliver the exact custom aesthetic.
   - Built a custom Android View (`RideCadenceChartView`):
     - **Left Y-Axis (Distance in km)**: Dynamic scale with subtle horizontal gridlines.
     - **Right Y-Axis (SoC Used in %)**: Vibrant emerald labels (`0%` to `120%`) with top headroom.
     - **Bars**: Rounded translucent cyan bars.
     - **Curve**: Smooth cubic bezier spline passing through illuminated node beads.
     - **Interactive Scrubber**: Touch event listener calculating column hits with floating telemetry tooltip.
3. **Smart Ride Clustering Heuristic Engine**:
   - Problem: Quick stops (e.g., traffic signal, corner shop) split rides into 0.1 km fragments.
   - Solution: An algorithm that detects ride boundaries, groups consecutive trips within a time window, and splits rides when a battery charging event occurs.
4. **Maintenance Lifecycle Roadmap**:
   - Official EV maintenance roadmap (1,000 km, 5,000 km, 10,000 km up to 25,000 km).
   - Highlights the next target service automatically while letting riders scroll past milestones.

---

### ⏱️ 6:30 - 8:45 | Chapter 4: AI-Assisted Development & Cloud Architecture

#### 🎥 Visuals:
- VSCode showing the Node.js Express backend (`nexus_api`).
- Web browser showing the **Interactive Admin Dashboard** (`/admin`).
- Code snippet of the multi-provider AI engine in `aiProviderManager.js`.

#### 🎙️ Talking Points:
1. **AI-Assisted Workflow**:
   - How agentic AI tools accelerated the reverse engineering: interpreting bitmask flags, generating regression tests, and converting Figma/HTML specs into native Android XML layouts.
2. **Cloud Telemetry Ingestion**:
   - High-throughput REST API deployed on Vercel Serverless with MongoDB Atlas.
   - Privacy-first hardware isolation: Using `SHA-256(MAC + SALT)` so no raw vehicle hardware identifiers are stored in plaintext.
3. **The Swappable Multi-Provider AI Copilot**:
   - Implemented a unified AI provider interface supporting:
     - **Google Gemini**
     - **Mistral AI**
     - **Groq Cloud (Fast inference)**
     - **NVIDIA NIM**
     - **Local Ollama** (100% offline inference)
     - **Deterministic Physics Fallback** (calculates exact kWh and petrol savings if offline).
   - Generates contextual ride critiques, energy efficiency tips, and predictive maintenance alerts.

---

### ⏱️ 8:45 - 10:45 | Chapter 5: Real-World Vehicle Demo

#### 🎥 Visuals:
- Split-screen or picture-in-picture:
  - **Camera 1**: Physical camera mounted looking at the scooter's digital dashboard and handlebars.
  - **Camera 2**: Screen recording of the Android app running on the mounted phone.
- Action clips:
  - Turn on key -> Phone connects in under 2 seconds.
  - Incoming phone call triggered: Caller ID appears on the scooter screen; press handlebar switch to answer or decline.
  - Music playing on Spotify -> Scooter display shows song title and artist.
  - Short test ride showing real-time speed and battery consumption updating live.
  - Opening the **Ride Cadence** graph showing the newly recorded trip.

#### 🎙️ Talking Points:
1. *"Here you can see the app connecting automatically the second the scooter turns on."*
2. *"When a call comes in, the scooter cluster displays the caller's name, and with a single click of the handlebar button, we can answer or decline without touching the phone."*
3. *"The battery percentage and odometer delta update smoothly, and our daily commute graph plots both the distance covered and the exact battery percentage consumed."*

---

### ⏱️ 10:45 - 12:00 | Chapter 6: Key Takeaways & Open Source

#### 🎥 Visuals:
- Talking head or final hero montage of the scooter and the app.
- On-screen links to GitHub repositories with the educational disclaimer.

#### 🎙️ Talking Points:
1. **What We Learned**:
   - Reverse engineering IoT protocols isn't magic—it's methodical observation, packet capture, and testing hypotheses.
   - Modern AI tools serve as incredible force multipliers when building end-to-end full-stack systems.
2. **Why Not Play Store?**:
   - Address why this app is kept as an open-source personal project rather than a commercial Play Store app (OEM trademark protection, private API policies, and vehicle safety boundaries).
3. **Open Source & Next Steps**:
   - Both the Android client and the Node.js API are open-source on GitHub for educational and research purposes.
   - Call to action: Like the video, subscribe for more hardware reverse engineering deep dives, and star the GitHub repository!

---

## 📝 YouTube Video Description Template

Copy and paste this directly into your YouTube upload box:

```markdown
In this video, I break down how I reverse-engineered the Bluetooth Low Energy (BLE) protocol of my electric scooter and built a custom cyber-automotive companion app with an AI-powered telemetry backend.

We explore how to capture Bluetooth HCI snoop logs, decode raw telemetry packets (speed, 16-cell battery voltage, SoC, and odometer deltas), map handlebar button events, and build a high-performance dual-axis canvas chart in Android.

📌 CHAPTERS:
0:00 - The Problem with Stock EV Companion Apps
1:15 - Bluetooth Low Energy (BLE) Reverse Engineering (REA)
2:30 - Decoding 0xFF11 Telemetry & 0xFF12 Battery Packets
3:45 - Intercepting Handlebar Call & Media Controls
5:00 - Designing the Cyber-Automotive Android UI
6:30 - Building the Dual-Axis Ride Cadence Chart
8:00 - Multi-Provider AI Telemetry Engine (Gemini, Mistral, Groq)
9:15 - Live Vehicle Demo (Cluster Sync & Button Controls)
11:00 - Key Takeaways & Open Source Resources

🔗 GITHUB REPOSITORIES (Educational & Research Study):
📱 Android Client: https://github.com/thirumurthy/nexussmartride
☁️ Cloud Telemetry API: https://github.com/thirumurthy/nexus_api

⚠️ DISCLAIMER:
This project is an independent educational and research case study demonstrating Bluetooth Low Energy protocol analysis and AI-assisted development. It is NOT an official app and is NOT affiliated with, endorsed by, or connected to Greaves Electric Mobility Ltd., Ampere Vehicles, or any vehicle manufacturer. All trademarks belong to their respective owners. Do not interact with mobile applications while operating a vehicle.

#ElectricScooter #ReverseEngineering #AndroidDev #IoT #BluetoothLE #ArtificialIntelligence #Kotlin #Nodejs
```

---

## 🏷️ Recommended Video Tags

```
Electric Scooter Reverse Engineering, Bluetooth BLE Protocol Analysis, Android Canvas Chart, EV Telemetry Dashboard, IoT Bluetooth Hacking, Wireshark BLE Snoop, HCI Snoop Log Android, Ampere Nexus EV, Kotlin Android Tutorial, Cyberpunk UI Android, Node.js Fleet Telemetry, AI Copilot Gemini Groq, Reverse Engineering Anything, Smart Scooter Dashboard
```
