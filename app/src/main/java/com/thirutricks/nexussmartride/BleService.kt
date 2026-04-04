package com.thirutricks.nexussmartride

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.lifecycle.LifecycleService
import com.thirutricks.nexussmartride.data.ScooterDatabase
import com.thirutricks.nexussmartride.data.ScooterRepository
import kotlinx.coroutines.*
import java.util.UUID

/**
 * Foreground service that manages BLE connection to Ampere Nexus scooter.
 */
class BleService : LifecycleService() {

    private lateinit var repository: ScooterRepository
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "BleService"

        // Notification
        private const val NOTIF_CHANNEL_ID = "nexus_ble"
        private const val NOTIF_ID = 1001

        // Broadcast actions
        const val ACTION_CONNECTED    = "com.thirutricks.nexussmartride.CONNECTED"
        const val ACTION_DISCONNECTED = "com.thirutricks.nexussmartride.DISCONNECTED"
        const val ACTION_DATA         = "com.thirutricks.nexussmartride.DATA"
        const val ACTION_SCAN_RESULT  = "com.thirutricks.nexussmartride.SCAN_RESULT"
        const val ACTION_SCAN_FAILED  = "com.thirutricks.nexussmartride.SCAN_FAILED"

        // Broadcast extras
        const val EXTRA_SPEED     = "speed"
        const val EXTRA_VOLTAGE   = "voltage"
        const val EXTRA_CURRENT   = "current"
        const val EXTRA_SOC       = "soc"
        const val EXTRA_RANGE     = "range"
        const val EXTRA_STATUS    = "status"
        const val EXTRA_TEMP      = "temp"
        const val EXTRA_ERROR     = "error"
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val EXTRA_DEVICE_NAME    = "device_name"

        // Commands
        const val CMD_SCAN       = "scan"
        const val CMD_CONNECT    = "connect"
        const val CMD_DISCONNECT = "disconnect"
        const val EXTRA_ADDRESS  = "address"

        // Timeouts
        private const val SCAN_PERIOD_MS       = 15_000L
        private const val WATCHDOG_TIMEOUT_MS  = 8_000L
        private const val RECONNECT_DELAY_MS   = 2_000L
        private const val POST_CONNECT_WAIT_MS = 1_600L
        private const val DESCRIPTOR_WRITE_DELAY_MS = 300L
        private const val SYNC_INTERVAL_MS         = 1800_000L // 30 minutes
        private const val DB_LOG_INTERVAL_MS       = 60_000L   // 1 minute for DB snapshots
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null

    private var targetAddress: String? = null
    private var isConnected = false
    private var isScanning = false
    private var reconnectAttempts = 0
    private val MAX_RECONNECT_ATTEMPTS = 500 // Basically forever

    private val handler = Handler(Looper.getMainLooper())

    private val descriptorQueue = ArrayDeque<BluetoothGattDescriptor>()
    private var isWritingDescriptor = false

