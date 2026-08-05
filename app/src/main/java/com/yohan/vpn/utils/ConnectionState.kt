package com.yohan.vpn.utils

/**
 * Yohan VPN - Connection State
 * 
 * Enum representing the various states of the VPN connection.
 * Used to track and display the current connection status.
 */
enum class ConnectionState {
    /** Initial state, no connection attempt made */
    DISCONNECTED,

    /** Currently attempting to connect */
    CONNECTING,

    /** Successfully connected and tunnel is active */
    CONNECTED,

    /** Currently disconnecting */
    DISCONNECTING,

    /** Connection attempt failed */
    ERROR,

    /** Attempting to reconnect after failure */
    RECONNECTING;

    /**
     * Returns a user-friendly string representation of the state.
     */
    fun displayName(): String {
        return when (this) {
            DISCONNECTED -> "Disconnected"
            CONNECTING -> "Connecting..."
            CONNECTED -> "Connected"
            DISCONNECTING -> "Disconnecting..."
            ERROR -> "Connection Error"
            RECONNECTING -> "Reconnecting..."
        }
    }
}
