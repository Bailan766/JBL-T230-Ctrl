package com.jblt230.ctrl.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jblt230.ctrl.logging.ProtocolLog
import com.jblt230.ctrl.protocol.GattConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class Incoming(val seq: Long, val frame: Frame?, val raw: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is Incoming && seq == other.seq && frame == other.frame && raw.contentEquals(other.raw)

    override fun hashCode(): Int = 31 * (31 * seq.hashCode() + (frame?.hashCode() ?: 0)) + raw.contentHashCode()
}

sealed interface LinkState {
    data object Idle : LinkState
    data class Connecting(val address: String) : LinkState
    data class Discovering(val address: String) : LinkState
    data class Ready(val address: String, val mtu: Int) : LinkState
    data class Failed(val reason: String) : LinkState
    data object Disconnected : LinkState
}

/** Owns the single BLE control link. Released as soon as the UI leaves the foreground. */
@SuppressLint("MissingPermission")
class GattTransport(
    private val context: Context,
    private val config: GattConfig,
    private val guard: CommandGuard,
    private val log: ProtocolLog
) {
    private val _linkState = MutableStateFlow<LinkState>(LinkState.Idle)
    val linkState: StateFlow<LinkState> = _linkState.asStateFlow()

    private val _incoming = MutableSharedFlow<Incoming>(extraBufferCapacity = 128)
    val incoming: SharedFlow<Incoming> = _incoming.asSharedFlow()

    private val seq = AtomicLong(0)
    fun currentSeq(): Long = seq.get()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var pendingWrite: CompletableDeferred<Boolean>? = null
    private var lastAddress: String = ""
    private var negotiatedMtu: Int = config.mtu

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "GATT connected, requesting MTU ${config.mtu}")
                _linkState.value = LinkState.Discovering(lastAddress)
                g.requestMtu(config.mtu)
            } else {
                Log.w(TAG, "GATT disconnected status=$status newState=$newState")
                _linkState.value = if (status == BluetoothGatt.GATT_SUCCESS) {
                    LinkState.Disconnected
                } else {
                    LinkState.Failed("连接失败 status=$status")
                }
                teardown()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU=$mtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS && mtu > 23) {
                negotiatedMtu = mtu
            }
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _linkState.value = LinkState.Failed("服务发现失败 status=$status")
                return
            }
            // Match official GenServiceDiscovery: switch connection interval to high priority
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

            val service = g.getService(UUID.fromString(config.service))
            if (service == null) {
                _linkState.value = LinkState.Failed("未找到 Harman 控制服务 65786365-...-0000")
                log.add("!", "", "control service not found on this device")
                return
            }
            notifyChar = service.getCharacteristic(UUID.fromString(config.notify))
            writeChar = service.getCharacteristic(UUID.fromString(config.write))
            val notify = notifyChar
            if (notify == null || writeChar == null) {
                _linkState.value = LinkState.Failed("控制特征值缺失")
                return
            }
            g.setCharacteristicNotification(notify, true)
            val descriptor = notify.getDescriptor(UUID.fromString(config.cccd))
            if (descriptor == null) {
                _linkState.value = LinkState.Failed("CCCD 缺失")
                return
            }
            val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(descriptor, value) == 0
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }
            if (!ok) _linkState.value = LinkState.Failed("订阅通知失败")
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "CCCD subscribed, stabilizing link for ${READY_DELAY_MS}ms")
                // Official BleDevice sleeps ~300ms after CCCD write before issuing first command
                mainHandler.postDelayed({
                    if (gatt === g) {
                        _linkState.value = LinkState.Ready(lastAddress, negotiatedMtu)
                    }
                }, READY_DELAY_MS)
            } else {
                _linkState.value = LinkState.Failed("CCCD 写入失败 status=$status")
            }
        }

        @Deprecated("Used on API < 33")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            characteristic.value?.let(::onFrameReceived)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) {
            onFrameReceived(value)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
            pendingWrite = null
        }
    }

    private fun onFrameReceived(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val frame = FrameCodec.decode(bytes)
        if (frame != null) {
            log.add("←", frame.hex, if (FrameCodec.isAck(frame)) "ACK" else "")
            _incoming.tryEmit(Incoming(seq.incrementAndGet(), frame, bytes))
            return
        }
        // Raw multi-part EQ continuation frames start with chunk index (00..0F), not 0xAA
        val first = bytes[0].toInt() and 0xFF
        if (first in 0x00..0x0F && bytes.size >= 2) {
            log.add("←", FrameCodec.hex(bytes), "分块数据 #$first")
            _incoming.tryEmit(Incoming(seq.incrementAndGet(), null, bytes))
        } else {
            log.add("←", FrameCodec.hex(bytes), "无法解析的帧")
        }
    }

    fun connect(device: BluetoothDevice): Boolean {
        if (gatt != null) return true
        lastAddress = device.address
        negotiatedMtu = config.mtu
        _linkState.value = LinkState.Connecting(lastAddress)
        log.add("i", "", "连接控制链路 ${log.redact(device.address)}")
        gatt = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Throwable) {
            Log.e(TAG, "connectGatt error: ${e.message}", e)
            null
        }
        return gatt != null
    }

    /** Sends one AA frame. Every frame passes through CommandGuard first. */
    suspend fun writeFrame(frame: ByteArray): Boolean {
        guard.assertAllowed(frame)
        log.add("→", FrameCodec.hex(frame), "")
        return writeRawInternal(frame)
    }

    /** Sends one continuation chunk of a guard-approved 0x41 EQ transfer. */
    suspend fun writeEqChunk(chunk: ByteArray, expectedSeqIndex: Int): Boolean {
        guard.assertAllowedEqChunk(chunk, expectedSeqIndex)
        log.add("→", FrameCodec.hex(chunk), "EQ 分块 #$expectedSeqIndex")
        return writeRawInternal(chunk)
    }

    private suspend fun writeRawInternal(bytes: ByteArray): Boolean {
        val g = gatt ?: return false
        val ch = writeChar ?: return false
        if (_linkState.value !is LinkState.Ready) return false

        val deferred = CompletableDeferred<Boolean>()
        pendingWrite = deferred
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
        } else {
            @Suppress("DEPRECATION")
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            ch.value = bytes
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
        if (!ok) {
            pendingWrite = null
            return false
        }
        return withTimeoutOrNull(WRITE_TIMEOUT_MS) { deferred.await() } ?: false
    }

    /** Releases the single control link so the official JBL app can reconnect. */
    fun release() {
        teardown()
        _linkState.value = LinkState.Disconnected
    }

    private fun teardown() {
        mainHandler.removeCallbacksAndMessages(null)
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (ignored: SecurityException) {
            Log.w(TAG, "SecurityException while closing GATT")
        }
        gatt = null
        writeChar = null
        notifyChar = null
        pendingWrite = null
    }

    companion object {
        private const val TAG = "JblT230Ctrl/Gatt"
        private const val WRITE_TIMEOUT_MS = 2000L
        private const val READY_DELAY_MS = 300L
    }
}
