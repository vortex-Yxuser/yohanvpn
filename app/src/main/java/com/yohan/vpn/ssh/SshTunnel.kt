package com.yohan.vpn.ssh

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.Session
import com.yohan.vpn.utils.Constants
import com.yohan.vpn.utils.Logger
import kotlinx.coroutines.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Yohan VPN - SSH Tunnel (SOCKS5 Proxy)
 *
 * Implements a local SOCKS5 proxy that accepts connections and forwards
 * every request through the established SSH session using ChannelDirectTCPIP.
 */
class SshTunnel(private val logger: Logger) {

    companion object {
        private const val TAG = "SshTunnel"
        private const val BUFFER_SIZE = 16384
    }

    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeConnections = ConcurrentHashMap<Socket, Job>()

    @Volatile
    private var isRunning = false

    private var localPort: Int = Constants.DEFAULT_LOCAL_PORT
    private var sshManager: SshManager? = null

    /**
     * Starts the local SOCKS5 proxy server.
     */
    suspend fun start(sshManager: SshManager, port: Int = Constants.DEFAULT_LOCAL_PORT): Boolean =
        withContext(Dispatchers.IO) {
            try {
                stopInternal()

                this@SshTunnel.sshManager = sshManager
                this@SshTunnel.localPort = port

                logger.info("Starting SOCKS5 proxy server on 127.0.0.1:$port ...")

                serverSocket = ServerSocket()
                serverSocket?.reuseAddress = true
                serverSocket?.bind(InetSocketAddress("127.0.0.1", port))

                isRunning = true
                logger.info("SOCKS5 proxy started successfully on port $port")

                startAcceptingConnections()
                true
            } catch (e: Exception) {
                logger.error("Failed to start SOCKS proxy: ${e.message}")
                false
            }
        }

    private fun startAcceptingConnections() {
        scope.launch {
            while (isActive && isRunning) {
                try {
                    val clientSocket = serverSocket?.accept() ?: break
                    clientSocket.soTimeout = 0
                    logger.debug("New client connection from ${clientSocket.remoteSocketAddress}")
                    handleClientConnection(clientSocket)
                } catch (e: IOException) {
                    if (isRunning) {
                        logger.error("Error accepting connection: ${e.message}")
                    }
                }
            }
        }
    }

    private fun handleClientConnection(clientSocket: Socket) {
        val job = scope.launch {
            try {
                val input = clientSocket.getInputStream()
                val output = clientSocket.getOutputStream()

                // ---- SOCKS5 Greeting ----
                val version = input.read()
                if (version != 0x05) {
                    logger.warning("Unsupported SOCKS version: $version")
                    clientSocket.close()
                    return@launch
                }

                val nMethods = input.read()
                if (nMethods <= 0) {
                    clientSocket.close()
                    return@launch
                }
                val methods = ByteArray(nMethods)
                readFully(input, methods)

                // Reply: no authentication required
                output.write(byteArrayOf(0x05, 0x00))
                output.flush()

                // ---- SOCKS5 Request ----
                val req = ByteArray(4)
                readFully(input, req)

                if (req[0].toInt() != 0x05 || req[1].toInt() != 0x01) { // CONNECT only
                    output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    output.flush()
                    clientSocket.close()
                    return@launch
                }

                val atyp = req[3].toInt() and 0xFF
                val targetHost: String
                val targetPort: Int

                when (atyp) {
                    0x01 -> { // IPv4
                        val addr = ByteArray(4)
                        readFully(input, addr)
                        targetHost = "${addr[0].toInt() and 0xFF}.${addr[1].toInt() and 0xFF}.${addr[2].toInt() and 0xFF}.${addr[3].toInt() and 0xFF}"
                        val portBytes = ByteArray(2)
                        readFully(input, portBytes)
                        targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
                    }
                    0x03 -> { // Domain
                        val len = input.read()
                        if (len <= 0) {
                            clientSocket.close()
                            return@launch
                        }
                        val domain = ByteArray(len)
                        readFully(input, domain)
                        targetHost = String(domain, Charsets.UTF_8)
                        val portBytes = ByteArray(2)
                        readFully(input, portBytes)
                        targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
                    }
                    0x04 -> { // IPv6
                        val addr = ByteArray(16)
                        readFully(input, addr)
                        targetHost = addr.joinToString(":") { "%02x".format(it) }
                        val portBytes = ByteArray(2)
                        readFully(input, portBytes)
                        targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
                    }
                    else -> {
                        logger.warning("Unsupported address type: $atyp")
                        output.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                        output.flush()
                        clientSocket.close()
                        return@launch
                    }
                }

                logger.debug("SOCKS CONNECT → $targetHost:$targetPort")

                // ---- Open SSH channel ----
                val session: Session? = sshManager?.getSession()
                if (session == null || !session.isConnected) {
                    logger.error("SSH session not available")
                    output.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    output.flush()
                    clientSocket.close()
                    return@launch
                }

                val channel = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
                channel.setHost(targetHost)
                channel.setPort(targetPort)
                channel.setOrgIPAddress("127.0.0.1")
                channel.setOrgPort(0)

                channel.connect(15000)

                if (!channel.isConnected) {
                    logger.warning("Failed to open channel to $targetHost:$targetPort")
                    output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    output.flush()
                    clientSocket.close()
                    return@launch
                }

                // Success reply
                output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()

                // Bidirectional pipe
                val channelIn = channel.inputStream
                val channelOut = channel.outputStream

                val clientToRemote = async {
                    pipe(input, channelOut)
                }
                val remoteToClient = async {
                    pipe(channelIn, output)
                }

                // Wait until one side closes
                select {
                    clientToRemote.onAwait { }
                    remoteToClient.onAwait { }
                }

                clientToRemote.cancel()
                remoteToClient.cancel()

                try { channel.disconnect() } catch (_: Exception) {}

            } catch (e: Exception) {
                logger.debug("Client connection ended: ${e.message}")
            } finally {
                try { clientSocket.close() } catch (_: Exception) {}
                activeConnections.remove(clientSocket)
            }
        }

        activeConnections[clientSocket] = job
    }

    private suspend fun pipe(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val read = withContext(Dispatchers.IO) { from.read(buffer) }
                if (read == -1) break
                withContext(Dispatchers.IO) {
                    to.write(buffer, 0, read)
                    to.flush()
                }
            }
        } catch (_: Exception) {
            // Normal when the other side closes
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) throw IOException("Unexpected end of stream")
            offset += read
        }
    }

    private fun stopInternal() {
        isRunning = false
        activeConnections.values.forEach { it.cancel() }
        activeConnections.clear()
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }

    fun stop() {
        logger.info("Stopping SOCKS5 proxy server...")
        stopInternal()
        logger.info("SOCKS5 proxy stopped")
    }

    fun isRunning(): Boolean = isRunning

    fun getLocalPort(): Int = localPort
}
