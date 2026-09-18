package com.c1recorder.app.ui

import androidx.compose.foundation.clickable
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
import com.c1recorder.app.ble.C1ClientState
import com.c1recorder.app.ble.ScanState
import com.c1recorder.app.ble.ScannedDevice
import com.c1recorder.app.data.C1Device
import com.c1recorder.app.protocol.C1Protocol

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
            DeviceInfoSection(device)
            Button(onClick = viewModel::disconnect, enabled = connectionState != C1ClientState.Disconnected) {
                Text("断开连接")
            }

            ScanSection(
                scanState = scanState,
                discoveredDevices = discoveredDevices,
                onStartScan = { onRequestPermissions(); viewModel.startScan() },
                onStopScan = viewModel::stopScan,
                onDeviceClick = { viewModel.connect(it.address) },
            )
        }
    }
}

@Composable
private fun DeviceInfoSection(device: C1Device?) {
    if (device == null) {
        Text("尚未连接设备")
        return
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(device.name ?: device.address, style = MaterialTheme.typography.titleSmall)
            Text("SN: ${device.sn ?: "读取失败"}")
            Text("固件版本: ${device.firmware ?: "读取失败"}")
            Text("电量: ${device.batteryPercent?.let { "$it%" } ?: "读取失败"}")
            Text("状态: ${device.stateRaw.toStateLabel()}")
            Text("存储: ${formatStorage(device.freeStorageKB, device.totalStorageKB)}")
        }
    }
}

private fun Int?.toStateLabel(): String = when (this) {
    null -> "读取失败"
    C1Protocol.StateValue.IDLE -> "空闲"
    C1Protocol.StateValue.RECORDING -> "录音中"
    else -> "未知 (0x%04x)".format(this)
}

private fun formatStorage(freeKB: Long?, totalKB: Long?): String {
    if (freeKB == null || totalKB == null) return "读取失败"
    val freeGB = freeKB / 1024.0 / 1024.0
    val totalGB = totalKB / 1024.0 / 1024.0
    return "%.2f / %.2f GB 可用".format(freeGB, totalGB)
}

@Composable
private fun ScanSection(
    scanState: ScanState,
    discoveredDevices: List<ScannedDevice>,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDeviceClick: (ScannedDevice) -> Unit,
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
        Text("点击设备进行连接", style = MaterialTheme.typography.bodySmall)

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(discoveredDevices, key = { it.address }) { device ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onDeviceClick(device) },
                ) {
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

private fun C1ClientState.label(): String = when (this) {
    C1ClientState.Disconnected -> "未连接"
    C1ClientState.Connecting -> "连接中"
    C1ClientState.DiscoveringServices -> "发现服务中"
    C1ClientState.PerformingHandshake -> "握手中"
    C1ClientState.Ready -> "已就绪"
    is C1ClientState.ConnectionFailed -> "连接失败: $reason"
    is C1ClientState.MissingCharacteristics -> "缺少必要特征值: ${missing.joinToString()}"
}

private fun ScanState.label(): String = when (this) {
    ScanState.IDLE -> "空闲"
    ScanState.SCANNING -> "扫描中"
    ScanState.BLUETOOTH_DISABLED -> "蓝牙未开启"
    ScanState.PERMISSION_DENIED -> "权限被拒绝"
}
