package com.smartstick.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.smartstick.bluetooth.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), BluetoothSerialService.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothService: BluetoothSerialService? = null
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val discoveredDevices = mutableListOf<BluetoothDevice>()

    // Commands
    private val COMMAND_IDLE        = "mot0"
    private val COMMAND_SHOT_POWER  = "mot1"
    private val COMMAND_STICK_FLEX  = "mot2"
    private val COMMAND_SMART       = "mot101"

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) startDeviceDiscovery()
        else showToast("Bluetooth permissions denied.")
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) checkPermissionsAndDiscover()
        else showToast("Bluetooth is required to use this app.")
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        if (!discoveredDevices.contains(it)) {
                            discoveredDevices.add(it)
                            appendLog("📡 Found: ${getDeviceName(it)} [${it.address}]")
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    binding.btnScan.isEnabled = true
                    binding.btnScan.text = "Scanning..."
                    appendLog("✅ Scan complete — ${discoveredDevices.size} device(s)")
                }
            }
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device: BluetoothDevice? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                when (bondState) {
                    BluetoothDevice.BOND_BONDED -> {
                        appendLog("🔗 Paired successfully with ${getDeviceName(device)}")
                        device?.let { connectToDevice(it) }
                    }
                    BluetoothDevice.BOND_BONDING -> appendLog("⏳ Pairing in progress...")
                    BluetoothDevice.BOND_NONE    -> appendLog("❌ Pairing cancelled")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        setupUI()
        registerReceivers()
        bluetoothService = BluetoothSerialService(this)
        showPairedDevices()
    }

    private fun setupUI() {
        binding.btnScan.setOnClickListener { checkPermissionsAndDiscover() }
        binding.btnConnect.setOnClickListener { showDeviceChooser() }
        binding.btnDisconnect.setOnClickListener { bluetoothService?.disconnect() }

        // Cinematic image buttons — FrameLayouts
        binding.btnRigide.setOnClickListener     { sendCommand(COMMAND_SHOT_POWER) }
        binding.btnNonRigide.setOnClickListener  { sendCommand(COMMAND_STICK_FLEX) }
        binding.btnAutoDetect.setOnClickListener { sendCommand(COMMAND_SMART) }

        binding.btnClear.setOnClickListener { binding.tvTerminal.text = "" }

        binding.btnSend.setOnClickListener {
            val cmd = binding.etCommand.text.toString().trim()
            if (cmd.isNotEmpty()) {
                sendCommand(cmd)
                binding.etCommand.text?.clear()
            }
        }

        updateConnectionState(false)
    }

    private fun registerReceivers() {
        val discoveryFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(discoveryReceiver, discoveryFilter)
        registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
    }

    private fun checkPermissionsAndDiscover() {
        if (!bluetoothAdapter.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)

        val notGranted = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isEmpty()) startDeviceDiscovery()
        else permissionLauncher.launch(notGranted.toTypedArray())
    }

    private fun startDeviceDiscovery() {
        discoveredDevices.clear()
        showPairedDevices()
        if (bluetoothAdapter.isDiscovering) bluetoothAdapter.cancelDiscovery()
        binding.btnScan.isEnabled = false
        binding.btnScan.text = "Scanning..."
        appendLog("🔍 Starting Bluetooth scan...")
        bluetoothAdapter.startDiscovery()
    }

    private fun showPairedDevices() {
        try {
            val paired = bluetoothAdapter.bondedDevices
            paired?.forEach { if (!discoveredDevices.contains(it)) discoveredDevices.add(it) }
            appendLog("📋 ${paired?.size ?: 0} paired device(s)")
        } catch (e: SecurityException) {
            appendLog("⚠️ Permission required")
        }
    }

    private fun showDeviceChooser() {
        if (discoveredDevices.isEmpty()) {
            showToast("No devices found. Please scan first.")
            return
        }
        val items = discoveredDevices.map { device ->
            val name = getDeviceName(device)
            val bonded = if (device.bondState == BluetoothDevice.BOND_BONDED) "✓" else "○"
            "$bonded $name\n${device.address}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Choose a device")
            .setItems(items) { _, index -> initiateConnection(discoveredDevices[index]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun initiateConnection(device: BluetoothDevice) {
        if (bluetoothAdapter.isDiscovering) bluetoothAdapter.cancelDiscovery()
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            appendLog("⏳ Pairing with ${getDeviceName(device)}...")
            try { device.createBond() } catch (e: SecurityException) { appendLog("❌ Permission denied") }
        } else {
            connectToDevice(device)
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        appendLog("🔌 Connecting to ${getDeviceName(device)}...")
        binding.tvDeviceName.text = getDeviceName(device)
        bluetoothService?.connect(device)
    }

    private fun sendCommand(command: String) {
        if (bluetoothService?.isConnected() == true) {
            bluetoothService?.send(command)
            appendLog("> $command", outgoing = true)
        } else {
            showToast("Not connected. Please connect first.")
        }
    }

    override fun onConnected(deviceName: String) {
        runOnUiThread {
            updateConnectionState(true)
            binding.tvDeviceName.text = deviceName
            appendLog("✅ Connected to $deviceName")
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            updateConnectionState(false)
            appendLog("🔴 Disconnected")
        }
    }

    override fun onDataReceived(data: String) {
        runOnUiThread { appendLog(data, incoming = true) }
    }

    override fun onError(message: String) {
        runOnUiThread { appendLog("⚠️ $message") }
    }

    private fun updateConnectionState(connected: Boolean) {
        binding.btnConnect.visibility     = if (connected) View.GONE else View.VISIBLE
        binding.btnDisconnect.visibility  = if (connected) View.VISIBLE else View.GONE
        binding.statusIndicator.setBackgroundResource(
            if (connected) R.drawable.circle_green else R.drawable.circle_red
        )
        binding.tvConnectionStatus.text = if (connected) "Connected" else "Disconnected"

        // Enable/disable image buttons
        binding.btnRigide.isEnabled     = connected
        binding.btnNonRigide.isEnabled  = connected
        binding.btnAutoDetect.isEnabled = connected
        binding.btnRigide.alpha         = if (connected) 1.0f else 0.5f
        binding.btnNonRigide.alpha      = if (connected) 1.0f else 0.5f
        binding.btnAutoDetect.alpha     = if (connected) 1.0f else 0.5f
        binding.btnSend.isEnabled       = connected
    }

    private fun appendLog(text: String, incoming: Boolean = false, outgoing: Boolean = false) {
        val time = sdf.format(Date())
        val prefix = when { outgoing -> "> "; incoming -> "< "; else -> "  " }
        val current = binding.tvTerminal.text.toString()
        binding.tvTerminal.text = if (current.isEmpty()) "$time $prefix$text"
                                  else "$current\n$time $prefix$text"
        binding.scrollTerminal.post { binding.scrollTerminal.fullScroll(View.FOCUS_DOWN) }
    }

    private fun getDeviceName(device: BluetoothDevice?): String {
        return try { device?.name ?: "Unknown device" } catch (e: SecurityException) { "Unknown device" }
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(discoveryReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(bondReceiver) } catch (_: Exception) {}
        bluetoothService?.disconnect()
    }
}
