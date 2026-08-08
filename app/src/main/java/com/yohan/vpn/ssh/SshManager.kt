package com.yohan.vpn.ssh

import com.jcraft.jsch.*
import com.yohan.vpn.utils.Constants
import com.yohan.vpn.utils.Logger
import kotlinx.coroutines.*
import java.util.Properties

/**
 * Yohan VPN - SSH Manager
 *
 * Manages SSH connections using JSch library.
 * Supports OpenSSH private key authentication.
 * The actual SOCKS proxy is handled by [SshTunnel] which opens
 * ChannelDirectTCPIP for every client request.
 */
class SshManager(private val logger: Logger) {

    companion object {
        private const val TAG = "SshManager"
    }

    private var jsch: JSch? = null
    private var session: Session? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var isConnected = false

    private var reconnectJob: Job? = null

    private var currentHost: String = ""
    private var currentPort: Int = Constants.DEFAULT_SSH_PORT
    private var currentUsername: String = ""
    private var currentPrivateKey: String = ""
    private var currentPayload: String = ""

    interface ConnectionCallback {
        fun onConnected()
        fun onDisconnected()
        fun onError(error: String)
        fun onReconnecting(attempt: Int, maxAttempts: Int)
    }

    private var callback: ConnectionCallback? = null

    fun setCallback(callback: ConnectionCallback) {
        this.callback = callback
    }

    /**
     * Returns the active JSch Session (used by SshTunnel to open channels).
     */
    fun getSession(): Session? = session

    /**
     * Establishes an SSH connection with the specified parameters.
     */
    suspend fun connect(
        host: String,
        port: Int = Constants.DEFAULT_SSH_PORT,
        username: String,
        privateKey: String,
        payload: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Clean any previous session first
            disconnectInternal()

            currentHost = host
            currentPort = port
            currentUsername = username
            currentPrivateKey = privateKey
            currentPayload = payload

            logger.info("Initializing SSH connection to $host:$port")

            jsch = JSch()

            addPrivateKey(privateKey)

            logger.info("Creating SSH session...")

            val newSession = jsch!!.getSession(username, host, port)

            val config = Properties().apply {
                put("StrictHostKeyChecking", "no")
                put("PreferredAuthentications", "publickey")
                put("ConnectTimeout", Constants.CONNECTION_TIMEOUT.toString())
                put("ServerAliveInterval", "30000")
                put("ServerAliveCountMax", "5")
            }
            newSession.setConfig(config)
            newSession.timeout = Constants.CONNECTION_TIMEOUT

            logger.info("Connecting to SSH server...")
            newSession.connect(Constants.CONNECTION_TIMEOUT)

            if (!newSession.isConnected) {
                throw JSchException("Session failed to connect")
            }

            logger.info("SSH connection established successfully")

            // Optional HTTP payload
            if (payload.isNotBlank()) {
                sendPayload(newSession, payload, host, port)
            }

            session = newSession
            isConnected = true

            setupDisconnectListener(newSession)

            logger.info("SSH session ready – SOCKS tunnel will be started by SshTunnel")
            callback?.onConnected()

            true
        } catch (e: Exception) {
            val errorMsg = "SSH connection failed: ${e.message}"
            logger.error(errorMsg)
            logger.error(e.stackTraceToString())
            callback?.onError(errorMsg)
            isConnected = false
            session = null
            false
        }
    }

    private fun addPrivateKey(privateKey: String) {
        try {
            logger.info("Adding private key identity...")

            val trimmed = privateKey.trim()
            if (!trimmed.contains("BEGIN") || !trimmed.contains("END")) {
                throw IllegalArgumentException("Invalid private key format. Expected OpenSSH or PEM format.")
            }

            val keyBytes = trimmed.toByteArray(Charsets.UTF_8)

            jsch?.addIdentity(
                "yohan_vpn_key_${System.currentTimeMillis()}",
                keyBytes,
                null,
                null
            )

            logger.info("Private key added successfully")
        } catch (e: Exception) {
            logger.error("Failed to add private key: ${e.message}")
            throw e
        }
    }

    private fun sendPayload(session: Session, payload: String, host: String, port: Int) {
        try {
            logger.info("Preparing to send HTTP payload...")

            val processedPayload = payload
                .replace("[host]", host)
                .replace("[port]", port.toString())
                .replace("\\r\\n", "\r\n")
                .replace("\\n", "\n")

            val channel = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
            channel.setHost(host)
            channel.setPort(port)
            channel.connect(Constants.CONNECTION_TIMEOUT)

            val outputStream = channel.outputStream
            outputStream.write(processedPayload.toByteArray(Charsets.UTF_8))
            outputStream.flush()

            logger.info("Payload sent successfully")

            Thread.sleep(400)
            channel.disconnect()
        } catch (e: Exception) {
            logger.warning("Payload sending encountered an issue: ${e.message}")
        }
    }

    private fun setupDisconnectListener(session: Session) {
        scope.launch {
            while (isActive && isConnected) {
                delay(4000)
                if (!session.isConnected) {
                    logger.warning("SSH session disconnected unexpectedly")
                    isConnected = false
                    callback?.onDisconnected()
                    attemptReconnection()
                    break
                }
            }
        }
    }

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

                delayMs = (delayMs * Constants.RECONNECT_BACKOFF_MULTIPLIER).toLong()
                    .coerceAtMost(Constants.MAX_RECONNECT_DELAY_MS)
            }

            logger.error("Max reconnection attempts reached. Giving up.")
            callback?.onError("Failed to reconnect after $attempt attempts")
        }
    }

    private fun disconnectInternal() {
        try {
            session?.disconnect()
        } catch (_: Exception) {}
        session = null
        isConnected = false
    }

    fun disconnect() {
        logger.info("Disconnecting from SSH server...")

        reconnectJob?.cancel()
        reconnectJob = null

        isConnected = false

        try {
            session?.disconnect()
            session = null
            logger.info("SSH disconnected successfully")
            callback?.onDisconnected()
        } catch (e: Exception) {
            logger.error("Error during disconnect: ${e.message}")
        }
    }

    fun isConnected(): Boolean {
        return isConnected && session?.isConnected == true
    }

    fun cleanup() {
        disconnect()
        scope.cancel()
    }
}
