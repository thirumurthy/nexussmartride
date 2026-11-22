package com.thirutricks.nexussmartride

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Foreground service that captures the phone's screen (Google Maps), scales it to 800x360,
 * and streams it as JPEG frames to the Ampere Nexus cluster display over a Local Wi-Fi Hotspot (Option B).
 */
class ScreenMirroringService : Service() {

    companion object {
        private const val TAG = "ScreenMirroringService"
        private const val NOTIF_CHANNEL_ID = "nexus_mirroring"
        private const val NOTIF_ID = 2002

        const val ACTION_MIRROR_STATUS = "com.thirutricks.nexussmartride.MIRROR_STATUS"
        const val EXTRA_IS_MIRRORING = "is_mirroring"
        const val EXTRA_HOTSPOT_SSID = "hotspot_ssid"
        const val EXTRA_HOTSPOT_IP = "hotspot_ip"

        const val CMD_START = "start_mirror"
        const val CMD_STOP = "stop_mirror"
        const val CMD_SET_ORIENTATION = "set_orientation"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_ORIENTATION_MODE = "extra_orientation_mode"

        const val MODE_LANDSCAPE_AUTO = 0  // Standard landscape (800x360 capture, 0 deg)
        const val MODE_ROTATE_90_CW = 1    // Portrait phone -> 360x800 capture, rotated 90° CW to 800x360
        const val MODE_ROTATE_90_CCW = 2   // Portrait phone -> 360x800 capture, rotated 270° CCW to 800x360
        const val MODE_FIT_CENTER = 3      // 800x360 capture with letterbox bars

        // Cluster Display target resolution
        const val CLUSTER_WIDTH = 800
        const val CLUSTER_HEIGHT = 360
        const val CLUSTER_DENSITY = 160
        const val FRAME_DELAY_MS = 200L // 5 FPS
        const val MAX_FRAME_BYTES = 51200 // 50 KB max JPEG size
    }

    private lateinit var httpServer: MirroringHttpServer
    private var wifiManager: WifiManager? = null
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var scheduler: ScheduledExecutorService? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isMirroring = false
    private var orientationMode = MODE_LANDSCAPE_AUTO

