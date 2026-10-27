package com.busys8

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.busys8.server.BusyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps the emulated device and its HTTP server alive independently of the UI. */
class BusyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: BusyServer? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification())
        val app = BusyS8App.of(this)
        app.device.start(scope)
        server = BusyServer(app.bar, app.assetSource).also { it.start() }
        scope.launch {
            while (isActive) {
                app.bar.infoLines = listOf("BUSY S8") + localIpv4().map { "$it:${BusyServer.DEFAULT_PORT}" }
                delay(5000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        server?.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "BUSY S8", NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("BUSY S8 running")
            .setSmallIcon(android.R.drawable.presence_busy)
            .build()
    }

    companion object {
        private const val CHANNEL = "busy"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) = context.startForegroundService(Intent(context, BusyService::class.java))
    }
}

/** Starts the service at boot, before the HOME activity comes up. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BusyService.start(context)
    }
}
