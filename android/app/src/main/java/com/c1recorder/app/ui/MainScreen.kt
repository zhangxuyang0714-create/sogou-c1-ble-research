package com.c1recorder.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.c1recorder.app.data.C1ConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val device by viewModel.device.collectAsState()

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
        }
    }
}

private fun C1ConnectionState.label(): String = when (this) {
    C1ConnectionState.DISCONNECTED -> "未连接"
    C1ConnectionState.SCANNING -> "扫描中"
    C1ConnectionState.CONNECTING -> "连接中"
    C1ConnectionState.CONNECTED -> "已连接"
}
