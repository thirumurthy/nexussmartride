package com.thirutricks.nexussmartride.data

import org.json.JSONObject

data class MaintenanceChecklistItem(
    val item: String,
    val desc: String
)

data class ServiceMilestoneItem(
    val milestoneKm: Int,
    val serviceNumber: Int,
    val title: String,
    val intervalLabel: String,
    val checklist: List<MaintenanceChecklistItem>,
    val status: String, // "COMPLETED", "UPCOMING", "FUTURE"
    val isNext: Boolean,
    val kmRemaining: Double
)

data class OwnerTaskItem(
    val title: String,
    val description: String,
    val area: String = ""
)

data class EvVsPetrolItem(
    val item: String,
    val petrol: String,
    val ev: String
)

data class FaqItem(
    val q: String,
    val a: String
)

data class MaintenanceReport(
    val currentOdometerKm: Double = 0.0,
    val nextMilestoneKm: Int = 1000,
    val kmUntilNextService: Double = 1000.0,
    val nextServiceTitle: String = "1st Service (Initial Bedding & Fit)",
    val nextServiceChecklist: List<MaintenanceChecklistItem> = emptyList(),
    val timeline: List<ServiceMilestoneItem> = emptyList(),
    val weeklyTasks: List<OwnerTaskItem> = emptyList(),
    val monthlyTasks: List<OwnerTaskItem> = emptyList(),
    val evVsPetrol: List<EvVsPetrolItem> = emptyList(),
    val batteryDailyHabits: List<String> = emptyList(),
    val batteryLongTermCare: List<String> = emptyList(),
    val faqs: List<FaqItem> = emptyList()
)

object MaintenanceRepository {

    val DEFAULT_WEEKLY_TASKS = listOf(
        OwnerTaskItem(
            title = "Tyre Pressure (Cold)",
            description = "Check tyre pressure when tyres are cold. Front: 28 PSI, Rear: 32 PSI. Under-inflation reduces range by 5–10%."
        ),
        OwnerTaskItem(
            title = "Tread & Sidewalls",
            description = "Inspect tread for embedded stones, cuts, or uneven wear. Ensure tread depth remains above 2 mm."
        ),
        OwnerTaskItem(
            title = "Body, Seat & Display Wipe",
            description = "Wipe body, seat, and 7-inch TFT cluster with a clean damp microfiber cloth. Avoid harsh pressure jets."
        ),
        OwnerTaskItem(
            title = "Charging Port Inspection",
            description = "Verify charging socket is clean, dry, and sealed with rubber flap before and after every charging session."
        ),
        OwnerTaskItem(
            title = "Lights & Horn Verification",
            description = "Test headlamp high/low beam, front & rear turn indicators, brake lights, and electric horn response."
        )
    )

    val DEFAULT_MONTHLY_TASKS = listOf(
        OwnerTaskItem(
            area = "Fasteners",
            title = "Handlebar, Mirror & Seat Bolts",
            description = "Check for loose handlebar, rear-view mirror, seat hinge, and footboard bolts. Re-tighten if play is detected."
        ),
        OwnerTaskItem(
            area = "Brakes",
            title = "Lever Travel & Noise Check",
            description = "Check lever travel, cable smoothness, and listen for squealing/grinding noises during progressive braking."
        ),
        OwnerTaskItem(
            area = "Charger",
            title = "Cable, Plug & Socket Heat",
            description = "Inspect charger cord and 15A wall plug for cuts, kinks, bent pins, or thermal discoloration marks."
        ),
        OwnerTaskItem(
            area = "Wiring",
            title = "Under-Deck Harness Inspection",
            description = "Inspect visible wiring harness under deck and behind fork for rubbing, pinches, or loose connectors."
        ),
        OwnerTaskItem(
            area = "Motor & Wheels",
            title = "Wheel Rotation & Bearing Noise",
            description = "Place scooter on center stand, spin wheels by hand, and listen for smooth rotation with no bearing resistance."
        ),
        OwnerTaskItem(
            area = "Suspension",
            title = "Fork & Shock Oil Seal Check",
            description = "Look for hydraulic oil leaks on front forks or rear dampers; test for smooth, progressive bump absorption."
        )
    )

