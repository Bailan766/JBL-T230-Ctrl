package com.jblt230.ctrl.xposed

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
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
import com.jblt230.ctrl.bridge.BridgeContract
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LSPosed hooks running inside ColorOS `com.oplus.melody` (both main & `:fg` processes, plus
 * `com.heytap.accessory` / `com.oplus.wirelesssettings`) so `JBL TUNE230NC TWS` presents as an
 * official OPPO Enco headset:
 *
 * 1. Whitelist spoofing (`S8.a`, `S8.b`, `whitelist.a`, `V`, `l7.a`): maps JBL MAC/name to
 *    Melody's bundled OPPO Enco `WhitelistConfigDTO` (3D animations, capsule URIs, ANC/EQ metadata).
 * 2. Process-split isolation: `:fg` only hooks whitelist lookups and state mirrors while the
 *    main `com.oplus.melody` process manages SPP short-circuit, `EarphoneRepository` (`U.g1`
 *    fallback + `T()` persistence + `A8.a.h().m()` animation prefetch), `Q6.m.n` (`onForwardConnectedPopup`),
 *    and `Q6.m.m` (native欢律 Fluid Cloud capsule).
 * 3. Reliable popup launch: uses `route_from = "onForwardConnectedPopup"` (never `"onNewDeviceDiscovery"`
 *    which auto-dismisses when Fast-Discovery list is empty), waits 280ms after EventBus init &
 *    DTO injection, verifies `Q6.m.f` state mutation, and falls back to direct `DiscoveryDialogActivity`
 *    launch on forced user triggers.
 * 4. Bidirectional control (`BluetoothService$a.handleMessage`): relays ANC / EQ / FindMyBuds taps
 *    from ColorOS native UI back to `com.jblt230.ctrl/.bridge.JblBridgeReceiver`.
 */
object MelodySpoofHooks {

    private const val TAG = "JblColorOsHook"
    private const val POPUP_COOLDOWN_MS = 12_000L
    private const val CAPSULE_COOLDOWN_MS = 8_000L

    private const val EVENT_BATTERY_MSG = 1048588
    private const val EVENT_BATTERY = 1048591
    private const val EVENT_CONNECT_STATE = 1048592
    private const val EVENT_HEADSET_STATE = 1048594
    private const val EVENT_A2DP_STATE = 1048595
    private const val EVENT_STATUS = 1048598
    private const val EVENT_NOISE_MODE = 1048616
    private const val EVENT_EQ_MODE = 1048617

    private val mainHandler = Handler(Looper.getMainLooper())
    private val receiverRegistered = AtomicBoolean(false)
    private val lastPopupMsByMac = ConcurrentHashMap<String, Long>()
    private val lastCapsuleMsByMac = ConcurrentHashMap<String, Long>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var appClassLoader: ClassLoader? = null

    @Volatile
    private var currentProcessName: String = ""

    @Volatile
    private var isMelodyMainProcess: Boolean = false

    @Volatile
    private var isMelodyFgProcess: Boolean = false

    private data class SpoofState(
        val enabled: Boolean = true,
        val targetMac: String? = null,
        val realName: String = "JBL TUNE230NC TWS",
        val spoofModel: String = "OPPO Enco X2",
        val spoofProductIdHex: String = "063C10",
        val spoofColor: Int = 0,
        val spoofType: String = "T1",
        val leftBattery: Int = -1,
        val rightBattery: Int = -1,
        val caseBattery: Int = -1,
        val leftCharging: Boolean = false,
        val rightCharging: Boolean = false,
        val caseCharging: Boolean = false,
        val noiseIndex: Int = 2,
        val eqType: Int = 0,
        val boxOpen: Boolean = true
    )

    @Volatile
    private var state = SpoofState()

