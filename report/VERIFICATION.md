# 真机逐项验证记录

设备：realme RMX3888 / Android 16，adb `b4b52452`；耳机 `78:5E:A2:EC:A0:CA : JBL TUNE230NC TWS`；官方 app `jbl.stc.com` 5.38.2；本 app `com.jblt230.ctrl` 0.1.0。
时间：2026-11-13 19:49–20:22。

## 0. 结论先行

**读取链路全部打通，写入链路尚未打通。**

- 独立 LE 控制侧扫描、GATT 连接、MTU 512、CCCD 订阅、成对收发帧、电量 / 降噪模式 / 手势表真机回读，均已成功，且与官方 app 显示一致。
- ANC 写入：帧与官方 `CmdGen.genSetANCModes()` 逐字节一致，但下发后回读仍是全 off，官方 app 状态也未变化 → **未验证**。
- EQ `A2` 写入：读当前 EQ（category 255）超时无应答；写入后回读超时，官方 app EQ 仍为 STUDIO → **未生效 / 未验证**。
- 本 app 对以上两项如实显示「未验证」并置灰，**没有伪装成生效**。

## 1. 环境与抓包

| 项 | 结果 |
|---|---|
| `adb devices -l` | `b4b52452 device` |
| `adb pull` 官方 APK | base.apk 187 MB + arm64 / xxxhdpi split |
| HCI snoop 开关 | `settings put global bluetooth_hci_snoop_log 1` + `cmd bluetooth_manager disable/enable` |
| **HCI 二进制抓包** | **本 ROM 不可用**：`adb bugreport` 中 122 个 `*.cfa` 全部只有 16 字节 btsnoop 头，无记录；无 `btsnoop_hci.log`。文本日志有 GATT 事件但无 ATT payload 字节 |
| 替代证据 | 官方 APK 静态反编译（jadx 1.5.6，11840 类）+ 本 app 真机主动下发/回读 + `dumpsys bluetooth_manager` + bugreport 文本 GATT 日志 |

## 2. 装机

| # | 检查项 | 结果 |
|---|---|---|
| 1 | `adb install -r build/outputs/jbl-t230-ctrl-debug.apk` | `Success` |
| 2 | 应用信息包名 | `com.jblt230.ctrl`，与 `jbl.stc.com` 不同 |
| 3 | `aapt dump badging` | minSdk 31 / targetSdk 35 / 无 `INTERNET` |
| 4 | `apksigner verify` | debug 签名有效 |
| 5 | 运行时权限 | 该 ROM 拒绝 `pm grant`（`GRANT_RUNTIME_PERMISSIONS`），但 app 启动后 `hasPermissions()` 为真，连接正常 |
| 6 | 冷启动 UI | 五个 tab、连接状态卡、系统层信息正常渲染 |

## 3. 真机 GATT（`adb bugreport` 文本日志，一手）

```
Service 0x000a-0x0011 : 65786365-6c70-6f69-6e74-2e636f6d0000
  Char 0x000b/0x000c : ...0001  prop=0x12 (READ|NOTIFY)
  Desc 0x000d        : 00002902 (CCCD)
  Char 0x000f/0x0010 : ...0002  prop=0x0c (WRITE|WRITE_NO_RESPONSE)
Service 0x0016-0x0022 : 0000fe2c (Google Fast Pair)
```
本 app `commands.json` 的 GATT 配置与真机完全一致。logcat：`GATT connected, requesting MTU 512` → `MTU=512 status=0` → `CCCD subscribed, control link ready`。

LE 侧：`dumpsys` 显示 classic `78:5E:A2:EC:A0:CA`（`ACL BR/EDR:Y LE:N`）只承载音频，另有多个 `JBL TUNE230NC TWS-LE` 的**独立轮换 LE 地址**。据此把连接策略改为「先扫 `-LE`、再回退 classic」，实测一次连上。

## 4. 真机成对帧（本 app 脱敏协议日志）

| 方向 | 帧 | 含义 |
|---|---|---|
| ← | `AA12254A424C2054554E453233304E432054004B2000DACAA0ECA25E780307005ADA021008000100` | 主动设备信息帧，含 ASCII `JBL TUNE230NC T`、product id `0x204B`、classic MAC |
| → / ← | `AA250100` / `AA250D0100015A5A004302108D10FE0A` | 电量：左 90 / 右 90 / 盒 67 |
| → / ← | `AA910111` / `AA910712010002000300` | 降噪模式读：全 off |
| → / ← | `AA770201FF` / `AA7711020609070408000CA109080A050B060EA1` | 手势表读：8 组 gesture/action |
| → / ← | `AA910710010102000300` / `AA910712010002000300` | **ANC 写入尝试**：回读未变 |
| → | `AAA20201FF` | 读当前 EQ（category 255）→ **超时无应答** |

