package com.yohan.vpn.ssh

import com.jcraft.jsch.*
import com.yohan.vpn.utils.Constants
import com.yohan.vpn.utils.Logger
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.util.Properties

/**
 * Yohan VPN - SSH Manager
 * 
 * Manages SSH connections using JSch library.
 * Supports OpenSSH private key authentication and creates dynamic port forwarding (SOCKS proxy).
 * 
 * @property logger Logger instance for connection logging
 */
class SshManager(private val logger: Logger) {

    companion object {
        private const val TAG = "SshManager"
    }

    // JSch instance for SSH operations
    private var jsch: JSch? = null

    // Active SSH session
    private var session: Session? = null

    // Active port forwarding (SOCKS proxy)
    private var forwardedPort: Int? = null

    // Coroutine scope for background operations
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Flag to track connection state
    @Volatile
    private var isConnected = false

    // Reconnection job
    private var reconnectJob: Job? = null

    // Current connection parameters (used for reconnection)
    private var currentHost: String = ""
    private var currentPort: Int = Constants.DEFAULT_SSH_PORT
    private var currentUsername: String = ""
    private var currentPrivateKey: String = ""
    private var currentPayload: String = ""

    /**
     * Callback interface for connection events.
     */
    interface ConnectionCallback {
        fun onConnected()
        fun onDisconnected()
        fun onError(error: String)
        fun onReconnecting(attempt: Int, maxAttempts: Int)
    }

    private var callback: ConnectionCallback? = null

    /**
     * Sets the connection callback.
     * 
     * @param callback The callback to receive connection events
     */
    fun setCallback(callback: ConnectionCallback) {
        this.callback = callback
    }

