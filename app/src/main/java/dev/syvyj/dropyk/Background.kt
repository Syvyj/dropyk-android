package dev.syvyj.dropyk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * "Receive in background": a foreground service keeps the phone page's server and Quick Share
 * alive while Dropyk is closed, and brings Dropyk to the screen when something starts arriving
 * — a file from the page, or a Quick Share request to accept. Popping up from the background
 * needs "Display over other apps"; without it the transfer still lands, the screen stays put.
 * Turned on, it also starts after a reboot.
 */
class Background : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(ID, notification)
        }
        Drop.start()
        QuickShare.start(this)
        scope.launch {
            Drop.state.collect { state ->
                val arriving = state is DropState.Receiving || state is DropState.Incoming || state is DropState.Link
                if (arriving && !MainActivity.visible && canPopUp(this@Background)) {
                    startActivity(Intent(this@Background, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.background_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.background_title))
            .setContentText(getString(R.string.background_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    /** Starts the service again after a reboot, if it was on. */
    class Boot : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED && isOn(context)) start(context)
        }
    }

    companion object {
        private const val ID = 1
        private const val CHANNEL = "background"

        fun isOn(context: Context) = context.getSharedPreferences("drop", Context.MODE_PRIVATE).getBoolean("background", false)

        fun canPopUp(context: Context) = Settings.canDrawOverlays(context)

        fun set(context: Context, on: Boolean) {
            context.getSharedPreferences("drop", Context.MODE_PRIVATE).edit { putBoolean("background", on) }
            if (on) start(context) else context.stopService(Intent(context, Background::class.java))
        }

        fun start(context: Context) {
            val intent = Intent(context, Background::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        /** The system screen for "Display over other apps", for this app where the TV allows it. */
        fun askToPopUp(context: Context) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
}
