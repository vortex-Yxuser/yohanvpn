package com.yohan.vpn.utils

/**
 * Yohan VPN - Constants
 * 
 * Contains all constant values used throughout the application.
 * Centralizing constants makes maintenance and updates easier.
 */
object Constants {

    // ============================================
    // Connection Defaults
    // ============================================

    /** Default SSH port */
    const val DEFAULT_SSH_PORT = 22

    /** Default local SOCKS proxy port */
    const val DEFAULT_LOCAL_PORT = 1080

    /** Connection timeout in milliseconds */
    const val CONNECTION_TIMEOUT = 30000

    /** Read timeout in milliseconds */
    const val READ_TIMEOUT = 30000

    // ============================================
    // Reconnection Settings
    // ============================================

    /** Maximum number of reconnection attempts */
    const val MAX_RECONNECT_ATTEMPTS = 5

    /** Initial reconnection delay in milliseconds */
    const val RECONNECT_DELAY_MS = 3000L

    /** Maximum reconnection delay in milliseconds */
    const val MAX_RECONNECT_DELAY_MS = 30000L

    /** Multiplier for exponential backoff */
    const val RECONNECT_BACKOFF_MULTIPLIER = 2.0

    // ============================================
    // VPN Settings
    // ============================================

    /** VPN MTU (Maximum Transmission Unit) */
    const val VPN_MTU = 1500

    /** VPN session name */
    const val VPN_SESSION_NAME = "YohanVPN"

    // ============================================
    // Shared Preferences Keys
    // ============================================

    /** Shared preferences file name */
    const val PREFS_NAME = "yohan_vpn_prefs"

    /** Key for saving host */
    const val PREF_HOST = "host"

    /** Key for saving port */
    const val PREF_PORT = "port"

    /** Key for saving username */
    const val PREF_USERNAME = "username"

    /** Key for saving private key */
    const val PREF_PRIVATE_KEY = "private_key"

    /** Key for saving payload */
    const val PREF_PAYLOAD = "payload"

    // ============================================
    // Intent Actions
    // ============================================

    /** Action to start VPN service */
    const val ACTION_CONNECT = "com.yohan.vpn.action.CONNECT"

    /** Action to stop VPN service */
    const val ACTION_DISCONNECT = "com.yohan.vpn.action.DISCONNECT"

    // ============================================
    // Broadcast Actions
    // ============================================

    /** Broadcast action for connection status updates */
    const val ACTION_CONNECTION_STATUS = "com.yohan.vpn.action.CONNECTION_STATUS"

    /** Broadcast action for log updates */
    const val ACTION_LOG_UPDATE = "com.yohan.vpn.action.LOG_UPDATE"

    /** Extra key for connection status */
    const val EXTRA_STATUS = "status"

    /** Extra key for log message */
    const val EXTRA_LOG_MESSAGE = "log_message"

    /** Extra key for error message */
    const val EXTRA_ERROR_MESSAGE = "error_message"

    // ============================================
    // Payload Defaults
    // ============================================

    /** Default HTTP payload template */
    const val DEFAULT_PAYLOAD = """GET / HTTP/1.1\r\nHost: [host]\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n"""
}
