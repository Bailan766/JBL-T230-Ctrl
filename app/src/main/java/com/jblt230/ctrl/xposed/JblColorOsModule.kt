package com.jblt230.ctrl.xposed

import com.jblt230.ctrl.bridge.BridgeContract
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Unified LSPosed module entry point bundled inside `com.jblt230.ctrl`.
 * Dispatches cleanly by package name so no cross-process pollution occurs.
 */
class JblColorOsModule : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            BridgeContract.PKG_MELODY,
            BridgeContract.PKG_ACCESSORY,
            BridgeContract.PKG_WIRELESS_SETTINGS -> {
                MelodySpoofHooks.install(lpparam.classLoader, lpparam.packageName)
            }

            BridgeContract.PKG_JBL_OFFICIAL -> {
                OfficialGattSnifferHooks.install(lpparam.classLoader)
            }
        }
    }
}