    val DEFAULT_EV_VS_PETROL = listOf(
        EvVsPetrolItem("Engine Oil Change", "Every 3,000–5,000 km", "Not applicable (Zero oil)"),
        EvVsPetrolItem("Spark Plug Replacement", "Every 10,000–15,000 km", "Not applicable"),
        EvVsPetrolItem("Air Filter Cleaning", "Every 5,000–8,000 km", "Not applicable"),
        EvVsPetrolItem("Coolant Flush", "Every 20,000 km", "Not applicable"),
        EvVsPetrolItem("Brake Pad Inspection", "Every 5,000–8,000 km", "Every 5,000–8,000 km (Front Disc)"),
        EvVsPetrolItem("Brake Fluid Flush", "Every 2 years", "Every 2 years (Nexus disc variant)"),
        EvVsPetrolItem("Tyre Pressure Check", "Monthly", "Monthly (Cold check)"),
        EvVsPetrolItem("Tyre Replacement", "Every 25,000–35,000 km", "Every 25,000–35,000 km (12\" Tubeless)"),
        EvVsPetrolItem("Software Updates", "Dealer visit only", "Over-The-Air via Nex.IO"),
        EvVsPetrolItem("Battery Health Check", "Lead-acid swap 2-3 yrs", "Annual Ampere diagnostic (5-Yr/75k km)")
    )

    val DEFAULT_BATTERY_HABITS = listOf(
        "Charge to 80–90% for daily commuting — reduces cell stress and preserves chemical longevity.",
        "Avoid letting the battery drop below 10–15% regularly — deep discharges accelerate degradation.",
        "A full 100% charge the night before a long journey is fine; avoid leaving it at 100% parked in direct sun.",
        "Charge in a shaded room-temperature area — avoid charging in direct noon summer sunlight.",
        "Always use the official Ampere-certified charger for optimal BMS safety handshake."
    )

    val DEFAULT_BATTERY_LONG_TERM = listOf(
        "If storing scooter for >2 weeks, maintain charge between 40–60%.",
        "Run a charge cycle (full charge + partial discharge) every 2–4 weeks if not in regular use.",
        "Annual battery diagnostic at authorized Ampere service center verifies cell balance (<15 mV delta) and capacity retention.",
        "Under normal use, Ampere LFP batteries retain ~90–93% capacity by Year 5 (warranted for 5 Years / 75,000 km)."
    )

    val DEFAULT_FAQS = listOf(
        FaqItem(
            q = "How often does an Ampere electric scooter need servicing?",
            a = "A brake, tyre, and safety inspection every 5,000 km and an annual comprehensive service every 10,000 km. Battery health diagnostics are performed during the annual visit. Software updates are delivered wirelessly via Nex.IO."
        ),
        FaqItem(
            q = "Do I need to change engine oil in my Ampere scooter?",
            a = "No. Electric scooters have no internal combustion engine and therefore require zero oil changes, spark plug replacements, or air filter maintenance. Servicing is 60–70% cheaper than petrol equivalents."
        ),
        FaqItem(
            q = "How long does an Ampere LFP battery last?",
            a = "Under typical daily use, LFP chemistry retains approximately 90–93% of original capacity by Year 5. Ampere covers the battery with a 5-year or 75,000 km manufacturer warranty."
        ),
        FaqItem(
            q = "Is it safe to charge an Ampere scooter in the rain?",
            a = "Riding in rain is safe thanks to IP67 ingress protection, but do NOT plug in while the socket is wet. Dry the charging socket and connector thoroughly with a dry cloth before connecting the charger, and charge in a sheltered area."
        ),
        FaqItem(
            q = "What should I do if my Ampere Nexus shows a battery warning?",
            a = "The Nex.IO platform will display a fault code on the 7-inch TFT cluster. Note down the code and contact the nearest authorized Ampere service center. Many minor calibration faults can be resolved via an OTA software update."
        )
    )

