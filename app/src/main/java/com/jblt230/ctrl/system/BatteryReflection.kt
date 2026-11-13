package com.jblt230.ctrl.system

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Build

/**
 * Best-effort system battery reading. `BluetoothDevice.getBatteryLevel()` is a
 * hidden API; on Android 16 a normal third-party app may be refused. When that
 * happens we return null and the UI says "电量由 BLE 协议读取" — never a fake value.
 */
object BatteryReflection {

    data class Result(val level: Int?, val reason: String)

    @SuppressLint("MissingPermission")
    fun read(device: BluetoothDevice): Result = try {
        val method = BluetoothDevice::class.java.getDeclaredMethod("getBatteryLevel")
        method.isAccessible = true
        val value = method.invoke(device) as? Int
        when {
            value == null -> Result(null, "反射返回空值")
            value < 0 -> Result(null, "系统返回未知电量（BATTERY_LEVEL_UNKNOWN）")
            value > 100 -> Result(null, "系统返回越界值 $value")
            else -> Result(value, "系统 hidden API（Android ${Build.VERSION.RELEASE}）")
        }
    } catch (e: NoSuchMethodException) {
        Result(null, "本系统无 getBatteryLevel()：${e.message ?: ""}")
    } catch (e: SecurityException) {
        Result(null, "权限被拒：${e.message ?: ""}")
    } catch (e: ReflectiveOperationException) {
        Result(null, "hidden API 被限制：${e.message ?: ""}")
    } catch (e: Throwable) {
        Result(null, "反射失败：${e.javaClass.simpleName}")
    }
}
