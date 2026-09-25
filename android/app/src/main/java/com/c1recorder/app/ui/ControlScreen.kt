package com.c1recorder.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.c1recorder.app.ble.C1ClientState
import com.c1recorder.app.ble.ScanState
import com.c1recorder.app.ble.ScannedDevice
import com.c1recorder.app.data.C1Device

/**
 * Default tab. Connection entry lives here at the top (not buried at the
 * bottom of a long list) plus the recording controls the user actually
 * needs day to day. Full device details (SN/firmware/storage) live on
 * DeviceScreen instead of being duplicated here.
 */
@Composable
fun ControlScreen(viewModel: MainViewModel, onRequestPermissions: () -> Unit) {
    val connectionState by viewModel.connectionState.collectAsState()
    val device by viewModel.device.collectAsState()
    val scanState by viewModel.scanState.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val capabilityTestLog by viewModel.capabilityTestLog.collectAsState()

    // Scanning is only meaningful while this screen is visible — stop it if
    // the user switches tabs. This does NOT touch the GATT connection itself
    // (owned by the ViewModel/Repository, independent of which screen is on
    // screen), so switching tabs never disconnects the device.
    DisposableEffect(Unit) {
        onDispose { viewModel.stopScan() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectionCard(
            connectionState = connectionState,
            device = device,
            scanState = scanState,
            discoveredDevices = discoveredDevices,
            onStartScan = { onRequestPermissions(); viewModel.startScan() },
            onStopScan = viewModel::stopScan,
            onDeviceClick = { viewModel.connect(it.address) },
            onDisconnect = viewModel::disconnect,
        )

        RecordingControlsCard(
            enabled = connectionState == C1ClientState.Ready,
            lastResult = capabilityTestLog.lastOrNull(),
            onStart = viewModel::testStartRealtime,
            onStop = viewModel::testStopRecord,
        )

        KeyStatusCard(connectionState, device)
    }
}

@Composable
private fun ConnectionCard(
    connectionState: C1ClientState,
    device: C1Device?,
    scanState: ScanState,
    discoveredDevices: List<ScannedDevice>,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDeviceClick: (ScannedDevice) -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("连接状态: ${connectionState.label()}", style = MaterialTheme.typography.titleMedium)
            Text(device?.name ?: "尚未连接设备")

            if (connectionState == C1ClientState.Disconnected) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStartScan, enabled = scanState != ScanState.SCANNING) {
                        Text("开始扫描")
                    }
                    Button(onClick = onStopScan, enabled = scanState == ScanState.SCANNING) {
                        Text("停止扫描")
                    }
                }
                Text("扫描状态: ${scanState.label()}", style = MaterialTheme.typography.bodySmall)
                when (scanState) {
                    ScanState.PERMISSION_DENIED -> Text("需要蓝牙扫描权限才能发现设备，请授权后重试")
                    ScanState.BLUETOOTH_DISABLED -> Text("请先打开手机蓝牙")
                    else -> Unit
                }
                if (discoveredDevices.isNotEmpty()) {
                    Text("点击设备进行连接", style = MaterialTheme.typography.bodySmall)
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp)) {
                        items(discoveredDevices, key = { it.address }) { scanned ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { onDeviceClick(scanned) },
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(scanned.name, style = MaterialTheme.typography.bodyMedium)
                                    Text("${scanned.address}  RSSI: ${scanned.rssi}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            } else {
                Button(onClick = onDisconnect) { Text("断开连接") }
            }
        }
    }
}

/**
 * No Pause button here (or anywhere in normal UI) — C1 V127's pause opcode
 * has never been shown to do anything other stop does; see
 * docs/ANDROID-HARDWARE-CAPABILITY-TEST.md Task 2. Presenting it as a
 * working feature would mislead the user, not just under-document it, so it
 * stays out rather than being labeled "experimental".
 */
@Composable
private fun RecordingControlsCard(
    enabled: Boolean,
    lastResult: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("录音控制", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = enabled) { Text("开始录音") }
                Button(onClick = onStop, enabled = enabled) { Text("停止录音") }
            }
            if (lastResult != null) {
                val isFailure = lastResult.contains("FAIL:")
                Text(
                    text = if (isFailure) "最近操作: 失败 — ${friendlyErrorMessage(lastResult)}" else "最近操作: $lastResult",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun KeyStatusCard(connectionState: C1ClientState, device: C1Device?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("设备状态", style = MaterialTheme.typography.titleMedium)
            when {
                connectionState == C1ClientState.Ready && device == null -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                        Text("正在读取设备信息...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                device != null -> {
                    Text("电量: ${device.batteryPercent?.let { "$it%" } ?: "读取失败"}")
                    Text("录音状态: ${device.stateRaw.toStateLabel()}")
                    Text("存储: ${formatStorage(device.freeStorageKB, device.totalStorageKB)}")
                }
                else -> Text("尚未连接设备")
            }
        }
    }
}
