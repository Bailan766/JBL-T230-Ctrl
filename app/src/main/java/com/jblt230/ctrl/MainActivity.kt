package com.jblt230.ctrl

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.jblt230.ctrl.data.AppViewModel
import com.jblt230.ctrl.ui.AppRoot

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private val bluetoothPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val launchPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothPermissions + Manifest.permission.POST_NOTIFICATIONS
        } else {
            bluetoothPermissions
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            vm.setPermissions(hasBluetoothPermissions())
            vm.refreshPermissionStates()
            vm.autoConnectIfReady()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        vm.setPermissions(hasBluetoothPermissions())
        vm.refreshPermissionStates()
        setContent {
            AppRoot(vm = vm, onRequestPermissions = { permissionLauncher.launch(launchPermissions) })
        }
        val missingAny = launchPermissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingAny) permissionLauncher.launch(launchPermissions)
    }

    override fun onResume() {
        super.onResume()
        vm.setPermissions(hasBluetoothPermissions())
        vm.refreshPermissionStates()
        vm.autoConnectIfReady()
    }

    /** Release idle link on background unless user explicitly enables persistent takeover. */
    override fun onStop() {
        if (!vm.takeoverConfig.value.keepLinkAlive) {
            vm.disconnect()
        }
        super.onStop()
    }

    private fun hasBluetoothPermissions(): Boolean = bluetoothPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
