package si.bolukba.flip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Keeps the process, and with it the hub link, alive while the app is in the background. */
class HubService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val driver = Driver.get(this)
        if (intent?.action == ACTION_DISCONNECT) {
            driver.halt()
            driver.hub.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        // Keep driving from the controller via root; without it, stay parked.
        val err = (rootPad ?: RootPad(this, driver).also { rootPad = it }).start()
        if (err != null) driver.halt()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hub connection", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, HubService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Hub connected")
            .setContentText(if (err == null) "Controller active in background" else "Motors stopped ($err) · tap to drive")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Disconnect", disconnect).build())
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        rootPad?.stop()
        rootPad = null
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Driver.get(this).run {
            halt()
            hub.stop()
        }
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "hub"
        private const val ACTION_DISCONNECT = "si.bolukba.flip.DISCONNECT"
        private var rootPad: RootPad? = null
    }
}
