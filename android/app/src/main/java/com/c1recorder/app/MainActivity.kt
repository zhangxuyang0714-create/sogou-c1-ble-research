package com.c1recorder.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.c1recorder.app.ble.AndroidC1BleClient
import com.c1recorder.app.ble.AndroidC1BleScanner
import com.c1recorder.app.data.DefaultC1Repository
import com.c1recorder.app.ui.MainScreen
import com.c1recorder.app.ui.MainViewModel

class MainActivity : ComponentActivity() {

    private val scanner by lazy { AndroidC1BleScanner(applicationContext) }
    private val bleClient by lazy { AndroidC1BleClient(applicationContext) }

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory(DefaultC1Repository(bleClient), scanner)
    }

    private val requestBlePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        // Result is read via checkSelfPermission at scan time (in AndroidC1BleScanner);
        // nothing to do here beyond letting the user retry the scan button.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        viewModel = viewModel,
                        onRequestPermissions = ::requestBlePermissionsIfNeeded,
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.stopScan()
    }

    private fun requestBlePermissionsIfNeeded() {
        // minSdk is 31 (Android 12), so these runtime permissions always apply.
        requestBlePermissions.launch(
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
        )
    }
}