    override fun onCreate() {
        super.onCreate()
        httpServer = MirroringHttpServer(this)
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            CMD_START -> {
                orientationMode = intent.getIntExtra(EXTRA_ORIENTATION_MODE, MODE_LANDSCAPE_AUTO)
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultCode != 0 && resultData != null) {
                    startForeground(NOTIF_ID, buildNotification("Screen Mirroring Active (800x360)"))
                    startMirroring(resultCode, resultData)
                } else {
                    Log.e(TAG, "Cannot start mirroring: Missing MediaProjection result data")
                    stopSelf()
                }
            }
            CMD_SET_ORIENTATION -> {
                val newMode = intent.getIntExtra(EXTRA_ORIENTATION_MODE, MODE_LANDSCAPE_AUTO)
                Log.i(TAG, "Setting mirror orientation mode to: $newMode")
                orientationMode = newMode
                if (isMirroring && mediaProjection != null) {
                    setupVirtualDisplay()
                }
            }
            CMD_STOP -> {
                stopMirroring()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopMirroring()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startMirroring(resultCode: Int, resultData: Intent) {
        if (isMirroring) return
        isMirroring = true

        // 1. Start Embedded HTTP server on port 1880
        httpServer.start()

        // 2. Start Local-Only Hotspot & provision credentials over BLE
        startHotspotAndProvision()

        // 3. Obtain MediaProjection
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, resultData)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped by system")
                    stopMirroring()
                }
            }, mainHandler)
        }

        // 4. Setup ImageReader and VirtualDisplay
        setupVirtualDisplay()

        // 5. Start frame capture loop at 5 FPS
        startCaptureLoop()

        broadcastStatus(true, "Starting hotspot…")
    }

    private fun setupVirtualDisplay() {
        val (capW, capH) = when (orientationMode) {
            MODE_ROTATE_90_CW, MODE_ROTATE_90_CCW -> Pair(CLUSTER_HEIGHT, CLUSTER_WIDTH) // 360, 800
            else -> Pair(CLUSTER_WIDTH, CLUSTER_HEIGHT) // 800, 360
        }

        if (imageReader != null && (imageReader?.width != capW || imageReader?.height != capH)) {
            try {
                imageReader?.close()
            } catch (_: Exception) {}
            imageReader = null
        }

        if (imageReader == null) {
            imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        }

        if (virtualDisplay == null) {
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScooterClusterDisplay",
                capW,
                capH,
                CLUSTER_DENSITY,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                mainHandler
            )
        } else {
            virtualDisplay?.resize(capW, capH, CLUSTER_DENSITY)
            virtualDisplay?.surface = imageReader?.surface
        }
        Log.i(TAG, "VirtualDisplay configured: ${capW}x$capH, mode=$orientationMode")
    }

    private fun startHotspotAndProvision() {
        val wm = wifiManager ?: return
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    super.onStarted(reservation)
                    hotspotReservation = reservation

                    val ssid: String
                    val password: String

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val config = reservation.softApConfiguration
                        ssid = config.ssid ?: "NexusHotspot"
                        password = config.passphrase ?: "12345678"
                    } else {
                        @Suppress("DEPRECATION")
                        val config = reservation.wifiConfiguration
                        ssid = config?.SSID ?: "NexusHotspot"
                        password = config?.preSharedKey ?: "12345678"
                    }

                    Log.i(TAG, "Local hotspot started: SSID=$ssid, PWD=$password")

                    // Provision Wi-Fi Hotspot credentials and start 1 Hz mirroring stream
                    BleService.instance?.startMirroringMode(ssid, password)

                    broadcastStatus(true, ssid)
                }

                override fun onFailed(reason: Int) {
                    super.onFailed(reason)
                    Log.e(TAG, "Failed to start local hotspot. Reason code: $reason. Falling back to personal hotspot.")
                    handleHotspotFallback()
                }
            }, mainHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting hotspot: ${e.message}. Falling back to personal hotspot.")
            handleHotspotFallback()
        }
    }

    private fun handleHotspotFallback() {
        val prefs = getSharedPreferences("nexus_prefs", Context.MODE_PRIVATE)
        val ssid = prefs.getString("personal_hotspot_ssid", "NexusHotspot") ?: "NexusHotspot"
        val password = prefs.getString("personal_hotspot_pwd", "12345678") ?: "12345678"

        Log.i(TAG, "Provisioning personal hotspot to cluster: SSID=$ssid")
        BleService.instance?.startMirroringMode(ssid, password)
        broadcastStatus(true, "Personal: $ssid")
    }

    private fun startCaptureLoop() {
        scheduler = Executors.newSingleThreadScheduledExecutor().apply {
            scheduleWithFixedDelay({
                captureAndCompressFrame()
            }, 0, FRAME_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun captureAndCompressFrame() {
        val reader = imageReader ?: return
        var image: android.media.Image? = null
        try {
            image = reader.acquireLatestImage() ?: return
            val capW = reader.width
            val capH = reader.height
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * capW

            val tempBitmap = Bitmap.createBitmap(
                capW + rowPadding / pixelStride,
                capH,
                Bitmap.Config.ARGB_8888
            )
            tempBitmap.copyPixelsFromBuffer(buffer)

            var finalBitmap = if (rowPadding == 0) {
                tempBitmap
            } else {
                Bitmap.createBitmap(tempBitmap, 0, 0, capW, capH).also {
                    tempBitmap.recycle()
                }
            }

            // If captured in portrait orientation (360x800), rotate to landscape (800x360)
            if (orientationMode == MODE_ROTATE_90_CW) {
                val matrix = Matrix().apply { postRotate(90f) }
                val rotated = Bitmap.createBitmap(finalBitmap, 0, 0, finalBitmap.width, finalBitmap.height, matrix, true)
                finalBitmap.recycle()
                finalBitmap = rotated
            } else if (orientationMode == MODE_ROTATE_90_CCW) {
                val matrix = Matrix().apply { postRotate(270f) }
                val rotated = Bitmap.createBitmap(finalBitmap, 0, 0, finalBitmap.width, finalBitmap.height, matrix, true)
                finalBitmap.recycle()
                finalBitmap = rotated
            }

            // Ensure exact 800x360 dimensions for the cluster display
            if (finalBitmap.width != CLUSTER_WIDTH || finalBitmap.height != CLUSTER_HEIGHT) {
                val scaled = Bitmap.createScaledBitmap(finalBitmap, CLUSTER_WIDTH, CLUSTER_HEIGHT, true)
                finalBitmap.recycle()
                finalBitmap = scaled
            }

            // Adaptive compression: step down quality by 5 until <= 50 KB (Ampere Connect rule)
            var quality = 85
            val out = ByteArrayOutputStream()
            do {
                out.reset()
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                quality -= 5
            } while (out.size() > MAX_FRAME_BYTES && quality >= 15)

            finalBitmap.recycle()

            // Atomically update stream.jpeg
            val tempFile = httpServer.streamTempFile
            val targetFile = httpServer.streamFile

            FileOutputStream(tempFile).use { fos ->
                fos.write(out.toByteArray())
                fos.flush()
            }
            if (targetFile.exists()) targetFile.delete()
            tempFile.renameTo(targetFile)

        } catch (e: Exception) {
            // Frame skip during fast refresh is normal
        } finally {
            image?.close()
        }
    }

    private fun stopMirroring() {
        if (!isMirroring) return
        isMirroring = false

        scheduler?.shutdownNow()
        scheduler = null

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjection?.stop()
        mediaProjection = null

        httpServer.stop()

        try {
            hotspotReservation?.close()
        } catch (_: Exception) {}
        hotspotReservation = null

        // Restore cluster display mode from mirroring back to navigation or idle
        BleService.instance?.stopMirroringMode()

        broadcastStatus(false, null)
    }

    private fun broadcastStatus(isMirroring: Boolean, ssid: String?) {
        val intent = Intent(ACTION_MIRROR_STATUS).apply {
            putExtra(EXTRA_IS_MIRRORING, isMirroring)
            putExtra(EXTRA_HOTSPOT_SSID, ssid)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Screen Mirroring Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Streams phone screen to Ampere Nexus scooter cluster"
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val pIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Ampere Nexus Screen Mirroring")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pIntent)
            .setOngoing(true)
            .build()
    }
}
