package com.thirutricks.nexussmartride

import android.util.Log

/**
 * Decodes BLE packets from Ampere Nexus scooter.
 *
 * Two packet types alternate every ~500ms:
 *   Packet A (0x02): Field definition / config frame
 *     e.g. 02-54-00-61-FF-FF-62-00-63-00-64-00-65-00-66-00-67-01-68-01
 *   Packet B (0x01): Live telemetry data
 *     e.g. 01-50-64-51-26-A7-01-00-52-7F-20-55-69-57-00-58-3F-59-17-FF
 *
 * Field IDs in Packet B:
 *   0x01 = Speed (1 byte, km/h)
 *   0x50 = State of Charge (1 byte, %)
 *   0x51 = Battery Voltage (2 bytes, value/100 = V)
 *   0x52 = Current (2 bytes, value/10 = A)
 *   0x55 = Remaining KM Ride (1 byte, km)
 *   0x57 = Error/State Code (1 byte)
 *   0x58 = Status Flags (1 byte, bitmask)
 *   0x59 = Temperature (1 byte, °C)
 */
object NexusParser {

    private const val TAG = "NexusParser"

    // Characteristic UUIDs observed in nRF log
    const val SERVICE_UUID_PRIMARY    = "20012202-1234-1234-1234-220201042403"
    const val SERVICE_UUID_SECONDARY  = "20022202-1234-1234-1234-220201042403"
    const val SERVICE_UUID_TERTIARY   = "20032202-1234-1234-1234-220201042403"
    const val SERVICE_UUID_QUATERNARY = "20042202-1234-1234-1234-220201042403"

    val SYNC_CANDIDATES = listOf(SERVICE_UUID_TERTIARY, SERVICE_UUID_QUATERNARY, SERVICE_UUID_PRIMARY, SERVICE_UUID_SECONDARY)

    // The primary telemetry characteristic (Notify + Read)
    const val CHAR_TELEMETRY = "20012202-1234-1234-1234-220201042403"

    // Standard BLE CCCD descriptor for enabling notifications
    const val DESCRIPTOR_CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    // Device name prefix for scan filter
    const val DEVICE_NAME_PREFIX = "NEX_"

    // Packet type bytes
    private const val TYPE_DATA   = 0x01
    private const val TYPE_CONFIG = 0x02

    // Field IDs
    private const val FIELD_SPEED  = 0x01
    private const val FIELD_SOC    = 0x50
    private const val FIELD_VOLT   = 0x51
    private const val FIELD_CURR   = 0x52
    private const val FIELD_RANGE  = 0x55
    private const val FIELD_ERROR  = 0x57
    private const val FIELD_STATUS = 0x58
    private const val FIELD_TEMP   = 0x59

    // Keep last known values so partial updates don't wipe good data
    private var lastData = ScooterData()

    fun parse(raw: ByteArray): ScooterData? {
        if (raw.isEmpty()) return null

        val type = raw[0].toInt() and 0xFF
        return when (type) {
            TYPE_DATA   -> parseDataPacket(raw)
            TYPE_CONFIG -> parseConfigPacket(raw)
            else        -> null
        }
    }

    private fun parseDataPacket(data: ByteArray): ScooterData {
        var speed       = lastData.speedKmh
        var voltage     = lastData.voltageV
        var current     = lastData.currentA
        var soc         = lastData.socPercent
        var remainingKm = lastData.remainingKm
        var status      = lastData.statusFlags
        var temperature = lastData.temperatureC
        var error       = lastData.vehicleState

        var i = 1
        while (i < data.size) {
            val field = data[i].toInt() and 0xFF

            when (field) {
                FIELD_SPEED -> {
                    if (i + 1 < data.size) {
                        speed = data[i + 1].toInt() and 0xFF
                        i += 2
                    } else { i++; break }
                }

                FIELD_VOLT -> {
                    if (i + 2 < data.size) {
                        val raw = ((data[i + 1].toInt() and 0xFF) shl 8) or
                                  (data[i + 2].toInt() and 0xFF)
                        voltage = raw / 100.0
                        i += 3
                    } else { i++; break }
                }

                FIELD_CURR -> {
                    if (i + 2 < data.size) {
                        val raw = ((data[i + 1].toInt() and 0xFF) shl 8) or
                                  (data[i + 2].toInt() and 0xFF)
                        // Treat as signed 16-bit (negative = regen/charging)
                        val signed = if (raw > 0x7FFF) raw - 0x10000 else raw
                        current = signed / 10.0
                        i += 3
                    } else { i++; break }
                }

                FIELD_SOC -> {
                    if (i + 1 < data.size) {
                        soc = (data[i + 1].toInt() and 0xFF).coerceIn(0, 100)
                        i += 2
                    } else { i++; break }
                }

                FIELD_RANGE -> {
                    if (i + 1 < data.size) {
                        remainingKm = data[i + 1].toInt() and 0xFF
                        i += 2
                    } else { i++; break }
                }

                FIELD_STATUS -> {
                    if (i + 1 < data.size) {
                        status = data[i + 1].toInt() and 0xFF
                        i += 2
                    } else { i++; break }
                }

                FIELD_TEMP -> {
                    if (i + 1 < data.size) {
                        temperature = data[i + 1].toInt() and 0xFF
                        i += 2
                    } else { i++; break }
                }

                FIELD_ERROR -> {
                    if (i + 1 < data.size) {
                        error = data[i + 1].toInt() and 0xFF
                        i += 2
                    } else { i++; break }
                }

                0xFF -> {
                    // End-of-packet sentinel
                    break
                }

                else -> {
                    // Unknown field — skip 1 byte to resync
                    i++
                }
            }
        }

        val result = ScooterData(
            speedKmh      = speed,
            voltageV      = voltage,
            currentA      = current,
            socPercent    = soc,
            remainingKm   = remainingKm,
            statusFlags   = status,
            temperatureC  = temperature,
            vehicleState  = error,
            field54       = lastData.field54,
            field61       = lastData.field61,
            timestampMs   = System.currentTimeMillis()
        )
        lastData = result
        Log.d(TAG, "DATA → spd=$speed km/h  volt=${voltage}V  curr=${current}A  soc=$soc%  range=$remainingKm  temp=${temperature}°C  err=$error")
        return result
    }

    private fun parseConfigPacket(data: ByteArray): ScooterData? {
        // Config packets define field metadata. We track a couple of known fields.
        var f54 = lastData.field54
        var f61 = lastData.field61

        var i = 1
        while (i < data.size - 1) {
            val field = data[i].toInt() and 0xFF
            val value = data[i + 1].toInt() and 0xFF
            when (field) {
                0x54 -> { f54 = value; i += 2 }
                0x61 -> { f61 = value; i += 2 }
                0xFF -> break
                else -> i += 2
            }
        }

        lastData = lastData.copy(field54 = f54, field61 = f61)
        return null  // Config packets don't update the UI
    }

    fun reset() {
        lastData = ScooterData()
    }

    fun isSyncCharacteristic(uuid: String): Boolean {
        return SYNC_CANDIDATES.any { it.equals(uuid, ignoreCase = true) }
    }

    fun toHexString(data: ByteArray): String =
        data.joinToString("-") { "%02X".format(it) }

    fun shortUuid(uuid: String): String {
        return if (uuid.length > 12) uuid.substring(0, 8) + "..." + uuid.substring(uuid.length - 4) else uuid
    }
}
