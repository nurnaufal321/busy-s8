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
 */
object Kiosk {
    private const val TAG = "Kiosk"

    fun apply(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            Log.i(TAG, "not device owner; kiosk policies skipped")
            return
        }
        val admin = ComponentName(context, AdminReceiver::class.java)
        dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
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

    fun enterLockTask(activity: Activity) {
        val dpm = activity.getSystemService(DevicePolicyManager::class.java)
        if (dpm.isLockTaskPermitted(activity.packageName)) activity.startLockTask()
    }
}
