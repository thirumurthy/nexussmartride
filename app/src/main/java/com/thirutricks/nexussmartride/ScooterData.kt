package com.thirutricks.nexussmartride

/**
 * Holds all decoded telemetry from Ampere Nexus BLE packets.
 * Values are set to -1 when not yet received.
 */
data class ScooterData(
    val speedKmh: Int = -1,          // 0x01 - Speed in km/h
    val voltageV: Double = -1.0,     // 0x51 - Battery voltage (raw/100)
    val currentA: Double = -1.0,     // 0x52 - Current (raw/10)
    val socPercent: Int = -1,        // 0x50 - State of Charge 0-100
    val remainingKm: Int = -1,       // 0x55 - Remaining KM Ride
    val statusFlags: Int = -1,       // 0x58 - Status bitmask
    val temperatureC: Int = -1,      // 0x59 - Temperature °C
    val vehicleState: Int = -1,         // 0x57 - Vehicle state (1=Parked, 2=Running)
    val field54: Int = -1,           // 0x54 from Packet A (config byte)
    val field61: Int = -1,           // 0x61 from Packet A
    val timestampMs: Long = System.currentTimeMillis()
) {
    val isValid: Boolean get() = speedKmh >= 0
 
    val statusText: String get() = when (vehicleState) {
        1 -> "Parking"
        2 -> "Running"
        else -> {
            if (vehicleState > 2 && vehicleState != 0xFF) "Error: $vehicleState"
            else when {
                statusFlags == 0 -> "Idle"
                (statusFlags and 0x01) != 0 -> "Ready"
                else -> "Standby"
            }
        }
    }

    val socBarColor: Int get() = when {
        socPercent < 0 -> 0
        socPercent < 20 -> 0xFFE53935.toInt()   // Red
        socPercent < 40 -> 0xFFFB8C00.toInt()   // Orange
        else -> 0xFF43A047.toInt()               // Green
    }
}