**电量布局修正**：真机帧 `AA 25 0D 01 00 01 5A 5A 00 43 ...` 证明 payload 是
`01, secondaryCharging, primaryCharging, secondaryLevel, primaryLevel, caseCharging, caseLevel, ...`。
前人文档推断的偏移是错的，`FrameCodec.parseBattery()` 已按真机修正（左=p[3]、右=p[4]、盒=p[6]）。修正后本 app 解析 90/90/67，官方 app 显示 90/90/64（刷新时间差），**一致**。

## 5. 逐项验证表

| # | 检查项 | 实际 | 结论 |
|---|---|---|---|
| 1 | 桌面新图标、独立包名 | 与官方 app 并排 | 真实 |
| 2 | 控制链路 | MTU 512 / CCCD 订阅成功 | 真实 |
| 3 | 系统层状态 | `JBL TUNE230NC TWS` / A2DP true / HFP true / 音量 12-16 | 真实 |
| 4 | 电量 `0x25` | 本 app 90/90/67，官方 90/90/64 | **真实** |
| 5 | 降噪模式读 `0x91` | `AA910712010002000300` | **真实（只读）** |
| 6 | 手势读 `0x77` | `AA771102...` | **真实（只读）** |
| 7 | ANC 开 | 回读仍全 off；官方 app 无变化 | **未验证** |
| 8 | Ambient / TalkThru | 未逐项验证（同族） | 未验证 |
| 9 | EQ 双向同步 | 本 app 设 BASS → 官方仍 **STUDIO** | **未生效** |
| 10 | 徽标诚实 | 降噪/EQ 均显示「未验证」+ 具体原因 | 真实 |
| 11 | 日志脱敏 | MAC 替换为 `<MAC>`；长帧 >80 字符替换 | 真实 |
| 12 | 危险命令拦截 | 单测 + 运行期拦截；日志无危险帧 | 真实 |
| 13 | 单连接互斥 | 释放后官方 app 正常打开并读到 90/90/64、EQ=STUDIO | 真实 |
| 14 | COMMAND_MATRIX 迁移 | 已迁移 3 条读命令 + GATT；写入项如实保留未验证 | 部分 |

**验收门槛（EQ / ANC / 电量三项「下发→回读一致→官方同步」）目前只达成 1 项（电量只读）**，因此不宣称「已完全真控制」。

## 6. 写入未打通的原因分析（下一步方向）

1. **缺少初始化握手**：官方 `Tune230Control.getAllDashboardCommands()` 一次发十余条读命令（`getDeviceStatus(ALL)`、`reqDeviceMultiStatus()`、`getEQSettings2(202)`、`getEQSettings2(255)`、`getSmartSwitchStatus()`、`getGestureStatus(255)`、`reqVoiceAwareStatus()`、`getMultiAIStatus()`、`getSeriesNumber()` …）。本 app 只发了 4 条读。耳机固件可能要等完整初始化后才接受写入。
2. **EQ calibration**：官方 `BesEQControl.setEQSetting()` 用 design EQ（category 202）读回的 calibration；本 app 暂置 0，且 `AAA20201FF` 读当前 EQ 无应答，说明 A2 读路径本身可能还需要 handshake 或分片确认（`AA 01 02 43 00` 等）。
3. **ANC 总开关**：官方 app 的「环境声音控制」Switch 当前 `checked=false`，模式写入可能被主开关 gate 住。
4. **分片/确认序列**：官方 `EqCmdData.setLongCmd(true)` 表明长 EQ 帧走分片队列；`BesEQControl.setEQSettings2()` 在帧长 > MTU-18 时用 `GenGroupCmd` 分片。本帧 120 字节 < 512，理论上无需分片，但可能仍需要确认帧。

建议下一轮：先在官方 app 上开启「环境声音控制」总开关，再重试 ANC 写入；同时补齐官方初始化读序列，并先读 design EQ(202) 拿 calibration 后再写 A2。

## 7. 收尾

- HCI snoop 已关闭：`settings put global bluetooth_hci_snoop_log 0`。
- 官方 app 已恢复正常，可读到 90/90/64、EQ=STUDIO，耳机设置未被破坏。
- 未触发恢复出厂 / 固件升级 / 关机；未 root、未改系统分区、未动个人数据。
- `reverse/` 内的官方 APK 与反编译产物仅本地保存，不随交付物分发。
