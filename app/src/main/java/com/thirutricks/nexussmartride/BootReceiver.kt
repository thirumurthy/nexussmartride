package com.thirutricks.nexussmartride

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Starts the BLE service automatically after the phone reboots,
 * so the scooter reconnects without you having to open the app.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return

        val prefs = context.getSharedPreferences("nexus_prefs", Context.MODE_PRIVATE)
        val savedAddress = prefs.getString("device_address", null) ?: return

        Log.i("BootReceiver", "Boot complete — auto-connecting to $savedAddress")

        val serviceIntent = Intent(context, BleService::class.java).apply {
            action = BleService.CMD_CONNECT
            putExtra(BleService.EXTRA_ADDRESS, savedAddress)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
