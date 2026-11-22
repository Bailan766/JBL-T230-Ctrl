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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import com.jblt230.ctrl.bridge.BridgeContract
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LSPosed hooks running inside ColorOS `com.oplus.melody` (main & `:fg`), `com.heytap.accessory`,
 * and `com.oplus.wirelesssettings` so `JBL TUNE230NC TWS` presents cleanly as a native OPPO headset:
 *
 * 1. Whitelist sanitization & settings export (`S8.a`, `S8.b`, `whitelist.a`, `WhitelistOutsideSnapshot`):
 *    - Exports JBL names in `ears_whitelist` and notifies `WirelessSettingHelper` so ColorOS Bluetooth
 *      Settings recognizes JBL natively.
 *    - Deep-clones the spoofed OPPO `WhitelistConfigDTO` and strips ~40 fake OPPO-only rows
 *      (wear detection, fit test, dual-device, golden sound, OTA, etc.), keeping ONLY real working
 *      controls (Battery, ANC/Transparency/TalkThru, EQ presets, Find My Buds).
 * 2. Live interactive capability & ACKs (`1048649`, `1048605`, `1048604`, `1048576`):
 *    - Emits capability ready (`1048649` without `265` wear-sensor block) & noise support bitmask.
 *    - Intercepts `BluetoothService$a.handleMessage` (`1011`, `1013`, `1014`, `1015`), relays commands
 *      to `JblBridgeReceiver`, emits immediate `1048576` (`SetCommandStateInfo` success) ACKs, and
 *      short-circuits dead SPP readbacks (`1016`, `1017`, `1022`..`1026`, `1039`, `1045`, `1065`).
 * 3. Incremental sync without event storms:
 *    - Full initialization runs once per MAC/model switch; subsequent updates push only changed
 *      battery/noise/EQ deltas for lag-free ColorOS UI updates.
 */
object MelodySpoofHooks {

    private const val TAG = "JblColorOsHook"
    private const val POPUP_COOLDOWN_MS = 12_000L
    private const val CAPSULE_COOLDOWN_MS = 8_000L
    private const val SYNC_REQUEST_COOLDOWN_MS = 5_000L
    private const val BONDED_CHECK_TTL_MS = 5_000L

    private const val EVENT_SET_CMD_STATE = 1048576
    private const val EVENT_BATTERY_MSG = 1048588
    private const val EVENT_BATTERY_HEADSET = 1048589
    private const val EVENT_BATTERY = 1048591
    private const val EVENT_CONNECT_STATE = 1048592
    private const val EVENT_HEADSET_STATE = 1048594
    private const val EVENT_A2DP_STATE = 1048595
    private const val EVENT_STATUS = 1048598
    private const val EVENT_VERSION = 1048599
    private const val EVENT_HEADSET_VERSION = 1048600
    private const val EVENT_SWITCH_NOISE_REDUCTION = 1048604
    private const val EVENT_SUPPORT_NOISE_REDUCTION = 1048605
    private const val EVENT_COLOR_ID = 1048614
    private const val EVENT_NOISE_MODE = 1048616
    private const val EVENT_EQ_MODE = 1048617
    private const val EVENT_CAPABILITY_READY = 1048649

    private const val RSP_FIND_MODE = 33792
    private const val RSP_NOISE_REDUCTION = 33796
    private const val RSP_EQ_MODE = 33798

    private val JBL_WHITELIST_NAMES = listOf(
        "JBL TUNE230NC TWS",
        "JBL TUNE230NC TWS-LE",
        "JBL Tune 230NC"
    )

    // Capability IDs for DetailMainViewModel / K (deliberately omits 265 = ear-wear check).
    private val JBL_CAPABILITIES = arrayListOf(1024, 1028, 1030)

    // Bitmask of supported noise protocol indices: 0 (OFF), 1 (ANC), 2 (TRANS), 8 (AMBIENT), 9 (TALKTHRU).
    private const val JBL_NOISE_SUPPORT_MASK = (1 shl 0) or (1 shl 1) or (1 shl 2) or (1 shl 8) or (1 shl 9)

    private val UNSUPPORTED_INT_FIELDS = listOf(
        "personalNoise", "multiDevicesConnect", "dolbyAtmos", "highAudio", "highToneQuality",
        "hearingEnhancement", "hearingEnhancementNew", "hearingOptimize", "adaptiveVolume",
        "adaptiveEar", "voiceWake", "speechPerception", "aiTranslate", "aiSummary",
        "aiSummaryToneSource", "gameMode", "whiteNoise", "zenMode", "spineHealth",
        "sleepDetection", "meetingAssistant", "clickTakePic", "clickTakePicNew",
        "headSetSoundRecord", "deviceLostRemind", "controlAutoVolumeSupport", "longPressVolume",
        "controlGuideSupport", "voiceCommand", "headMotion", "customDress", "wearDetection",
        "fitDetection", "swiftPair", "tapLevelSetting", "promptVolume", "heyMelodyDownload",
        "autoFirmwareUpdate", "functionGuide", "functionIntroductionGuide", "newFunctionGuide",
        "feedback", "collectLogs", "diagnostic", "smartCall", "vocalEnhance", "opsReduction",
        "customEqualizer", "bassEngineSupport", "rlmMoreFunction", "related", "autoSwitchLink",
        "earScan", "longPowerMode", "cleanGuide", "carouselDress", "honorWall", "immersiveRecord",
        "incomingCallControl", "aiClearCall", "spatialVip", "spatialEarphoneFirst",
        "spatialHiResMutex", "spatialDescriptionType", "spyTap", "supportPinch",
        "strongNoiseReductionRealTime", "inBoxStatus", "openBoxPairing", "holdInBoxPairing",
        "wearingVideoTutorial"
    )

