package com.c1recorder.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.c1recorder.app.ble.C1ClientState
import com.c1recorder.app.data.RecordingSession
import com.c1recorder.app.data.SessionDownloadState
import java.io.File

@Composable
fun RecordingsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val recordingsDir = context.getExternalFilesDir("recordings") ?: context.filesDir

    val sessions by viewModel.sessions.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val isRefreshing by viewModel.isRefreshingSessions.collectAsState()
    val downloadStates by viewModel.downloadStates.collectAsState()
    val playingSessionId by viewModel.playingSessionId.collectAsState()

    LaunchedEffect(sessions) {
        viewModel.checkLocalFiles(recordingsDir)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("录音列表 (${sessions.size})", style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = viewModel::refreshSessions,
                enabled = connectionState == C1ClientState.Ready && !isRefreshing,
            ) {
                Text(if (isRefreshing) "正在同步..." else "刷新录音列表")
            }
        }

        if (sessions.isEmpty()) {
            Text("暂无录音——请先连接设备并刷新", style = MaterialTheme.typography.bodyMedium)
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            items(sessions.sortedByDescending { it.sessionId }, key = { it.sessionId }) { session ->
                val dlState = downloadStates[session.sessionId] ?: SessionDownloadState.Idle
                val isPlaying = playingSessionId == session.sessionId

                RecordingCard(
                    session = session,
                    downloadState = dlState,
                    isPlaying = isPlaying,
                    isDeviceReady = connectionState == C1ClientState.Ready,
                    onDownload = { viewModel.downloadSession(session, recordingsDir) },
                    onTogglePlay = { wavFile -> viewModel.togglePlayAudio(session.sessionId, wavFile) },
                    onShare = { wavFile -> shareRecordingFile(context, wavFile) },
                )
            }
        }
    }
}

@Composable
private fun RecordingCard(
    session: RecordingSession,
    downloadState: SessionDownloadState,
    isPlaying: Boolean,
    isDeviceReady: Boolean,
    onDownload: () -> Unit,
    onTogglePlay: (File) -> Unit,
    onShare: (File) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatSessionDateTime(session.sessionId),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "0x%08x".format(session.sessionId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Text(
                text = "时长: ${formatDuration(session.durationMs)}",
                style = MaterialTheme.typography.bodyMedium,
            )

            when (downloadState) {
                is SessionDownloadState.Idle -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(
                            onClick = onDownload,
                            enabled = isDeviceReady,
                        ) {
                            Text("下载录音 (BLE)")
                        }
                    }
                }

                is SessionDownloadState.FetchingFiles -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                        Text("正在检索录音文件...", style = MaterialTheme.typography.bodySmall)
                    }
                }

                is SessionDownloadState.Downloading -> {
                    // This percentage only covers the BLE receive phase — it
                    // is not the whole task's progress. Verifying/Decoding
                    // below are separate, real stages, not a continuation of
                    // this bar, so it never fakes progress past what has
                    // actually been received.
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(
                            progress = { (downloadState.percent.toFloat() / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "正在接收录音: ${downloadState.percent}%",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = "${downloadState.bytesReceived / 1024} KB",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                is SessionDownloadState.Verifying -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                        Text("正在校验 CRC...", style = MaterialTheme.typography.bodySmall)
                    }
                }

                is SessionDownloadState.Decoding -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                        Text("正在解码并生成 WAV...", style = MaterialTheme.typography.bodySmall)
                    }
                }

                is SessionDownloadState.Completed -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "✓ 已完成 (${downloadState.wavFile.length() / 1024} KB WAV${if (downloadState.crcVerified) " · CRC校验通过" else ""})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { onTogglePlay(downloadState.wavFile) },
                                colors = if (isPlaying) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
                            ) {
                                Text(if (isPlaying) "停止播放" else "播放 WAV")
                            }
                            OutlinedButton(
                                onClick = { onShare(downloadState.wavFile) },
                            ) {
                                Text("分享")
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            OutlinedButton(
                                onClick = onDownload,
                                enabled = isDeviceReady,
                            ) {
                                Text("重新下载")
                            }
                        }
                    }
                }

                is SessionDownloadState.Error -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "下载失败: ${friendlyErrorMessage(downloadState.message)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = "详情: ${downloadState.message}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Button(
                                onClick = onDownload,
                                enabled = isDeviceReady,
                            ) {
                                Text("重试下载")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun shareRecordingFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享录音"))
    } catch (e: Exception) {
        android.util.Log.e("RecordingsScreen", "Share error", e)
    }
}
