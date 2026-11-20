package com.thirutricks.nexussmartride

/**
 * Holds all decoded telemetry from Ampere Nexus BLE packets.
 * Values are set to -1 / -1.0 when not yet received.
 */
data class ScooterData(
    val speedKmh: Int = -1,              // Speed in km/h (0x57 in Nexus ST, 0x01 in legacy)
    val voltageV: Double = -1.0,         // Battery voltage (raw/100) if available
    val currentA: Double = -1.0,         // Current (raw/10) if available
    val socPercent: Int = -1,            // 0x50 - State of Charge 0-100%
    val remainingKm: Int = -1,           // 0x55 - Remaining KM Range (DTE)
    val odometerKm: Double = -1.0,       // 0x51 - Total Odometer in km (from 4B LE / 10.0)
    val tripKm: Double = -1.0,           // 0x52 - Trip Odometer in km (from 2B LE / 10.0)
    val maxSpeedKmh: Int = -1,           // 0x58 - Max Speed in km/h
    val avgSpeedKmh: Int = -1,           // 0x59 - Average Speed in km/h
    val ridingMode: String = "Park",     // 02 54 frame: "ECO", "City", "Power", "Park"
    val limpHome: Boolean = false,       // 02 54 frame: Limp home state
    val timeToChargeMin: Double = -1.0,  // 02 54 frame: Time to full charge
    val statusFlags: Int = -1,           // Status bitmask
    val temperatureC: Int = -1,          // Temperature °C
    val vehicleState: Int = -1,          // Vehicle state code
    val field54: Int = -1,               // 0x54 from config frame
    val field61: Int = -1,               // 0x61 from config frame
    val callAction: Int = -1,            // 0x47 - Scooty button (1=Answer, 2=Reject)
    val songAction: Int = -1,            // 0x48 - Scooty button (1=Play, 2=Pause, 3=Next, 4=Prev)
    val timestampMs: Long = System.currentTimeMillis()
) {
    val isValid: Boolean get() = speedKmh >= 0 || socPercent >= 0 || odometerKm >= 0

    val statusText: String get() = ridingMode.ifBlank { "Standby" }

    val socBarColor: Int get() = when {
        socPercent < 0 -> 0
        socPercent < 20 -> 0xFFE53935.toInt()   // Red
        socPercent < 40 -> 0xFFFB8C00.toInt()   // Orange
        else -> 0xFF43A047.toInt()               // Green
    }
}
