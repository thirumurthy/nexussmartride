package com.thirutricks.nexussmartride

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.*
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
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
 * Includes seamless auto-reconnect, global media key routing, and cluster call alerts.
 */
class BleService : LifecycleService() {

    private lateinit var repository: ScooterRepository
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "BleService"
        var instance: BleService? = null
            private set

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
        private const val WATCHDOG_TIMEOUT_MS  = 60_000L // 60s avoids false watchdog disconnects during sync
        private const val POST_CONNECT_WAIT_MS = 1_600L
        private const val DESCRIPTOR_WRITE_DELAY_MS = 300L
        private const val SYNC_INTERVAL_MS         = 20_000L // 20s periodic sync matches official app
        private const val DB_LOG_INTERVAL_MS       = 60_000L // 1 minute for DB snapshots
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null

    private var targetAddress: String? = null
    private var isConnected = false
    private var isScanning = false
    private var isAutoReconnecting = false

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

    private val periodicSyncRunnable = object : Runnable {
        override fun run() {
            if (isConnected) {
                bluetoothGatt?.let { gatt ->
                    Log.d(TAG, "Running periodic time sync & handshake refresh (20s interval)")
                    val missed = NexusParser.getMissedCallCount(this@BleService)
                    val timePacket = NexusParser.buildTimeSyncPacket(missed)
                    writePacketToCluster(timePacket)
                    sendRegistrationHandshake(gatt)
                }
            }
            handler.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }

    private val clearCallRunnable = Runnable {
        Log.i(TAG, "Call Idle timeout elapsed: Clearing cluster call display back to default")
        sendCallDetailsToCluster(callerName = null, callStatus = 0xFF, missedCount = NexusParser.getMissedCallCount(this@BleService))
    }

    private var lastPeriodicCloudSyncTime = 0L
    private val dbLogRunnable = object : Runnable {
        override fun run() {
            if (isConnected) {
                val data = repository.currentData.value
                if (data.isValid) {
                    serviceScope.launch {
                        repository.saveLog(data)
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastPeriodicCloudSyncTime >= 10 * 60 * 1000L) {
                        lastPeriodicCloudSyncTime = now
                        com.thirutricks.nexussmartride.sync.NexusCloudSyncManager.getInstance(this@BleService)
                            .onPeriodicSync(targetAddress, data)
                    }
                }
            }
            handler.postDelayed(this, DB_LOG_INTERVAL_MS)
        }
    }

    // ─── Broadcast Receivers ─────────────────────────────────────────────────

    private val phoneStateReceiver = object : BroadcastReceiver() {
        private var lastState = TelephonyManager.EXTRA_STATE_IDLE
        private var lastIncomingNumber: String? = null

        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return

            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    handler.removeCallbacks(clearCallRunnable)
                    val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                    lastIncomingNumber = number
                    val callerName = NexusParser.getCallerName(context, number)
                    val missed = NexusParser.getMissedCallCount(context)
                    Log.i(TAG, "Incoming Call: $callerName ($number), missed: $missed")
                    sendCallDetailsToCluster(callerName, callStatus = 0, missedCount = missed)
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    handler.removeCallbacks(clearCallRunnable)
                    if (lastState == TelephonyManager.EXTRA_STATE_RINGING) {
                        val callerName = NexusParser.getCallerName(context, lastIncomingNumber)
                        val missed = NexusParser.getMissedCallCount(context)
                        Log.i(TAG, "Call Answered: $callerName")
                        sendCallDetailsToCluster(callerName, callStatus = 1, missedCount = missed)
                    }
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    val wasRinging = (lastState == TelephonyManager.EXTRA_STATE_RINGING)
                    val wasOffhook = (lastState == TelephonyManager.EXTRA_STATE_OFFHOOK)
                    if (wasRinging || wasOffhook) {
                        val missed = NexusParser.getMissedCallCount(context)
                        val status = if (wasRinging && missed > 0) 3 else 2 // 3=Missed, 2=Ended
                        val callerName = NexusParser.getCallerName(context, lastIncomingNumber)
                        Log.i(TAG, "Call Ended: status=$status, missed=$missed, caller=$callerName")
                        sendCallDetailsToCluster(callerName, callStatus = status, missedCount = missed)
                        // After 2.5 seconds, clear the call alert from the scooter display
                        handler.removeCallbacks(clearCallRunnable)
                        handler.postDelayed(clearCallRunnable, 2500L)
                    } else {
                        // Spurious or initial idle broadcast - ensure no call alert
                        handler.removeCallbacks(clearCallRunnable)
                        val missed = NexusParser.getMissedCallCount(context)
                        sendCallDetailsToCluster(callerName = null, callStatus = 0xFF, missedCount = missed)
                    }
                }
            }
            lastState = state
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    Log.i(TAG, "Bluetooth turned ON: initiating auto-connection")
                    targetAddress?.let { startAutoReconnectScan(it) } ?: startScan()
                } else if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                    disconnect()
                }
            }
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this

        val db = ScooterDatabase.getDatabase(this)
        repository = ScooterRepository.getInstance(db.scooterDao())

        createNotificationChannel()
        val manager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = manager?.adapter

        // Load saved address for persistent reconnection
        val prefs = getSharedPreferences("nexus_prefs", Context.MODE_PRIVATE)
        targetAddress = prefs.getString("device_address", null)

        handler.post(dbLogRunnable)

        // Register telephony and bluetooth state receivers
        try {
            registerReceiver(phoneStateReceiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
            registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        } catch (e: Exception) {
            Log.e(TAG, "Error registering receivers: ${e.message}")
        }

        Log.i(TAG, "BleService created, savedAddress=$targetAddress")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIF_ID, buildNotification("Searching for scooter…"))
        when (intent?.action) {
            CMD_SCAN -> startScan()
            CMD_CONNECT -> {
                val addr = intent.getStringExtra(EXTRA_ADDRESS) ?: return START_STICKY
                targetAddress = addr
                getSharedPreferences("nexus_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("device_address", addr)
                    .apply()
                connectToDevice(addr, autoConnect = false)
            }
            CMD_DISCONNECT -> {
                targetAddress = null
                stopAutoReconnectScan()
                disconnect()
            }
            else -> {
                // If service started by system/boot without specific intent, auto-reconnect to saved scooter
                if (!isConnected && targetAddress != null) {
                    startAutoReconnectScan(targetAddress!!)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "onTaskRemoved: App swiped away from recent apps; maintaining BleService in foreground")
        // Keep foreground service notification active
        updateNotification(if (isConnected) "Connected to Ampere Nexus" else "Searching for scooter…")
        // If not connected and we have a saved target address, ensure targeted auto-reconnect scan is active
        if (!isConnected && targetAddress != null) {
            startAutoReconnectScan(targetAddress!!)
        }
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        serviceScope.cancel()
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(phoneStateReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(bluetoothStateReceiver) } catch (e: Exception) {}
        stopScan()
        stopAutoReconnectScan()
        disconnect()
        Log.i(TAG, "BleService destroyed")
    }

    // ─── BLE Scanning & Auto-Reconnection ────────────────────────────────────

    private fun startScan() {
        if (!hasPermissions()) { sendBroadcast(ACTION_SCAN_FAILED); return }
        if (isScanning) return
        isScanning = true
        isConnected = false
        updateNotification("Scanning for Nexus scooter…")
        Log.i(TAG, "Starting manual BLE scan")

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

            connectToDevice(address, autoConnect = false)
        }
    }

    /**
     * Rock-solid seamless auto-reconnection:
     * Uses targeted BLE background scan matching the saved MAC address.
     * The exact moment the scooter turns ON and advertises, onScanResult fires
     * and connects directly within < 500ms without laggy exponential backoff loops.
     */
    private fun startAutoReconnectScan(address: String) {
        if (!hasPermissions() || isConnected || isAutoReconnecting) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        isAutoReconnecting = true
        Log.i(TAG, "Starting targeted auto-reconnect scan for: $address")
        updateNotification("Waiting for scooter ($address)…")

        val filters = listOf(
            ScanFilter.Builder().setDeviceAddress(address).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        try {
            scanner.startScan(filters, settings, autoReconnectCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting auto-reconnect scan: ${e.message}")
            isAutoReconnecting = false
        }
    }

    private val autoReconnectCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val addr = result.device.address
            Log.i(TAG, "Target scooter detected in scan: $addr. Connecting immediately!")
            stopAutoReconnectScan()
            connectToDevice(addr, autoConnect = false)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Auto-reconnect scan failed: $errorCode")
            isAutoReconnecting = false
            handler.postDelayed({ targetAddress?.let { startAutoReconnectScan(it) } }, 2000)
        }
    }

    private fun stopAutoReconnectScan() {
        if (!isAutoReconnecting) return
        isAutoReconnecting = false
        if (hasPermissions()) {
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(autoReconnectCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping auto-reconnect scan: ${e.message}")
            }
        }
    }

    private fun connectToDevice(address: String, autoConnect: Boolean) {
        if (!hasPermissions()) return
        Log.i(TAG, "Connecting to $address (autoConnect=$autoConnect)")
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return

        // Clean up previous GATT instance to prevent Error 133 / handle leaks
        bluetoothGatt?.close()
        bluetoothGatt = null

        bluetoothGatt = device.connectGatt(this, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun disconnect() {
        isConnected = false
        if (hasPermissions()) {
            try {
                bluetoothGatt?.disconnect()
            } catch (e: Exception) {}
        }
        handler.postDelayed({
            try {
                bluetoothGatt?.close()
            } catch (e: Exception) {}
            bluetoothGatt = null
        }, 500)
    }

    private fun reconnect() {
        val addr = targetAddress ?: return
        Log.i(TAG, "Scheduling immediate background scan auto-reconnection for $addr")
        disconnect()
        handler.postDelayed({
            startAutoReconnectScan(addr)
        }, 800)
    }

    // ─── GATT Callback ────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                stopAutoReconnectScan()
                stopScan()
                handler.postDelayed({ if (hasPermissions()) gatt.discoverServices() }, POST_CONNECT_WAIT_MS)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                sendBroadcast(ACTION_DISCONNECTED)
                updateNotification("Disconnected from scooter")

                // Cloud Sync on Cluster Disconnect (Ride End)
                com.thirutricks.nexussmartride.sync.NexusCloudSyncManager.getInstance(this@BleService)
                    .onClusterDisconnected(targetAddress, repository.currentData.value)

                if (targetAddress != null) {
                    reconnect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered (${gatt.services.size})")
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                val mtuReq = gatt.requestMtu(512)
                if (!mtuReq) {
                    Log.w(TAG, "requestMtu returned false, proceeding to enableNotifications directly")
                    enableNotifications(gatt)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "onMtuChanged: mtu=$mtu, status=$status")
            // Immediately start notification setup regardless of PHY update
            enableNotifications(gatt)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                try {
                    gatt.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
                } catch (e: Exception) {
                    Log.w(TAG, "setPreferredPhy exception: ${e.message}")
                }
            }
        }

        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            Log.i(TAG, "onPhyUpdate: txPhy=$txPhy, rxPhy=$rxPhy, status=$status")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            isWritingDescriptor = false
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (descriptorQueue.isEmpty()) {
                    Log.i(TAG, "GATT Setup Complete. Ready for communication.")
                    isConnected = true
                    lastPacketTime = System.currentTimeMillis()
                    updateNotification("Connected to Ampere Nexus")
                    sendBroadcast(ACTION_CONNECTED)

                    handler.removeCallbacks(watchdogRunnable)
                    handler.postDelayed(watchdogRunnable, WATCHDOG_TIMEOUT_MS)

                    // Execute time sync across multiple candidate characteristics with live toasts
                    syncScooterTime(gatt)

                    // Immediately query and sync active song if media player is running
                    MapNotificationListenerService.triggerActiveMediaSync()

                    handler.removeCallbacks(periodicSyncRunnable)
                    handler.postDelayed(periodicSyncRunnable, SYNC_INTERVAL_MS)

                    // Cloud Sync on Cluster Connect (Session Start)
                    com.thirutricks.nexussmartride.sync.NexusCloudSyncManager.getInstance(this@BleService)
                        .onClusterConnected(targetAddress, repository.currentData.value)
                }
            }
            handler.postDelayed({ writeNextDescriptor(gatt) }, DESCRIPTOR_WRITE_DELAY_MS)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.removeCallbacks(charWriteTimeoutRunnable)
            val shortUuid = NexusParser.shortUuid(characteristic.uuid.toString())
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Cluster write success on $shortUuid")
                if (NexusParser.isSyncCharacteristic(characteristic.uuid.toString())) {
                    showToast("Nexus: $shortUuid Synced OK")
                }
            } else {
                Log.w(TAG, "Cluster write failed on $shortUuid, status=$status")
                if (NexusParser.isSyncCharacteristic(characteristic.uuid.toString())) {
                    showToast("Sync Error $shortUuid: Stat=$status")
                }
            }
            synchronized(charWriteQueue) {
                if (charWriteQueue.isNotEmpty()) {
                    charWriteQueue.removeFirst()
                }
            }
            isWritingCharacteristic = false
            handler.postDelayed({ writeNextCharacteristic() }, 60L)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value ?: return
            lastPacketTime = System.currentTimeMillis()
            val parsed = NexusParser.parse(data) ?: return

            repository.updateCurrentData(parsed)

            // Feed live telemetry to Edge AI Engine
            com.thirutricks.nexussmartride.ai.SmartAiEngine.updateTelemetry(parsed)

            // Handle Global Media Action from Scooty Handlebar
            if (parsed.songAction != -1) {
                handleGlobalMediaAction(parsed.songAction)
            }

            // Handle Call Answering / Rejecting from Scooty Handlebar
            if (parsed.callAction != -1) {
                handleScooterCallAction(parsed.callAction)
            }

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

    // ─── Global Media & Call Actions ─────────────────────────────────────────

    /**
     * Executes media action globally using Android system AudioManager and MediaController.
     * Controls Spotify, YouTube, YouTube Music, Apple Music, Audible, or any active media player.
     *
     * 0x01 = Play
     * 0x02 = Pause
     * 0x03 = Next Track
     * 0x04 = Previous Track
     */
    private fun handleGlobalMediaAction(action: Int) {
        val controller = MapNotificationListenerService.activeMediaController
        if (controller != null) {
            try {
                when (action) {
                    0x01 -> controller.transportControls.play()
                    0x02 -> controller.transportControls.pause()
                    0x03 -> controller.transportControls.skipToNext()
                    0x04 -> controller.transportControls.skipToPrevious()
                }
                val label = when (action) {
                    0x01 -> "Play"
                    0x02 -> "Pause"
                    0x03 -> "Next Track"
                    0x04 -> "Previous Track"
                    else -> ""
                }
                showToast("Scooter: $label (${controller.packageName})")
                return
            } catch (e: Exception) {
                Log.w(TAG, "MediaController action failed: ${e.message}, falling back to AudioManager")
            }
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val keyCode = when (action) {
            0x01 -> KeyEvent.KEYCODE_MEDIA_PLAY
            0x02 -> KeyEvent.KEYCODE_MEDIA_PAUSE
            0x03 -> KeyEvent.KEYCODE_MEDIA_NEXT
            0x04 -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return
        }

        Log.i(TAG, "Executing Global Media Action via KeyEvent: $action (KeyCode=$keyCode)")
        val eventTime = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))

        val label = when (action) {
            0x01 -> "Play"
            0x02 -> "Pause"
            0x03 -> "Next Track"
            0x04 -> "Previous Track"
            else -> ""
        }
        showToast("Scooter: $label")
    }

    /**
     * Executes call actions from the scooter cluster buttons.
     * 0x01 = Answer Call
     * 0x02 = Reject Call
     */
    private fun handleScooterCallAction(action: Int) {
        Log.i(TAG, "Executing Scooter Call Action: $action")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                when (action) {
                    0x01 -> {
                        Log.i(TAG, "Answering call via scooter button")
                        telecomManager?.acceptRingingCall()
                        showToast("Call Answered")
                    }
                    0x02 -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            Log.i(TAG, "Rejecting call via scooter button")
                            telecomManager?.endCall()
                            showToast("Call Rejected")
                        }
                    }
                }
            } else {
                Log.w(TAG, "ANSWER_PHONE_CALLS permission not granted")
            }
        }
    }

    // ─── Thread-Safe Characteristic Write Queue with Watchdog ─────────────────
    private val charWriteQueue = java.util.ArrayDeque<Pair<BluetoothGattCharacteristic, ByteArray>>()
    private var isWritingCharacteristic = false

    private val charWriteTimeoutRunnable = Runnable {
        Log.w(TAG, "Characteristic write timed out after 1500ms! Auto-advancing write queue...")
        synchronized(charWriteQueue) {
            if (charWriteQueue.isNotEmpty()) {
                charWriteQueue.removeFirst()
            }
        }
        isWritingCharacteristic = false
        handler.postDelayed({ writeNextCharacteristic() }, 100L)
    }

    private fun queueCharacteristicWrite(char: BluetoothGattCharacteristic, data: ByteArray) {
        synchronized(charWriteQueue) {
            charWriteQueue.add(Pair(char, data))
            if (!isWritingCharacteristic) {
                writeNextCharacteristic()
            }
        }
    }

    private fun writeNextCharacteristic() {
        handler.removeCallbacks(charWriteTimeoutRunnable)
        val gatt = bluetoothGatt ?: run {
            isWritingCharacteristic = false
            return
        }
        val next = synchronized(charWriteQueue) {
            if (charWriteQueue.isEmpty()) {
                isWritingCharacteristic = false
                return
            }
            isWritingCharacteristic = true
            charWriteQueue.peek()
        } ?: return

        val char = next.first
        val data = next.second
        val writeType = if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        // Arm 1500ms watchdog so dropped GATT callbacks never freeze future packet writes
        handler.postDelayed(charWriteTimeoutRunnable, 1500L)

        val success = writeCharacteristicCompat(gatt, char, data, writeType)
        if (!success) {
            Log.w(TAG, "writeCharacteristic returned false for ${NexusParser.shortUuid(char.uuid.toString())}")
            handler.removeCallbacks(charWriteTimeoutRunnable)
            synchronized(charWriteQueue) {
                if (charWriteQueue.isNotEmpty()) charWriteQueue.removeFirst()
            }
            isWritingCharacteristic = false
            handler.postDelayed({ writeNextCharacteristic() }, 200)
        } else if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            handler.removeCallbacks(charWriteTimeoutRunnable)
            synchronized(charWriteQueue) {
                if (charWriteQueue.isNotEmpty()) charWriteQueue.removeFirst()
            }
            isWritingCharacteristic = false
            handler.postDelayed({ writeNextCharacteristic() }, 50)
        }
    }

    /**
     * Sends caller name, call status, and missed call count to the scooter dashboard.
     */
    fun sendCallDetailsToCluster(callerName: String?, callStatus: Int, missedCount: Int) {
        val payload = NexusParser.buildCallAlertPacket(callerName, callStatus, missedCount)
        writePacketToCluster(payload)
    }

    /**
     * Sends currently playing song metadata to the scooter dashboard.
     */
    fun sendMediaDetailsToCluster(
        title: String?,
        artist: String?,
        isPlaying: Boolean,
        progressSec: Int = 0,
        durationSec: Int = 0
    ) {
        if (!title.isNullOrBlank()) {
            showToast("Song: $title")
        }
        val payload = NexusParser.buildMediaDisplayPacket(title, artist, isPlaying, progressSec, durationSec)
        writePacketToCluster(payload)
    }

    /**
     * Sends Google Maps turn-by-turn navigation cues to the scooter cluster display (Option A).
     */
    fun sendNavigationToCluster(
        maneuverDirection: Int,
        turnDistanceMeters: Int,
        totalDistanceMeters: Int,
        roadName: String?,
        isGpsFixed: Boolean = true
    ) {
        val payload = NexusParser.buildNavigationPacket(
            maneuverDirection,
            turnDistanceMeters,
            totalDistanceMeters,
            roadName,
            isGpsFixed
        )
        writePacketToCluster(payload)
    }

    /**
     * Sends raw packet directly to the scooter cluster (used for Option B Wi-Fi provisioning).
     */
    fun writePacketToClusterDirect(payload: ByteArray) {
        writePacketToCluster(payload)
    }

    private fun writePacketToCluster(payload: ByteArray) {
        val gatt = bluetoothGatt ?: return
        if (!isConnected || !hasPermissions()) return

        // 160-byte cluster frame & 100-byte Wi-Fi provisioning packet target 20032202 (TERTIARY)
        var targetChar = findCharacteristic(gatt, NexusParser.SERVICE_UUID_TERTIARY)
        if (targetChar == null) {
            val fallbacks = listOf(
                NexusParser.SERVICE_UUID_SECONDARY,
                NexusParser.SERVICE_UUID_QUATERNARY,
                NexusParser.CHAR_TELEMETRY
            )
            for (uuid in fallbacks) {
                targetChar = findCharacteristic(gatt, uuid)
                if (targetChar != null) break
            }
        }
        targetChar?.let { queueCharacteristicWrite(it, payload) }
    }

    private fun writeHandshakeToCluster(payload: ByteArray) {
        val gatt = bluetoothGatt ?: return
        if (!isConnected || !hasPermissions()) return

        // Registration handshake packets (010C, 0110, 0111, 0112) target 20022202 (SECONDARY)
        val targetChar = findCharacteristic(gatt, NexusParser.SERVICE_UUID_SECONDARY)
            ?: findCharacteristic(gatt, NexusParser.SERVICE_UUID_TERTIARY)

        targetChar?.let { queueCharacteristicWrite(it, payload) }
    }

    private fun findCharacteristic(gatt: BluetoothGatt, targetUuid: String): BluetoothGattCharacteristic? {
        for (service in gatt.services) {
            for (char in service.characteristics) {
                if (char.uuid.toString().equals(targetUuid, ignoreCase = true) &&
                    ((char.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0)) {
                    return char
                }
            }
        }
        return null
    }

    /**
     * Manually triggers time sync routine across all candidate characteristics with toast messages.
     */
    fun triggerManualSync() {
        val gatt = bluetoothGatt ?: run {
            showToast("Cannot sync: Not connected to scooter")
            return
        }
        if (!isConnected) {
            showToast("Cannot sync: Scooter not connected")
            return
        }
        syncScooterTime(gatt)
        MapNotificationListenerService.triggerActiveMediaSync()
    }

    // ─── Time Sync Routine Across Candidate Characteristics ──────────────────

    private fun syncScooterTime(gatt: BluetoothGatt) {
        if (!hasPermissions() || !isConnected) return

        val prefs = getSharedPreferences("nexus_prefs", Context.MODE_PRIVATE)
        val method = prefs.getInt("sync_method", 0) // 0=Both, 1=Unix, 2=Calendar
        val methodText = when (method) {
            1 -> "Unix Only"
            2 -> "Calendar Only"
            else -> "Full Sync"
        }

        Log.i(TAG, "Executing time sync routine across candidate addresses ($methodText)")
        showToast("Syncing time ($methodText)...")

        // 160-byte Unix packet (exact matching official app protocol)
        val missed = NexusParser.getMissedCallCount(this)
        val configPayload = NexusParser.buildTimeSyncPacket(missed)

        // 8-byte Calendar block (used only if user explicitly selects Calendar Only)
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

        val model = prefs.getString("scooter_model", "NEXUS ST") ?: "NEXUS ST"
        val vin = prefs.getString("scooter_vin", "MCPN23300CLH185") ?: "MCPN23300CLH185"
        val owner = prefs.getString("scooter_owner", "Thirumurthy") ?: "Thirumurthy"

        val p1 = NexusParser.buildHandshake010C(model)
        val p2 = NexusParser.buildHandshake0110(vin)
        val p3 = NexusParser.buildHandshake0111()
        val p4 = NexusParser.buildHandshake0112(owner)

        var candidateDelay = 0L
        for (candidateUuid in NexusParser.SYNC_CANDIDATES) {
            val targetChar = findCharacteristic(gatt, candidateUuid) ?: continue
            val shortUuid = NexusParser.shortUuid(targetChar.uuid.toString())

            handler.postDelayed({
                if (!isConnected || !hasPermissions()) return@postDelayed

                Log.i(TAG, "Sequential Candidate Sync: Attempting write to $shortUuid")
                showToast("Syncing via $shortUuid…")

                when {
                    candidateUuid.equals(NexusParser.SERVICE_UUID_TERTIARY, ignoreCase = true) -> {
                        // Characteristic 20032202: 160-byte cluster frame
                        if (method == 2) {
                            queueCharacteristicWrite(targetChar, calendarPayload)
                        } else {
                            queueCharacteristicWrite(targetChar, configPayload)
                        }
                    }
                    candidateUuid.equals(NexusParser.SERVICE_UUID_SECONDARY, ignoreCase = true) -> {
                        // Characteristic 20022202: 4 registration handshake packets
                        queueCharacteristicWrite(targetChar, p1)
                        queueCharacteristicWrite(targetChar, p2)
                        queueCharacteristicWrite(targetChar, p3)
                        queueCharacteristicWrite(targetChar, p4)
                    }
                    else -> {
                        // Candidate probe write
                        if (method == 2) {
                            queueCharacteristicWrite(targetChar, calendarPayload)
                        } else {
                            queueCharacteristicWrite(targetChar, configPayload)
                        }
                    }
                }
            }, candidateDelay)

            candidateDelay += 2000L
        }
    }

    private fun sendRegistrationHandshake(gatt: BluetoothGatt) {
        val targetChar = findCharacteristic(gatt, NexusParser.SERVICE_UUID_SECONDARY)
            ?: findCharacteristic(gatt, NexusParser.SERVICE_UUID_TERTIARY) ?: return

        val prefs = getSharedPreferences("nexus_prefs", Context.MODE_PRIVATE)
        val model = prefs.getString("scooter_model", "NEXUS ST") ?: "NEXUS ST"
        val vin = prefs.getString("scooter_vin", "MCPN23300CLH185") ?: "MCPN23300CLH185"
        val owner = prefs.getString("scooter_owner", "Thirumurthy") ?: "Thirumurthy"

        val p1 = NexusParser.buildHandshake010C(model)
        val p2 = NexusParser.buildHandshake0110(vin)
        val p3 = NexusParser.buildHandshake0111()
        val p4 = NexusParser.buildHandshake0112(owner)

        Log.d(TAG, "Queueing 4 registration handshake packets to ${NexusParser.shortUuid(targetChar.uuid.toString())}")
        queueCharacteristicWrite(targetChar, p1)
        queueCharacteristicWrite(targetChar, p2)
        queueCharacteristicWrite(targetChar, p3)
        queueCharacteristicWrite(targetChar, p4)
    }

    // ─── BLE Descriptor & Notification Setup ─────────────────────────────────

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

    private fun writeCharacteristicCompat(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, data: ByteArray, writeType: Int): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeCharacteristic(char, data, writeType)
            result == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION") char.value = data
            @Suppress("DEPRECATION") char.writeType = writeType
            @Suppress("DEPRECATION") val result = gatt.writeCharacteristic(char)
            result
        }
    }

    private var currentToast: Toast? = null
    private fun showToast(msg: String) {
        handler.post {
            try {
                currentToast?.cancel()
                val toast = Toast.makeText(this@BleService, msg, Toast.LENGTH_SHORT)
                currentToast = toast
                toast.show()
            } catch (e: Exception) {
                Log.w(TAG, "showToast error: ${e.message}")
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
