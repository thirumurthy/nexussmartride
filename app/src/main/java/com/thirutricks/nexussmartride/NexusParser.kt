package com.thirutricks.nexussmartride

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

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
    private const val FIELD_SPEED       = 0x01
    private const val FIELD_CALL_ACTION = 0x47  // 0x01: Answer, 0x02: Reject
    private const val FIELD_SONG_ACTION = 0x48  // 0x01: Play, 0x02: Pause, 0x03: Next, 0x04: Prev
    private const val FIELD_SOC         = 0x50
    private const val FIELD_VOLT        = 0x51
    private const val FIELD_CURR        = 0x52
    private const val FIELD_RANGE       = 0x55
    private const val FIELD_ERROR       = 0x57
    private const val FIELD_STATUS      = 0x58
    private const val FIELD_TEMP        = 0x59

    // Keep last known values so partial updates don't wipe good data
    private var lastData = ScooterData()

    fun parse(raw: ByteArray): ScooterData? {
        if (raw.isEmpty()) return null

        val b0 = raw[0].toInt() and 0xFF
        val b1 = if (raw.size > 1) raw[1].toInt() and 0xFF else -1

        // 1. Scooter Handlebar Call Button Action (0147)
        if (b0 == 0x01 && b1 == 0x47 && raw.size >= 3) {
            val action = raw[2].toInt() and 0xFF
            Log.i(TAG, "Scooter Call Action Received: $action")
            val result = lastData.copy(callAction = action, timestampMs = System.currentTimeMillis())
            lastData = result
            return result
        }

        // 2. Scooter Handlebar Song Button Action (0148 or tag 48)
        if (b0 == 0x01 && b1 == 0x48 && raw.size >= 3) {
            val action = raw[2].toInt() and 0xFF
            Log.i(TAG, "Scooter Song Action Received: $action")
            val result = lastData.copy(songAction = action, timestampMs = System.currentTimeMillis())
            lastData = result
            return result
        }
        if (raw.size >= 5 && raw[3].toInt() and 0xFF == 0x48) {
            val action = raw[4].toInt() and 0xFF
            Log.i(TAG, "Scooter Song Action Received (embedded): $action")
            val result = lastData.copy(songAction = action, timestampMs = System.currentTimeMillis())
            lastData = result
            return result
        }

        // 3. Ampere Nexus ST Live Telemetry (0150 frame - Function 13231)
        if (b0 == 0x01 && b1 == 0x50 && raw.size >= 3) {
            return parseNexusTelemetryPacket(raw)
        }

        // 4. Ampere Nexus ST Mode / Charging (0254 frame - Function 13231)
        if (b0 == 0x02 && b1 == 0x54 && raw.size >= 3) {
            return parseNexusModePacket(raw)
        }

        // 5. Generic / Legacy TLV Data Packet
        if (b0 == TYPE_DATA) {
            return parseDataPacket(raw)
        }

        // 6. Generic Config Packet
        if (b0 == TYPE_CONFIG) {
            return parseConfigPacket(raw)
        }

        return null
    }

    /**
     * Decodes Ampere Nexus ST 0150 frame matching official Hermes Function 13231.
     * Byte offsets:
     *   raw[2]      = State of charge (0..100%)
     *   raw[4..7]   = Total odometer (4 bytes Little-Endian) / 10.0 = km
     *   raw[9..10]  = Trip distance (2 bytes Little-Endian) / 10.0 = km
     *   raw[12]     = Remaining range (DTE in km)
     *   raw[14]     = Speed (km/h)
     *   raw[16]     = Max speed (km/h)
     *   raw[18]     = Average speed (km/h)
     */
    private fun parseNexusTelemetryPacket(raw: ByteArray): ScooterData {
        val soc = (raw[2].toInt() and 0xFF).coerceIn(0, 100)

        val odoKm = if (raw.size >= 8) {
            val rawOdo = ((raw[7].toLong() and 0xFF) shl 24) or
                         ((raw[6].toLong() and 0xFF) shl 16) or
                         ((raw[5].toLong() and 0xFF) shl 8) or
                         (raw[4].toLong() and 0xFF)
            rawOdo / 10.0
        } else lastData.odometerKm

        val tripKm = if (raw.size >= 11) {
            val rawTrip = ((raw[10].toInt() and 0xFF) shl 8) or (raw[9].toInt() and 0xFF)
            rawTrip / 10.0
        } else lastData.tripKm

        val dte = if (raw.size >= 13) (raw[12].toInt() and 0xFF) else lastData.remainingKm
        val spd = if (raw.size >= 15) (raw[14].toInt() and 0xFF) else lastData.speedKmh
        val maxSpd = if (raw.size >= 17) (raw[16].toInt() and 0xFF) else lastData.maxSpeedKmh
        val avgSpd = if (raw.size >= 19) (raw[18].toInt() and 0xFF) else lastData.avgSpeedKmh

        val result = lastData.copy(
            speedKmh     = spd,
            socPercent   = soc,
            odometerKm   = odoKm,
            tripKm       = tripKm,
            remainingKm  = dte,
            maxSpeedKmh  = maxSpd,
            avgSpeedKmh  = avgSpd,
            callAction   = -1,
            songAction   = -1,
            timestampMs  = System.currentTimeMillis()
        )
        lastData = result
        Log.d(TAG, "NEXUS-0150 → spd=$spd km/h  soc=$soc%  odo=${odoKm}km  trip=${tripKm}km  dte=${dte}km  mode=${result.ridingMode}")
        return result
    }

    /**
     * Decodes Ampere Nexus ST 0254 frame matching official Hermes Function 13231.
     * Byte offsets:
     *   raw[2]     = Riding mode (1=ECO, 2=City, 3=Power, else=Park)
     *   raw[4]     = Limp home state (0/1)
     *   raw[6..7]  = Time to charge (2 bytes Little-Endian) / 10.0 = minutes
     */
    private fun parseNexusModePacket(raw: ByteArray): ScooterData {
        val modeByte = raw[2].toInt() and 0xFF
        val mode = when (modeByte) {
            1 -> "ECO"
            2 -> "City"
            3 -> "Power"
            else -> "Park"
        }

        val limp = if (raw.size >= 5) (raw[4].toInt() and 0xFF) != 0 else lastData.limpHome

        val ttc = if (raw.size >= 8) {
            val rawTtc = ((raw[7].toInt() and 0xFF) shl 8) or (raw[6].toInt() and 0xFF)
            rawTtc / 10.0
        } else lastData.timeToChargeMin

        val result = lastData.copy(
            ridingMode      = mode,
            limpHome        = limp,
            timeToChargeMin = ttc,
            callAction      = -1,
            songAction      = -1,
            timestampMs     = System.currentTimeMillis()
        )
        lastData = result
        Log.d(TAG, "NEXUS-0254 → mode=$mode  limp=$limp  ttc=${ttc}m")
        return result
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
        var callAction  = -1
        var songAction  = -1

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

                FIELD_CALL_ACTION -> {
                    if (i + 1 < data.size) {
                        callAction = data[i + 1].toInt() and 0xFF
                        i += 2
                    } else { i++; break }
                }

                FIELD_SONG_ACTION -> {
                    if (i + 1 < data.size) {
                        songAction = data[i + 1].toInt() and 0xFF
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
            callAction    = callAction,
            songAction    = songAction,
            timestampMs   = System.currentTimeMillis()
        )
        lastData = result
        Log.d(TAG, "DATA → spd=$speed km/h  volt=${voltage}V  curr=${current}A  soc=$soc%  callAct=$callAction  songAct=$songAction")
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

    // ─── Unified Cluster State (160 Bytes Total) ──────────────────────────────
    private var cachedManeuver: Int = 0xFF
    private var cachedTurnDist: Int = 0
    private var cachedTripDist: Int = 0
    private var cachedRoadName: String = ""
    private var cachedGpsFixed: Boolean = false

    private var cachedCallerName: String? = null
    private var cachedCallStatus: Int = 0xFF
    private var cachedMissedCount: Int = 0

    private var cachedSongTitle: String? = null
    private var cachedArtistName: String? = null
    private var cachedIsPlaying: Boolean = false
    private var cachedProgressSec: Int = 0
    private var cachedDurationSec: Int = 0

    /**
     * Builds the exact 160-byte cluster packet bit-for-bit matching the Ampere Nexus cluster protocol.
     * Always stamps the current UNIX epoch timestamp (LE 4B) for atomic time synchronization.
     */
    fun buildUnifiedClusterPacket(
        timestamp: Long = System.currentTimeMillis() / 1000L,
        maneuverDirection: Int = cachedManeuver,
        turnDistanceMeters: Int = cachedTurnDist,
        totalDistanceMeters: Int = cachedTripDist,
        roadName: String? = cachedRoadName,
        isGpsFixed: Boolean = cachedGpsFixed,
        callerName: String? = cachedCallerName,
        callStatus: Int = cachedCallStatus,
        missedCount: Int = cachedMissedCount,
        songTitle: String? = cachedSongTitle,
        artistName: String? = cachedArtistName,
        isPlaying: Boolean = cachedIsPlaying,
        progressSec: Int = cachedProgressSec,
        durationSec: Int = cachedDurationSec
    ): ByteArray {
        // Cache current values
        cachedManeuver = maneuverDirection
        cachedTurnDist = turnDistanceMeters
        cachedTripDist = totalDistanceMeters
        cachedRoadName = roadName ?: ""
        cachedGpsFixed = isGpsFixed
        cachedCallerName = callerName
        cachedCallStatus = callStatus
        cachedMissedCount = missedCount
        cachedSongTitle = songTitle
        cachedArtistName = artistName
        cachedIsPlaying = isPlaying
        cachedProgressSec = progressSec
        cachedDurationSec = durationSec

        val timeBytes = byteArrayOf(
            (timestamp and 0xFF).toByte(),
            ((timestamp shr 8) and 0xFF).toByte(),
            ((timestamp shr 16) and 0xFF).toByte(),
            ((timestamp shr 24) and 0xFF).toByte()
        )
        val tsHex = "%02X%02X%02X%02X".format(timeBytes[0], timeBytes[1], timeBytes[2], timeBytes[3])

        val dirHex = "%02X".format(maneuverDirection and 0xFF)
        val turnDistHex = formatDistanceHexLE(turnDistanceMeters)
        val totalDistHex = formatDistanceHexLE(totalDistanceMeters)

        // Road block: 2 hex (1B) + 02 + 38 hex (19B) + 03 + 24 hex (12B) = 34 bytes = 68 hex chars (Function 13173)
        val roadHex = toPaddedHex(cachedRoadName, 64)
        val roadBlock = roadHex.substring(0, 2) + "02" + roadHex.substring(2, 40) + "03" + roadHex.substring(40, 64)

        val gpsHex = if (isGpsFixed) "01" else "FF"

        // Caller block: 44 + 4 hex (2B) + 04 + 38 hex (19B) + 05 + 6 hex (3B) = 26 bytes = 52 hex chars (Function 13174)
        val isCallActive = !callerName.isNullOrEmpty() && (callStatus == 0 || callStatus == 1 || callStatus == 2 || callStatus == 3)
        val callerBlock = if (isCallActive) {
            val nameHex = toPaddedHex(callerName ?: "", 48)
            nameHex.substring(0, 4) + "04" + nameHex.substring(4, 42) + "05" + nameHex.substring(42, 48)
        } else {
            "FFFF04" + "F".repeat(38) + "05FFFFFF"
        }

        // Call status opcode (Tag 4D, Function 13211) & Missed call count (Tag 4E, Function 13177)
        val stHex = when (callStatus) {
            0 -> "00" // Ringing incoming call
            1 -> "01" // Answered / in call
            2 -> "02" // Ended
            3 -> "03" // Missed
            else -> "FF" // Idle - MUST be FF when no active call so scooter never falsely shows "Calling"!
        }
        val missedHex = if (missedCount > 0) "%02X".format(missedCount.coerceIn(0, 255)) else "00"

        // Song title block: 45 + 11B (22 hex) + 06 + 12B (24 hex) + 00 = 26 bytes = 52 hex chars
        val songStr = if (!songTitle.isNullOrBlank()) songTitle else "No Music Selected"
        val songHex = toPaddedHex(songStr, 46)
        val songBlock = songHex.substring(0, 22) + "06" + songHex.substring(22, 46) + "00"

        // Artist block: 46 + 5B (10 hex) + 07 + 19B (38 hex) + 08 = 27 bytes = 54 hex chars
        val artistStr = if (!artistName.isNullOrBlank()) artistName else "Pick a Song You Love"
        val artistHex = toPaddedHex(artistStr, 48)
        val artistBlock = artistHex.substring(0, 10) + "07" + artistHex.substring(10, 48) + "08"

        val progHex = "%02X%02X".format(progressSec and 0xFF, (progressSec shr 8) and 0xFF)
        val durHex = "%02X%02X".format(durationSec and 0xFF, (durationSec shr 8) and 0xFF)
        val stateHex = if (isPlaying) "01" else (if (!songTitle.isNullOrBlank()) "02" else "09")

        val hexString = "0102" +
            tsHex +
            "40" + dirHex +
            "41" + turnDistHex +
            "42" + totalDistHex +
            "43" + roadBlock +
            "49" + gpsHex + "4FFF" +
            "44" + callerBlock +
            "4D" + stHex + "4E" + missedHex +
            "45" + songBlock +
            "46" + artistBlock +
            "4A" + progHex +
            "4B" + durHex +
            "4C" + stateHex +
            "390000FFFFFFFFFFFFFFFF"

        return hexStringToByteArray(hexString)
    }

    /**
     * Clears active caller alert display from cluster back to default state.
     */
    fun clearCallAlert(): ByteArray {
        cachedCallerName = null
        cachedCallStatus = 0xFF
        return buildUnifiedClusterPacket(callerName = null, callStatus = 0xFF)
    }

    /**
     * ─── Cluster Registration Handshake Packets (Characteristic 20022202) ────
     * Reverse engineered from official Ampere Connect btsnoop HCI logs.
     * The cluster firmware suppresses secondary displays (navigation arrows, song titles,
     * caller alert screens, time sync) until registered with these 4 packets.
     */
    fun buildHandshake010C(model: String = "NEXUS ST"): ByteArray {
        val sb = StringBuilder("010C2E010C")
        val hex = toAsciiHex(model.ifBlank { "NEXUS ST" })
        sb.append(hex)
        while (sb.length < 40) sb.append("FF")
        return hexStringToByteArray(sb.substring(0, 40))
    }

    fun buildHandshake0110(vin: String = "MCPN23300CLH185"): ByteArray {
        val sb = StringBuilder("01102E0110")
        val hex = toAsciiHex(vin.ifBlank { "MCPN23300CLH185" })
        sb.append(hex).append("023330")
        while (sb.length < 80) sb.append("FF")
        return hexStringToByteArray(sb.substring(0, 80))
    }

    fun buildHandshake0111(): ByteArray {
        val sb = StringBuilder("01112E0111")
        while (sb.length < 40) sb.append("FF")
        return hexStringToByteArray(sb.substring(0, 40))
    }

    fun buildHandshake0112(owner: String = "Thirumurthy"): ByteArray {
        val sb = StringBuilder("01122E0112")
        val hex = toAsciiHex(owner.ifBlank { "Thirumurthy" })
        sb.append(hex).append("FFFFFFFF02")
        while (sb.length < 80) sb.append("FF")
        return hexStringToByteArray(sb.substring(0, 80))
    }

    private fun toAsciiHex(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            sb.append("%02X".format(ch.code and 0xFF))
        }
        return sb.toString()
    }

    /**
     * Builds packet to send caller info, call status, and missed call count to the scooter dashboard.
     */
    fun buildCallAlertPacket(callerName: String?, callStatus: Int, missedCount: Int): ByteArray {
        return buildUnifiedClusterPacket(
            callerName = callerName,
            callStatus = callStatus,
            missedCount = missedCount
        )
    }

    /**
     * Builds packet to send turn-by-turn navigation data to the scooter cluster display.
     */
    fun buildNavigationPacket(
        maneuverDirection: Int,
        turnDistanceMeters: Int,
        totalDistanceMeters: Int,
        roadName: String?,
        isGpsFixed: Boolean = true
    ): ByteArray {
        return buildUnifiedClusterPacket(
            maneuverDirection = maneuverDirection,
            turnDistanceMeters = turnDistanceMeters,
            totalDistanceMeters = totalDistanceMeters,
            roadName = roadName,
            isGpsFixed = isGpsFixed
        )
    }

    /**
     * Builds packet to update scooter cluster time to the current system clock.
     */
    fun buildTimeSyncPacket(missedCount: Int): ByteArray {
        return buildUnifiedClusterPacket(missedCount = missedCount)
    }

    /**
     * Converts a distance in meters to a 4-byte little-endian hexadecimal string.
     * Matches Ampere Connect Function 13222 (convertDistanceToHexNXGT).
     */
    private fun formatDistanceHexLE(distanceMeters: Int): String {
        if (distanceMeters < 0) return "00000000"
        val clamped = distanceMeters.coerceIn(0, 0x7FFFFFFF)
        val b0 = clamped and 0xFF
        val b1 = (clamped shr 8) and 0xFF
        val b2 = (clamped shr 16) and 0xFF
        val b3 = (clamped shr 24) and 0xFF
        return "%02X%02X%02X%02X".format(b0, b1, b2, b3)
    }

    /**
     * Builds packet to provision Wi-Fi Hotspot credentials to the scooter cluster display.
     * Reverse engineered from Ampere Connect Function 13180 and 13183.
     *
     * Format: 0x20 + SSID (hex padded to 64 chars) + Password (hex padded to 128 chars) + FFFFFF
     */
    fun buildHotspotProvisioningPacket(ssid: String, password: String): ByteArray {
        val ssidHex = toPaddedHex(ssid, 64)
        val pwdHex = toPaddedHex(password, 128)
        val hexString = "20" + ssidHex + pwdHex + "FFFFFF"
        return hexStringToByteArray(hexString)
    }

    /**
     * Builds packet to send currently playing song metadata to the scooter dashboard.
     */
    fun buildMediaDisplayPacket(
        songTitle: String?,
        artistName: String?,
        isPlaying: Boolean,
        progressSec: Int = 0,
        durationSec: Int = 0
    ): ByteArray {
        return buildUnifiedClusterPacket(
            songTitle = songTitle,
            artistName = artistName,
            isPlaying = isPlaying,
            progressSec = progressSec,
            durationSec = durationSec
        )
    }

    private fun toPaddedHex(text: String, hexLen: Int): String {
        val sb = StringBuilder()
        val maxChars = hexLen / 2
        val trimmed = text.trim().take(maxChars)
        for (ch in trimmed) {
            val code = ch.code and 0xFF
            sb.append("%02X".format(code))
        }
        while (sb.length < hexLen) {
            sb.append("FF")
        }
        return sb.substring(0, hexLen)
    }

    fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            val h = Character.digit(s[i], 16)
            val l = Character.digit(s[i + 1], 16)
            data[i / 2] = ((h shl 4) or l).toByte()
            i += 2
        }
        return data
    }

    /**
     * Resolves caller display name from ContactsContract.PhoneLookup
     */
    fun getCallerName(context: Context, number: String?): String {
        if (number.isNullOrEmpty() || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return number ?: "Unknown Number"
        }
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (idx != -1) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error looking up contact: ${e.message}")
        }
        return number ?: "Unknown Number"
    }

    /**
     * Queries unacknowledged missed calls count from CallLog.Calls (type=3 AND new=1)
     */
    fun getMissedCallCount(context: Context): Int {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return 0
        }
        try {
            val uri = CallLog.Calls.CONTENT_URI
            val projection = arrayOf(CallLog.Calls.TYPE)
            val selection = "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.NEW} = ?"
            val selectionArgs = arrayOf(CallLog.Calls.MISSED_TYPE.toString(), "1")
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                return cursor.count
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting missed call count: ${e.message}")
        }
        return 0
    }
}
