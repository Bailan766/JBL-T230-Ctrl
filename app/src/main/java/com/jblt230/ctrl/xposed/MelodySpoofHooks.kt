package com.jblt230.ctrl.xposed

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Parcelable
import android.util.Log
import com.jblt230.ctrl.ble.NoiseMode
import com.jblt230.ctrl.bridge.BridgeContract
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LSPosed hooks running inside ColorOS `com.oplus.melody` (and optionally `com.heytap.accessory` /
 * `com.oplus.wirelesssettings`) to present `JBL TUNE230NC TWS` as a whitelisted OPPO Enco headset:
 *
 * 1. Whitelist spoofing (`S8.a`, `S8.b`, `whitelist.a`, `V`, `p159l7.a`): maps the JBL MAC/name to
 *    a cached native OPPO `WhitelistConfigDTO` already bundled inside Melody.
 * 2. Native popup + Fluid Cloud trigger (`Q6.m.n` / `DiscoveryActionManager`) on ACL connect or
 *    manual test command from `com.jblt230.ctrl`.
 * 3. SPP short-circuit (`p343z7.b.b`) + `DeviceInfoManager` / `HeadsetCoreService.m0` injection so
 *    ColorOS displays real L/R/Case battery, ANC state, and EQ state without OPPO RFCOMM sockets.
 * 4. Bidirectional control (`BluetoothService.a.handleMessage`): forwards ColorOS ANC / EQ / FindMyBuds
 *    taps back to `com.jblt230.ctrl/.bridge.JblBridgeReceiver`.
 */
object MelodySpoofHooks {

    private const val TAG = "JblColorOsHook"
    private const val POPUP_COOLDOWN_MS = 12_000L

    private const val EVENT_BATTERY = 1048591
    private const val EVENT_CONNECT_STATE = 1048592
    private const val EVENT_STATUS = 1048598
    private const val EVENT_NOISE_MODE = 1048616
    private const val EVENT_EQ_MODE = 1048617

    private val mainHandler = Handler(Looper.getMainLooper())
    private val receiverRegistered = AtomicBoolean(false)
    private val lastPopupMsByMac = ConcurrentHashMap<String, Long>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var appClassLoader: ClassLoader? = null

    private data class SpoofState(
        val enabled: Boolean = true,
        val targetMac: String? = null,
        val realName: String = "JBL TUNE230NC TWS",
        val spoofModel: String = "OPPO Enco X2",
        val spoofProductIdHex: String = "060414",
        val spoofColor: Int = 1,
        val leftBattery: Int = -1,
        val rightBattery: Int = -1,
        val caseBattery: Int = -1,
        val leftCharging: Boolean = false,
        val rightCharging: Boolean = false,
        val caseCharging: Boolean = false,
        val noiseIndex: Int = 2,
        val eqType: Int = 0,
        val twsConnected: Boolean = false,
        val boxOpen: Boolean = true,
        val controlReady: Boolean = false
    )

    @Volatile
    private var state = SpoofState()

    fun install(classLoader: ClassLoader, packageName: String) {
        appClassLoader = classLoader
        log("Installing MelodySpoofHooks in $packageName")

        hookApplicationCreate(classLoader)
        hookWhitelistLookups(classLoader)
        if (packageName == BridgeContract.PKG_MELODY) {
            hookBroadcastReceiver(classLoader)
            hookSppConnect(classLoader)
            hookBluetoothServiceControl(classLoader)
        }
    }

