package com.yohan.vpn.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.yohan.vpn.MainActivity
import com.yohan.vpn.R
import com.yohan.vpn.YohanVpnApplication
import com.yohan.vpn.ssh.SshManager
import com.yohan.vpn.ssh.SshTunnel
import com.yohan.vpn.utils.ConnectionState
import com.yohan.vpn.utils.Constants
import com.yohan.vpn.utils.Logger
import kotlinx.coroutines.*

/**
 * Yohan VPN - SSH Connection Service
 * 
 * Foreground service that manages the SSH connection in the background.
 * This service maintains the SSH connection even when the app is in the background
 * and handles reconnection logic automatically.
 * 
 * The service coordinates between the SSH manager (for SSH connections) and
 * the VPN service (for system-level VPN tunneling).
 */
class SshConnectionService : Service() {

    companion object {
        private const val TAG = "SshConnectionService"
        private const val NOTIFICATION_ID = 1002
    }

    // Binder for activity-service communication
    private val binder = LocalBinder()

    // Logger instance
    private val logger = Logger.getInstance()

    // SSH Manager for SSH connections
    private lateinit var sshManager: SshManager

    // SSH Tunnel for local SOCKS proxy
    private lateinit var sshTunnel: SshTunnel

    // Coroutine scope for background operations
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Current connection state
    @Volatile
    private var currentState = ConnectionState.DISCONNECTED

    // Connection parameters
    private var connectionHost: String = ""
    private var connectionPort: Int = Constants.DEFAULT_SSH_PORT
    private var connectionUsername: String = ""
    private var connectionPrivateKey: String = ""
    private var connectionPayload: String = ""

    // Reconnection attempt counter
    private var reconnectAttempts = 0

    /**
     * Local binder class for service binding.
     */
    inner class LocalBinder : Binder() {
        fun getService(): SshConnectionService = this@SshConnectionService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        logger.info("SshConnectionService created")

        // Initialize SSH components
        sshManager = SshManager(logger)
        sshTunnel = SshTunnel(logger)

        // Set up SSH connection callback
        sshManager.setCallback(object : SshManager.ConnectionCallback {
            override fun onConnected() {
                updateState(ConnectionState.CONNECTED)
                reconnectAttempts = 0
                logger.info("SSH callback: Connected")
            }

            override fun onDisconnected() {
                updateState(ConnectionState.DISCONNECTED)
                logger.info("SSH callback: Disconnected")
            }

            override fun onError(error: String) {
                updateState(ConnectionState.ERROR)
                logger.error("SSH callback error: $error")
            }

            override fun onReconnecting(attempt: Int, maxAttempts: Int) {
                updateState(ConnectionState.RECONNECTING)
                logger.info("SSH callback: Reconnecting attempt $attempt/$maxAttempts")
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Constants.ACTION_CONNECT -> {
                // Extract connection parameters
                connectionHost = intent.getStringExtra(Constants.PREF_HOST) ?: ""
                connectionPort = intent.getIntExtra(Constants.PREF_PORT, Constants.DEFAULT_SSH_PORT)
                connectionUsername = intent.getStringExtra(Constants.PREF_USERNAME) ?: ""
                connectionPrivateKey = intent.getStringExtra(Constants.PREF_PRIVATE_KEY) ?: ""
                connectionPayload = intent.getStringExtra(Constants.PREF_PAYLOAD) ?: ""

                startConnection()
            }
            Constants.ACTION_DISCONNECT -> {
                stopConnection()
            }
        }

        return START_STICKY
    }

    /**
     * Starts the SSH connection process.
     * Establishes SSH connection and creates the local SOCKS proxy.
     */
    private fun startConnection() {
        serviceScope.launch {
            try {
                updateState(ConnectionState.CONNECTING)
                logger.info("Starting SSH connection to $connectionHost:$connectionPort")

                // Start as foreground service
                startForeground(NOTIFICATION_ID, createNotification(ConnectionState.CONNECTING))

                // Validate connection parameters
                if (connectionHost.isBlank()) {
                    throw IllegalArgumentException("Host cannot be empty")
                }
                if (connectionUsername.isBlank()) {
                    throw IllegalArgumentException("Username cannot be empty")
                }
                if (connectionPrivateKey.isBlank()) {
                    throw IllegalArgumentException("Private key cannot be empty")
                }

                // Establish SSH connection
                val sshSuccess = sshManager.connect(
                    host = connectionHost,
                    port = connectionPort,
                    username = connectionUsername,
                    privateKey = connectionPrivateKey,
                    payload = connectionPayload
                )

                if (!sshSuccess) {
                    throw Exception("SSH connection failed")
                }

                logger.info("SSH connection established")

                // Start local SOCKS proxy
                val tunnelSuccess = sshTunnel.start(sshManager, Constants.DEFAULT_LOCAL_PORT)

                if (!tunnelSuccess) {
                    throw Exception("Failed to start SOCKS proxy")
                }

                logger.info("SOCKS proxy started on port ${Constants.DEFAULT_LOCAL_PORT}")

                updateState(ConnectionState.CONNECTED)
                updateNotification(ConnectionState.CONNECTED)

                logger.info("SSH connection service fully operational")

            } catch (e: Exception) {
                updateState(ConnectionState.ERROR)
                logger.error("Connection error: ${e.message}")
                logger.error(e.stackTraceToString())

                // Update notification to show error
                updateNotification(ConnectionState.ERROR)
            }
        }
    }

    /**
     * Stops the SSH connection and cleans up resources.
     */
    private fun stopConnection() {
        serviceScope.launch {
            updateState(ConnectionState.DISCONNECTING)
            logger.info("Stopping SSH connection...")

            // Stop SSH tunnel
            sshTunnel.stop()
            logger.info("SSH tunnel stopped")

            // Disconnect SSH
            sshManager.disconnect()
            logger.info("SSH disconnected")

            updateState(ConnectionState.DISCONNECTED)

            // Stop foreground service
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()

            logger.info("SSH connection service stopped")
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
            .setSmallIcon(android.R.drawable.ic_secure)
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

    /**
     * Checks if currently connected.
     * 
     * @return true if connected, false otherwise
     */
    fun isConnected(): Boolean {
        return sshManager.isConnected()
    }

    override fun onDestroy() {
        super.onDestroy()

        // Clean up SSH components
        sshTunnel.stop()
        sshManager.cleanup()
        serviceScope.cancel()

        logger.info("SshConnectionService destroyed")
    }
}
