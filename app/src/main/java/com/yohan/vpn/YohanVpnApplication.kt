package com.yohan.vpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Yohan VPN Application Class
 * 
 * This is the main application class that initializes global components
 * and creates notification channels required for foreground services.
 */
class YohanVpnApplication : Application() {

    companion object {
        // Notification channel ID for VPN service notifications
        const val VPN_NOTIFICATION_CHANNEL_ID = "yohan_vpn_service_channel"
    }

    override fun onCreate() {
        super.onCreate()
        // Initialize notification channels for Android O and above
        createNotificationChannels()
    }

    /**
     * Creates notification channels required for foreground services.
     * This is mandatory for Android 8.0 (API 26) and above.
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                VPN_NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                // Disable sound and vibration for service notifications
                setSound(null, null)
                enableVibration(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
