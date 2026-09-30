package com.obd2dash.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.obd2dash.core.ObdRepository
import com.obd2dash.core.Prefs
import com.obd2dash.databinding.ActivityConnectBinding
import com.obd2dash.databinding.ItemDeviceBinding
import com.obd2dash.service.ObdService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Adapter picker. Pairing itself is left to Android's Bluetooth settings, which
 * handles the PIN prompt; this screen only lists what is already paired and
 * optionally scans for nearby devices.
 */
class ConnectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectBinding
    private lateinit var prefs: Prefs
    private val devices = ArrayList<BluetoothDevice>()
    private val adapterView = DeviceAdapter()
    private var receiverRegistered = false
    private var handedOff = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { !it }) {
                toast("Bluetooth permission is required to talk to the adapter")
            }
            refreshPairedDevices()
        }

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null && devices.none { it.address == device.address }) {
                        devices.add(device)
                        adapterView.notifyItemInserted(devices.size - 1)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    binding.scanButton.isEnabled = true
                    binding.scanButton.text = getString(com.obd2dash.R.string.scan)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.deviceList.layoutManager = LinearLayoutManager(this)
        binding.deviceList.adapter = adapterView

        binding.scanButton.setOnClickListener { startScan() }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        requestPermissions()
        observeState()
    }

    override fun onStart() {
        super.onStart()
        handedOff = false
        refreshPairedDevices()
        // Targeting API 34 requires an export flag. These are protected system
        // broadcasts, which reach a not-exported receiver regardless.
        ContextCompat.registerReceiver(
            this,
            discoveryReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true

        // Jump straight to the dashboard when a session is already running.
        if (isLive(ObdRepository.connectionState.value)) {
            openDashboard()
        } else if (prefs.autoConnect && prefs.lastDeviceAddress != null && !ObdRepository.userDisconnected) {
            binding.statusText.text = "Auto-connecting to " + (prefs.lastDeviceName ?: "last adapter")
            connect(prefs.lastDeviceAddress!!, prefs.lastDeviceName)
        }
    }

    override fun onStop() {
        super.onStop()
        if (receiverRegistered) {
            unregisterReceiver(discoveryReceiver)
            receiverRegistered = false
        }
        cancelDiscovery()
    }

    private fun observeState() {
        lifecycleScope.launch {
            ObdRepository.statusMessage.collectLatest { binding.statusText.text = it }
        }
        lifecycleScope.launch {
            ObdRepository.connectionState.collectLatest { state ->
                if (isLive(state)) openDashboard()
            }
        }
    }

    /** Connected, or holding the connection while the ignition is off. */
    private fun isLive(state: ObdRepository.ConnState) =
        state == ObdRepository.ConnState.CONNECTED || state == ObdRepository.ConnState.WAITING_FOR_ECU

    /**
     * Hands off to the dashboard and drops out of the back stack, so Back from
     * the dashboard exits rather than bouncing off this screen's auto-connect.
     */
    private fun openDashboard() {
        if (handedOff) return
        handedOff = true
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }

    /** Requests whichever Bluetooth permissions this Android version uses. */
    private fun requestPermissions() {
        val needed = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_CONNECT
            needed += Manifest.permission.BLUETOOTH_SCAN
        } else {
            // Pre-12, scanning for nearby devices is gated behind location.
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(needed.toTypedArray())
    }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @SuppressLint("MissingPermission", "NotifyDataSetChanged")
    private fun refreshPairedDevices() {
        val adapter = bluetoothAdapter()
        if (adapter == null) {
            binding.statusText.text = "This device has no Bluetooth radio"
            return
        }
        if (!adapter.isEnabled) {
            binding.statusText.text = "Bluetooth is off - turn it on to connect"
        }
        devices.clear()
        try {
            devices.addAll(adapter.bondedDevices.orEmpty())
        } catch (_: SecurityException) {
            binding.statusText.text = "Bluetooth permission denied"
        }
        adapterView.notifyDataSetChanged()
        binding.emptyHint.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val adapter = bluetoothAdapter() ?: return
        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            if (adapter.startDiscovery()) {
                binding.scanButton.isEnabled = false
                binding.scanButton.text = "Scanning..."
            } else {
                toast("Could not start scanning")
            }
        } catch (_: SecurityException) {
            toast("Scan permission denied")
        }
    }

    @SuppressLint("MissingPermission")
    private fun cancelDiscovery() {
        try {
            bluetoothAdapter()?.takeIf { it.isDiscovering }?.cancelDiscovery()
        } catch (_: SecurityException) {
        }
    }

    private fun connect(address: String, name: String?) {
        ObdRepository.userDisconnected = false
        cancelDiscovery()
        prefs.lastDeviceAddress = address
        prefs.lastDeviceName = name
        ObdService.connect(this, address)
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private inner class DeviceAdapter : RecyclerView.Adapter<DeviceAdapter.Holder>() {

        inner class Holder(val item: ItemDeviceBinding) : RecyclerView.ViewHolder(item.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        @SuppressLint("MissingPermission")
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val device = devices[position]
            val name = try {
                device.name
            } catch (_: SecurityException) {
                null
            } ?: "Unknown device"
            holder.item.deviceName.text = name
            holder.item.deviceAddress.text = device.address
            holder.item.root.setOnClickListener {
                binding.statusText.text = "Connecting to " + name
                connect(device.address, name)
            }
        }

        override fun getItemCount() = devices.size
    }
}
