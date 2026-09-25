package com.c1recorder.app.ui

import com.c1recorder.app.ble.C1ClientState
import com.c1recorder.app.ble.ScanState
import com.c1recorder.app.protocol.C1Protocol
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Labels/formatters shared by ControlScreen, RecordingsScreen and DeviceScreen — kept in one place instead of copied per screen. */

fun C1ClientState.label(): String = when (this) {
    C1ClientState.Disconnected -> "未连接"
    C1ClientState.Connecting -> "连接中"
    C1ClientState.DiscoveringServices -> "发现服务中"
    C1ClientState.PerformingHandshake -> "握手中"
    C1ClientState.Ready -> "已就绪"
    is C1ClientState.ConnectionFailed -> "连接失败: $reason"
    is C1ClientState.MissingCharacteristics -> "缺少必要特征值: ${missing.joinToString()}"
}

fun ScanState.label(): String = when (this) {
    ScanState.IDLE -> "空闲"
    ScanState.SCANNING -> "扫描中"
    ScanState.BLUETOOTH_DISABLED -> "蓝牙未开启"
    ScanState.PERMISSION_DENIED -> "权限被拒绝"
}

fun Int?.toStateLabel(): String = when (this) {
    null -> "读取失败"
    C1Protocol.StateValue.IDLE -> "空闲"
    C1Protocol.StateValue.RECORDING -> "录音中"
    else -> "未知 (0x%04x)".format(this)
}

fun formatStorage(freeKB: Long?, totalKB: Long?): String {
    if (freeKB == null || totalKB == null) return "读取失败"
    val freeGB = freeKB / 1024.0 / 1024.0
    val totalGB = totalKB / 1024.0 / 1024.0
    return "%.2f / %.2f GB 可用".format(freeGB, totalGB)
}

/**
 * Maps an internal error message (Kotlin exception text — protocol jargon,
 * hex CRC/opcode values, "FILE_TAIL", GATT status codes) to one short,
 * non-technical sentence for the normal UI. The raw message stays available
 * next to this wherever it's shown, for anyone who needs it — this only
 * decides what a normal user reads first.
 */
fun friendlyErrorMessage(raw: String): String = when {
    raw.contains("超时") -> "操作超时，请重试"
    raw.contains("CRC 校验失败") -> "数据校验未通过，可能不完整，请重试"
    raw.contains("解码失败") -> "音频解码失败，请重试"
    raw.contains("拒绝下载请求") -> "设备拒绝了这次请求"
    raw.contains("未就绪") || raw.contains("未连接") || raw.contains("连接已断开") -> "设备未连接，请重新连接后重试"
    raw.contains("已有操作进行中") -> "设备正忙，请稍后重试"
    raw.contains("检索文件列表失败") || raw.contains("未找到有效文件条目") -> "暂时无法获取该录音的文件信息"
    raw.contains("未收到任何音频数据") -> "未收到任何数据，请重试"
    else -> "操作失败，请重试"
}

fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d分%02d秒".format(minutes, seconds)
}

private val SESSION_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * sessionId is a Unix timestamp (UTC Epoch Seconds) taken from the device's
 * own clock, which this app syncs to UTC+8 (China Standard Time, no DST) —
 * see AndroidC1BleClient.syncDeviceTimeAndAwait. Formatting with the
 * viewing phone's own ZoneId.systemDefault() instead of that fixed offset
 * was wrong: it matched a phone set to CST but drifted on any other
 * timezone (confirmed against a real recording — RECORD/20260919/10_44_36.*
 * on the device's own USB storage vs. this formatter showing 11:44:36 on a
 * UTC+9 test phone).
 */
private val DEVICE_ZONE = ZoneOffset.ofHours(8)

fun formatSessionDateTime(sessionId: Long): String =
    Instant.ofEpochSecond(sessionId).atZone(DEVICE_ZONE).format(SESSION_DATE_TIME_FORMATTER)
