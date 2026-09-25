package com.c1recorder.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.c1recorder.app.ble.C1ClientState
import com.c1recorder.app.data.C1Device

/**
 * Full device details (moved off Control, which only shows a compact
 * summary) plus the hardware capability test panel. The test panel is
 * collapsed by default — see CapabilityTestSection's own doc comment for
 * why that matters, not just for cleanliness.
 */
@Composable
fun DeviceScreen(viewModel: MainViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val device by viewModel.device.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val capabilityTestLog by viewModel.capabilityTestLog.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("连接状态: ${connectionState.label()}", style = MaterialTheme.typography.titleMedium)
        DeviceInfoCard(connectionState, device)
        Button(onClick = viewModel::refreshDeviceInfo, enabled = connectionState == C1ClientState.Ready) {
            Text("强制刷新设备信息") // Auto-refreshes on every connect already — this is only for re-checking after something changed on the device itself (e.g. battery/storage), not a required step.
        }
        Button(onClick = viewModel::disconnect, enabled = connectionState != C1ClientState.Disconnected) {
            Text("断开连接")
        }

        CapabilityTestSection(
            enabled = connectionState == C1ClientState.Ready,
            latestSessionId = sessions.maxByOrNull { it.sessionId }?.sessionId,
            log = capabilityTestLog,
            onGetFiles = { viewModel.testGetFiles(it) },
            onAttemptDownload = { sessionId -> viewModel.testAttemptDownload(sessionId, fileId = 1, start = 0, end = 160) },
            onClearLog = viewModel::clearCapabilityTestLog,
        )
    }
}

@Composable
private fun DeviceInfoCard(connectionState: C1ClientState, device: C1Device?) {
    if (connectionState == C1ClientState.Ready && device == null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
            Text("正在读取设备信息...")
        }
        return
    }
    if (device == null) {
        Text("尚未连接设备")
        return
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(device.name ?: device.address, style = MaterialTheme.typography.titleSmall)
            Text("设备地址: ${device.address}")
            Text("SN: ${device.sn ?: "读取失败"}")
            Text("固件版本: ${device.firmware ?: "读取失败"}")
            Text("电量: ${device.batteryPercent?.let { "$it%" } ?: "读取失败"}")
            Text("状态: ${device.stateRaw.toStateLabel()}")
            Text("存储: ${formatStorage(device.freeStorageKB, device.totalStorageKB)}")
        }
    }
}

/**
 * docs/ANDROID-HARDWARE-CAPABILITY-TEST.md — investigative controls, not part
 * of the confirmed v1 feature set. GetFiles/attemptDownload always act on the
 * newest known session (highest sessionId — sessionId is a Unix timestamp,
 * see Task 6 in the doc).
 *
 * Start/Pause/Stop recording deliberately are NOT here: Start/Stop are
 * confirmed, real features and already live on Control, so repeating them
 * here would just be a duplicate button; Pause has never been shown to do
 * anything stop doesn't (Task 2 in the doc) and offering it — even labeled
 * "experimental" — risks the user reading it as a working feature, so it's
 * been dropped rather than kept as a misleading affordance. attemptDownload
 * stays: unlike Control/Recordings' real downloadRecording() pipeline, it
 * reports raw header/byte/CRC facts without decoding or saving anything,
 * which is still useful for low-level protocol debugging.
 *
 * Collapsed by default: regression-isolation testing found that rendering
 * this section's buttons (even idle/untapped) correlates with BLE scan
 * result delivery failing intermittently on at least one test device. The
 * mechanism isn't confirmed, so instead of guessing at a deeper fix, this
 * keeps the section's normal-state footprint down to a single toggle row —
 * the buttons only exist in the composition while expanded, and expanding
 * is something the user does deliberately when they're about to run a
 * capability test, not something that happens to be on screen every time
 * scanning is used. This is also why it lives on its own tab now, away from
 * the scan entry point on Control.
 */
@Composable
private fun CapabilityTestSection(
    enabled: Boolean,
    latestSessionId: Long?,
    log: List<String>,
    onGetFiles: (Long) -> Unit,
    onAttemptDownload: (Long) -> Unit,
    onClearLog: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起硬件能力测试" else "展开硬件能力测试")
        }

        if (expanded) {
            Text(
                latestSessionId?.let { "最新 session: 0x%08x".format(it) } ?: "尚无 session（先去录音页刷新列表）",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { latestSessionId?.let(onGetFiles) }, enabled = enabled && latestSessionId != null) {
                Text("BLE 文件读取 (getFiles, 最新session)")
            }
            Button(onClick = { latestSessionId?.let(onAttemptDownload) }, enabled = enabled && latestSessionId != null) {
                Text("BLE 原始下载诊断 (不解码/不保存文件)")
            }
            Button(onClick = onClearLog) { Text("清空日志") }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (log.isEmpty()) {
                        Text("（无日志）", style = MaterialTheme.typography.bodySmall)
                    } else {
                        log.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}
