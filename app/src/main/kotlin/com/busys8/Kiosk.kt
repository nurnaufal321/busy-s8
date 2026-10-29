package com.busys8

import android.Manifest
import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import android.util.Log

/** Device-admin component; becomes device owner via `dpm set-device-owner com.busys8/.AdminReceiver`. */
class AdminReceiver : DeviceAdminReceiver()

/**
 * Device-owner policies that turn the phone into an appliance. Every call is a no-op unless
 * this app is device owner, so the APK also runs as a normal app on a dev device.
 *
 * SETTINGS → Kiosk off undoes them ([release]) without giving up device owner, so switching it
 * back on (from the BUSY S8 launcher icon) needs no adb.
 */
object Kiosk {
    private const val TAG = "Kiosk"

    fun apply(context: Context, enabled: Boolean) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            Log.i(TAG, "not device owner; kiosk policies skipped")
            return
        }
        val admin = ComponentName(context, AdminReceiver::class.java)
        if (!enabled) return release(context, dpm, admin)
        // Settings too: on Samsung its Bluetooth pairing prompt is a Settings window, and lock
        // task would otherwise block it, so a phone could never pair over Bluetooth.
        dpm.setLockTaskPackages(admin, arrayOf(context.packageName, "com.android.settings"))
        dpm.setKeyguardDisabled(admin, true)
        dpm.setStatusBarDisabled(admin, true)
        // Never sleep on any power source: "sleep" is drawn as black pixels instead.
        val allPlugs = BatteryManager.BATTERY_PLUGGED_AC or BatteryManager.BATTERY_PLUGGED_USB or
            BatteryManager.BATTERY_PLUGGED_WIRELESS
        dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, allPlugs.toString())
        val home = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.addPersistentPreferredActivity(admin, home, ComponentName(context, MainActivity::class.java))
        // Location (Wi-Fi SSID), and the camera and microphone the Sensors settings switch on.
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            dpm.setPermissionGrantState(admin, context.packageName, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }
    }

    /** Back to an ordinary phone: status bar, lock screen, Samsung's home screen, no pinning. */
    private fun release(context: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        // Leaving the allow-list also ends lock task for a running task.
        dpm.setLockTaskPackages(admin, emptyArray())
        dpm.setKeyguardDisabled(admin, false)
        dpm.setStatusBarDisabled(admin, false)
        dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "0")
        dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
        Log.i(TAG, "kiosk released")
    }

    fun enterLockTask(activity: Activity) {
        val dpm = activity.getSystemService(DevicePolicyManager::class.java)
        if (dpm.isLockTaskPermitted(activity.packageName)) activity.startLockTask()
    }
}
