# Keep BLE callback classes
-keep class com.ampere.nexus.BleService$* { *; }
-keep class com.ampere.nexus.NexusParser { *; }
-keep class com.ampere.nexus.ScooterData { *; }

# Keep Bluetooth GATT callback
-keep class android.bluetooth.BluetoothGattCallback { *; }
