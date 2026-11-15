package com.droid.dolphy.bluetooth.forcereconnect

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Force Reconnect": fixes the common TWS-earbud bug where the phone still shows
 * a device as bonded/"connected" in its Bluetooth list, but no audio actually
 * flows (the profile-level connection silently dropped).
 *
 * This only ever touches devices that are ALREADY bonded to this phone
 * (BluetoothDevice.BOND_BONDED) — it does not scan for or connect to anyone
 * else's hardware. It uses the standard, documented BluetoothProfile proxy
 * APIs (getProfileProxy) and the hidden-but-widely-used connect(device) /
 * disconnect(device) methods that every "quick connect" Bluetooth widget on
 * Android relies on, via reflection, because the public BluetoothProfile
 * interface does not expose them directly.
 */
private data class BondedDeviceEntry(
    val device: BluetoothDevice,
    val name: String,
)

private enum class ReconnectState { IDLE, WORKING, SUCCESS, FAILED }

@SuppressLint("MissingPermission")
private fun forceReconnectProfile(context: Context, device: BluetoothDevice, profileId: Int): Boolean {
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        ?: return false

    var proxy: BluetoothProfile? = null
    val gotProxy = java.util.concurrent.CountDownLatch(1)
    val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
            proxy = p
            gotProxy.countDown()
        }
        override fun onServiceDisconnected(profile: Int) {}
    }

    adapter.getProfileProxy(context, listener, profileId)
    gotProxy.await(3, java.util.concurrent.TimeUnit.SECONDS)
    val p = proxy ?: return false

    return try {
        val disconnectMethod = p.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
        disconnectMethod.invoke(p, device)
        Thread.sleep(400)
        val connectMethod = p.javaClass.getMethod("connect", BluetoothDevice::class.java)
        val result = connectMethod.invoke(p, device)
        (result as? Boolean) ?: true
    } catch (_: Exception) {
        false
    } finally {
        runCatching { adapter.closeProfileProxy(profileId, p) }
    }
}

/**
 * Runs the force-reconnect on both the audio (A2DP) and call (HFP/Headset)
 * profiles, since a stuck earbud can be "connected" on one and dead on the
 * other. Only acts on an already-bonded device.
 */
private suspend fun forceReconnect(context: Context, device: BluetoothDevice): Boolean =
    withContext(Dispatchers.IO) {
        if (device.bondState != BluetoothDevice.BOND_BONDED) return@withContext false
        // BluetoothProfile.A2DP = 2, BluetoothProfile.HEADSET = 1
        val a2dpOk = forceReconnectProfile(context, device, BluetoothProfile.A2DP)
        val headsetOk = forceReconnectProfile(context, device, BluetoothProfile.HEADSET)
        a2dpOk || headsetOk
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForceReconnectScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bondedDevices by remember { mutableStateOf<List<BondedDeviceEntry>>(emptyList()) }
    var states by remember { mutableStateOf<Map<String, ReconnectState>>(emptyMap()) }

    fun hasBtConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ActivityCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun refreshBonded() {
        if (!hasBtConnectPermission()) return
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        bondedDevices = adapter?.bondedDevices
            .orEmpty()
            .map { BondedDeviceEntry(it, it.name ?: it.address) }
            .sortedBy { it.name }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) refreshBonded() }

    LaunchedEffect(Unit) {
        if (hasBtConnectPermission()) {
            refreshBonded()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Force Reconnect") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { refreshBonded() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                "Cütləşdirilmiş (paired) cihazların profil bağlantısını (A2DP/HFP) " +
                    "sıfırlayır. Yalnız telefonunda artıq qoşulu olan cihazlara toxunur — " +
                    "yad cihaz axtarmır.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (bondedDevices.isEmpty()) {
                Text(
                    "Cütləşdirilmiş cihaz tapılmadı. Əvvəlcə qulaqlığı telefonun Bluetooth " +
                        "ayarlarından bir dəfə cütləşdir.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(bondedDevices, key = { it.device.address }) { entry ->
                        val state = states[entry.device.address] ?: ReconnectState.IDLE
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                androidx.compose.foundation.layout.Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.BluetoothConnected,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(entry.name, style = MaterialTheme.typography.titleMedium)
                                        }
                                        Text(
                                            entry.device.address,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    when (state) {
                                        ReconnectState.WORKING -> CircularProgressIndicator(modifier = Modifier.height(24.dp))
                                        else -> Button(onClick = {
                                            states = states + (entry.device.address to ReconnectState.WORKING)
                                            scope.launch {
                                                val ok = forceReconnect(context, entry.device)
                                                states = states + (entry.device.address to
                                                    if (ok) ReconnectState.SUCCESS else ReconnectState.FAILED)
                                                delay(3000)
                                                states = states + (entry.device.address to ReconnectState.IDLE)
                                            }
                                        }) {
                                            Text("Reconnect")
                                        }
                                    }
                                }
                                when (state) {
                                    ReconnectState.SUCCESS -> Text(
                                        "✓ Yenidən qoşuldu",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 6.dp)
                                    )
                                    ReconnectState.FAILED -> Text(
                                        "✗ Alınmadı — cihazı əl ilə söndür/aç",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 6.dp)
                                    )
                                    else -> {}
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