    private val BASE_MILESTONES = listOf(
        Triple(
            1000,
            "1st Service (Initial Bedding & Fit)",
            listOf(
                MaintenanceChecklistItem("Fasteners & Chassis", "Recheck and torque-fit all bolt and nut connections across frame, fork, and swingarm."),
                MaintenanceChecklistItem("Electrical & Controls", "Comprehensive inspection of headlamp, indicators, brake lights, horn, and switches."),
                MaintenanceChecklistItem("Brake Bedding", "Check brake lever free-play, cable tension, and front disc pad seating."),
                MaintenanceChecklistItem("Wheel Alignment", "Initial wheel alignment check and tyre pressure balancing."),
                MaintenanceChecklistItem("Nex.IO Software", "Initial TFT cluster diagnostics and OTA software integrity check.")
            )
        ),
        Triple(
            5000,
            "2nd Service (Safety & Wear Check)",
            listOf(
                MaintenanceChecklistItem("Brake Pads & Shoes", "Inspect front disc pad thickness (ensure >=2 mm) and rear drum shoe wear."),
                MaintenanceChecklistItem("Suspension Inspection", "Inspect front telescopic fork and twin rear shock absorbers for smooth damping and oil leaks."),
                MaintenanceChecklistItem("Tyres & Tread", "Inspect tyre tread depth (minimum 2 mm), sidewall integrity, and embedded stones."),
                MaintenanceChecklistItem("Battery Diagnostic", "BMS automated health scan, cell balance verification, and terminal inspection."),
                MaintenanceChecklistItem("Brake Cable Tension", "Lubricate and adjust front and rear CBS brake cables.")
            )
        ),
        Triple(
            10000,
            "3rd Service (Annual Comprehensive)",
            listOf(
                MaintenanceChecklistItem("Brake Fluid Flush", "Inspect brake fluid clarity; flush and replenish DOT4 brake fluid (Nexus front disc)."),
                MaintenanceChecklistItem("Battery Pack Health", "Deep diagnostic on 3.3 kWh LFP pack, cell balance (delta V < 15 mV), and capacity retention."),
                MaintenanceChecklistItem("Motor & Controller", "Check BLDC/PMSM motor wiring, controller heat sink, and phase cable torque."),
                MaintenanceChecklistItem("Wiring Harness", "Inspect all visible wiring harnesses for rubbing, abrasion, or loose weather-sealed plugs."),
                MaintenanceChecklistItem("Drum Brake Cleaning", "Disassemble rear drum, clean out accumulated brake dust, and adjust return springs.")
            )
        ),
        Triple(
            15000,
            "4th Service (Chassis & Wheel Alignment)",
            listOf(
                MaintenanceChecklistItem("Tyres & Alignment", "Check uneven tyre wear patterns; perform complete front-to-rear wheel alignment."),
                MaintenanceChecklistItem("Tyre Replacement Assessment", "Assess tread depth against 2 mm safety threshold; recommend tyre change if worn."),
                MaintenanceChecklistItem("Suspension Bushings", "Inspect swingarm pivot bushings and fork seals; grease steering head bearings."),
                MaintenanceChecklistItem("Brake System Overhaul", "Replace front brake pads if under 2 mm; check CBS balance and lever firmness.")
            )
        ),
        Triple(
            20000,
            "5th Service (Deep Powertrain Diagnostics)",
            listOf(
                MaintenanceChecklistItem("Battery & Motor Performance", "Full dyno/telemetry test of motor torque delivery and regenerative braking curve."),
                MaintenanceChecklistItem("LFP Pack Health Assessment", "Diagnostic check for capacity retention (rated for >90% retention at 5 years)."),
                MaintenanceChecklistItem("Controller & BMS Recalibration", "Calibrate state-of-charge tracking algorithms and thermal throttle curves."),
                MaintenanceChecklistItem("Brake Fluid Replacement", "Mandatory 2-year brake fluid flush and system bleed.")
            )
        ),
        Triple(
            25000,
            "6th Service (Major Lifecycle Milestone)",
            listOf(
                MaintenanceChecklistItem("Comprehensive Multi-Point Check", "All checklist items from 1st through 5th services consolidated into full inspection."),
                MaintenanceChecklistItem("Tyre Replacement", "Replace front and rear 12-inch tubeless tyres (standard 25,000–35,000 km interval)."),
                MaintenanceChecklistItem("Disc Rotor Inspection", "Inspect front disc rotor for scoring, grooves, or runout warp; replace if needed."),
                MaintenanceChecklistItem("Motor Bearings & Seals", "Inspect motor wheel bearings for free rotation and water-tight seal integrity."),
                MaintenanceChecklistItem("Nex.IO Major OTA Update", "Apply latest Nex.IO firmware patch and battery management algorithms.")
            )
        )
    )