    private fun hookApplicationCreate(classLoader: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? Application ?: return
                        appContext = app.applicationContext
                        if (appClassLoader == null) {
                            appClassLoader = app.classLoader
                        }
                        registerSyncReceiverIfNeeded(app.applicationContext)
                    }
                }
            )
        }.onFailure { log("hookApplicationCreate failed: ${it.message}") }
    }

    private fun registerSyncReceiverIfNeeded(context: Context) {
        if (!receiverRegistered.compareAndSet(false, true)) return
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != BridgeContract.ACTION_STATE_SYNC) return
                    handleStateSync(ctx, intent)
                }
            }
            val filter = IntentFilter(BridgeContract.ACTION_STATE_SYNC)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            log("Registered ACTION_STATE_SYNC receiver in ${context.packageName}")
        }.onFailure {
            receiverRegistered.set(false)
            log("registerSyncReceiver failed: ${it.message}")
        }
    }

    private fun handleStateSync(context: Context, intent: Intent) {
        val mac = intent.getStringExtra(BridgeContract.EXTRA_MAC)?.uppercase()
        val prev = state
        val updated = SpoofState(
            enabled = intent.getBooleanExtra(BridgeContract.EXTRA_ENABLED, prev.enabled),
            targetMac = mac ?: prev.targetMac,
            realName = intent.getStringExtra(BridgeContract.EXTRA_DEVICE_NAME) ?: prev.realName,
            spoofModel = intent.getStringExtra(BridgeContract.EXTRA_SPOOF_MODEL) ?: prev.spoofModel,
            spoofProductIdHex = intent.getStringExtra(BridgeContract.EXTRA_SPOOF_PRODUCT_ID) ?: prev.spoofProductIdHex,
            spoofColor = intent.getIntExtra(BridgeContract.EXTRA_SPOOF_COLOR, prev.spoofColor),
            leftBattery = intent.getIntExtra(BridgeContract.EXTRA_LEFT_BATTERY, prev.leftBattery),
            rightBattery = intent.getIntExtra(BridgeContract.EXTRA_RIGHT_BATTERY, prev.rightBattery),
            caseBattery = intent.getIntExtra(BridgeContract.EXTRA_CASE_BATTERY, prev.caseBattery),
            leftCharging = intent.getBooleanExtra(BridgeContract.EXTRA_LEFT_CHARGING, prev.leftCharging),
            rightCharging = intent.getBooleanExtra(BridgeContract.EXTRA_RIGHT_CHARGING, prev.rightCharging),
            caseCharging = intent.getBooleanExtra(BridgeContract.EXTRA_CASE_CHARGING, prev.caseCharging),
            noiseIndex = intent.getIntExtra(BridgeContract.EXTRA_NOISE_INDEX, prev.noiseIndex),
            eqType = intent.getIntExtra(BridgeContract.EXTRA_EQ_TYPE, prev.eqType),
            twsConnected = intent.getBooleanExtra(BridgeContract.EXTRA_TWS_CONNECTED, prev.twsConnected),
            boxOpen = intent.getBooleanExtra(BridgeContract.EXTRA_BOX_OPEN, prev.boxOpen),
            controlReady = intent.getBooleanExtra(BridgeContract.EXTRA_CONTROL_READY, prev.controlReady)
        )
        state = updated
        if (!updated.enabled) return

        val loader = appClassLoader ?: context.classLoader
        val activeMac = updated.targetMac
        if (!activeMac.isNullOrBlank()) {
            injectIntoMelodyState(loader, activeMac, updated)
            if (intent.getBooleanExtra(BridgeContract.EXTRA_TRIGGER_POPUP, false)) {
                triggerNativePopup(loader, activeMac, updated, force = true)
            }
        }
    }

    private fun hookWhitelistLookups(classLoader: ClassLoader) {
        val repoClasses = listOf(
            "S8.a",
            "S8.b",
            "com.oplus.melody.model.repository.whitelist.a"
        )
        for (clsName in repoClasses) {
            val cls = findClassOrNull(clsName, classLoader) ?: continue
            hookMethodSafe(cls, "a", String::class.java) { param ->
                if (!state.enabled || param.result != null) return@hookMethodSafe
                val mac = param.args.firstOrNull() as? String
                if (isTargetMac(mac)) {
                    param.result = resolveSpoofWhitelistDto(classLoader)
                }
            }
            hookMethodSafe(cls, "c", String::class.java, String::class.java) { param ->
                if (!state.enabled || param.result != null) return@hookMethodSafe
                val name = param.args.getOrNull(1) as? String
                if (BridgeContract.isTargetJblName(name)) {
                    param.result = resolveSpoofWhitelistDto(classLoader)
                }
            }
            hookMethodSafe(cls, "b", Int::class.javaPrimitiveType!!, String::class.java) { param ->
                if (!state.enabled || param.result != null) return@hookMethodSafe
                val name = param.args.getOrNull(1) as? String
                if (BridgeContract.isTargetJblName(name)) {
                    param.result = resolveSpoofWhitelistDto(classLoader)
                }
            }
        }

        findClassOrNull("l7.a", classLoader)?.let { supportCls ->
            hookMethodSafe(supportCls, "a", String::class.java) { param ->
                if (!state.enabled || param.result != null) return@hookMethodSafe
                val mac = param.args.firstOrNull() as? String
                if (isTargetMac(mac)) {
                    param.result = resolveSpoofWhitelistDto(classLoader)
                }
            }
        }

        findClassOrNull("com.oplus.melody.common.util.V", classLoader)?.let { vCls ->
            hookMethodSafe(vCls, "b", BluetoothDevice::class.java, Collection::class.java) { param ->
                if (!state.enabled || param.result != null) return@hookMethodSafe
                val dev = param.args.getOrNull(0) as? BluetoothDevice ?: return@hookMethodSafe
                val coll = param.args.getOrNull(1) as? Collection<*>
                if (isTargetDevice(dev.address, safeDeviceName(dev))) {
                    learnTargetMac(dev.address)
                    param.result = findDtoInCollection(coll) ?: resolveSpoofWhitelistDto(classLoader)
                }
            }
            hookMethodSafe(vCls, "a", Collection::class.java, String::class.java, String::class.java) { param ->
                if (!state.enabled || param.result != null) return@hookMethodSafe
                val coll = param.args.getOrNull(0) as? Collection<*>
                val name = param.args.getOrNull(2) as? String
                if (BridgeContract.isTargetJblName(name)) {
                    param.result = findDtoInCollection(coll) ?: resolveSpoofWhitelistDto(classLoader)
                }
            }
        }
    }

    private fun hookBroadcastReceiver(classLoader: ClassLoader) {
        val receiverCls = findClassOrNull(
            "com.oplus.melody.app.bluetooth.BluetoothBroadcastReceiver",
            classLoader
        ) ?: return

        hookMethodSafe(receiverCls, "b", Context::class.java, Intent::class.java) { param ->
            if (!state.enabled) return@hookMethodSafe
            val context = param.args.getOrNull(0) as? Context ?: return@hookMethodSafe
            val intent = param.args.getOrNull(1) as? Intent ?: return@hookMethodSafe
            @Suppress("DEPRECATION")
            val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            val mac = device?.address ?: return@hookMethodSafe
            val name = safeDeviceName(device)
            if (!isTargetDevice(mac, name)) return@hookMethodSafe

            learnTargetMac(mac)
            val action = intent.action ?: return@hookMethodSafe
            val profileState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
            val connectedEvent = action == BluetoothDevice.ACTION_ACL_CONNECTED ||
                ((action == "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED" ||
                    action == "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED") &&
                    profileState == BluetoothProfile.STATE_CONNECTED)

            if (connectedEvent) {
                log("Detected JBL BT connect in Melody: $action ($mac)")
                sendRemoteCommand(context, mac, BridgeContract.CMD_CONNECT_AND_SYNC)
                injectIntoMelodyState(classLoader, mac, state.copy(targetMac = mac))
                triggerNativePopup(classLoader, mac, state.copy(targetMac = mac), force = false)
            }
        }
    }

    private fun hookSppConnect(classLoader: ClassLoader) {
        val devConnCls = findClassOrNull("z7.b", classLoader) ?: return
        val deviceInfoCls = findClassOrNull("com.oplus.melody.btsdk.api.data.DeviceInfo", classLoader)
            ?: return

        try {
            XposedHelpers.findAndHookMethod(
                devConnCls,
                "b",
                deviceInfoCls,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!state.enabled) return
                        val info = param.args.firstOrNull() ?: return
                        val mac = runCatching {
                            XposedHelpers.callMethod(info, "getDeviceAddress") as? String
                        }.getOrNull() ?: return
                        val name = runCatching {
                            XposedHelpers.callMethod(info, "getDeviceName") as? String
                        }.getOrNull()
                        if (!isTargetDevice(mac, name)) return

                        learnTargetMac(mac)
                        log("Short-circuiting OPPO SPP handshake for JBL ($mac)")
                        param.result = null
                        val current = state.copy(targetMac = mac)
                        applySnapshotToDeviceInfo(classLoader, info, current)
                        emitMelodyEvents(classLoader, info)
                        appContext?.let { sendRemoteCommand(it, mac, BridgeContract.CMD_CONNECT_AND_SYNC) }
                    }
                }
            )
        } catch (t: Throwable) {
            log("hookSppConnect failed: ${t.message}")
        }
    }

    private fun hookBluetoothServiceControl(classLoader: ClassLoader) {
        val handlerCls = findClassOrNull(
            "com.oplus.melody.btsdk.manager.service.BluetoothService\$a",
            classLoader
        ) ?: return

        try {
            XposedHelpers.findAndHookMethod(
                handlerCls,
                "handleMessage",
                Message::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!state.enabled) return
                        val msg = param.args.firstOrNull() as? Message ?: return
                        val intent = msg.obj as? Intent ?: return
                        val mac = intent.getStringExtra("param_address") ?: return
                        if (!isTargetMac(mac)) return
                        val ctx = appContext ?: return

                        when (msg.what) {
                            1011 -> {
                                val inFind = intent.getBooleanExtra("param_in_find_mode", false)
                                val extra = Bundle().apply {
                                    putBoolean(BridgeContract.EXTRA_FIND_LEFT, inFind)
                                    putBoolean(BridgeContract.EXTRA_FIND_RIGHT, inFind)
                                }
                                sendRemoteCommand(ctx, mac, BridgeContract.CMD_FIND_BUDS, extra)
                                param.result = null
                            }

                            1013 -> {
                                @Suppress("DEPRECATION")
                                val nrInfo: Parcelable? = intent.getParcelableExtra("param_noise_reduction_info")
                                val value = nrInfo?.let {
                                    runCatching { XposedHelpers.callMethod(it, "getValue") as? Int }.getOrNull()
                                } ?: return
                                forwardNoiseMode(ctx, classLoader, mac, value)
                                param.result = null
                            }

                            1014 -> {
                                @Suppress("DEPRECATION")
                                val curInfo: Parcelable? = intent.getParcelableExtra("param_current_noise_mode_info")
                                val index = curInfo?.let {
                                    runCatching {
                                        XposedHelpers.callMethod(it, "getCurrentNoiseReductionModeIndex") as? Int
                                    }.getOrNull()
                                } ?: return
                                if (index >= 0) {
                                    forwardNoiseMode(ctx, classLoader, mac, index)
                                }
                                param.result = null
                            }

                            1015 -> {
                                @Suppress("DEPRECATION")
                                val eqInfo: Parcelable? = intent.getParcelableExtra("param_equalizer_mode_info")
                                val type = eqInfo?.let {
                                    runCatching {
                                        XposedHelpers.callMethod(it, "getEqualizerModeType") as? Int
                                    }.getOrNull()
                                } ?: return
                                val preset = BridgeContract.colorOsTypeToEqPreset(type)
                                state = state.copy(eqType = type)
                                val extra = Bundle().apply {
                                    putString(BridgeContract.EXTRA_EQ_PRESET, preset)
                                    putInt(BridgeContract.EXTRA_EQ_TYPE, type)
                                }
                                sendRemoteCommand(ctx, mac, BridgeContract.CMD_SET_EQ_PRESET, extra)
                                getOrCreateDeviceInfo(classLoader, mac, state)?.let {
                                    applySnapshotToDeviceInfo(classLoader, it, state)
                                    emitMelodyEvents(classLoader, it)
                                }
                                param.result = null
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            log("hookBluetoothServiceControl failed: ${t.message}")
        }
    }

    private fun forwardNoiseMode(ctx: Context, classLoader: ClassLoader, mac: String, index: Int) {
        val mode = BridgeContract.colorOsIndexToNoiseMode(index)
        state = state.copy(noiseIndex = index)
        val extra = Bundle().apply {
            putString(BridgeContract.EXTRA_NOISE_MODE, mode.name)
            putInt(BridgeContract.EXTRA_NOISE_INDEX, index)
        }
        sendRemoteCommand(ctx, mac, BridgeContract.CMD_SET_NOISE_MODE, extra)
        getOrCreateDeviceInfo(classLoader, mac, state)?.let {
            applySnapshotToDeviceInfo(classLoader, it, state)
            emitMelodyEvents(classLoader, it)
        }
    }

    private fun injectIntoMelodyState(classLoader: ClassLoader, mac: String, current: SpoofState) {
        mainHandler.post {
            runCatching {
                val info = getOrCreateDeviceInfo(classLoader, mac, current) ?: return@post
                applySnapshotToDeviceInfo(classLoader, info, current)
                emitMelodyEvents(classLoader, info)
            }.onFailure { log("injectIntoMelodyState failed: ${it.message}") }
        }
    }

    private fun getOrCreateDeviceInfo(
        classLoader: ClassLoader,
        mac: String,
        current: SpoofState
    ): Any? {
        val mgrCompanion = findClassOrNull(
            "com.oplus.melody.btsdk.api.manager.DeviceInfoManager\$a",
            classLoader
        ) ?: return null
        val manager = XposedHelpers.getStaticObjectField(mgrCompanion, "a") ?: return null
        var info = runCatching { XposedHelpers.callMethod(manager, "h", mac) }.getOrNull()
        val pid = resolveProductIdInt(classLoader, current)
        if (info == null) {
            val btDev = runCatching { BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(mac) }.getOrNull()
            val mgrCls = manager.javaClass
            info = runCatching {
                XposedHelpers.callStaticMethod(mgrCls, "f", pid, btDev, mac, current.spoofModel)
            }.getOrNull()
            if (info != null) {
                runCatching { XposedHelpers.callMethod(manager, "j", pid, mac) }
                @Suppress("UNCHECKED_CAST")
                val map = runCatching {
                    XposedHelpers.getObjectField(manager, "a") as? MutableMap<String, Any>
                }.getOrNull()
                map?.put(mac, info)
            }
        } else {
            runCatching { XposedHelpers.callMethod(manager, "b", pid, mac) }
        }
        return info
    }

    private fun applySnapshotToDeviceInfo(classLoader: ClassLoader, info: Any, current: SpoofState) {
        val pid = resolveProductIdInt(classLoader, current)
        runCatching {
            XposedHelpers.callMethod(info, "setProductId", pid)
            XposedHelpers.callMethod(info, "setDeviceName", current.spoofModel)
            XposedHelpers.callMethod(info, "setDeviceConnectState", 2)
            XposedHelpers.callMethod(info, "setDeviceAclConnectState", 2)
            XposedHelpers.callMethod(info, "setDeviceA2dpConnectState", 2)
            XposedHelpers.callMethod(info, "setDeviceHeadsetConnectState", 2)
        }

        val batteryCls = findClassOrNull("com.oplus.melody.btsdk.api.data.BatteryInfo", classLoader)
        if (batteryCls != null) {
            val list = ArrayList<Any>(3)
            if (current.leftBattery in 0..100) {
                list.add(
                    XposedHelpers.newInstance(
                        batteryCls,
                        1,
                        current.leftBattery,
                        if (current.leftCharging) 1 else 0
                    )
                )
            }
            if (current.rightBattery in 0..100) {
                list.add(
                    XposedHelpers.newInstance(
                        batteryCls,
                        2,
                        current.rightBattery,
                        if (current.rightCharging) 1 else 0
                    )
                )
            }
            if (current.caseBattery in 0..100) {
                list.add(
                    XposedHelpers.newInstance(
                        batteryCls,
                        3,
                        current.caseBattery,
                        if (current.caseCharging) 1 else 0
                    )
                )
            }
            if (list.isNotEmpty()) {
                runCatching {
                    XposedHelpers.callMethod(info, "setBatteryInfo", list)
                    XposedHelpers.callMethod(info, "setBatteryInfoMillisNow")
                }
            }
        }

        val statusCls = findClassOrNull("com.oplus.melody.btsdk.api.data.StatusInfo", classLoader)
        if (statusCls != null) {
            val statuses = ArrayList<Any>(2)
            for (side in 1..2) {
                val st = XposedHelpers.newInstance(statusCls)
                XposedHelpers.callMethod(st, "setDeviceType", side)
                XposedHelpers.callMethod(st, "setBoxCoverClosed", !current.boxOpen)
                XposedHelpers.callMethod(st, "setInBox", !current.boxOpen)
                XposedHelpers.callMethod(st, "setInEar", current.boxOpen)
                statuses.add(st)
            }
            runCatching { XposedHelpers.callMethod(info, "setStatusInfo", statuses) }
        }

        val noiseCls = findClassOrNull("com.oplus.melody.btsdk.api.data.CurrentNoiseModeInfo", classLoader)
        if (noiseCls != null) {
            runCatching {
                val curNoise = XposedHelpers.newInstance(noiseCls)
                XposedHelpers.callMethod(curNoise, "setType", 1)
                XposedHelpers.callMethod(curNoise, "setCurrentNoiseReductionModeValue", current.noiseIndex, true)
                XposedHelpers.callMethod(info, "setCurrentNoiseModeInfo", curNoise)
            }
        }

        val eqCls = findClassOrNull("com.oplus.melody.btsdk.api.data.EqualizerModeInfo", classLoader)
        if (eqCls != null) {
            runCatching {
                val eqInfo = XposedHelpers.newInstance(eqCls, current.eqType)
                XposedHelpers.callMethod(info, "setEqualizerModeInfo", eqInfo)
            }
        }
    }

    private fun emitMelodyEvents(classLoader: ClassLoader, deviceInfo: Any) {
        val coreCls = findClassOrNull(
            "com.oplus.melody.btsdk.multidevice.HeadsetCoreService",
            classLoader
        ) ?: return
        val recvCls = findClassOrNull(
            "com.oplus.melody.btsdk.api.data.BluetoothReceiveData",
            classLoader
        ) ?: return
        val events = intArrayOf(
            EVENT_CONNECT_STATE,
            EVENT_BATTERY,
            EVENT_STATUS,
            EVENT_NOISE_MODE,
            EVENT_EQ_MODE
        )
        for (code in events) {
            runCatching {
                val data = XposedHelpers.newInstance(recvCls, code, deviceInfo)
                XposedHelpers.callStaticMethod(coreCls, "m0", data)
            }
        }
    }

    private fun triggerNativePopup(
        classLoader: ClassLoader,
        mac: String,
        current: SpoofState,
        force: Boolean
    ) {
        val now = System.currentTimeMillis()
        val last = lastPopupMsByMac[mac] ?: 0L
        if (!force && now - last < POPUP_COOLDOWN_MS) return
        lastPopupMsByMac[mac] = now

        mainHandler.post {
            runCatching {
                val discCls = findClassOrNull("com.oplus.melody.model.scan.a", classLoader)
                    ?: return@post
                val manager = XposedHelpers.callStaticMethod(discCls, "a") ?: return@post
                val dto = resolveSpoofWhitelistDto(classLoader)
                val pidHex = (dto?.let {
                    runCatching { XposedHelpers.callMethod(it, "getId") as? String }.getOrNull()
                } ?: current.spoofProductIdHex).uppercase()
                val color = dto?.let {
                    runCatching { XposedHelpers.callMethod(it, "getDefaultColor") as? Int }.getOrNull()
                } ?: current.spoofColor

                log("Triggering Melody Discovery popup for $mac as ${current.spoofModel} (pid=$pidHex, color=$color)")
                XposedHelpers.callMethod(
                    manager,
                    "n",
                    mac,
                    "onNewDeviceDiscovery",
                    pidHex,
                    -48,
                    color,
                    false
                )
            }.onFailure { log("triggerNativePopup failed: ${it.message}") }
        }
    }

    private fun resolveSpoofWhitelistDto(classLoader: ClassLoader): Any? {
        findClassOrNull("S8.a", classLoader)?.let { repoCls ->
            val repo = runCatching { XposedHelpers.callStaticMethod(repoCls, "f") }.getOrNull()
            if (repo != null) {
                val list = runCatching { XposedHelpers.callMethod(repo, "g") as? Collection<*> }.getOrNull()
                findDtoInCollection(list)?.let { return it }
            }
        }
        findClassOrNull("l7.a\$a", classLoader)?.let { supportCls ->
            val inst = runCatching { XposedHelpers.getStaticObjectField(supportCls, "a") }.getOrNull()
            if (inst != null) {
                val list = runCatching { XposedHelpers.callMethod(inst, "b") as? Collection<*> }.getOrNull()
                findDtoInCollection(list)?.let { return it }
            }
        }
        return null
    }

    private fun findDtoInCollection(coll: Collection<*>?): Any? {
        if (coll.isNullOrEmpty()) return null
        val targetModel = state.spoofModel
        val targetPid = state.spoofProductIdHex
        var fallbackEnco: Any? = null
        for (item in coll) {
            if (item == null) continue
            val name = runCatching { XposedHelpers.callMethod(item, "getName") as? String }.getOrNull()
            val id = runCatching { XposedHelpers.callMethod(item, "getId") as? String }.getOrNull()
            if (name != null && name.equals(targetModel, ignoreCase = true)) return item
            if (id != null && id.equals(targetPid, ignoreCase = true)) return item
            if (fallbackEnco == null && name != null && name.contains("Enco", ignoreCase = true)) {
                fallbackEnco = item
            }
        }
        return fallbackEnco ?: coll.firstOrNull()
    }

    private fun resolveProductIdInt(classLoader: ClassLoader, current: SpoofState): Int {
        val dto = resolveSpoofWhitelistDto(classLoader)
        val pidHex = dto?.let {
            runCatching { XposedHelpers.callMethod(it, "getId") as? String }.getOrNull()
        }
        return BridgeContract.parseProductIdInt(pidHex ?: current.spoofProductIdHex)
    }

    private fun sendRemoteCommand(context: Context, mac: String, cmd: String, extras: Bundle? = null) {
        runCatching {
            val intent = Intent(BridgeContract.ACTION_REMOTE_COMMAND).apply {
                setClassName(
                    BridgeContract.PKG_SELF,
                    "${BridgeContract.PKG_SELF}.bridge.JblBridgeReceiver"
                )
                putExtra(BridgeContract.EXTRA_CMD, cmd)
                putExtra(BridgeContract.EXTRA_MAC, mac)
                if (extras != null) putExtras(extras)
            }
            context.sendBroadcast(intent)
        }.onFailure { log("sendRemoteCommand($cmd) failed: ${it.message}") }
    }

    private fun isTargetMac(mac: String?): Boolean {
        if (mac.isNullOrBlank()) return false
        val known = state.targetMac
        if (!known.isNullOrBlank()) return known.equals(mac, ignoreCase = true)
        return runCatching {
            val dev = BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(mac)
            val name = dev?.let { safeDeviceName(it) }
            if (BridgeContract.isTargetJblName(name)) {
                learnTargetMac(mac)
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    private fun isTargetDevice(mac: String?, name: String?): Boolean {
        if (BridgeContract.isTargetJblName(name)) return true
        val known = state.targetMac ?: return false
        return !mac.isNullOrBlank() && known.equals(mac, ignoreCase = true)
    }

    private fun learnTargetMac(mac: String?) {
        if (mac.isNullOrBlank()) return
        val upper = mac.uppercase()
        if (state.targetMac != upper) {
            state = state.copy(targetMac = upper)
            log("Learned JBL target MAC: $upper")
        }
    }

    @Suppress("MissingPermission")
    private fun safeDeviceName(dev: BluetoothDevice): String? =
        runCatching { dev.name }.getOrNull()

    private fun hookMethodSafe(
        cls: Class<*>,
        methodName: String,
        vararg parameterTypes: Class<*>,
        after: (XC_MethodHook.MethodHookParam) -> Unit
    ) {
        try {
            XposedHelpers.findAndHookMethod(
                cls,
                methodName,
                *parameterTypes,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        after(param)
                    }
                }
            )
        } catch (_: Throwable) {
            // ProGuard or OS variant may omit an overload; ignore missing overloads cleanly.
        }
    }

    private fun findClassOrNull(name: String, classLoader: ClassLoader): Class<*>? =
        runCatching { XposedHelpers.findClass(name, classLoader) }.getOrNull()

    private fun log(msg: String) {
        Log.i(TAG, msg)
        runCatching { XposedBridge.log("$TAG: $msg") }
    }
}
