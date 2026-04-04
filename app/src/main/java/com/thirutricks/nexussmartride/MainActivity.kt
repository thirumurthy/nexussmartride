package com.thirutricks.nexussmartride

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.lifecycle.lifecycleScope
import com.thirutricks.nexussmartride.data.ScooterDatabase
import com.thirutricks.nexussmartride.data.ScooterRepository
import com.thirutricks.nexussmartride.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main dashboard for Ampere Nexus telemetry.
 * Binds to BleService via LocalBroadcastManager.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: ScooterRepository
    private val prefs by lazy { getSharedPreferences("nexus_prefs", MODE_PRIVATE) }

    // Saved device (so we auto-connect on next launch)
    private var savedAddress: String?
        get() = prefs.getString("device_address", null)
        set(v) = prefs.edit().putString("device_address", v).apply()

    private var savedName: String?
        get() = prefs.getString("device_name", null)
        set(v) = prefs.edit().putString("device_name", v).apply()

    private var isConnected = false
    private var lastUpdateTime = 0L
    private var packetCount = 0
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // UI blink animation for connection indicator
    private val blinkHandler = Handler(Looper.getMainLooper())
    private var blinkState = false

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val db = ScooterDatabase.getDatabase(this)
        repository = ScooterRepository.getInstance(db.scooterDao())

        // Keep screen on while connected
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupUi()
        registerReceivers()
        observeData()
        checkPermissionsAndStart()
    }

    private fun observeData() {
        lifecycleScope.launch {
            repository.currentData.collectLatest { data ->
                updateUi(data)
            }
        }
        
        // Periodic travel stats refresh
        lifecycleScope.launch {
            while (true) {
                refreshTravelStats()
                kotlinx.coroutines.delay(10_000)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Show stale indicator if data is old
        updateStaleIndicator()
    }

    override fun onDestroy() {
        super.onDestroy()
        blinkHandler.removeCallbacksAndMessages(null)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(bleReceiver)
    }

    // ─── UI Setup ─────────────────────────────────────────────────────────────

    private fun setupUi() {
        // Scan / Connect button
        binding.btnConnect.setOnClickListener {
            if (isConnected) {
                disconnectScooter()
            } else {
                scanForScooter()
            }
        }

        // Forget saved device
        binding.btnForget.setOnClickListener {
            savedAddress = null
            savedName = null
            disconnectScooter()
            setUiDisconnected()
            binding.tvDeviceName.text = "No device saved"
            Toast.makeText(this, "Device forgotten", Toast.LENGTH_SHORT).show()
        }

        // Show saved device name
        binding.tvDeviceName.text = savedName ?: "No device paired"
        setUiDisconnected()
    }

    // ─── BLE Broadcast Receiver ───────────────────────────────────────────────

    private val bleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BleService.ACTION_CONNECTED    -> onConnected()
                BleService.ACTION_DISCONNECTED -> onDisconnected()
                BleService.ACTION_SCAN_RESULT  -> onScanResult(intent)
                BleService.ACTION_SCAN_FAILED  -> onScanFailed()
                BleService.ACTION_DATA         -> onDataReceived(intent)
            }
        }
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(BleService.ACTION_CONNECTED)
            addAction(BleService.ACTION_DISCONNECTED)
            addAction(BleService.ACTION_SCAN_RESULT)
            addAction(BleService.ACTION_SCAN_FAILED)
            addAction(BleService.ACTION_DATA)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(bleReceiver, filter)
    }

    // ─── Event handlers ───────────────────────────────────────────────────────

    private fun onConnected() {
        isConnected = true
        runOnUiThread {
            setUiConnected()
        }
    }

    private fun onDisconnected() {
        isConnected = false
        packetCount = 0
        runOnUiThread { setUiDisconnected() }
    }

    private fun onScanResult(intent: Intent) {
        val address = intent.getStringExtra(BleService.EXTRA_DEVICE_ADDRESS) ?: return
        val name    = intent.getStringExtra(BleService.EXTRA_DEVICE_NAME) ?: "NEX_?"

        savedAddress = address
        savedName    = name

        runOnUiThread {
            binding.tvDeviceName.text = "$name found — connecting…"
            binding.tvStatus.text = "Found $name"
        }

        // Auto-connect to found device
        connectToAddress(address)
    }

    private fun onScanFailed() {
        runOnUiThread {
            binding.tvStatus.text = "Scan failed. Is Bluetooth on?"
            binding.btnConnect.isEnabled = true
            binding.btnConnect.text = "Scan & Connect"
        }
    }

    private fun onDataReceived(intent: Intent) {
        // Redundant with Repository observer but kept for broadcast compatibility
        // The Repository observer (observeData) is now the primary driver
    }

    @SuppressLint("SetTextI18n")
    private fun updateUi(data: ScooterData) {
        if (!data.isValid) return

        lastUpdateTime = System.currentTimeMillis()
        packetCount++

        runOnUiThread {
            // Speed — big central display
            binding.tvSpeed.text = data.speedKmh.toString()
            binding.speedGraph.addSample(data.speedKmh)

            // Battery voltage
            binding.tvVoltage.text = "%.1fV".format(data.voltageV)

            // State of Charge
            binding.tvSoc.text = "${data.socPercent}%"
            binding.progressSoc.progress = data.socPercent
            
            // Remaining Range
            binding.tvRange.text = "${data.remainingKm} km"

            // Status / Error
            binding.tvStatus.text = data.statusText.uppercase()
            
            // Last updated time
            binding.tvLastUpdate.text = "Live Sync: ${timeFormat.format(Date())}"
        }
    }

    private suspend fun refreshTravelStats() {
        val today = repository.getTodayDistance()
        val yesterday = repository.getYesterdayDistance()
        runOnUiThread {
            binding.tvTodayKm.text = "%.1f km".format(today)
            binding.tvYesterdayKm.text = "%.1f km".format(yesterday)
        }
    }

    // ─── UI State ─────────────────────────────────────────────────────────────

    private fun setUiConnected() {
        binding.tvStatus.text = "CONNECTED"
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
        binding.btnConnect.text = "STOP"
    }

    private fun setUiDisconnected() {
        binding.tvStatus.text = "DISCONNECTED"
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        binding.btnConnect.text = "SEARCH"
        binding.tvSpeed.text = "0"
        binding.tvRange.text = "0 km"
        binding.tvLastUpdate.text = "Not Synced"
    }

    private fun updateStaleIndicator() {
        if (lastUpdateTime > 0) {
            val age = System.currentTimeMillis() - lastUpdateTime
            if (age > 15_000) {
                binding.tvLastUpdate.setTextColor(ContextCompat.getColor(this, R.color.accent_orange))
            } else {
                binding.tvLastUpdate.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
        }
    }

    private fun setUiScanning() {
        binding.tvStatus.text = "SCANNIG..."
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_orange))
        binding.btnConnect.text = "SCAN..."
    }

    private fun setUiConnecting() {
        binding.tvStatus.text = "CONNECTING..."
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_orange))
        binding.btnConnect.text = "CONNECT..."
    }

    // ─── BLE Operations ───────────────────────────────────────────────────────

    private fun scanForScooter() {
        setUiScanning()
        val intent = Intent(this, BleService::class.java).apply {
            action = BleService.CMD_SCAN
        }
        startBleService(intent)
    }

    private fun connectToAddress(address: String) {
        setUiConnecting()
        val intent = Intent(this, BleService::class.java).apply {
            action = BleService.CMD_CONNECT
            putExtra(BleService.EXTRA_ADDRESS, address)
        }
        startBleService(intent)
    }

    private fun disconnectScooter() {
        isConnected = false
        val intent = Intent(this, BleService::class.java).apply {
            action = BleService.CMD_DISCONNECT
        }
        startBleService(intent)
        setUiDisconnected()
    }

    private fun startBleService(intent: Intent) {
        ContextCompat.startForegroundService(this, intent)
    }

    // ─── Permissions ─────────────────────────────────────────────────────────

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startAutoConnect()
        } else {
            Toast.makeText(
                this,
                "Bluetooth permissions required for scooter connectivity",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun checkPermissionsAndStart() {
        val required = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val missing = required.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startAutoConnect()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startAutoConnect() {
        // Check Bluetooth is on
        val btManager = getSystemService(BluetoothManager::class.java)
        if (btManager?.adapter?.isEnabled != true) {
            binding.tvStatus.text = "Bluetooth is off"
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        // Auto-connect to last known device
        val address = savedAddress
        if (address != null) {
            binding.tvDeviceName.text = savedName ?: address
            binding.tvStatus.text = "Auto-connecting…"
            connectToAddress(address)
        } else {
            binding.tvStatus.text = "Tap to scan for scooter"
        }
    }
}