    fun generateTimeline(currentOdo: Double): List<ServiceMilestoneItem> {
        val odo = maxOf(0.0, currentOdo)
        val list = mutableListOf<ServiceMilestoneItem>()

        // Add base milestones (1k, 5k, 10k, 15k, 20k, 25k)
        var serviceIdx = 1
        for ((km, title, checklist) in BASE_MILESTONES) {
            val label = when (km) {
                1000 -> "1,000 km"
                5000 -> "5,000 km"
                10000 -> "10,000 km"
                15000 -> "15,000 km"
                20000 -> "20,000 km"
                25000 -> "25,000 km"
                else -> "${km} km"
            }
            list.add(
                ServiceMilestoneItem(
                    milestoneKm = km,
                    serviceNumber = serviceIdx++,
                    title = title,
                    intervalLabel = label,
                    checklist = checklist,
                    status = "FUTURE",
                    isNext = false,
                    kmRemaining = 0.0
                )
            )
        }

        // Extend recurring 5,000 km milestones if odometer is approaching or beyond 25,000 km
        val targetMax = maxOf(35000, (((odo / 5000).toInt() + 2) * 5000))
        var nextKm = 30000
        while (nextKm <= targetMax) {
            val sNum = serviceIdx++
            list.add(
                ServiceMilestoneItem(
                    milestoneKm = nextKm,
                    serviceNumber = sNum,
                    title = "${sNum}th Service (Periodic Major)",
                    intervalLabel = "${nextKm.toString().replace(Regex("(\\d)(?=(\\d{3})+$)"), "$1,")} km",
                    checklist = listOf(
                        MaintenanceChecklistItem("Comprehensive Multi-Point Check", "Full safety review following all Ampere recommended inspection protocols."),
                        MaintenanceChecklistItem("Brake Pads & CBS Tuning", "Inspect front disc pads and rear drum wear; adjust CBS lever proportioning."),
                        MaintenanceChecklistItem("LFP Battery Capacity Scan", "Annual dealer battery health test and cell balance trimming."),
                        MaintenanceChecklistItem("Suspension & Chassis Torque", "Inspect suspension damping, swingarm bushings, and frame fastener torques."),
                        MaintenanceChecklistItem("Nex.IO Firmware Integrity", "Check over-the-air software updates and cluster telemetry.")
                    ),
                    status = "FUTURE",
                    isNext = false,
                    kmRemaining = 0.0
                )
            )
            nextKm += 5000
        }

        // Determine COMPLETED, UPCOMING, FUTURE
        var upcomingFound = false
        return list.map { m ->
            val status: String
            val isNext: Boolean
            val kmRemaining: Double

            if (odo >= m.milestoneKm) {
                status = "COMPLETED"
                isNext = false
                kmRemaining = 0.0
            } else if (!upcomingFound) {
                status = "UPCOMING"
                isNext = true
                upcomingFound = true
                kmRemaining = maxOf(0.0, m.milestoneKm - odo)
            } else {
                status = "FUTURE"
                isNext = false
                kmRemaining = m.milestoneKm - odo
            }

            m.copy(
                status = status,
                isNext = isNext,
                kmRemaining = Math.round(kmRemaining * 10.0) / 10.0
            )
        }
    }

