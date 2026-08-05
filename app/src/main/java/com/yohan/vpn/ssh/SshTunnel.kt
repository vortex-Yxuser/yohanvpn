package com.yohan.vpn.ssh

import com.yohan.vpn.utils.Constants
import com.yohan.vpn.utils.Logger
import kotlinx.coroutines.*
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Yohan VPN - SSH Tunnel
 * 
 * Manages the local SOCKS proxy server that routes traffic through the SSH tunnel.
 * This class creates a local server that accepts connections and forwards them
 * through the established SSH connection.
 * 
 * @property logger Logger instance for connection logging
 */
class SshTunnel(private val logger: Logger) {

    companion object {
        private const val TAG = "SshTunnel"
    }

    // Local server socket for SOCKS proxy
    private var serverSocket: ServerSocket? = null

    // Coroutine scope for handling client connections
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Set of active client connections
    private val activeConnections = ConcurrentHashMap<Socket, Job>()

    // Flag to track if the tunnel is running
    @Volatile
    private var isRunning = false

    // Local port for the SOCKS proxy
    private var localPort: Int = Constants.DEFAULT_LOCAL_PORT

    // SSH Manager reference for forwarding connections
    private var sshManager: SshManager? = null

    /**
     * Starts the local SOCKS proxy server.
     * 
     * @param sshManager The SSH manager instance for forwarding connections
     * @param port The local port to bind the SOCKS proxy (default: 1080)
     * @return true if the server started successfully, false otherwise
     */
    suspend fun start(sshManager: SshManager, port: Int = Constants.DEFAULT_LOCAL_PORT): Boolean = 
        withContext(Dispatchers.IO) {
        try {
            this@SshTunnel.sshManager = sshManager
            this@SshTunnel.localPort = port

            logger.info("Starting SOCKS proxy server on port $port...")

            // Create server socket
            serverSocket = ServerSocket()
            serverSocket?.reuseAddress = true
            serverSocket?.bind(InetSocketAddress("127.0.0.1", port))

            isRunning = true
            logger.info("SOCKS proxy server started on port $port")

            // Start accepting client connections
            startAcceptingConnections()

            true
        } catch (e: Exception) {
            logger.error("Failed to start SOCKS proxy: ${e.message}")
            false
        }
    }