    fun install(
        classLoader: ClassLoader,
        packageName: String,
        processName: String = packageName
    ) {
        appClassLoader = classLoader
        currentProcessName = processName
        isMelodyMainProcess =
            packageName == BridgeContract.PKG_MELODY && processName == BridgeContract.PKG_MELODY
        isMelodyFgProcess =
            packageName == BridgeContract.PKG_MELODY && processName != BridgeContract.PKG_MELODY

        log("Installing MelodySpoofHooks in $packageName (proc=$processName, main=$isMelodyMainProcess, fg=$isMelodyFgProcess)")

        hookApplicationCreate(classLoader, packageName)
        hookWhitelistLookups(classLoader)
        if (isMelodyMainProcess) {
            hookBroadcastReceiver(classLoader)
            hookSppConnect(classLoader)
            hookBluetoothServiceControl(classLoader)
            hookEarphoneRepositoryFallback(classLoader)
        }
    }

    private fun hookApplicationCreate(classLoader: ClassLoader, packageName: String) {
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
                        if (packageName == BridgeContract.PKG_MELODY) {
                            registerSyncReceiverIfNeeded(app.applicationContext)
                            if (isMelodyMainProcess) {
                                sendHookAck(
                                    app.applicationContext,
                                    "boot_ready",
                                    "欢律主进程 Hook 已就绪"
                                )
                            }
                        }
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
                    if (intent.action != BridgeContract.ACTION_MELODY_SYNC) return
                    handleMelodySync(ctx, intent)
                }
            }
            val filter = IntentFilter(BridgeContract.ACTION_MELODY_SYNC)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            log("Registered ACTION_MELODY_SYNC receiver in ${context.packageName} (proc=$currentProcessName)")
        }.onFailure {
            receiverRegistered.set(false)
            log("registerSyncReceiver failed: ${it.message}")
        }
    }

    private fun handleMelodySync(context: Context, intent: Intent) {
        val mac = intent.getStringExtra(BridgeContract.EXTRA_MAC)?.takeIf { it.isNotBlank() }?.uppercase()
        val prev = state
        val updated = SpoofState(
            enabled = intent.getBooleanExtra(BridgeContract.EXTRA_MELODY_SPOOF_ENABLED, prev.enabled),
            targetMac = mac ?: prev.targetMac,
            realName = intent.getStringExtra(BridgeContract.EXTRA_DEVICE_NAME) ?: prev.realName,
            spoofModel = intent.getStringExtra(BridgeContract.EXTRA_SPOOF_MODEL) ?: prev.spoofModel,
            spoofProductIdHex = intent.getStringExtra(BridgeContract.EXTRA_SPOOF_PID) ?: prev.spoofProductIdHex,
            spoofColor = intent.getIntExtra(BridgeContract.EXTRA_SPOOF_COLOR, prev.spoofColor),
            spoofType = intent.getStringExtra(BridgeContract.EXTRA_SPOOF_TYPE) ?: prev.spoofType,
            leftBattery = intent.getIntExtra(BridgeContract.EXTRA_LEFT_BATTERY, prev.leftBattery),
            rightBattery = intent.getIntExtra(BridgeContract.EXTRA_RIGHT_BATTERY, prev.rightBattery),
            caseBattery = intent.getIntExtra(BridgeContract.EXTRA_CASE_BATTERY, prev.caseBattery),
            leftCharging = intent.getBooleanExtra(BridgeContract.EXTRA_LEFT_CHARGING, prev.leftCharging),
            rightCharging = intent.getBooleanExtra(BridgeContract.EXTRA_RIGHT_CHARGING, prev.rightCharging),
            caseCharging = intent.getBooleanExtra(BridgeContract.EXTRA_CASE_CHARGING, prev.caseCharging),
            noiseIndex = intent.getIntExtra(BridgeContract.EXTRA_NOISE_INDEX, prev.noiseIndex),
            eqType = intent.getIntExtra(BridgeContract.EXTRA_EQ_TYPE, prev.eqType)
        )
        state = updated

        if (isMelodyFgProcess || !isMelodyMainProcess) {
            return
        }
        if (!updated.enabled) {
            sendHookAck(context, "disabled", "欢律伪装已关闭")
            return
        }

        val activeMac = updated.targetMac
        if (activeMac.isNullOrBlank()) {
            sendHookAck(context, "handshake_ok", "欢律已握手（等待耳机 MAC）")
            return
        }

        val loader = appClassLoader ?: context.classLoader
        injectIntoMelodyState(loader, activeMac, updated)

        val triggerPopup = intent.getBooleanExtra(BridgeContract.EXTRA_TRIGGER_MELODY_POPUP, false)
        val triggerCapsule = intent.getBooleanExtra(BridgeContract.EXTRA_TRIGGER_MELODY_CAPSULE, false)
        when {
            triggerPopup -> {
                mainHandler.postDelayed({
                    triggerNativePopup(loader, context, activeMac, updated, force = true)
                }, 280L)
            }
            triggerCapsule -> {
                mainHandler.postDelayed({
                    triggerNativeCapsule(loader, context, activeMac, updated, force = true)
                }, 220L)
            }
            else -> {
                sendHookAck(
                    context,
                    "synced",
                    "已同步 ${updated.spoofModel} (${updated.spoofProductIdHex}) 电量 L=${updated.leftBattery}% R=${updated.rightBattery}% 盒=${updated.caseBattery}%"
                )
            }
        }
    }

    private fun hookWhitelistLookups(classLoader: ClassLoader) {
        val concreteRepoClasses = listOf(
            "S8.b",
            "com.oplus.melody.model.repository.whitelist.a"
        )
        for (clsName in concreteRepoClasses) {
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
                val pid = param.args.getOrNull(0) as? String
                val name = param.args.getOrNull(1) as? String
                if (BridgeContract.isTargetJblName(name) ||
                    (!pid.isNullOrBlank() && pid.equals("000000", ignoreCase = true) && isTargetDevice(null, name))
                ) {
                    param.result = resolveSpoofWhitelistDto(classLoader)
                }
            }
        }

        findClassOrNull("S8.a", classLoader)?.let { abstractRepoCls ->
            hookMethodSafe(abstractRepoCls, "b", Int::class.javaPrimitiveType!!, String::class.java) { param ->
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

    private fun hookEarphoneRepositoryFallback(classLoader: ClassLoader) {
        val repoImplCls = findClassOrNull("com.oplus.melody.model.repository.earphone.U", classLoader)
            ?: return
        hookMethodSafe(repoImplCls, "g1", String::class.java) { param ->
            if (!state.enabled || param.result != null) return@hookMethodSafe
            val mac = param.args.firstOrNull() as? String ?: return@hookMethodSafe
            if (!isTargetMac(mac)) return@hookMethodSafe
            val dto = synthesizeEarphoneDto(classLoader, mac.uppercase(), state)
            if (dto != null) {
                param.result = dto
            }
        }
    }

    private fun synthesizeEarphoneDto(
        classLoader: ClassLoader,
        mac: String,
        current: SpoofState
    ): Any? = runCatching {
        val entityCls = findClassOrNull("com.oplus.melody.model.db.C", classLoader) ?: return@runCatching null
        val statusDoCls = findClassOrNull(
            "com.oplus.melody.model.repository.earphone.EarphoneStatusDO",
            classLoader
        ) ?: return@runCatching null
        val batItemCls = findClassOrNull(
            "com.oplus.melody.model.repository.earphone.EarphoneStatusDO\$a",
            classLoader
        )
        val earphoneDtoCls = findClassOrNull(
            "com.oplus.melody.model.repository.earphone.EarphoneDTO",
            classLoader
        ) ?: return@runCatching null

        val dto = resolveSpoofWhitelistDto(classLoader)
        val pidHex = (dto?.let {
            runCatching { XposedHelpers.callMethod(it, "getId") as? String }.getOrNull()
        } ?: current.spoofProductIdHex).uppercase()
        val modelName = dto?.let {
            runCatching { XposedHelpers.callMethod(it, "getName") as? String }.getOrNull()
        } ?: current.spoofModel
        val color = dto?.let {
            runCatching { XposedHelpers.callMethod(it, "getDefaultColor") as? Int }.getOrNull()
        } ?: current.spoofColor

        val entity = XposedHelpers.newInstance(entityCls)
        XposedHelpers.callMethod(entity, "setMacAddress", mac)
        XposedHelpers.callMethod(entity, "setName", modelName)
        XposedHelpers.callMethod(entity, "setProductId", pidHex)
        XposedHelpers.callMethod(entity, "setColorId", color)
        XposedHelpers.callMethod(entity, "setReconnectPopupSwitch", 1)

        val statusDo = XposedHelpers.newInstance(statusDoCls)
        XposedHelpers.callMethod(statusDo, "setConnectionState", 2)
        XposedHelpers.callMethod(statusDo, "setAclConnectionState", 2)
        XposedHelpers.callMethod(statusDo, "setA2dpConnectionState", 2)
        XposedHelpers.callMethod(statusDo, "setHeadsetConnectionState", 2)
        XposedHelpers.callMethod(statusDo, "setDeviceBonded", true)
        XposedHelpers.callMethod(statusDo, "setNoiseReductionModeIndex", current.noiseIndex)
        XposedHelpers.callMethod(statusDo, "setEqType", current.eqType)

        if (batItemCls != null) {
            fun makeBat(lvl: Int, chg: Boolean): Any? {
                if (lvl !in 0..100) return null
                return XposedHelpers.newInstance(batItemCls).also {
                    XposedHelpers.callMethod(it, "setBattery", lvl)
                    XposedHelpers.callMethod(it, "setCharging", chg)
                }
            }
            makeBat(current.leftBattery, current.leftCharging)?.let {
                XposedHelpers.callMethod(statusDo, "setLeftBatteryStatus", it)
                XposedHelpers.callMethod(statusDo, "setHeadsetLeftBatteryStatus", it)
            }
            makeBat(current.rightBattery, current.rightCharging)?.let {
                XposedHelpers.callMethod(statusDo, "setRightBatteryStatus", it)
                XposedHelpers.callMethod(statusDo, "setHeadsetRightBatteryStatus", it)
            }
            makeBat(current.caseBattery, current.caseCharging)?.let {
                XposedHelpers.callMethod(statusDo, "setBoxBatteryStatus", it)
                XposedHelpers.callMethod(statusDo, "setHeadsetBoxBatteryStatus", it)
            }
        }

        XposedHelpers.callStaticMethod(earphoneDtoCls, "createFrom", entity, statusDo)
    }.onFailure { log("synthesizeEarphoneDto failed: ${it.message}") }.getOrNull()

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

            val action = intent.action ?: return@hookMethodSafe
            if (action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                lastPopupMsByMac.remove(mac)
                lastCapsuleMsByMac.remove(mac)
                return@hookMethodSafe
            }

            learnTargetMac(mac)
            val profileState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
            val connectedEvent = action == BluetoothDevice.ACTION_ACL_CONNECTED ||
                ((action == "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED" ||
                    action == "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED") &&
                    profileState == BluetoothProfile.STATE_CONNECTED)

            if (connectedEvent) {
                log("Detected JBL BT connect in Melody: $action ($mac)")
                sendHookAck(context, "bt_connected", "欢律已捕获 JBL 蓝牙连接 ($mac)")
                sendRemoteCommand(context, mac, BridgeContract.CMD_CONNECT_AND_SYNC)
                val current = state.copy(targetMac = mac)
                injectIntoMelodyState(classLoader, mac, current)
                // Trigger 3D popup first; triggering capsule first causes Q6.m.n's g(mac) guard to suppress the popup.
                mainHandler.postDelayed({
                    triggerNativePopup(classLoader, context, mac, current, force = false)
                }, 280L)
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
                        ensureEventBusSubscribersInitialized(classLoader)
                        applySnapshotToDeviceInfo(classLoader, info, current)
                        emitMelodyEvents(classLoader, info)
                        appContext?.let {
                            sendHookAck(it, "spp_shortcircuit", "已短接欢律 SPP 通道 ($mac)")
                            sendRemoteCommand(it, mac, BridgeContract.CMD_CONNECT_AND_SYNC)
                        }
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

    private fun ensureEventBusSubscribersInitialized(classLoader: ClassLoader) {
        runCatching {
            findClassOrNull("m8.a\$a", classLoader)?.let { btCompanion ->
                XposedHelpers.callStaticMethod(btCompanion, "a")
            }
        }
        runCatching {
            findClassOrNull("com.oplus.melody.model.repository.earphone.c", classLoader)?.let { earRepoCls ->
                XposedHelpers.callStaticMethod(earRepoCls, "G")
            }
        }
    }

    private fun injectIntoMelodyState(classLoader: ClassLoader, mac: String, current: SpoofState) {
        mainHandler.post {
            runCatching {
                ensureEventBusSubscribersInitialized(classLoader)
                val dto = resolveSpoofWhitelistDto(classLoader)
                val pidHex = (dto?.let {
                    runCatching { XposedHelpers.callMethod(it, "getId") as? String }.getOrNull()
                } ?: current.spoofProductIdHex).uppercase()
                val title = dto?.let {
                    runCatching { XposedHelpers.callMethod(it, "getName") as? String }.getOrNull()
                } ?: current.spoofModel
                val color = dto?.let {
                    runCatching { XposedHelpers.callMethod(it, "getDefaultColor") as? Int }.getOrNull()
                } ?: current.spoofColor

                runCatching {
                    findClassOrNull("com.oplus.melody.model.repository.earphone.c", classLoader)?.let { earRepoCls ->
                        val repo = XposedHelpers.callStaticMethod(earRepoCls, "G")
                        if (repo != null) {
                            XposedHelpers.callMethod(repo, "T", color, mac, title, pidHex)
                        }
                    }
                }

                runCatching {
                    findClassOrNull("A8.a", classLoader)?.let { dlCls ->
                        val dl = XposedHelpers.callStaticMethod(dlCls, "h")
                        if (dl != null) {
                            XposedHelpers.callMethod(dl, "m", color, pidHex)
                        }
                    }
                }

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

    private fun buildBatteryInfoList(classLoader: ClassLoader, current: SpoofState): ArrayList<Any> {
        val list = ArrayList<Any>(3)
        val batteryCls = findClassOrNull("com.oplus.melody.btsdk.api.data.BatteryInfo", classLoader)
            ?: return list
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
        return list
    }

    private fun applySnapshotToDeviceInfo(classLoader: ClassLoader, info: Any, current: SpoofState) {
        val dto = resolveSpoofWhitelistDto(classLoader)
        val pid = resolveProductIdInt(classLoader, current)
        val color = dto?.let {
            runCatching { XposedHelpers.callMethod(it, "getDefaultColor") as? Int }.getOrNull()
        } ?: current.spoofColor
        val type = dto?.let {
            runCatching { XposedHelpers.callMethod(it, "getType") as? String }.getOrNull()
        } ?: current.spoofType
        val modelName = dto?.let {
            runCatching { XposedHelpers.callMethod(it, "getName") as? String }.getOrNull()
        } ?: current.spoofModel

        runCatching {
            XposedHelpers.callMethod(info, "setProductId", pid)
            XposedHelpers.callMethod(info, "setColorId", color)
            XposedHelpers.callMethod(info, "setType", type)
            XposedHelpers.callMethod(info, "setIsSupportSpp", true)
            XposedHelpers.callMethod(info, "setDeviceBondState", 12)
            XposedHelpers.callMethod(info, "setDeviceName", modelName)
            XposedHelpers.callMethod(info, "setDeviceConnectState", 2)
            XposedHelpers.callMethod(info, "setDeviceAclConnectState", 2)
            XposedHelpers.callMethod(info, "setDeviceA2dpConnectState", 2)
            XposedHelpers.callMethod(info, "setDeviceHeadsetConnectState", 2)
        }

        val batteryList = buildBatteryInfoList(classLoader, current)
        if (batteryList.isNotEmpty()) {
            runCatching {
                XposedHelpers.callMethod(info, "setBatteryInfo", batteryList)
                XposedHelpers.callMethod(info, "setBatteryInfoMillisNow")
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
            EVENT_HEADSET_STATE,
            EVENT_A2DP_STATE,
            EVENT_BATTERY_MSG,
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

    private fun triggerNativeCapsule(
        classLoader: ClassLoader,
        context: Context,
        mac: String,
        current: SpoofState,
        force: Boolean
    ) {
        val now = System.currentTimeMillis()
        val last = lastCapsuleMsByMac[mac] ?: 0L
        if (!force && now - last < CAPSULE_COOLDOWN_MS) return
        lastCapsuleMsByMac[mac] = now

        mainHandler.post {
            runCatching {
                val discCls = findClassOrNull("com.oplus.melody.model.scan.a", classLoader)
                    ?: error("DiscoveryActionManager not found")
                val manager = XposedHelpers.callStaticMethod(discCls, "a")
                    ?: error("DiscoveryActionManager instance null")
                val dto = resolveSpoofWhitelistDto(classLoader)
                val pidHex = (dto?.let {
                    runCatching { XposedHelpers.callMethod(it, "getId") as? String }.getOrNull()
                } ?: current.spoofProductIdHex).uppercase()
                val color = dto?.let {
                    runCatching { XposedHelpers.callMethod(it, "getDefaultColor") as? Int }.getOrNull()
                } ?: current.spoofColor
                val title = dto?.let {
                    runCatching { XposedHelpers.callMethod(it, "getName") as? String }.getOrNull()
                } ?: current.spoofModel
                val batList = buildBatteryInfoList(classLoader, current)

                log("Triggering Melody native capsule Q6.m.m for $mac ($title / pid=$pidHex, bat=${batList.size})")
                XposedHelpers.callMethod(
                    manager,
                    "m",
                    mac,
                    pidHex,
                    color,
                    title,
                    "已连接",
                    batList
                )
                sendHookAck(context, "capsule_launched", "欢律原生胶囊已触发 ($title / $pidHex)")
            }.onFailure {
                val msg = it.message ?: it.javaClass.simpleName
                log("triggerNativeCapsule failed: $msg")
                sendHookAck(context, "capsule_error", "欢律胶囊触发失败：$msg")
            }
        }
    }

    private fun triggerNativePopup(
        classLoader: ClassLoader,
        context: Context,
        mac: String,
        current: SpoofState,
        force: Boolean
    ) {
        val now = System.currentTimeMillis()
        val last = lastPopupMsByMac[mac] ?: 0L
        if (!force && now - last < POPUP_COOLDOWN_MS) return
        lastPopupMsByMac[mac] = now

        mainHandler.post {
            val dto = resolveSpoofWhitelistDto(classLoader)
            val pidHex = (dto?.let {
                runCatching { XposedHelpers.callMethod(it, "getId") as? String }.getOrNull()
            } ?: current.spoofProductIdHex).uppercase()
            val color = dto?.let {
                runCatching { XposedHelpers.callMethod(it, "getDefaultColor") as? Int }.getOrNull()
            } ?: current.spoofColor
            val title = dto?.let {
                runCatching { XposedHelpers.callMethod(it, "getName") as? String }.getOrNull()
            } ?: current.spoofModel
            val productType = dto?.let {
                runCatching { XposedHelpers.callMethod(it, "getType") as? String }.getOrNull()
            } ?: current.spoofType

            var launchedViaManager = false
            runCatching {
                val discCls = findClassOrNull("com.oplus.melody.model.scan.a", classLoader)
                val manager = discCls?.let { XposedHelpers.callStaticMethod(it, "a") }
                if (manager != null) {
                    val before = runCatching { XposedHelpers.getObjectField(manager, "f") }.getOrNull()
                    log("Triggering Melody Discovery popup Q6.m.n for $mac as $title (pid=$pidHex, color=$color)")
                    XposedHelpers.callMethod(
                        manager,
                        "n",
                        mac,
                        "onForwardConnectedPopup",
                        pidHex,
                        0,
                        color,
                        false
                    )
                    val after = runCatching { XposedHelpers.getObjectField(manager, "f") }.getOrNull()
                    if (after != null && after !== before) {
                        launchedViaManager = true
                        sendHookAck(context, "popup_launched", "欢律 Q6.m.n 已触发 3D 弹窗 ($title / $pidHex)")
                    }
                }
            }.onFailure {
                log("Q6.m.n popup call failed: ${it.message}")
            }

            if (!launchedViaManager && force) {
                runCatching {
                    val intent = Intent().apply {
                        component = ComponentName(
                            BridgeContract.PKG_MELODY,
                            "com.oplus.melody.app.discovery.DiscoveryDialogActivity"
                        )
                        action = Intent.ACTION_VIEW
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("route_path", "/app/discovery")
                        putExtra("device_mac_info", mac)
                        putExtra("product_id", pidHex)
                        putExtra("device_name", title)
                        putExtra("product_type", productType)
                        putExtra("product_color", color.toString())
                        putExtra("route_from", "onForwardConnectedPopup")
                        putExtra("route_value", "0")
                        putExtra("route_value2", "false")
                    }
                    context.startActivity(intent)
                    sendHookAck(context, "popup_direct", "已直启欢律 3D 弹窗 ($title / $pidHex)")
                }.onFailure {
                    val msg = it.message ?: it.javaClass.simpleName
                    log("Direct DiscoveryDialogActivity launch failed: $msg")
                    sendHookAck(context, "popup_error", "直启欢律弹窗失败：$msg")
                }
            }
        }
    }

    private fun resolveSpoofWhitelistDto(classLoader: ClassLoader): Any? {
        findClassOrNull("S8.a", classLoader)?.let { repoCls ->
            val repo = runCatching { XposedHelpers.callStaticMethod(repoCls, "f") }.getOrNull()
            if (repo != null) {
                val direct = runCatching {
                    XposedHelpers.callMethod(repo, "c", state.spoofProductIdHex, null)
                }.getOrNull()
                if (direct != null) return direct
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
            if (id != null && id.equals(targetPid, ignoreCase = true)) return item
            if (name != null && name.equals(targetModel, ignoreCase = true)) return item
            if (fallbackEnco == null && name != null && name.contains("Enco X2", ignoreCase = true)) {
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

    private fun sendHookAck(
        context: Context,
        stage: String = "ack",
        status: String = "欢律进程已实时握手"
    ) {
        runCatching {
            val intent = Intent(BridgeContract.ACTION_MELODY_HOOK_ACK).apply {
                setClassName(
                    BridgeContract.PKG_SELF,
                    "${BridgeContract.PKG_SELF}.bridge.JblBridgeReceiver"
                )
                putExtra(BridgeContract.EXTRA_HOOK_PROCESS, currentProcessName.ifBlank { context.packageName })
                putExtra(BridgeContract.EXTRA_HOOK_STAGE, stage)
                putExtra(BridgeContract.EXTRA_HOOK_STATUS, status)
            }
            context.sendBroadcast(intent)
        }
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