    fun buildLocalReport(currentOdo: Double): MaintenanceReport {
        val timeline = generateTimeline(currentOdo)
        val nextMilestone = timeline.firstOrNull { it.isNext } ?: timeline.firstOrNull()

        return MaintenanceReport(
            currentOdometerKm = currentOdo,
            nextMilestoneKm = nextMilestone?.milestoneKm ?: 1000,
            kmUntilNextService = nextMilestone?.kmRemaining ?: 1000.0,
            nextServiceTitle = nextMilestone?.title ?: "1st Service",
            nextServiceChecklist = nextMilestone?.checklist ?: emptyList(),
            timeline = timeline,
            weeklyTasks = DEFAULT_WEEKLY_TASKS,
            monthlyTasks = DEFAULT_MONTHLY_TASKS,
            evVsPetrol = DEFAULT_EV_VS_PETROL,
            batteryDailyHabits = DEFAULT_BATTERY_HABITS,
            batteryLongTermCare = DEFAULT_BATTERY_LONG_TERM,
            faqs = DEFAULT_FAQS
        )
    }

    fun parseFromJson(json: JSONObject, currentOdo: Double): MaintenanceReport {
        val odo = json.optDouble("currentOdometerKm", currentOdo)
        val timelineJson = json.optJSONArray("timeline")
        val parsedTimeline = mutableListOf<ServiceMilestoneItem>()

        if (timelineJson != null && timelineJson.length() > 0) {
            for (i in 0 until timelineJson.length()) {
                val tObj = timelineJson.optJSONObject(i) ?: continue
                val checklistJson = tObj.optJSONArray("checklist")
                val checklist = mutableListOf<MaintenanceChecklistItem>()
                if (checklistJson != null) {
                    for (c in 0 until checklistJson.length()) {
                        val cObj = checklistJson.optJSONObject(c) ?: continue
                        checklist.add(
                            MaintenanceChecklistItem(
                                item = cObj.optString("item", ""),
                                desc = cObj.optString("desc", "")
                            )
                        )
                    }
                }
                parsedTimeline.add(
                    ServiceMilestoneItem(
                        milestoneKm = tObj.optInt("milestoneKm", 0),
                        serviceNumber = tObj.optInt("serviceNumber", i + 1),
                        title = tObj.optString("title", ""),
                        intervalLabel = tObj.optString("intervalLabel", ""),
                        checklist = checklist,
                        status = tObj.optString("status", "FUTURE"),
                        isNext = tObj.optBoolean("isNext", false),
                        kmRemaining = tObj.optDouble("kmRemaining", 0.0)
                    )
                )
            }
        }

        val timeline = if (parsedTimeline.isNotEmpty()) parsedTimeline else generateTimeline(odo)
        val nextMilestone = timeline.firstOrNull { it.isNext } ?: timeline.firstOrNull()

        return MaintenanceReport(
            currentOdometerKm = odo,
            nextMilestoneKm = json.optInt("nextMilestoneKm", nextMilestone?.milestoneKm ?: 1000),
            kmUntilNextService = json.optDouble("kmUntilNextService", nextMilestone?.kmRemaining ?: 1000.0),
            nextServiceTitle = json.optString("nextServiceTitle", nextMilestone?.title ?: "1st Service"),
            nextServiceChecklist = nextMilestone?.checklist ?: emptyList(),
            timeline = timeline,
            weeklyTasks = DEFAULT_WEEKLY_TASKS,
            monthlyTasks = DEFAULT_MONTHLY_TASKS,
            evVsPetrol = DEFAULT_EV_VS_PETROL,
            batteryDailyHabits = DEFAULT_BATTERY_HABITS,
            batteryLongTermCare = DEFAULT_BATTERY_LONG_TERM,
            faqs = DEFAULT_FAQS
        )
    }
}