    private var lastPacketTime = 0L
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isConnected && lastPacketTime > 0) {
                val elapsed = System.currentTimeMillis() - lastPacketTime
                if (elapsed > WATCHDOG_TIMEOUT_MS) {
                    Log.w(TAG, "Watchdog: no packet for ${elapsed}ms, forcing reconnect")
                    reconnect()
                    return
                }
            }
            handler.postDelayed(this, WATCHDOG_TIMEOUT_MS)
        }
    }

    private val stopScanRunnable = Runnable { stopScan() }
    private val reconnectRunnable = Runnable {
        targetAddress?.let { connectToDevice(it, autoConnect = true) }
    }

    private val periodicSyncRunnable = object : Runnable {
        override fun run() {
            if (isConnected) {
                bluetoothGatt?.let { gatt ->
                    Log.i(TAG, "Running periodic time sync")
                    syncScooterTime(gatt)
                }
            }
            handler.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }

    private val dbLogRunnable = object : Runnable {
        override fun run() {
            if (isConnected) {
                val data = repository.currentData.value
                if (data.isValid) {
                    serviceScope.launch {
                        repository.saveLog(data)
                    }
                }
            }
            handler.postDelayed(this, DB_LOG_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        val db = ScooterDatabase.getDatabase(this)
        repository = ScooterRepository.getInstance(db.scooterDao())
        
        createNotificationChannel()
        val manager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = manager?.adapter
        
        // Load saved address for persistent reconnection
        val prefs = getSharedPreferences("nexus_prefs", Context.MODE_PRIVATE)
        targetAddress = prefs.getString("device_address", null)
        
        handler.post(dbLogRunnable)
        
        Log.i(TAG, "BleService created, savedAddress=$targetAddress")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIF_ID, buildNotification("Searching for scooter…"))
        when (intent?.action) {
            CMD_SCAN       -> startScan()
            CMD_CONNECT    -> {
                val addr = intent.getStringExtra(EXTRA_ADDRESS) ?: return START_STICKY
                targetAddress = addr
                reconnectAttempts = 0
                // Save to prefs so it persists service restarts
                getSharedPreferences("nexus_prefs", Context.MODE_PRIVATE).edit().putString("device_address", addr).apply()
                connectToDevice(addr, autoConnect = false)
            }
            CMD_DISCONNECT -> {
                targetAddress = null
                disconnect()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        handler.removeCallbacksAndMessages(null)
        stopScan()
        disconnect()
        Log.i(TAG, "BleService destroyed")
    }

    private fun startScan() {
        if (!hasPermissions()) { sendBroadcast(ACTION_SCAN_FAILED); return }
        if (isScanning) return
        isScanning = true
        isConnected = false
        updateNotification("Scanning for Nexus scooter…")
        Log.i(TAG, "Starting BLE scan")
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        bleScanner?.startScan(null, settings, scanCallback)
        handler.postDelayed(stopScanRunnable, SCAN_PERIOD_MS)
    }

    private fun stopScan() {
        if (!isScanning) return
        isScanning = false
        handler.removeCallbacks(stopScanRunnable)
        if (hasPermissions()) bleScanner?.stopScan(scanCallback)
        bleScanner = null
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            if (!name.startsWith(NexusParser.DEVICE_NAME_PREFIX, ignoreCase = true)) return
            
            Log.i(TAG, "Found Nexus device: $name (${result.device.address})")
            stopScan()

            // Update target address and persist it
            val address = result.device.address
            targetAddress = address
            getSharedPreferences("nexus_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("device_address", address)
                .putString("device_name", name)
                .apply()

            val intent = Intent(ACTION_SCAN_RESULT).apply {
                putExtra(EXTRA_DEVICE_ADDRESS, address)
                putExtra(EXTRA_DEVICE_NAME, name)
            }
            LocalBroadcastManager.getInstance(this@BleService).sendBroadcast(intent)
            
            // Automatically connect to the found NEX_ device
            connectToDevice(address, autoConnect = false)
        }
    }

    private fun connectToDevice(address: String, autoConnect: Boolean) {
        if (!hasPermissions()) return
        Log.i(TAG, "Connecting to $address (autoConnect=$autoConnect)")
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return
        bluetoothGatt?.close()
        bluetoothGatt = device.connectGatt(this, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun disconnect() {
        isConnected = false
        if (hasPermissions()) bluetoothGatt?.disconnect()
        handler.postDelayed({ bluetoothGatt?.close(); bluetoothGatt = null }, 500)
    }

    private fun reconnect() {
        if (targetAddress == null || reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) return
        reconnectAttempts++
        val delay = (RECONNECT_DELAY_MS * reconnectAttempts).coerceAtMost(60_000L)
        Log.i(TAG, "Scheduling reconnect #$reconnectAttempts in ${delay}ms")
        disconnect()
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delay)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                handler.postDelayed({ if (hasPermissions()) gatt.discoverServices() }, POST_CONNECT_WAIT_MS)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                if (targetAddress != null) reconnect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered (${gatt.services.size})")
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                gatt.requestMtu(512)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
            }
        }

        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            enableNotifications(gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            isWritingDescriptor = false
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // If this is the last descriptor in our setup queue (or the telemetry one)
                if (descriptor.characteristic.uuid.toString().lowercase() == NexusParser.CHAR_TELEMETRY.lowercase()) {
                    isConnected = true
                    reconnectAttempts = 0
                    lastPacketTime = System.currentTimeMillis()
                    handler.removeCallbacks(watchdogRunnable)
                    handler.postDelayed(watchdogRunnable, WATCHDOG_TIMEOUT_MS)
                    
                    // Sync time immediately upon successful secure connection
                    syncScooterTime(gatt)
                    
                    handler.removeCallbacks(periodicSyncRunnable)
                    handler.postDelayed(periodicSyncRunnable, SYNC_INTERVAL_MS)
                }
            }
            handler.postDelayed({ writeNextDescriptor(gatt) }, DESCRIPTOR_WRITE_DELAY_MS)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value ?: return
            lastPacketTime = System.currentTimeMillis()
            val parsed = NexusParser.parse(data) ?: return
            
            // Push to repository for cross-app access
            repository.updateCurrentData(parsed)

            val intent = Intent(ACTION_DATA).apply {
                putExtra(EXTRA_SPEED,   parsed.speedKmh)
                putExtra(EXTRA_VOLTAGE, parsed.voltageV)
                putExtra(EXTRA_CURRENT, parsed.currentA)
                putExtra(EXTRA_SOC,     parsed.socPercent)
                putExtra(EXTRA_RANGE,   parsed.remainingKm)
                putExtra(EXTRA_STATUS,  parsed.statusFlags)
                putExtra(EXTRA_TEMP,    parsed.temperatureC)
                putExtra(EXTRA_ERROR,   parsed.vehicleState)
            }
            LocalBroadcastManager.getInstance(this@BleService).sendBroadcast(intent)
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt) {
        if (!hasPermissions()) return
        descriptorQueue.clear()
        val cccdUuid = UUID.fromString(NexusParser.DESCRIPTOR_CCCD)
        for (service in gatt.services) {
            for (char in service.characteristics) {
                val props = char.properties
                if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                    gatt.setCharacteristicNotification(char, true)
                    char.getDescriptor(cccdUuid)?.let {
                        it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        descriptorQueue.add(it)
                    }
                }
            }
        }
        writeNextDescriptor(gatt)
    }

    private fun writeNextDescriptor(gatt: BluetoothGatt) {
        if (isWritingDescriptor || descriptorQueue.isEmpty() || !hasPermissions()) return
        isWritingDescriptor = true
        val descriptor = descriptorQueue.removeFirst()
        if (!gatt.writeDescriptor(descriptor)) {
            isWritingDescriptor = false
            handler.postDelayed({ writeNextDescriptor(gatt) }, 500)
        }
    }

    private fun syncScooterTime(gatt: BluetoothGatt) {
        if (!hasPermissions()) return
        Log.i(TAG, "Executing time sync routine (Staggered Unix + Calendar)")

        // Config block (Unix Time)
        val configPayload = ByteArray(160) { 0xFF.toByte() }
        val unixTime = (System.currentTimeMillis() / 1000L).toInt()
        configPayload[0] = 0x01
        configPayload[1] = 0x02
        configPayload[2] = (unixTime and 0xFF).toByte()
        configPayload[3] = ((unixTime shr 8) and 0xFF).toByte()
        configPayload[4] = ((unixTime shr 16) and 0xFF).toByte()
        configPayload[5] = ((unixTime shr 24) and 0xFF).toByte()
        configPayload[6] = 0x40.toByte()
        configPayload[8] = 0x41.toByte()
        configPayload[13] = 0x42.toByte()

        // Calendar block
        val cal = java.util.Calendar.getInstance()
        val year = cal.get(java.util.Calendar.YEAR)
        val calendarPayload = byteArrayOf(
            0x01,
            (year and 0xFF).toByte(),
            ((year shr 8) and 0xFF).toByte(),
            (cal.get(java.util.Calendar.MONTH) + 1).toByte(),
            cal.get(java.util.Calendar.DAY_OF_MONTH).toByte(),
            cal.get(java.util.Calendar.HOUR_OF_DAY).toByte(),
            cal.get(java.util.Calendar.MINUTE).toByte(),
            cal.get(java.util.Calendar.SECOND).toByte()
        )

        val candidates = listOf(NexusParser.SERVICE_UUID_SECONDARY, NexusParser.SERVICE_UUID_TERTIARY, NexusParser.SERVICE_UUID_QUATERNARY)
        for (service in gatt.services) {
            for (char in service.characteristics) {
                if (candidates.any { it.equals(char.uuid.toString(), ignoreCase = true) }) {
                    if ((char.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) {
                        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        handler.postDelayed({
                            if (isConnected && hasPermissions()) {
                                Log.d(TAG, "Sync: Unix to ${char.uuid}")
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    gatt.writeCharacteristic(char, configPayload, writeType)
                                } else {
                                    @Suppress("DEPRECATION") char.value = configPayload
                                    @Suppress("DEPRECATION") char.writeType = writeType
                                    @Suppress("DEPRECATION") gatt.writeCharacteristic(char)
                                }
                            }
                        }, 1000)
                        handler.postDelayed({
                            if (isConnected && hasPermissions()) {
                                Log.d(TAG, "Sync: Calendar to ${char.uuid}")
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    gatt.writeCharacteristic(char, calendarPayload, writeType)
                                } else {
                                    @Suppress("DEPRECATION") char.value = calendarPayload
                                    @Suppress("DEPRECATION") char.writeType = writeType
                                    @Suppress("DEPRECATION") gatt.writeCharacteristic(char)
                                }
                            }
                        }, 2500)
                    }
                }
            }
        }
    }

    private fun hasPermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.all { ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(NOTIF_CHANNEL_ID, "Nexus BLE", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
        .setContentTitle("Ampere Nexus").setContentText(text).setSmallIcon(android.R.drawable.ic_menu_compass)
        .setOngoing(true).setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun sendBroadcast(action: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(action))
    }
}