    private val UNSUPPORTED_OBJECT_FIELDS = listOf(
        "personalNoiseCompat", "multiConnectFunctions", "spatialTypes", "aiTranslateCompat",
        "gameModeList", "gameSoundList", "gameEqualizer", "gameEqPkgList", "gameSoundMutexes",
        "control", "callControl", "musicControl", "controlList", "dressFilterList",
        "equalizerModeByColor", "equalizerModeByVersion", "equalizerModeCompat",
        "introductionGuideList", "promptVolumeRange", "dialogTags", "voiceCommandItems",
        "customEqFrequency", "mutexFunctions", "leFilterFunctions"
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val receiverRegistered = AtomicBoolean(false)
    private val inRawWhitelistLookup = ThreadLocal.withInitial { false }
    private val sanitizedDtoCache = ConcurrentHashMap<String, Any>()
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

    @Volatile
    private var isWirelessSettingsProcess: Boolean = false

    @Volatile
    private var lastFullInjectKey: String = ""

    @Volatile
    private var lastWhitelistNotifiedKey: String = ""

    @Volatile
    private var lastEmittedState: SpoofState? = null

    @Volatile
    private var lastBondedCheckMs: Long = 0L

    @Volatile
    private var lastBondedJblFound: Boolean = false

    @Volatile
    private var lastSyncRequestMs: Long = 0L

    private data class SpoofState(
        val enabled: Boolean = true,
        val targetMac: String? = null,
        val realName: String = "JBL Tune 230NC",
        val fwVersion: String = "",
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
        val noiseIndex: Int = 0,
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
        isWirelessSettingsProcess = packageName == BridgeContract.PKG_WIRELESS_SETTINGS

        log("Installing MelodySpoofHooks in $packageName (proc=$processName, main=$isMelodyMainProcess, fg=$isMelodyFgProcess, ws=$isWirelessSettingsProcess)")

        hookApplicationCreate(classLoader, packageName)
        if (isWirelessSettingsProcess) {
            hookWirelessSettingsWhitelist(classLoader)
            return
        }
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
                        if (packageName == BridgeContract.PKG_MELODY || packageName == BridgeContract.PKG_WIRELESS_SETTINGS) {
                            registerSyncReceiverIfNeeded(app.applicationContext)
                        }
                        if (packageName == BridgeContract.PKG_MELODY && isMelodyMainProcess) {
                            notifySystemWhitelistChanged(app.applicationContext, state.spoofProductIdHex)
                            sendHookAck(
                                app.applicationContext,
                                "boot_ready",
                                "欢律主进程 Hook 已就绪"
                            )
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
            realName = BridgeContract.cleanDisplayName(
                intent.getStringExtra(BridgeContract.EXTRA_DEVICE_NAME) ?: prev.realName
            ),
            fwVersion = intent.getStringExtra(BridgeContract.EXTRA_FW_VERSION) ?: prev.fwVersion,
            spoofModel = intent.getStringExtra(BridgeContract.EXTRA_SPOOF_MODEL) ?: prev.spoofModel,
            spoofProductIdHex = (intent.getStringExtra(BridgeContract.EXTRA_SPOOF_PID)
                ?: prev.spoofProductIdHex).uppercase(),
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
        if (prev.spoofProductIdHex != updated.spoofProductIdHex || prev.enabled != updated.enabled) {
            sanitizedDtoCache.clear()
            val loader = appClassLoader ?: context.classLoader
            if (isMelodyFgProcess) {
                clearMelodyFgWhitelistCache(loader)
            }
            if (isWirelessSettingsProcess) {
                invalidateWirelessSettingsCache(loader)
            }
        }

        if (isMelodyFgProcess || !isMelodyMainProcess) {
            return
        }
        if (!updated.enabled) {
            lastFullInjectKey = ""
            lastEmittedState = null
            sendHookAck(context, "disabled", "欢律伪装已关闭")
            return
        }

        val activeMac = updated.targetMac
        if (activeMac.isNullOrBlank()) {
            sendHookAck(context, "handshake_ok", "欢律已握手（等待耳机 MAC）")
            return
        }

        val loader = appClassLoader ?: context.classLoader
        notifySystemWhitelistChanged(context, updated.spoofProductIdHex)
        injectIntoMelodyState(loader, activeMac, updated)

        val triggerPopup = intent.getBooleanExtra(BridgeContract.EXTRA_TRIGGER_MELODY_POPUP, false)
        val triggerCapsule = intent.getBooleanExtra(BridgeContract.EXTRA_TRIGGER_MELODY_CAPSULE, false)
        when {
            triggerPopup -> {
                mainHandler.postDelayed({
                    triggerNativePopup(loader, context, activeMac, updated, force = true)
                }, 220L)
            }
            triggerCapsule -> {
                mainHandler.postDelayed({
                    triggerNativeCapsule(loader, context, activeMac, updated, force = true)
                }, 180L)
            }
            else -> {
                sendHookAck(
                    context,
                    "synced",
                    "已同步 ${updated.spoofModel} (${updated.spoofProductIdHex}) L=${updated.leftBattery}% R=${updated.rightBattery}% 盒=${updated.caseBattery}%"
                )
            }
        }
    }

    private fun clearMelodyFgWhitelistCache(classLoader: ClassLoader) {
        runCatching {
            val abstractRepoCls = findClassOrNull("S8.a", classLoader) ?: return@runCatching
            val repo = XposedHelpers.callStaticMethod(abstractRepoCls, "f") ?: return@runCatching
            if (repo.javaClass.name == "S8.b") {
                @Suppress("UNCHECKED_CAST")
                (XposedHelpers.getObjectField(repo, "d") as? MutableMap<*, *>)?.clear()
                XposedHelpers.setObjectField(repo, "f", null)
                log("Cleared S8.b whitelist cache in :fg process")
            }
        }.onFailure { log("clearMelodyFgWhitelistCache skipped: ${it.message}") }
    }

    private fun invalidateWirelessSettingsCache(classLoader: ClassLoader) {
        runCatching {
            findClassOrNull("com.oplus.wirelesssettings.api.MelodyCacheClient", classLoader)?.let { cls ->
                XposedHelpers.callStaticMethod(cls, "invalidateEarsWhitelist")
                log("Invalidated WirelessSettings MelodyCacheClient cache")
            }
        }.onFailure { log("invalidateWirelessSettingsCache skipped: ${it.message}") }
    }

    private fun hookWirelessSettingsWhitelist(classLoader: ClassLoader) {
        val clientCls = findClassOrNull("com.oplus.wirelesssettings.api.MelodyCacheClient", classLoader)
            ?: return
        val resultCls = findClassOrNull("com.oplus.wirelesssettings.api.EarsWhitelistResult", classLoader)
            ?: return
        val rowCls = findClassOrNull("com.oplus.wirelesssettings.api.EarsWhitelistRow", classLoader)
            ?: return

        hookMethodSafe(clientCls, "queryEarsWhitelist", Context::class.java) { param ->
            patchWirelessSettingsResult(resultCls, rowCls, param.result)?.let { param.result = it }
        }
        hookMethodSafe(clientCls, "queryEarsWhitelist", Context::class.java, Array<String>::class.java) { param ->
            patchWirelessSettingsResult(resultCls, rowCls, param.result)?.let { param.result = it }
        }
        log("Hooked WirelessSettings MelodyCacheClient.queryEarsWhitelist")
    }

    private fun patchWirelessSettingsResult(
        resultCls: Class<*>,
        rowCls: Class<*>,
        origResult: Any?
    ): Any? = runCatching {
        if (!state.enabled) return@runCatching null
        @Suppress("UNCHECKED_CAST")
        val existing = (origResult?.let {
            runCatching { XposedHelpers.callMethod(it, "getRows") as? List<*> }.getOrNull()
        }).orEmpty().filterNotNull()

        val existingNames = existing.mapNotNull { row ->
            runCatching { XposedHelpers.callMethod(row, "getString", "name") as? String }.getOrNull()?.uppercase()
        }.toSet()
        if (JBL_WHITELIST_NAMES.all { it.uppercase() in existingNames }) {
            return@runCatching null
        }

        val combined = ArrayList<Any>(existing.size + JBL_WHITELIST_NAMES.size)
        combined.addAll(existing)
        val pidUpper = state.spoofProductIdHex.uppercase()
        for (jblName in JBL_WHITELIST_NAMES) {
            if (jblName.uppercase() !in existingNames) {
                val map = hashMapOf(
                    "name" to jblName,
                    "product_id" to pidUpper,
                    "support_wear_check" to "0"
                )
                combined.add(XposedHelpers.newInstance(rowCls, map))
            }
        }
        XposedHelpers.newInstance(resultCls, combined)
    }.onFailure { log("patchWirelessSettingsResult failed: ${it.message}") }.getOrNull()

    private fun hookWhitelistLookups(classLoader: ClassLoader) {
        val concreteRepoClasses = listOf(
            "S8.b",
            "com.oplus.melody.model.repository.whitelist.a"
        )
        for (clsName in concreteRepoClasses) {
            val cls = findClassOrNull(clsName, classLoader) ?: continue
            hookMethodSafe(cls, "a", String::class.java) { param ->
                if (!state.enabled || inRawWhitelistLookup.get() == true) return@hookMethodSafe
                val mac = param.args.firstOrNull() as? String
                if (isTargetMac(mac)) {
                    resolveSpoofWhitelistDto(classLoader)?.let { param.result = it }
                    if (mac != null) requestFreshSyncIfStale(mac)
                }
            }
            hookMethodSafe(cls, "c", String::class.java, String::class.java) { param ->
                if (!state.enabled || inRawWhitelistLookup.get() == true) return@hookMethodSafe
                val pid = param.args.getOrNull(0) as? String
                val name = param.args.getOrNull(1) as? String
                val isTargetPidWithJblActive = !pid.isNullOrBlank() &&
                    pid.equals(state.spoofProductIdHex, ignoreCase = true) &&
                    hasActiveOrBondedJbl()
                if (BridgeContract.isTargetJblName(name) || isTargetPidWithJblActive ||
                    (param.result == null && !pid.isNullOrBlank() && pid.equals("000000", ignoreCase = true) && isTargetDevice(null, name))
                ) {
                    resolveSpoofWhitelistDto(classLoader)?.let { param.result = it }
                }
            }
            hookMethodSafe(cls, "g") { param ->
                if (!state.enabled || inRawWhitelistLookup.get() == true || !hasActiveOrBondedJbl()) return@hookMethodSafe
                val list = param.result as? List<*> ?: return@hookMethodSafe
                val targetPid = state.spoofProductIdHex
                val sanitized = resolveSpoofWhitelistDto(classLoader) ?: return@hookMethodSafe
                var replaced = false
                val copied = ArrayList<Any?>(list.size)
                for (item in list) {
                    val id = item?.let { runCatching { XposedHelpers.callMethod(it, "getId") as? String }.getOrNull() }
                    if (id != null && id.equals(targetPid, ignoreCase = true)) {
                        copied.add(sanitized)
                        replaced = true
                    } else {
                        copied.add(item)
                    }
                }
                if (replaced) {
                    param.result = copied
                }
            }
            hookMethodSafe(cls, "k") { param ->
                if (!state.enabled) return@hookMethodSafe
                val orig = param.result ?: return@hookMethodSafe
                val patched = appendJblToOutsideSnapshot(classLoader, orig, state.spoofProductIdHex)
                if (patched != null) {
                    param.result = patched
                }
            }
        }

        findClassOrNull("S8.a", classLoader)?.let { abstractRepoCls ->
            hookMethodSafe(abstractRepoCls, "b", Int::class.javaPrimitiveType!!, String::class.java) { param ->
                if (!state.enabled || inRawWhitelistLookup.get() == true) return@hookMethodSafe
                val pidInt = (param.args.getOrNull(0) as? Int) ?: 0
                val name = param.args.getOrNull(1) as? String
                val targetPidInt = BridgeContract.parseProductIdInt(state.spoofProductIdHex)
                if (BridgeContract.isTargetJblName(name) ||
                    (pidInt == targetPidInt && hasActiveOrBondedJbl())
                ) {
                    resolveSpoofWhitelistDto(classLoader)?.let { param.result = it }
                }
            }
        }

        findClassOrNull("l7.a", classLoader)?.let { supportCls ->
            hookMethodSafe(supportCls, "a", String::class.java) { param ->
                if (!state.enabled || inRawWhitelistLookup.get() == true) return@hookMethodSafe
                val mac = param.args.firstOrNull() as? String
                if (isTargetMac(mac)) {
                    resolveSpoofWhitelistDto(classLoader)?.let { param.result = it }
                }
            }
        }

        findClassOrNull("com.oplus.melody.common.util.V", classLoader)?.let { vCls ->
            hookMethodSafe(vCls, "b", BluetoothDevice::class.java, Collection::class.java) { param ->
                if (!state.enabled || inRawWhitelistLookup.get() == true) return@hookMethodSafe
                val dev = param.args.getOrNull(0) as? BluetoothDevice ?: return@hookMethodSafe
                val coll = param.args.getOrNull(1) as? Collection<*>
                if (isTargetDevice(dev.address, safeDeviceName(dev))) {
                    learnTargetMac(dev.address)
                    param.result = resolveSpoofWhitelistDto(classLoader) ?: findDtoInCollection(coll)
                }
            }
            hookMethodSafe(vCls, "a", Collection::class.java, String::class.java, String::class.java) { param ->
                if (!state.enabled || inRawWhitelistLookup.get() == true) return@hookMethodSafe
                val coll = param.args.getOrNull(0) as? Collection<*>
                val pid = param.args.getOrNull(1) as? String
                val name = param.args.getOrNull(2) as? String
                val isTargetPidActive = !pid.isNullOrBlank() &&
                    pid.equals(state.spoofProductIdHex, ignoreCase = true) &&
                    hasActiveOrBondedJbl()
                if (BridgeContract.isTargetJblName(name) || isTargetPidActive) {
                    param.result = resolveSpoofWhitelistDto(classLoader) ?: findDtoInCollection(coll)
                }
            }
        }
    }

    private fun appendJblToOutsideSnapshot(
        classLoader: ClassLoader,
        snapshot: Any,
        pidHex: String
    ): Any? = runCatching {
        val snapCls = snapshot.javaClass
        val itemCls = findClassOrNull(
            "com.oplus.melody.model.repository.whitelist.WhitelistOutsideSnapshot\$Item",
            classLoader
        ) ?: return@runCatching null
        val version = runCatching { XposedHelpers.callMethod(snapshot, "getVersion") as? String }.getOrNull() ?: "1"
        @Suppress("UNCHECKED_CAST")
        val existing = runCatching {
            XposedHelpers.callMethod(snapshot, "getWhitelist") as? List<Any>
        }.getOrNull().orEmpty()

        val existingNames = existing.mapNotNull {
            runCatching { XposedHelpers.callMethod(it, "getName") as? String }.getOrNull()?.uppercase()
        }.toSet()
        if (JBL_WHITELIST_NAMES.all { it.uppercase() in existingNames }) {
            return@runCatching null
        }

        val combined = ArrayList<Any>(existing.size + JBL_WHITELIST_NAMES.size)
        combined.addAll(existing)
        val pidUpper = pidHex.uppercase()
        for (name in JBL_WHITELIST_NAMES) {
            if (name.uppercase() !in existingNames) {
                combined.add(XposedHelpers.newInstance(itemCls, pidUpper, name, 0))
            }
        }
        val bumpedVersion = BridgeContract.bumpOutsideSnapshotVersion(version, pidUpper)
        XposedHelpers.newInstance(snapCls, bumpedVersion, combined)
    }.onFailure { log("appendJblToOutsideSnapshot failed: ${it.message}") }.getOrNull()

    private fun notifySystemWhitelistChanged(context: Context, pidHex: String) {
        val key = "${context.packageName}|${pidHex.uppercase()}"
        if (lastWhitelistNotifiedKey == key) return
        lastWhitelistNotifiedKey = key
        runCatching {
            val helperCls = findClassOrNull(
                "com.oplus.melody.model.helper.WirelessSettingHelper",
                context.classLoader
            ) ?: return@runCatching
            XposedHelpers.callStaticMethod(helperCls, "sendWhiteListChangedBroadCast", context)
            val authority = runCatching {
                findClassOrNull("com.oplus.melody.common.util.V", context.classLoader)
                    ?.let { XposedHelpers.callStaticMethod(it, "d", context) as? String }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: "com.oplus.melody.providers.MelodyProvider"
            val uri = Uri.withAppendedPath(Uri.parse("content://$authority"), "ears_whitelist")
            context.contentResolver.notifyChange(uri, null, 0)
            log("Broadcasted & notified $uri whitelist change for JBL ($pidHex)")
        }.onFailure { log("sendWhiteListChangedBroadCast skipped: ${it.message}") }
    }

    private fun hookEarphoneRepositoryFallback(classLoader: ClassLoader) {
        val repoImplCls = findClassOrNull("com.oplus.melody.model.repository.earphone.U", classLoader)
            ?: return
        hookMethodSafe(repoImplCls, "g1", String::class.java) { param ->
            if (!state.enabled) return@hookMethodSafe
            val mac = param.args.firstOrNull() as? String ?: return@hookMethodSafe
            if (!isTargetMac(mac)) return@hookMethodSafe
            requestFreshSyncIfStale(mac)
            if (param.result != null) return@hookMethodSafe
            val dto = synthesizeEarphoneDto(classLoader, mac.uppercase(), state)
            if (dto != null) {
                param.result = dto
            }
        }
    }

    @Suppress("MissingPermission")
    private fun hasActiveOrBondedJbl(): Boolean {
        if (!state.targetMac.isNullOrBlank()) return true
        val now = System.currentTimeMillis()
        if (now - lastBondedCheckMs < BONDED_CHECK_TTL_MS) return lastBondedJblFound
        val found = runCatching {
            BluetoothAdapter.getDefaultAdapter()?.bondedDevices.orEmpty().firstOrNull { dev ->
                BridgeContract.isTargetJblName(safeDeviceName(dev))
            }?.also { dev ->
                learnTargetMac(dev.address)
            } != null
        }.getOrDefault(false)
        lastBondedCheckMs = now
        lastBondedJblFound = found
        return found
    }

    private fun requestFreshSyncIfStale(mac: String) {
        if (state.leftBattery >= 0) return
        val now = System.currentTimeMillis()
        if (now - lastSyncRequestMs < SYNC_REQUEST_COOLDOWN_MS) return
        lastSyncRequestMs = now
        appContext?.let {
            sendRemoteCommand(it, mac.uppercase(), BridgeContract.CMD_CONNECT_AND_SYNC)
        }
    }

    private fun buildSupportNoiseReductionInfo(classLoader: ClassLoader): Any? = runCatching {
        val nrCls = findClassOrNull("com.oplus.melody.btsdk.api.data.NoiseReductionInfo", classLoader)
            ?: return@runCatching null
        XposedHelpers.newInstance(nrCls).also {
            XposedHelpers.callMethod(it, "setAction", 1)
            XposedHelpers.callMethod(it, "setType", 1)
            XposedHelpers.callMethod(it, "setValue", JBL_NOISE_SUPPORT_MASK)
        }
    }.getOrNull()

    private fun buildSupportNoiseReductionDto(classLoader: ClassLoader): Any? = runCatching {
        val dtoCls = findClassOrNull(
            "com.oplus.melody.model.repository.earphone.NoiseReductionInfoDTO",
            classLoader
        ) ?: return@runCatching null
        XposedHelpers.newInstance(dtoCls, 1, 1, JBL_NOISE_SUPPORT_MASK)
    }.getOrNull()

    private fun synthesizeEarphoneDto(
        classLoader: ClassLoader,
        mac: String,
        current: SpoofState
    ): Any? = runCatching {
        val entityCls = findClassOrNull("com.oplus.melody.model.db.C", classLoader) ?: return@runCatching null
        val statusDoCls = findClassOrNull("com.oplus.melody.model.repository.earphone.V", classLoader)
            ?: findClassOrNull("com.oplus.melody.model.repository.earphone.EarphoneStatusDO", classLoader)
            ?: return@runCatching null
        val batItemCls = findClassOrNull("com.oplus.melody.model.repository.earphone.V\$a", classLoader)
            ?: findClassOrNull("com.oplus.melody.model.repository.earphone.EarphoneStatusDO\$a", classLoader)
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
        val color = (dto?.let {
            runCatching { XposedHelpers.callMethod(it, "getDefaultColor") as? Int }.getOrNull()
        } ?: current.spoofColor).takeIf { it >= 0 } ?: 0

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
        XposedHelpers.callMethod(statusDo, "setInitCmdCompleted", true)
        XposedHelpers.callMethod(statusDo, "setCapabilityReady", true)
        XposedHelpers.callMethod(statusDo, "setCapability", ArrayList(JBL_CAPABILITIES))
        buildSupportNoiseReductionDto(classLoader)?.let {
            XposedHelpers.callMethod(statusDo, "setSupportNoiseReductionInfo", it)
            XposedHelpers.callMethod(statusDo, "setSwitchNoiseReductionInfo", it)
        }
        XposedHelpers.callMethod(statusDo, "setNoiseReductionModeIndex", current.noiseIndex)
        XposedHelpers.callMethod(statusDo, "setEqType", current.eqType)
        buildDeviceVersionDtoList(classLoader, current.fwVersion)?.takeIf { it.isNotEmpty() }?.let { verDtos ->
            XposedHelpers.callMethod(statusDo, "setDeviceVersionList", verDtos)
            XposedHelpers.callMethod(statusDo, "setHeadsetVersionList", verDtos)
            XposedHelpers.callMethod(statusDo, "setVersionListReceived", true)
        }

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
                notifySystemWhitelistChanged(context, state.spoofProductIdHex)
                sendHookAck(context, "bt_connected", "欢律已捕获 JBL 蓝牙连接 ($mac)")
                sendRemoteCommand(context, mac, BridgeContract.CMD_CONNECT_AND_SYNC)
                val current = state.copy(targetMac = mac)
                injectIntoMelodyState(classLoader, mac, current)
                // Trigger 3D popup first; triggering capsule first causes Q6.m.n's g(mac) guard to suppress the popup.
                mainHandler.postDelayed({
                    triggerNativePopup(classLoader, context, mac, current, force = false)
                }, 260L)
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
                        emitMelodyEvents(classLoader, info, full = true, prev = null, current = current)
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
                                emitSetCommandAck(classLoader, mac, RSP_FIND_MODE)
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
                                    emitSingleEvent(classLoader, EVENT_EQ_MODE, it)
                                }
                                emitSetCommandAck(classLoader, mac, RSP_EQ_MODE)
                                param.result = null
                            }

                            // Short-circuit Melody SPP readback requests so dead OPPO SPP never stalls UI.
                            1016 -> {
                                getOrCreateDeviceInfo(classLoader, mac, state)?.let {
                                    applySnapshotToDeviceInfo(classLoader, it, state)
                                    emitSingleEvent(classLoader, EVENT_EQ_MODE, it)
                                }
                                param.result = null
                            }

                            1023 -> {
                                getOrCreateDeviceInfo(classLoader, mac, state)?.let {
                                    applySnapshotToDeviceInfo(classLoader, it, state)
                                    emitSingleEvent(classLoader, EVENT_NOISE_MODE, it)
                                }
                                param.result = null
                            }

                            1024 -> {
                                getOrCreateDeviceInfo(classLoader, mac, state)?.let {
                                    applySnapshotToDeviceInfo(classLoader, it, state)
                                    emitSingleEvent(classLoader, EVENT_SUPPORT_NOISE_REDUCTION, it)
                                }
                                param.result = null
                            }

                            1025 -> {
                                getOrCreateDeviceInfo(classLoader, mac, state)?.let {
                                    applySnapshotToDeviceInfo(classLoader, it, state)
                                    emitSingleEvent(classLoader, EVENT_SWITCH_NOISE_REDUCTION, it)
                                }
                                param.result = null
                            }

                            1043 -> {
                                getOrCreateDeviceInfo(classLoader, mac, state)?.let {
                                    applySnapshotToDeviceInfo(classLoader, it, state)
                                    if (state.fwVersion.isNotBlank()) {
                                        emitSingleEvent(classLoader, EVENT_HEADSET_VERSION, it)
                                    }
                                }
                                param.result = null
                            }

                            1046 -> {
                                getOrCreateDeviceInfo(classLoader, mac, state)?.let {
                                    applySnapshotToDeviceInfo(classLoader, it, state)
                                    emitSingleEvent(classLoader, EVENT_BATTERY_HEADSET, it)
                                }
                                param.result = null
                            }

                            1017, 1022, 1026, 1039, 1045, 1065 -> {
                                // Safely consume unsupported OPPO SPP query opcodes on JBL MAC.
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
            emitSingleEvent(classLoader, EVENT_NOISE_MODE, it)
        }
        emitSetCommandAck(classLoader, mac, RSP_NOISE_REDUCTION)
    }

    private fun emitSetCommandAck(
        classLoader: ClassLoader,
        mac: String,
        responseCommand: Int,
        status: Int = 0
    ) {
        runCatching {
            val infoCls = findClassOrNull(
                "com.oplus.melody.btsdk.api.data.SetCommandStateInfo",
                classLoader
            ) ?: return@runCatching
            val coreCls = findClassOrNull(
                "com.oplus.melody.btsdk.multidevice.HeadsetCoreService",
                classLoader
            ) ?: return@runCatching
            val recvCls = findClassOrNull(
                "com.oplus.melody.btsdk.api.data.BluetoothReceiveData",
                classLoader
            ) ?: return@runCatching

            val ack = XposedHelpers.newInstance(infoCls)
            XposedHelpers.setObjectField(ack, "mAddress", mac)
            XposedHelpers.setIntField(ack, "mSetStatus", status)
            XposedHelpers.setIntField(ack, "mResponseCommand", responseCommand)
            XposedHelpers.setIntField(ack, "mRspValue", 0)
            val data = XposedHelpers.newInstance(recvCls, EVENT_SET_CMD_STATE, ack)
            XposedHelpers.callStaticMethod(coreCls, "m0", data)
        }.onFailure { log("emitSetCommandAck failed: ${it.message}") }
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
                val key = "$mac|${current.spoofProductIdHex}|${current.spoofModel}"
                val needFullInit = lastFullInjectKey != key
                val prevEmitted = lastEmittedState

                if (needFullInit) {
                    val dto = resolveSpoofWhitelistDto(classLoader)
                    val pidHex = (dto?.let {
                        runCatching { XposedHelpers.callMethod(it, "getId") as? String }.getOrNull()
                    } ?: current.spoofProductIdHex).uppercase()
                    val title = dto?.let {
                        runCatching { XposedHelpers.callMethod(it, "getName") as? String }.getOrNull()
                    } ?: current.spoofModel
                    val color = (dto?.let {
                        runCatching { XposedHelpers.callMethod(it, "getDefaultColor") as? Int }.getOrNull()
                    } ?: current.spoofColor).takeIf { it >= 0 } ?: 0

                    runCatching {
                        findClassOrNull("com.oplus.melody.model.repository.earphone.c", classLoader)?.let { earRepoCls ->
                            val repo = XposedHelpers.callStaticMethod(earRepoCls, "G")
                            if (repo != null) {
                                XposedHelpers.callMethod(repo, "T", color, mac, title, pidHex)
                                XposedHelpers.callMethod(repo, "U", color, mac, title, pidHex)
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

                    runCatching {
                        findClassOrNull("w8.a\$a", classLoader)?.let { detailComp ->
                            val detailRepo = XposedHelpers.callStaticMethod(detailComp, "a")
                            if (detailRepo != null) {
                                XposedHelpers.callMethod(detailRepo, "a", color, pidHex)
                                XposedHelpers.callMethod(detailRepo, "b", color, pidHex)
                            }
                        }
                    }
                }

                if (current.leftBattery in 0..100 || current.rightBattery in 0..100) {
                    runCatching {
                        findClassOrNull("j7.d", classLoader)?.let { batPrefCls ->
                            XposedHelpers.callStaticMethod(
                                batPrefCls,
                                "b",
                                current.caseBattery.coerceIn(0, 100),
                                current.leftBattery.coerceIn(0, 100),
                                current.rightBattery.coerceIn(0, 100),
                                mac
                            )
                        }
                    }
                }

                val info = getOrCreateDeviceInfo(classLoader, mac, current) ?: return@post
                applySnapshotToDeviceInfo(classLoader, info, current)
                emitMelodyEvents(
                    classLoader = classLoader,
                    deviceInfo = info,
                    full = needFullInit,
                    prev = prevEmitted,
                    current = current
                )
                lastFullInjectKey = key
                lastEmittedState = current
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

    private fun buildVersionInfoList(classLoader: ClassLoader, fwVersion: String): ArrayList<Any> {
        val list = ArrayList<Any>(3)
        val triplet = BridgeContract.splitFirmwareTriplet(fwVersion) ?: return list
        val verCls = findClassOrNull("com.oplus.melody.btsdk.api.data.VersionInfo", classLoader)
            ?: return list
        runCatching {
            list.add(XposedHelpers.newInstance(verCls, 1, 2, triplet.first))
            list.add(XposedHelpers.newInstance(verCls, 2, 2, triplet.second))
            list.add(XposedHelpers.newInstance(verCls, 3, 2, triplet.third))
        }
        return list
    }

    private fun buildDeviceVersionDtoList(classLoader: ClassLoader, fwVersion: String): List<*>? {
        val versions = buildVersionInfoList(classLoader, fwVersion)
        if (versions.isEmpty()) return null
        val dtoCls = findClassOrNull(
            "com.oplus.melody.model.repository.earphone.DeviceVersionDTO",
            classLoader
        ) ?: return null
        return runCatching {
            XposedHelpers.callStaticMethod(dtoCls, "fromVersionList", versions) as? List<*>
        }.getOrNull()
    }

    private fun applySnapshotToDeviceInfo(classLoader: ClassLoader, info: Any, current: SpoofState) {
        val dto = resolveSpoofWhitelistDto(classLoader)
        val pid = resolveProductIdInt(classLoader, current)
        val color = (dto?.let {
            runCatching { XposedHelpers.callMethod(it, "getDefaultColor") as? Int }.getOrNull()
        } ?: current.spoofColor).takeIf { it >= 0 } ?: 0
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
            XposedHelpers.callMethod(info, "setCapability", ArrayList(JBL_CAPABILITIES))
        }

        buildSupportNoiseReductionInfo(classLoader)?.let { nrInfo ->
            runCatching {
                XposedHelpers.callMethod(info, "setNoiseReductionInfo", nrInfo)
                XposedHelpers.callMethod(info, "setSwitchNoiseReductionInfo", nrInfo)
            }
        }

        val versions = buildVersionInfoList(classLoader, current.fwVersion)
        if (versions.isNotEmpty()) {
            runCatching { XposedHelpers.callMethod(info, "setVersionInfo", versions) }
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

    private fun emitMelodyEvents(
        classLoader: ClassLoader,
        deviceInfo: Any,
        full: Boolean,
        prev: SpoofState?,
        current: SpoofState
    ) {
        val coreCls = findClassOrNull(
            "com.oplus.melody.btsdk.multidevice.HeadsetCoreService",
            classLoader
        ) ?: return
        val recvCls = findClassOrNull(
            "com.oplus.melody.btsdk.api.data.BluetoothReceiveData",
            classLoader
        ) ?: return

        val events = ArrayList<Int>(15)
        if (full || prev == null) {
            events.addAll(
                listOf(
                    EVENT_CONNECT_STATE,
                    EVENT_HEADSET_STATE,
                    EVENT_A2DP_STATE,
                    EVENT_COLOR_ID,
                    EVENT_CAPABILITY_READY,
                    EVENT_SUPPORT_NOISE_REDUCTION,
                    EVENT_SWITCH_NOISE_REDUCTION,
                    EVENT_BATTERY_MSG,
                    EVENT_BATTERY,
                    EVENT_STATUS,
                    EVENT_NOISE_MODE,
                    EVENT_EQ_MODE
                )
            )
            if (current.fwVersion.isNotBlank()) {
                events.add(EVENT_VERSION)
                events.add(EVENT_HEADSET_VERSION)
            }
        } else {
            val batChanged = prev.leftBattery != current.leftBattery ||
                prev.rightBattery != current.rightBattery ||
                prev.caseBattery != current.caseBattery ||
                prev.leftCharging != current.leftCharging ||
                prev.rightCharging != current.rightCharging ||
                prev.caseCharging != current.caseCharging
            if (batChanged) {
                events.add(EVENT_BATTERY)
                events.add(EVENT_BATTERY_HEADSET)
            }
            if (prev.noiseIndex != current.noiseIndex) {
                events.add(EVENT_NOISE_MODE)
            }
            if (prev.eqType != current.eqType) {
                events.add(EVENT_EQ_MODE)
            }
            if (prev.fwVersion != current.fwVersion && current.fwVersion.isNotBlank()) {
                events.add(EVENT_VERSION)
                events.add(EVENT_HEADSET_VERSION)
            }
        }
        for (code in events) {
            runCatching {
                val data = XposedHelpers.newInstance(recvCls, code, deviceInfo)
                XposedHelpers.callStaticMethod(coreCls, "m0", data)
            }
        }
    }

    private fun emitSingleEvent(classLoader: ClassLoader, eventCode: Int, deviceInfo: Any) {
        val coreCls = findClassOrNull(
            "com.oplus.melody.btsdk.multidevice.HeadsetCoreService",
            classLoader
        ) ?: return
        val recvCls = findClassOrNull(
            "com.oplus.melody.btsdk.api.data.BluetoothReceiveData",
            classLoader
        ) ?: return
        runCatching {
            val data = XposedHelpers.newInstance(recvCls, eventCode, deviceInfo)
            XposedHelpers.callStaticMethod(coreCls, "m0", data)
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
                val color = (dto?.let {
                    runCatching { XposedHelpers.callMethod(it, "getDefaultColor") as? Int }.getOrNull()
                } ?: current.spoofColor).takeIf { it >= 0 } ?: 0
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
                sendHookAck(context, "capsule_launched", "欢律原生流体云已触发 ($title / $pidHex)")
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
            val color = (dto?.let {
                runCatching { XposedHelpers.callMethod(it, "getDefaultColor") as? Int }.getOrNull()
            } ?: current.spoofColor).takeIf { it >= 0 } ?: 0
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
        val raw = resolveRawWhitelistDto(classLoader) ?: return null
        val pidHex = runCatching { XposedHelpers.callMethod(raw, "getId") as? String }
            .getOrNull()?.uppercase()
            ?: state.spoofProductIdHex.uppercase()
        sanitizedDtoCache[pidHex]?.let { return it }
        val built = buildSanitizedJblDto(classLoader, raw)
        if (built != null) {
            sanitizedDtoCache[pidHex] = built
            return built
        }
        return raw
    }

    private fun resolveRawWhitelistDto(classLoader: ClassLoader): Any? {
        val prevFlag = inRawWhitelistLookup.get() ?: false
        inRawWhitelistLookup.set(true)
        try {
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
        } finally {
            inRawWhitelistLookup.set(prevFlag)
        }
    }

    private fun cloneWhitelistDto(classLoader: ClassLoader, rawDto: Any): Any? {
        val parcelClone = runCatching {
            val p = Parcel.obtain()
            try {
                (rawDto as Parcelable).writeToParcel(p, 0)
                p.setDataPosition(0)
                @Suppress("UNCHECKED_CAST")
                val creator = XposedHelpers.getStaticObjectField(
                    rawDto.javaClass,
                    "CREATOR"
                ) as Parcelable.Creator<Any>
                creator.createFromParcel(p)
            } finally {
                p.recycle()
            }
        }.getOrNull()
        if (parcelClone != null) return parcelClone

        return runCatching {
            val baseCls = findClassOrNull("com.oplus.melody.common.data.a", classLoader)
                ?: return@runCatching null
            XposedHelpers.callStaticMethod(baseCls, "deepCopyOf", rawDto, rawDto.javaClass)
        }.getOrNull()
    }

    private fun buildSanitizedJblDto(classLoader: ClassLoader, rawDto: Any): Any? = runCatching {
        val cloned = cloneWhitelistDto(classLoader, rawDto) ?: return@runCatching rawDto
        val function = runCatching { XposedHelpers.callMethod(cloned, "getFunction") }.getOrNull()
            ?: return@runCatching cloned

        for (field in UNSUPPORTED_INT_FIELDS) {
            setIntFieldSafe(function, field, 0)
        }
        for (field in UNSUPPORTED_OBJECT_FIELDS) {
            setObjectFieldSafe(function, field, null)
        }

        setIntFieldSafe(function, "batteryInfo", 1)
        setIntFieldSafe(function, "fastDiscovery", 1)
        setIntFieldSafe(function, "findDevice", 1)
        setIntFieldSafe(function, "equalizer", 1)
        setIntFieldSafe(function, "noiseReductionUIVersion", 1)

        buildJblNoiseModeList(classLoader)?.let {
            setObjectFieldSafe(function, "noiseReductionMode", it)
        }
        buildJblEqModeList(classLoader)?.let {
            setObjectFieldSafe(function, "equalizerMode", it)
        }
        val pidHex = runCatching { XposedHelpers.callMethod(cloned, "getId") as? String }.getOrNull()
        log("Sanitized WhitelistConfigDTO for $pidHex (personalNoise=0, multiDevicesConnect=0)")
        cloned
    }.onFailure { log("buildSanitizedJblDto failed: ${it.message}") }.getOrNull()

    private fun buildJblNoiseModeList(classLoader: ClassLoader): ArrayList<Any>? = runCatching {
        val nrCls = findClassOrNull(
            "com.oplus.melody.common.data.WhitelistConfigDTO\$NoiseReductionMode",
            classLoader
        ) ?: return@runCatching null

        fun makeMode(protocolIndex: Int, modeType: Int, children: List<Any>? = null): Any {
            return XposedHelpers.newInstance(nrCls).also { mode ->
                setIntFieldSafe(mode, "protocolIndex", protocolIndex)
                setIntFieldSafe(mode, "modeType", modeType)
                setBooleanFieldSafe(mode, "decideByEarDevice", false)
                setObjectFieldSafe(mode, "childrenMode", children)
            }
        }

        val transChildren = arrayListOf(
            makeMode(protocolIndex = 8, modeType = 2),
            makeMode(protocolIndex = 9, modeType = 6)
        )
        arrayListOf(
            makeMode(protocolIndex = 1, modeType = 5, children = null), // 降噪
            makeMode(protocolIndex = 0, modeType = 1, children = null), // 关闭
            makeMode(protocolIndex = 2, modeType = 2, children = transChildren) // 通透 + 人声增强
        )
    }.getOrNull()

    private fun buildJblEqModeList(classLoader: ClassLoader): ArrayList<Any>? = runCatching {
        val eqCls = findClassOrNull(
            "com.oplus.melody.common.data.WhitelistConfigDTO\$EqualizerMode",
            classLoader
        ) ?: return@runCatching null

        fun makeEq(protocolIndex: Int, modeType: Int, order: Int): Any {
            return XposedHelpers.newInstance(eqCls).also { eq ->
                setIntFieldSafe(eq, "protocolIndex", protocolIndex)
                setIntFieldSafe(eq, "modeType", modeType)
                setIntFieldSafe(eq, "order", order)
            }
        }

        arrayListOf(
            makeEq(protocolIndex = 0, modeType = 22, order = 0), // 原声 (off)
            makeEq(protocolIndex = 1, modeType = 11, order = 1), // 均衡/爵士 (jazz)
            makeEq(protocolIndex = 2, modeType = 14, order = 2), // 纯净人声 (vocal)
            makeEq(protocolIndex = 3, modeType = 12, order = 3), // 低音增强 (bass)
            makeEq(protocolIndex = 4, modeType = 23, order = 4), // 澎湃动感 (club)
            makeEq(protocolIndex = 5, modeType = 17, order = 5)  // 平衡/录音室 (studio)
        )
    }.getOrNull()

    private fun setIntFieldSafe(target: Any, fieldName: String, value: Int) {
        runCatching { XposedHelpers.setIntField(target, fieldName, value) }
    }

    private fun setBooleanFieldSafe(target: Any, fieldName: String, value: Boolean) {
        runCatching { XposedHelpers.setBooleanField(target, fieldName, value) }
    }

    private fun setObjectFieldSafe(target: Any, fieldName: String, value: Any?) {
        runCatching { XposedHelpers.setObjectField(target, fieldName, value) }
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
