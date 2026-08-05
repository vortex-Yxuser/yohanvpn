package com.yohan.vpn

import android.content.*
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.yohan.vpn.databinding.ActivityMainBinding
import com.yohan.vpn.service.SshConnectionService
import com.yohan.vpn.service.YohanVpnService
import com.yohan.vpn.utils.ConnectionState
import com.yohan.vpn.utils.Constants
import com.yohan.vpn.utils.Logger

/**
 * Yohan VPN - Main Activity
 *
 * Main user interface for the Yohan VPN application.
 * Provides input fields for SSH connection parameters, connection controls,
 * and real-time log display.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val logger = Logger.getInstance()

    private var vpnService: YohanVpnService? = null
    private var sshService: SshConnectionService? = null
    private var vpnServiceBound = false
    private var sshServiceBound = false
    private var currentState = ConnectionState.DISCONNECTED

    private val connectionStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Constants.ACTION_CONNECTION_STATUS -> {
                    val statusStr = intent.getStringExtra(Constants.EXTRA_STATUS)
                    statusStr?.let {
                        updateConnectionState(ConnectionState.valueOf(it))
                    }
                }
            }
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnConnection()
        } else {
            logger.error(getString(R.string.error_vpn_permission_denied))
            Toast.makeText(this, R.string.error_vpn_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUI()
        setupLogger()
        loadSavedConfiguration()
        registerReceivers()
    }

    private fun setupUI() {
        binding.btnConnect.setOnClickListener {
            if (validateInputs()) prepareVpnConnection()
        }
        binding.btnDisconnect.setOnClickListener { disconnectServices() }
        binding.btnClearLogs.setOnClickListener {
            logger.clearLogs()
            binding.tvLogs.text = ""
        }
        updateConnectionState(ConnectionState.DISCONNECTED)
    }

    private fun setupLogger() {
        logger.setLogListener { logMessage ->
            runOnUiThread {
                binding.tvLogs.append("$logMessage\n")
                binding.scrollLogs.post { binding.scrollLogs.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun loadSavedConfiguration() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        binding.etHost.setText(prefs.getString(Constants.PREF_HOST, ""))
        binding.etPort.setText(prefs.getInt(Constants.PREF_PORT, Constants.DEFAULT_SSH_PORT).toString())
        binding.etUsername.setText(prefs.getString(Constants.PREF_USERNAME, ""))
        binding.etPrivateKey.setText(prefs.getString(Constants.PREF_PRIVATE_KEY, ""))
        binding.etPayload.setText(prefs.getString(Constants.PREF_PAYLOAD, Constants.DEFAULT_PAYLOAD))
    }

    private fun saveConfiguration() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(Constants.PREF_HOST, binding.etHost.text.toString().trim())
            putInt(Constants.PREF_PORT, binding.etPort.text.toString().toIntOrNull() ?: Constants.DEFAULT_SSH_PORT)
            putString(Constants.PREF_USERNAME, binding.etUsername.text.toString().trim())
            putString(Constants.PREF_PRIVATE_KEY, binding.etPrivateKey.text.toString().trim())
            putString(Constants.PREF_PAYLOAD, binding.etPayload.text.toString().trim())
            apply()
        }
        Toast.makeText(this, R.string.success_config_saved, Toast.LENGTH_SHORT).show()
    }

    private fun registerReceivers() {
        registerReceiver(connectionStatusReceiver, IntentFilter(Constants.ACTION_CONNECTION_STATUS))
    }

    private fun validateInputs(): Boolean {
        val host = binding.etHost.text.toString().trim()
        val username = binding.etUsername.text.toString().trim()
        val privateKey = binding.etPrivateKey.text.toString().trim()
        val portStr = binding.etPort.text.toString().trim()

        return when {
            host.isEmpty() -> { binding.tilHost.error = getString(R.string.error_empty_host); false }
            username.isEmpty() -> { binding.tilUsername.error = getString(R.string.error_empty_username); false }
            privateKey.isEmpty() -> { binding.tilPrivateKey.error = getString(R.string.error_empty_key); false }
            portStr.isEmpty() || portStr.toIntOrNull() == null || portStr.toInt() !in 1..65535 -> {
                binding.tilPort.error = getString(R.string.error_invalid_port); false
            }
            else -> {
                binding.tilHost.error = null
                binding.tilUsername.error = null
                binding.tilPrivateKey.error = null
                binding.tilPort.error = null
                true
            }
        }
    }

    private fun prepareVpnConnection() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startVpnConnection()
        }
    }

    private fun startVpnConnection() {
        val host = binding.etHost.text.toString().trim()
        val port = binding.etPort.text.toString().toIntOrNull() ?: Constants.DEFAULT_SSH_PORT
        val username = binding.etUsername.text.toString().trim()
        val privateKey = binding.etPrivateKey.text.toString().trim()
        val payload = binding.etPayload.text.toString().trim()

        saveConfiguration()
        logger.info(getString(R.string.log_starting))

        val sshIntent = Intent(this, SshConnectionService::class.java).apply {
            action = Constants.ACTION_CONNECT
            putExtra(Constants.PREF_HOST, host)
            putExtra(Constants.PREF_PORT, port)
            putExtra(Constants.PREF_USERNAME, username)
            putExtra(Constants.PREF_PRIVATE_KEY, privateKey)
            putExtra(Constants.PREF_PAYLOAD, payload)
        }
        startService(sshIntent)
        bindService(sshIntent, sshServiceConnection, Context.BIND_AUTO_CREATE)

        val vpnIntent = Intent(this, YohanVpnService::class.java).apply {
            action = Constants.ACTION_CONNECT
            putExtra(Constants.PREF_HOST, host)
            putExtra(Constants.PREF_PORT, port)
            putExtra(Constants.PREF_USERNAME, username)
            putExtra(Constants.PREF_PRIVATE_KEY, privateKey)
            putExtra(Constants.PREF_PAYLOAD, payload)
        }
        startService(vpnIntent)
        bindService(vpnIntent, vpnServiceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun disconnectServices() {
        logger.info(getString(R.string.log_disconnecting))
        val sshIntent = Intent(this, SshConnectionService::class.java).apply {
            action = Constants.ACTION_DISCONNECT
        }
        startService(sshIntent)
        val vpnIntent = Intent(this, YohanVpnService::class.java).apply {
            action = Constants.ACTION_DISCONNECT
        }
        startService(vpnIntent)
        if (sshServiceBound) { unbindService(sshServiceConnection); sshServiceBound = false }
        if (vpnServiceBound) { unbindService(vpnServiceConnection); vpnServiceBound = false }
    }

    private fun updateConnectionState(state: ConnectionState) {
        currentState = state
        runOnUiThread {
            binding.tvStatus.text = state.displayName()
            when (state) {
                ConnectionState.CONNECTED -> {
                    binding.tvStatus.setTextColor(getColor(R.color.status_connected))
                    binding.btnConnect.isEnabled = false
                    binding.btnDisconnect.isEnabled = true
                    binding.progressBar.visibility = View.GONE
                    setInputsEnabled(false)
                }
                ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> {
                    binding.tvStatus.setTextColor(getColor(R.color.status_connecting))
                    binding.btnConnect.isEnabled = false
                    binding.btnDisconnect.isEnabled = true
                    binding.progressBar.visibility = View.VISIBLE
                }
                ConnectionState.DISCONNECTED -> {
                    binding.tvStatus.setTextColor(getColor(R.color.status_disconnected))
                    binding.btnConnect.isEnabled = true
                    binding.btnDisconnect.isEnabled = false
                    binding.progressBar.visibility = View.GONE
                    setInputsEnabled(true)
                }
                ConnectionState.ERROR -> {
                    binding.tvStatus.setTextColor(getColor(R.color.status_error))
                    binding.btnConnect.isEnabled = true
                    binding.btnDisconnect.isEnabled = false
                    binding.progressBar.visibility = View.GONE
                    setInputsEnabled(true)
                }
                ConnectionState.DISCONNECTING -> {
                    binding.tvStatus.setTextColor(getColor(R.color.status_disconnected))
                    binding.btnConnect.isEnabled = false
                    binding.btnDisconnect.isEnabled = false
                    binding.progressBar.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun setInputsEnabled(enabled: Boolean) {
        binding.etHost.isEnabled = enabled
        binding.etPort.isEnabled = enabled
        binding.etUsername.isEnabled = enabled
        binding.etPrivateKey.isEnabled = enabled
        binding.etPayload.isEnabled = enabled
    }

    private val vpnServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            vpnService = (service as YohanVpnService.LocalBinder).getService()
            vpnServiceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null; vpnServiceBound = false
        }
    }

    private val sshServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            sshService = (service as SshConnectionService.LocalBinder).getService()
            sshServiceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            sshService = null; sshServiceBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(connectionStatusReceiver)
        logger.removeLogListener()
        if (vpnServiceBound) unbindService(vpnServiceConnection)
        if (sshServiceBound) unbindService(sshServiceConnection)
    }
}