    /**
     * Establishes an SSH connection with the specified parameters.
     * 
     * @param host SSH server hostname or IP address
     * @param port SSH server port (default: 22)
     * @param username SSH username
     * @param privateKey OpenSSH format private key string
     * @param payload HTTP payload to send before establishing tunnel
     * @return true if connection was successful, false otherwise
     */
    suspend fun connect(
        host: String,
        port: Int = Constants.DEFAULT_SSH_PORT,
        username: String,
        privateKey: String,
        payload: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Store connection parameters for potential reconnection
            currentHost = host
            currentPort = port
            currentUsername = username
            currentPrivateKey = privateKey
            currentPayload = payload

            logger.info("Initializing SSH connection to $host:$port")

            // Initialize JSch
            jsch = JSch()

            // Add private key identity
            addPrivateKey(privateKey)

            logger.info("Creating SSH session...")

            // Create session
            val newSession = jsch!!.getSession(username, host, port)

            // Configure session properties
            val config = Properties().apply {
                // Strict host key checking disabled for flexibility
                // In production, you should verify host keys
                put("StrictHostKeyChecking", "no")
                // Preferred authentication methods
                put("PreferredAuthentications", "publickey")
                // Connection timeout
                put("ConnectTimeout", Constants.CONNECTION_TIMEOUT.toString())
                // Server alive interval to keep connection alive
                put("ServerAliveInterval", "60000")
                // Server alive count max
                put("ServerAliveCountMax", "3")
            }
            newSession.setConfig(config)

            // Set timeout
            newSession.timeout = Constants.CONNECTION_TIMEOUT

            logger.info("Connecting to SSH server...")

            // Connect to server
            newSession.connect()

            logger.info("SSH connection established successfully")

            // Send HTTP payload if provided
            if (payload.isNotBlank()) {
                sendPayload(newSession, payload, host, port)
            }

            // Create dynamic port forwarding (SOCKS proxy)
            logger.info("Creating SOCKS proxy on port ${Constants.DEFAULT_LOCAL_PORT}...")
            val localPort = newSession.setPortForwardingL(
                Constants.DEFAULT_LOCAL_PORT,
                "0.0.0.0",
                0
            )
            forwardedPort = localPort
            logger.info("SOCKS proxy created on local port: $localPort")

            // Store session
            session = newSession
            isConnected = true

            // Set up disconnect listener
            setupDisconnectListener(newSession)

            logger.info("SSH tunnel established successfully")
            callback?.onConnected()

            true
        } catch (e: Exception) {
            val errorMsg = "SSH connection failed: ${e.message}"
            logger.error(errorMsg)
            logger.error(e.stackTraceToString())
            callback?.onError(errorMsg)
            isConnected = false
            false
        }
    }

    /**
     * Adds a private key to the JSch identity repository.
     * 
     * @param privateKey OpenSSH format private key string
     */
    private fun addPrivateKey(privateKey: String) {
        try {
            logger.info("Adding private key identity...")

            // Validate key format
            if (!privateKey.contains("BEGIN") || !privateKey.contains("END")) {
                throw IllegalArgumentException("Invalid private key format. Expected OpenSSH format.")
            }

            // Convert string to byte array
            val keyBytes = privateKey.toByteArray(Charsets.UTF_8)

            // Add identity from byte array
            jsch?.addIdentity(
                "yohan_vpn_key",  // Key name identifier
                keyBytes,          // Private key bytes
                null,              // Public key bytes (will be derived)
                null               // Passphrase (no passphrase support in this version)
            )

            logger.info("Private key added successfully")
        } catch (e: Exception) {
            logger.error("Failed to add private key: ${e.message}")
            throw e
        }
    }

    /**
     * Sends an HTTP payload through the SSH connection before establishing the tunnel.
     * This is commonly used to bypass certain network restrictions.
     * 
     * @param session Active SSH session
     * @param payload HTTP payload string
     * @param host Target host
     * @param port Target port
     */
    private fun sendPayload(session: Session, payload: String, host: String, port: Int) {
        try {
            logger.info("Preparing to send HTTP payload...")

            // Replace placeholders in payload
            val processedPayload = payload
                .replace("[host]", host)
                .replace("[port]", port.toString())
                .replace("\r\n", "\r\n")

            logger.info("Sending payload to server...")

            // Open a direct TCP channel to send the payload
            val channel = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
            channel.host = host
            channel.port = port

            // Connect the channel
            channel.connect(Constants.CONNECTION_TIMEOUT)

            // Send the payload
            val outputStream = channel.outputStream
            outputStream.write(processedPayload.toByteArray(Charsets.UTF_8))
            outputStream.flush()

            logger.info("Payload sent successfully")

            // Small delay to allow server to process
            Thread.sleep(500)

            // Close the channel
            channel.disconnect()

        } catch (e: Exception) {
            logger.warning("Payload sending encountered an issue: ${e.message}")
            // Don't throw here - payload sending is optional and may fail
        }
    }

    /**
     * Sets up a listener to detect SSH session disconnections.
     * 
     * @param session The SSH session to monitor
     */
    private fun setupDisconnectListener(session: Session) {
        scope.launch {
            while (isActive && isConnected) {
                delay(5000) // Check every 5 seconds

                if (!session.isConnected) {
                    logger.warning("SSH session disconnected unexpectedly")
                    isConnected = false
                    callback?.onDisconnected()

                    // Attempt reconnection
                    attemptReconnection()
                    break
                }
            }
        }
    }

    /**
     * Attempts to reconnect to the SSH server with exponential backoff.
     */
    private fun attemptReconnection() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var attempt = 0
            var delayMs = Constants.RECONNECT_DELAY_MS

            while (attempt < Constants.MAX_RECONNECT_ATTEMPTS && isActive) {
                attempt++
                callback?.onReconnecting(attempt, Constants.MAX_RECONNECT_ATTEMPTS)
                logger.info("Reconnection attempt $attempt/${Constants.MAX_RECONNECT_ATTEMPTS} in ${delayMs}ms...")

                delay(delayMs)

                val success = connect(
                    currentHost,
                    currentPort,
                    currentUsername,
                    currentPrivateKey,
                    currentPayload
                )

                if (success) {
                    logger.info("Reconnection successful")
                    return@launch
                }

                // Exponential backoff
                delayMs = (delayMs * Constants.RECONNECT_BACKOFF_MULTIPLIER).toLong()
                    .coerceAtMost(Constants.MAX_RECONNECT_DELAY_MS)
            }

            logger.error("Max reconnection attempts reached. Giving up.")
            callback?.onError("Failed to reconnect after $attempt attempts")
        }
    }

    /**
     * Disconnects from the SSH server and cleans up resources.
     */
    fun disconnect() {
        logger.info("Disconnecting from SSH server...")

        // Cancel reconnection job
        reconnectJob?.cancel()
        reconnectJob = null

        isConnected = false

        try {
            // Remove port forwarding
            forwardedPort?.let { port ->
                try {
                    session?.delPortForwardingL(port)
                    logger.info("Port forwarding removed")
                } catch (e: Exception) {
                    logger.warning("Error removing port forwarding: ${e.message}")
                }
            }
            forwardedPort = null

            // Disconnect session
            session?.disconnect()
            session = null

            logger.info("SSH disconnected successfully")
            callback?.onDisconnected()
        } catch (e: Exception) {
            logger.error("Error during disconnect: ${e.message}")
        }
    }

    /**
     * Checks if currently connected to the SSH server.
     * 
     * @return true if connected, false otherwise
     */
    fun isConnected(): Boolean {
        return isConnected && session?.isConnected == true
    }

    /**
     * Gets the local SOCKS proxy port.
     * 
     * @return The local port number, or null if not connected
     */
    fun getLocalPort(): Int? {
        return forwardedPort
    }

    /**
     * Cleans up resources when the manager is no longer needed.
     */
    fun cleanup() {
        disconnect()
        scope.cancel()
    }
}
