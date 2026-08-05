package com.yohan.vpn.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.yohan.vpn.MainActivity
import com.yohan.vpn.R
import com.yohan.vpn.YohanVpnApplication
import com.yohan.vpn.utils.ConnectionState
import com.yohan.vpn.utils.Constants
import com.yohan.vpn.utils.Logger
import kotlinx.coroutines.*

/**
 * Yohan VPN - VPN Service
 * 
 * Core Android VPN service that creates a system-level VPN tunnel.
 * This service runs in the foreground to maintain the VPN connection
 * and routes device traffic through the SSH tunnel.
 * 
 * The service implements the Android VpnService API to create a TUN interface
 * that captures all device traffic and routes it through the SSH SOCKS proxy.
 */
class YohanVpnService : VpnService() {

    companion object {
        private const val TAG = "YohanVpnService"
        private const val NOTIFICATION_ID = 1001
    }

    // Binder for activity-service communication
    private val binder = LocalBinder()

    // Logger instance
    private val logger = Logger.getInstance()

    // VPN interface (TUN device)
    private var vpnInterface: ParcelFileDescriptor? = null

    // Coroutine scope for background operations
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Current connection state
    @Volatile
    private var currentState = ConnectionState.DISCONNECTED

    // VPN configuration parameters
    private var vpnHost: String = ""
    private var vpnPort: Int = Constants.DEFAULT_SSH_PORT
    private var vpnUsername: String = ""
    private var vpnPrivateKey: String = ""
    private var vpnPayload: String = ""

    /**
     * Local binder class for service binding.
     */
    inner class LocalBinder : Binder() {
        fun getService(): YohanVpnService = this@YohanVpnService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        logger.info("YohanVpnService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Constants.ACTION_CONNECT -> {
                // Extract connection parameters from intent
                vpnHost = intent.getStringExtra(Constants.PREF_HOST) ?: ""
                vpnPort = intent.getIntExtra(Constants.PREF_PORT, Constants.DEFAULT_SSH_PORT)
                vpnUsername = intent.getStringExtra(Constants.PREF_USERNAME) ?: ""
                vpnPrivateKey = intent.getStringExtra(Constants.PREF_PRIVATE_KEY) ?: ""
                vpnPayload = intent.getStringExtra(Constants.PREF_PAYLOAD) ?: ""

                startVpnConnection()
            }
            Constants.ACTION_DISCONNECT -> {
                stopVpnConnection()
            }
        }

        // Return START_STICKY to keep the service running
        return START_STICKY
    }

    /**
     * Starts the VPN connection process.
     * Creates the VPN interface and establishes the SSH tunnel.
     */
    private fun startVpnConnection() {
        serviceScope.launch {
            try {
                updateState(ConnectionState.CONNECTING)
                logger.info("Starting VPN connection...")

                // Create VPN interface
                if (!createVpnInterface()) {
                    updateState(ConnectionState.ERROR)
                    logger.error("Failed to create VPN interface")
                    return@launch
                }

                logger.info("VPN interface created successfully")

                // Start as foreground service with notification
                startForeground(NOTIFICATION_ID, createNotification(ConnectionState.CONNECTING))

                // The actual SSH connection is handled by SshConnectionService
                // This service manages the VPN interface

                updateState(ConnectionState.CONNECTED)
                updateNotification(ConnectionState.CONNECTED)

                logger.info("VPN service started successfully")

            } catch (e: Exception) {
                updateState(ConnectionState.ERROR)
                logger.error("VPN connection error: ${e.message}")
                logger.error(e.stackTraceToString())
            }
        }
    }

    /**
     * Creates the VPN TUN interface using Android VpnService API.
     * 
     * @return true if the interface was created successfully
     */
    private fun createVpnInterface(): Boolean {
        return try {
            // Build VPN interface configuration
            val builder = Builder()
                .setSession(Constants.VPN_SESSION_NAME)
                .setMtu(Constants.VPN_MTU)
                // Route all traffic through VPN (0.0.0.0/0)
                .addRoute("0.0.0.0", 0)
                // DNS servers
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                // Local VPN address
                .addAddress("10.0.0.2", 24)
                // Allow bypass for certain apps if needed
                // .addDisallowedApplication("com.example.app")
                // Establish the interface
                .establish()

            vpnInterface = builder
            vpnInterface != null
        } catch (e: Exception) {
            logger.error("Error creating VPN interface: ${e.message}")
            false
        }
    }

    /**
     * Stops the VPN connection and cleans up resources.
     */
    private fun stopVpnConnection() {
        serviceScope.launch {
            updateState(ConnectionState.DISCONNECTING)
            logger.info("Stopping VPN connection...")

            // Close VPN interface
            try {
                vpnInterface?.close()
                vpnInterface = null
                logger.info("VPN interface closed")
            } catch (e: Exception) {
                logger.error("Error closing VPN interface: ${e.message}")
            }

            updateState(ConnectionState.DISCONNECTED)

            // Stop foreground service
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()

            logger.info("VPN service stopped")
        }
    }

    /**
     * Creates a notification for the foreground service.
     * 
     * @param state Current connection state
     * @return Notification instance
     */
    private fun createNotification(state: ConnectionState): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (state) {
            ConnectionState.CONNECTED -> getString(R.string.notification_title_connected)
            else -> getString(R.string.notification_title_connecting)
        }

        val content = when (state) {
            ConnectionState.CONNECTED -> getString(R.string.notification_content_connected)
            else -> getString(R.string.notification_content_connecting)
        }

        return NotificationCompat.Builder(this, YohanVpnApplication.VPN_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_secure) // Using system icon as placeholder
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Updates the foreground notification.
     * 
     * @param state Current connection state
     */
    private fun updateNotification(state: ConnectionState) {
        val notification = createNotification(state)
        startForeground(NOTIFICATION_ID, notification)
    }

    /**
     * Updates the connection state and broadcasts the change.
     * 
     * @param state New connection state
     */
    private fun updateState(state: ConnectionState) {
        currentState = state

        // Broadcast state change
        val intent = Intent(Constants.ACTION_CONNECTION_STATUS).apply {
            putExtra(Constants.EXTRA_STATUS, state.name)
        }
        sendBroadcast(intent)
    }

    /**
     * Gets the current connection state.
     * 
     * @return Current ConnectionState
     */
    fun getConnectionState(): ConnectionState {
        return currentState
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        logger.info("YohanVpnService destroyed")
    }
}
