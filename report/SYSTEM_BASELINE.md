# 系统层保底能力评估（Android 16 / realme RMX3888）

本文件记录「不依赖 JBL 私有协议」时，普通三方 app 能从 Android 标准 API 拿到什么，以及哪些 API 实际不可用。app 中的 `SystemBaseline.kt` / `BatteryReflection.kt` 按此结论实现。

| 能力 | API | 权限 / 可见性 | 结论 |
|---|---|---|---|
| 已配对设备列表 | `BluetoothAdapter.getBondedDevices()` | `BLUETOOTH_CONNECT` | ✅ 可用。用于定位 `JBL TUNE230...` 与其 MAC |
| 连接/断开事件 | `ACTION_ACL_CONNECTED` / `ACTION_ACL_DISCONNECTED` / `ACTION_STATE_CHANGED` | 普通广播 | ✅ 可用 |
| A2DP / HFP 连接状态 | `getProfileProxy(A2DP / HEADSET)` + `getConnectedDevices()` | `BLUETOOTH_CONNECT` | ✅ 可用（异步 `ServiceListener`，实现里做了 null 兜底） |
| 媒体音量 | `AudioManager.getStreamVolume / setStreamVolume(STREAM_MUSIC)` | 无特殊权限 | ✅ 可用，UI 里做成滑块 |
| A2DP 是否活跃 | `AudioManager.isBluetoothA2dpOn()` | 无 | ⚠️ 可用但已废弃（deprecated），仅作展示 |
| 输出设备信息 | `getDevices(GET_DEVICES_OUTPUTS)` + `AudioDeviceInfo` | API 31+ | ✅ 可用，用于展示蓝牙输出设备类型；**不含厂商编解码细节** |
| 蓝牙编解码（SBC/AAC/LDAC） | 无公开 API；`BluetoothA2dp.getCodecStatus()` 为隐藏 API | 隐藏 | ❌ 不作为数据源；真机上 `dumpsys` 能看到，但 app 读不到 |
| 耳机精确电量（左右耳/充电盒） | `BluetoothDevice.getBatteryLevel()` | **隐藏 API**，Android 16 可能拒绝反射 | ⚠️ 仅 try/catch 反射交叉校验；失败显示「不可用」并明确写「电量由 BLE 协议读取」，**绝不填假值** |
| 电量广播 | `ACTION_BATTERY_LEVEL_CHANGED` / `EXTRA_BATTERY_LEVEL` | **隐藏常量** | ⚠️ 以字符串字面量注册广播，失败静默忽略 |
| 系统元数据 | `getMetadata(METADATA_UNTETHERED_LEFT_BATTERY)` 等 | 需 `BLUETOOTH_PRIVILEGED` / companion device / 系统签名 | ❌ **不作为数据源**。实现里只反射尝试一次，记录「是否可读取」并在 UI 标注「不可用（权限受限）」 |
| hiddenapibypass 等绕过库 | — | — | ❌ 默认不引入。避免破坏系统兼容性与引入额外依赖 |

## 结论

系统层能稳定提供：**配对设备身份、连接/断开事件、A2DP/HFP 连接状态、媒体音量、蓝牙输出设备类型**。它**无法**提供左右耳与充电盒的精确电量、厂商编解码信息、ANC/EQ 等固件设置。

因此架构上把系统层定位为「保底基线」，固件级数据一律以 BLE 协议 `0x25` / `0x06` 的真实回读为准。两类来源在 UI 中分别标注，绝不混用、绝不用系统估算值冒充耳机真实值。

## 实测状态

✅ **已真机复核（2026-11-13）**：本 app 已装机并连接耳机，实测结果：

| 能力 | 真机结果 |
|---|---|
| 配对设备 / MAC | ✅ 正确识别 `JBL TUNE230NC TWS` / `78:5E:A2:EC:A0:CA` |
| A2DP / HFP | ✅ 均 `true`（`getProfileProxy` 可用） |
| 媒体音量 | ✅ 12/16，滑块可读写 |
| 系统电量 hidden API | ⚠️ 首次返回 `BATTERY_LEVEL_UNKNOWN`，后续返回 **90%**（说明 `getBatteryLevel()` 在本机反射可用，但值不稳定，故 UI 明确标注来源） |
| `METADATA_UNTETHERED_*` | ❌ 反射不可用，UI 显示「不可用（权限受限）」，符合预期 |
| 蓝牙编解码细节 | ❌ 普通三方 app 读不到，未作为数据源 |

结论不变：**精确左右耳/充电盒电量以 BLE `0x25` 为准**（本 app 90/90/67，官方 90/90/64），系统层只作交叉校验。详见 `report/VERIFICATION.md`。
