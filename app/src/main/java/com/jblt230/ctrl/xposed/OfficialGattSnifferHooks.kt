package com.jblt230.ctrl.xposed

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Handler
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap

/**
 * Clean LSPosed GATT sniffer injected only into official JBL (`jbl.stc.com`).
 * Logs all outgoing GATT writes (`TX`) and incoming characteristic notifications/reads (`RX`)
 * to `logcat` under tag `JblGattSniffer`.
 */
object OfficialGattSnifferHooks {

    private const val TAG = "JblGattSniffer"
    private val hookedCallbackClasses = ConcurrentHashMap.newKeySet<String>()

    fun install(classLoader: ClassLoader) {
        log("Installing OfficialGattSnifferHooks in jbl.stc.com")
        hookGattWrites()
        hookConnectGattCallbacks()
        hookGattReads()
        if (classLoader.hashCode() == 0) log("noop")
    }

    private fun hookGattWrites() {
        runCatching {
            @Suppress("DEPRECATION")
            XposedHelpers.findAndHookMethod(
                BluetoothGatt::class.java,
                "writeCharacteristic",
                BluetoothGattCharacteristic::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val charac = param.args.firstOrNull() as? BluetoothGattCharacteristic ?: return
                        @Suppress("DEPRECATION")
                        val bytes = charac.value ?: return
                        logFrame("TX18", charac.uuid.toString(), bytes)
                    }
                }
            )
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                BluetoothGatt::class.java,
                "writeCharacteristic",
                BluetoothGattCharacteristic::class.java,
                ByteArray::class.java,
                Int::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val charac = param.args.getOrNull(0) as? BluetoothGattCharacteristic ?: return
                        val bytes = param.args.getOrNull(1) as? ByteArray ?: return
                        logFrame("TX33", charac.uuid.toString(), bytes)
                    }
                }
            )
        }
    }

    private fun hookGattReads() {
        runCatching {
            XposedHelpers.findAndHookMethod(
                BluetoothGatt::class.java,
                "readCharacteristic",
                BluetoothGattCharacteristic::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val charac = param.args.firstOrNull() as? BluetoothGattCharacteristic ?: return
                        Log.i(TAG, "READ_REQ uuid=${charac.uuid}")
                    }
                }
            )
        }
    }

    private fun hookConnectGattCallbacks() {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val cb = param.args.firstOrNull { it is BluetoothGattCallback } ?: return
                hookCallbackClassOnce(cb.javaClass)
            }
        }
        val overloads = listOf(
            arrayOf<Class<*>>(Context::class.java, Boolean::class.javaPrimitiveType!!, BluetoothGattCallback::class.java),
            arrayOf<Class<*>>(Context::class.java, Boolean::class.javaPrimitiveType!!, BluetoothGattCallback::class.java, Int::class.javaPrimitiveType!!),
            arrayOf<Class<*>>(Context::class.java, Boolean::class.javaPrimitiveType!!, BluetoothGattCallback::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            arrayOf<Class<*>>(Context::class.java, Boolean::class.javaPrimitiveType!!, BluetoothGattCallback::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Handler::class.java)
        )
        for (sig in overloads) {
            runCatching {
                XposedHelpers.findAndHookMethod(BluetoothDevice::class.java, "connectGatt", *sig, hook)
            }
        }
    }

    private fun hookCallbackClassOnce(cls: Class<*>) {
        if (!hookedCallbackClasses.add(cls.name)) return
        log("Hooking BluetoothGattCallback subclass: ${cls.name}")

        runCatching {
            @Suppress("DEPRECATION")
            XposedHelpers.findAndHookMethod(
                cls,
                "onCharacteristicChanged",
                BluetoothGatt::class.java,
                BluetoothGattCharacteristic::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val charac = param.args.getOrNull(1) as? BluetoothGattCharacteristic ?: return
                        @Suppress("DEPRECATION")
                        val bytes = charac.value ?: return
                        logFrame("RX18", charac.uuid.toString(), bytes)
                    }
                }
            )
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                cls,
                "onCharacteristicChanged",
                BluetoothGatt::class.java,
                BluetoothGattCharacteristic::class.java,
                ByteArray::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val charac = param.args.getOrNull(1) as? BluetoothGattCharacteristic ?: return
                        val bytes = param.args.getOrNull(2) as? ByteArray ?: return
                        logFrame("RX33", charac.uuid.toString(), bytes)
                    }
                }
            )
        }
        runCatching {
            @Suppress("DEPRECATION")
            XposedHelpers.findAndHookMethod(
                cls,
                "onCharacteristicRead",
                BluetoothGatt::class.java,
                BluetoothGattCharacteristic::class.java,
                Int::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val charac = param.args.getOrNull(1) as? BluetoothGattCharacteristic ?: return
                        val status = param.args.getOrNull(2) as? Int ?: -1
                        @Suppress("DEPRECATION")
                        val bytes = charac.value ?: return
                        logFrame("RD18(s=$status)", charac.uuid.toString(), bytes)
                    }
                }
            )
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                cls,
                "onCharacteristicRead",
                BluetoothGatt::class.java,
                BluetoothGattCharacteristic::class.java,
                ByteArray::class.java,
                Int::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val charac = param.args.getOrNull(1) as? BluetoothGattCharacteristic ?: return
                        val bytes = param.args.getOrNull(2) as? ByteArray ?: return
                        val status = param.args.getOrNull(3) as? Int ?: -1
                        logFrame("RD33(s=$status)", charac.uuid.toString(), bytes)
                    }
                }
            )
        }
    }

    private fun logFrame(dir: String, uuid: String, bytes: ByteArray) {
        val hex = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        val shortUuid = uuid.substringBefore('-')
        Log.i(TAG, "[$dir] $shortUuid (${bytes.size}B): $hex")
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        runCatching { XposedBridge.log("$TAG: $msg") }
    }
}