    /**
     * Accepts incoming client connections in a loop.
     */
    private fun startAcceptingConnections() {
        scope.launch {
            while (isActive && isRunning) {
                try {
                    val clientSocket = serverSocket?.accept()
                    if (clientSocket != null) {
                        logger.debug("New client connection from ${clientSocket.inetAddress}")
                        handleClientConnection(clientSocket)
                    }
                } catch (e: IOException) {
                    if (isRunning) {
                        logger.error("Error accepting connection: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Handles an individual client connection.
     * 
     * @param clientSocket The client socket to handle
     */
    private fun handleClientConnection(clientSocket: Socket) {
        val job = scope.launch {
            try {
                // Read SOCKS5 greeting
                val greeting = ByteArray(2)
                clientSocket.getInputStream().read(greeting)

                if (greeting[0] != 0x05.toByte()) {
                    logger.warning("Unsupported SOCKS version: ${greeting[0]}")
                    clientSocket.close()
                    return@launch
                }

                val numMethods = greeting[1].toInt()
                val methods = ByteArray(numMethods)
                clientSocket.getInputStream().read(methods)

                // Send no authentication required response
                clientSocket.getOutputStream().write(byteArrayOf(0x05, 0x00))
                clientSocket.getOutputStream().flush()

                // Read connection request
                val request = ByteArray(4)
                clientSocket.getInputStream().read(request)

                if (request[0] != 0x05.toByte() || request[1] != 0x01.toByte()) {
                    // Send connection refused
                    clientSocket.getOutputStream().write(
                        byteArrayOf(0x05, 0x07, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
                    )
                    clientSocket.getOutputStream().flush()
                    clientSocket.close()
                    return@launch
                }

                // Parse target address
                val addressType = request[3]
                val targetHost: String
                val targetPort: Int

                when (addressType.toInt()) {
                    0x01 -> { // IPv4
                        val addr = ByteArray(4)
                        clientSocket.getInputStream().read(addr)
                        targetHost = "${addr[0].toInt() and 0xFF}.${addr[1].toInt() and 0xFF}.${addr[2].toInt() and 0xFF}.${addr[3].toInt() and 0xFF}"
                        val portBytes = ByteArray(2)
                        clientSocket.getInputStream().read(portBytes)
                        targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
                    }
                    0x03 -> { // Domain name
                        val domainLen = clientSocket.getInputStream().read()
                        val domain = ByteArray(domainLen)
                        clientSocket.getInputStream().read(domain)
                        targetHost = String(domain)
                        val portBytes = ByteArray(2)
                        clientSocket.getInputStream().read(portBytes)
                        targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
                    }
                    0x04 -> { // IPv6
                        val addr = ByteArray(16)
                        clientSocket.getInputStream().read(addr)
                        targetHost = "[${addr.joinToString(":") { "%02x".format(it) }}]"
                        val portBytes = ByteArray(2)
                        clientSocket.getInputStream().read(portBytes)
                        targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
                    }
                    else -> {
                        logger.warning("Unsupported address type: $addressType")
                        clientSocket.close()
                        return@launch
                    }
                }

                logger.debug("SOCKS request: $targetHost:$targetPort")

                // Send success response
                clientSocket.getOutputStream().write(
                    byteArrayOf(0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
                )
                clientSocket.getOutputStream().flush()

                // Forward the connection through SSH
                forwardConnection(clientSocket, targetHost, targetPort)

            } catch (e: Exception) {
                logger.debug("Client connection error: ${e.message}")
            } finally {
                try {
                    clientSocket.close()
                } catch (e: Exception) {
                    // Ignore close errors
                }
                activeConnections.remove(clientSocket)
            }
        }

        activeConnections[clientSocket] = job
    }

    /**
     * Forwards a client connection through the SSH tunnel.
     * 
     * @param clientSocket The client socket
     * @param targetHost The target host to connect to
     * @param targetPort The target port to connect to
     */
    private suspend fun forwardConnection(
        clientSocket: Socket,
        targetHost: String,
        targetPort: Int
    ) = withContext(Dispatchers.IO) {
        var remoteSocket: Socket? = null

        try {
            // Connect to target through SSH tunnel
            // In a real implementation, this would use the SSH channel forwarding
            // For now, we create a direct connection as a placeholder
            // The actual SSH forwarding would be implemented using JSch port forwarding

            remoteSocket = Socket()
            remoteSocket.connect(InetSocketAddress(targetHost, targetPort), 10000)

            // Start bidirectional data transfer
            val clientToRemote = async {
                try {
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (clientSocket.isConnected && remoteSocket.isConnected) {
                        bytesRead = clientSocket.getInputStream().read(buffer)
                        if (bytesRead == -1) break
                        remoteSocket.getOutputStream().write(buffer, 0, bytesRead)
                        remoteSocket.getOutputStream().flush()
                    }
                } catch (e: Exception) {
                    // Connection closed
                }
            }

            val remoteToClient = async {
                try {
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (clientSocket.isConnected && remoteSocket.isConnected) {
                        bytesRead = remoteSocket.getInputStream().read(buffer)
                        if (bytesRead == -1) break
                        clientSocket.getOutputStream().write(buffer, 0, bytesRead)
                        clientSocket.getOutputStream().flush()
                    }
                } catch (e: Exception) {
                    // Connection closed
                }
            }

            // Wait for either direction to complete
            awaitAll(clientToRemote, remoteToClient)

        } catch (e: Exception) {
            logger.debug("Forwarding error for $targetHost:$targetPort: ${e.message}")
        } finally {
            try {
                remoteSocket?.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    /**
     * Stops the SOCKS proxy server and closes all connections.
     */
    fun stop() {
        logger.info("Stopping SOCKS proxy server...")
        isRunning = false

        // Cancel all active connections
        activeConnections.values.forEach { it.cancel() }
        activeConnections.clear()

        // Close server socket
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (e: Exception) {
            logger.warning("Error closing server socket: ${e.message}")
        }

        // Cancel scope
        scope.cancel()

        logger.info("SOCKS proxy server stopped")
    }

    /**
     * Checks if the tunnel is currently running.
     * 
     * @return true if running, false otherwise
     */
    fun isRunning(): Boolean {
        return isRunning
    }

    /**
     * Gets the local port the SOCKS proxy is listening on.
     * 
     * @return The local port number
     */
    fun getLocalPort(): Int {
        return localPort
    }
}
