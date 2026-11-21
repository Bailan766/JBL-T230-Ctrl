package com.jblt230.ctrl

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import com.jblt230.ctrl.data.AppViewModel
import com.jblt230.ctrl.ui.AppRoot

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private val permissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            vm.setPermissions(result.values.all { it })
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.setPermissions(hasPermissions())
        setContent {
            Surface {
                AppRoot(vm = vm, onRequestPermissions = { permissionLauncher.launch(permissions) })
            }
        }
        if (!hasPermissions()) permissionLauncher.launch(permissions)
    }

    override fun onResume() {
        super.onResume()
        vm.refreshOverlayPermission()
    }

    /** Release idle link on background unless user explicitly enables persistent takeover. */
    override fun onStop() {
        if (!vm.takeoverConfig.value.keepLinkAlive) {
            vm.disconnect()
        }
        super.onStop()
    }

    private fun hasPermissions(): Boolean = permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
