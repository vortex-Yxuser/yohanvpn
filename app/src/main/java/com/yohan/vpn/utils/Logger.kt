package com.yohan.vpn.utils

import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Yohan VPN - Logger
 * 
 * Centralized logging system that manages connection logs.
 * Logs are stored in memory and broadcasted to the UI for real-time display.
 */
class Logger private constructor() {

    companion object {
        @Volatile
        private var instance: Logger? = null

        /**
         * Gets the singleton instance of the Logger.
         */
        fun getInstance(): Logger {
            return instance ?: synchronized(this) {
                instance ?: Logger().also { instance = it }
            }
        }
    }

    // List of log entries
    private val logEntries = mutableListOf<LogEntry>()

    // Maximum number of log entries to keep in memory
    private val maxLogEntries = 500

    // Date formatter for timestamps
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // Listener for log updates
    private var logListener: ((String) -> Unit)? = null

    /**
     * Data class representing a single log entry.
     */
    data class LogEntry(
        val timestamp: Long,
        val message: String,
        val level: LogLevel
    )

    /**
     * Enum representing log levels.
     */
    enum class LogLevel {
        DEBUG,
        INFO,
        WARNING,
        ERROR
    }

    /**
     * Adds a log entry with INFO level.
     * 
     * @param message The log message
     */
    fun info(message: String) {
        addLog(message, LogLevel.INFO)
    }

    /**
     * Adds a log entry with DEBUG level.
     * 
     * @param message The log message
     */
    fun debug(message: String) {
        addLog(message, LogLevel.DEBUG)
    }

    /**
     * Adds a log entry with WARNING level.
     * 
     * @param message The log message
     */
    fun warning(message: String) {
        addLog(message, LogLevel.WARNING)
    }

    /**
     * Adds a log entry with ERROR level.
     * 
     * @param message The log message
     */
    fun error(message: String) {
        addLog(message, LogLevel.ERROR)
    }

    /**
     * Internal method to add a log entry.
     * 
     * @param message The log message
     * @param level The log level
     */
    private fun addLog(message: String, level: LogLevel) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            message = message,
            level = level
        )

        synchronized(logEntries) {
            logEntries.add(entry)
            // Remove old entries if exceeding maximum
            if (logEntries.size > maxLogEntries) {
                logEntries.removeAt(0)
            }
        }

        // Format the log entry
        val formattedLog = formatLogEntry(entry)

        // Notify listener
        logListener?.invoke(formattedLog)

        // Also log to Android system log
        android.util.Log.d("YohanVPN", "[$level] $message")
    }

    /**
     * Formats a log entry into a readable string.
     * 
     * @param entry The log entry to format
     * @return Formatted string with timestamp and level
     */
    private fun formatLogEntry(entry: LogEntry): String {
        val timeStr = dateFormat.format(Date(entry.timestamp))
        val levelStr = when (entry.level) {
            LogLevel.DEBUG -> "D"
            LogLevel.INFO -> "I"
            LogLevel.WARNING -> "W"
            LogLevel.ERROR -> "E"
        }
        return "[$timeStr] [$levelStr] ${entry.message}"
    }

    /**
     * Gets all log entries as a formatted string.
     * 
     * @return All logs concatenated with newlines
     */
    fun getAllLogs(): String {
        return synchronized(logEntries) {
            logEntries.joinToString("\n") { formatLogEntry(it) }
        }
    }

    /**
     * Clears all log entries.
     */
    fun clearLogs() {
        synchronized(logEntries) {
            logEntries.clear()
        }
        info("Logs cleared")
    }

    /**
     * Sets a listener for real-time log updates.
     * 
     * @param listener Callback function that receives formatted log strings
     */
    fun setLogListener(listener: (String) -> Unit) {
        logListener = listener
    }

    /**
     * Removes the log listener.
     */
    fun removeLogListener() {
        logListener = null
    }
}
