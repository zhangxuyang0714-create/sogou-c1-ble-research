package com.c1recorder.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.c1recorder.app.ble.ScanState
import com.c1recorder.app.ble.ScannedDevice
import com.c1recorder.app.data.C1ConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel, onRequestPermissions: () -> Unit) {
    val connectionState by viewModel.connectionState.collectAsState()
    val device by viewModel.device.collectAsState()
    val scanState by viewModel.scanState.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()

    DisposableEffect(Unit) {
        onDispose { viewModel.stopScan() }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("C1 Recorder") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("连接状态: ${connectionState.label()}", style = MaterialTheme.typography.titleMedium)
            Text(device?.name ?: "尚未连接设备")

            ScanSection(
                scanState = scanState,
                discoveredDevices = discoveredDevices,
                onStartScan = { onRequestPermissions(); viewModel.startScan() },
                onStopScan = viewModel::stopScan,
            )
        }
    }
}

@Composable
private fun ScanSection(
    scanState: ScanState,
    discoveredDevices: List<ScannedDevice>,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("扫描状态: ${scanState.label()}", style = MaterialTheme.typography.titleMedium)

        when (scanState) {
            ScanState.PERMISSION_DENIED -> Text("需要蓝牙扫描权限才能发现设备，请授权后重试")
            ScanState.BLUETOOTH_DISABLED -> Text("请先打开手机蓝牙")
            else -> Unit
        }

        Button(onClick = onStartScan, enabled = scanState != ScanState.SCANNING) {
            Text("开始扫描")
        }
        Button(onClick = onStopScan, enabled = scanState == ScanState.SCANNING) {
            Text("停止扫描")
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(discoveredDevices, key = { it.address }) { device ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(device.name, style = MaterialTheme.typography.bodyLarge)
                        Text(device.address, style = MaterialTheme.typography.bodySmall)
                        Text("RSSI: ${device.rssi}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun C1ConnectionState.label(): String = when (this) {
    C1ConnectionState.DISCONNECTED -> "未连接"
    C1ConnectionState.SCANNING -> "扫描中"
    C1ConnectionState.CONNECTING -> "连接中"
    C1ConnectionState.CONNECTED -> "已连接"
}

private fun ScanState.label(): String = when (this) {
    ScanState.IDLE -> "空闲"
    ScanState.SCANNING -> "扫描中"
    ScanState.BLUETOOTH_DISABLED -> "蓝牙未开启"
    ScanState.PERMISSION_DENIED -> "权限被拒绝"
}
