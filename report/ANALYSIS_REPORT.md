# JBL Headphones 功能分析与「JBL T230 Ctrl」独立 App 交付报告

日期：2026-11-13（真机验证 19:49–20:22）
目标设备：JBL Tune 230NC TWS（`JBL TUNE230NC TWS`，product id `204B`/`204C`）
分析对象：官方 `jbl.stc.com` **5.38.2**（base.apk 187 MB）+ 用户自有耳机
手机：realme RMX3888 / 真我 GT5 Pro / Android 16

---

## 0. 结论摘要

**已交付**：可安装的独立 Android APK、完整 Kotlin/Compose 源码、官方 APK 静态分析报告、机器可读协议命令表、协议规格、危险命令黑名单、抓包/校验工具链、真机验证记录。

**真机验证结果（本轮手机重新连接后完成）**：

- ✅ **读取链路全部打通**：独立 LE 控制侧扫描 → GATT 连接（MTU 512）→ CCCD 订阅 → 电量 / 降噪模式 / 手势表真机回读，且与官方 app 显示一致。
- ✅ **EQ 命令族已裁决**：官方 dex 明确走 `AA A2`（不是 `41-43`，也不是 `A6`），官方预设曲线精确提取。
- ❌ **写入链路尚未打通**：ANC 写入回读不符、EQ 写入回读超时，官方 app 状态未改变。app 如实标「未验证」，没有伪装生效。
- ⚠️ 本机 realme ROM 的 HCI 二进制抓包不可用（122 个 `.cfa` 全是 16 字节空头），字节级证据改由「官方 APK 静态反编译 + 本 app 真机主动回读」取得。

- ⚠️ **2026-11-14 代码更新说明（无实体手机在线测试）**：已完成对 `Tune230Control` 与 `BesEQControl` 写入链路的深层排查修复，并补全官方 app 的全量控制功能；上述「✅ 已完成编码 + 编译 + 离线校验」成果尚待重新接入实体 Tune 230NC TWS 完成现场写入闭环测试。
  1. **EQ 写入根因修复**：确认 `Tune230Control` 实走 **`0x40`** 预设切换（`AA 40 01 <preset>`）+ **`0x41/0x42/0x43`** 多包 10 段自定义 EQ 传输（含 175B 读 / 179B 写偏移差异处理、`AA 01 02 43 00` 分块确认、`AA 04 43` 与 `AA 05 41` 结束帧），原 `0xA2` 仅保留作次级备援。
  2. **ANC 与 BLE 链路根因修复**：补全 `CONNECTION_PRIORITY_HIGH`、显式 `WRITE_TYPE_DEFAULT`、CCCD 订阅后 300ms 稳定等待、`AA 11 00` + `AA 10 02 00 00` 握手、`AA 21 01 30` 全状态 / `AA 21 01 41` 佩戴检测、以及 `0x91`（mode + SubMode + Ambient Aware level 1..7）与 `0x31/0x32` 双路径回退。
  3. **官方全量功能落地**：实现触控手势分组写与重置（`0x77`）、Smart Audio & Video（`0x81/82/83`）、VoiceAware（`0x98`）、Best Fit 贴合度检测（`0x21/42`）与耳塞类型（`0x9C`）、自动播停（`0x35`）、待机保留 ANC（`0x96`）、自动关机（`0x33`）、语音提示偏好（`0x93`）、Multi-AI（`0x34`）、硬件信息与电压（`0x12/25/26/94/9B`）及带安全弹窗+15s 自动停止的 Find My Buds（`0x23/36`）。

---

## 1. 分析对象与已核实事实

| 项 | 事实 |
|---|---|
| 官方 app | `jbl.stc.com` 5.38.2，base.apk 187 MB，4 个 dex（33 MB），5023 entries，245 个 assets |
| 型号条目 | `assets/product_list.json` `defaults[25]`：`JBL TUNE230NC TWS`，`pidS=["204B","204C"]`，`platform="Bes"`，`connectType="BLE"`，`eqGainRange=[-6,6]` |
| 控制类 | `com.harman.cmdctrl.imp.control.u3.bes.Tune230Control` |
| 协议族 | Harman / BES 老协议（`AA` 帧，BLE GATT）；**不是** Protocol 4、**不是** Airoha RACE |
| 真机 GATT | service `65786365-...-0000` @0x000a–0x0011；notify `...0001` @0x000c (prop 0x12)；write `...0002` @0x0010 (prop 0x0c)；CCCD @0x000d；另有 Fast Pair `0000fe2c` |
| LE 侧 | classic `78:5E:A2:EC:A0:CA` 只承载音频（`ACL BR/EDR:Y LE:N`），控制端是多个**独立轮换**的 `JBL TUNE230NC TWS-LE` 地址 |
| 官方功能面 | `assets/config/dash4/jbl_tune230nc_tws.json`：ANC / Ambient Aware / TalkThru / EQ / Gesture / VoiceAware / SmartAudioVideo / VoiceAssistant / FindMyBuds / PowerSaving / ResetToFactoryRest / OTA |

完整静态证据见 `reverse/notes/STATIC_FINDINGS.md`，真机证据见 `report/VERIFICATION.md`。

## 2. 功能提取清单（按用户优先级）

状态：`真实`=下发后回读一致；`只读`=能从耳机读到；`未验证`=命令存在但本机未确认；`降级`=禁用/占位/跳转官方 app。

| 组 | 功能 | 协议命令 | 证据 | 当前状态 |
|---|---|---|---|---|
| ① | EQ 6 个官方预设（Off/JAZZ/VOCAL/BASS/CLUB/STUDIO） | `AA A2 <len:2LE> 00 <category> ...` | `APK_STATIC`（官方 `EQCmd.sendCmd`） | **降级**（仅本地保存，写入回读超时） |
| ① | 自定义 10 段 EQ（32Hz–16kHz，±6dB，0.5 步进） | 同上，category 230 | `APK_STATIC` | **降级**（仅本地保存） |
| ② | ANC / Ambient Aware / TalkThru 写入 | `AA 91 07 10 ...` | `APK_STATIC`（官方 `CmdGen.genSetANCModes` 逐字节一致）+ 真机下发 | **未验证**（回读仍全 off） |
| ② | 降噪模式读取 | `AA 91 01 11` → `AA 91 07 12 ...` | `RECORDED` | **只读 / 真实** |
| ② | 自适应 ANC / 等级 | `91` sub `20-22` | `INFERRED` | 禁用 |
| ③ | 左右耳 + 充电盒电量 | `AA 25 01 00` → `AA 25 0D 01 ...` | `RECORDED` | **只读 / 真实**（本 app 90/90/67，官方 90/90/64） |
| ③ | 连接状态 / A2DP / HFP / 音量 | Android 标准 API | 真机 | **可用** |
| ③ | 全状态读 | `AA 21 01 30` | `PRIOR_DOC` | 未验证 |
| ④ | 触控手势表读取 | `AA 77 02 01 FF` → `AA 77 11 02 ...` | `RECORDED` | **只读 / 真实** |
| ④ | 手势写入 | `AA 77 <len> 00 ...` | `PRIOR_DOC` | 未验证 |
| ④ | 查找耳机 | 官方 `MyBudsInfo(leftBeeping,rightBeeping)` 取值 -1/0/1 | `APK_STATIC`（与前人文档不同） | **降级/禁用** |
| ④ | 语音助手 / 智能音视频 / 自动关机 / 贴合度 | status `35`、`AA 81/82/83`、feature | `APK_STATIC` / `INFERRED` | 降级 |

命令表 25 条，全部带 `confidence` 与 `source`；危险命令黑名单 31 项。

**官方 EQ 精确预设**（`assets/eq/jbl_tune230nc_tws.json`，`isUsingPresetEqPayload: true`）：

| 预设 | eqID | category | gains (dB) |
|---|---|---|---|
| Off | 1 | 0 | `[0,0,0,0,0,0,0,0,0,0]` |
| JAZZ | 2 | 1 | `[4,2,1,2.5,-1.5,-1.5,0,1,2,4]` |
| VOCAL | 3 | 2 | `[-1.5,-3,-3,1,3.5,3.5,3,1.5,0,-1.5]` |
| BASS | 4 | 3 | `[6,3,3,2.5,0.5,0,0,0,0,0]` |
| CLUB | 8 | 6 | `[1,0,1,0,0,0,-2,2,0,3]` |
| STUDIO | 9 | 7 | `[0,-2,-2,0,0,0,0,0,0,0]` |

## 3. 界面地图

```
JBL T230 Ctrl (MainActivity)
├─ 全局横幅：单连接冲突 / 「不假装生效」原则
├─ 概览：连接状态卡（链路/MAC/A2DP-HFP/系统电量/音量滑块）、三环形电量图、功能状态总览
├─ 声音：4 个降噪模式按钮 + 6 个官方预设 + 10 段滑块 + 曲线预览；每项带四态徽标与原因
├─ 手势：8 个 gesture 条目（左/右 × tap/double/triple/hold）
├─ 更多：查找耳机 / 助手 / 智能音视频 / 自动关机 / 贴合度（降级徽标 + 跳转官方 app）
└─ 设置：命令证据统计、关于与免责、脱敏协议日志（成对 →/← 帧）
```

`StatusBadge` 四态（真实 / 只读 / 未验证 / 降级）**只由 `DeviceState` 的回读结果驱动**，没有硬编码「生效」。

## 4. 数据来源与置信度

优先级：`RECORDED`（本机 + 本耳机真机帧）> `APK_STATIC`（官方 5.38.2 反编译/资源）> `PRIOR_DOC`（前人文档）> `INFERRED`。

当前：`RECORDED` 3 条（电量读、降噪模式读、手势读）+ GATT 整套；`APK_STATIC` 7 条；其余为 `PRIOR_DOC` / `INFERRED`。逐条迁移记录见 `protocol-spec/COMMAND_MATRIX.md`。

## 5. 架构与技术选型

| 决策 | 选择 |
|---|---|
| 语言/UI | Kotlin + Jetpack Compose |
| SDK | compileSdk 35 / targetSdk 35 / minSdk 31 |
| 构建 | JDK 17（本机已有）+ Gradle 8.9（缓存）+ AGP 8.7.0 + Kotlin 2.0.21；Maven 用 `repo1.maven.org` + 阿里云镜像（本机 `repo.maven.apache.org` TLS 握手失败） |
| 网络权限 | **不申请 `INTERNET`** |

分层：`UI` → `DeviceState(StateFlow)` → `CommandQueue(串行/1s 超时/写后回读)` → `CommandGuard(硬拦截)` → `FrameCodec` / `EqCodec` → `GattTransport` → `BleScanner`；旁路 `SystemBaseline`。

关键实现：
- 真机证据表明 LE 侧独立轮换 → **先扫 `-LE`、再回退 classic**（原实现相反，已修正）。
- 帧 `AA <cmd> <len> <payload>`；`0x06` 与**入站 `0xA2`** 用 2 字节小端长度；`A2` 请求用 1 字节。
- `EqCodec` 按官方 `EQCmd.sendCmd()` 构造 `AA A2 <len:2LE> 00 <category> <calib:f32LE> 48 <left/right:f32LE> 10 <10×10B band>`（116 字节 payload）。
- **ACK 不采信**；每个 set 必回读比对；写入无即时回复时记日志但不算成功。
- 单一控制连接：`onStop()` 释放 GATT；危险命令 `95/97/F0/AA/13/15` 在 `write()` 前硬拦截。
- `FrameCodec.parseBattery()` 按真机帧修正偏移（左=p[3]、右=p[4]、盒=p[6]）。

## 6. 抓包方法学

本机 realme ROM 的 HCI snoop 二进制日志**不可用**：`settings put global bluetooth_hci_snoop_log 1` + 重启蓝牙栈后，`adb bugreport` 中 `FS/data/misc/bluetooth/logs/bthci/CsLog_*/*.cfa` 的 122 个文件全部只有 16 字节 btsnoop 头，没有记录；也没有 `btsnoop_hci.log`。文本日志 `bluetooth_*.log` 有完整 GATT 服务发现与 read/write 事件，但不含 ATT payload 字节。

替代证据链（更硬）：
1. 官方 APK 静态反编译（jadx 1.5.6，11840 类）→ 裁决 EQ 命令族、拿到官方预设/智能音视频数值/控制类调用链。
2. 本 app 真机主动下发 + 回读 → 拿到成对的 `AA` 帧与耳机电量/模式/手势真实值。
3. `dumpsys bluetooth_manager` → LE 侧地址策略；`adb bugreport` 文本日志 → 真机 GATT 表。

工具保留：`protocol-spec/captures/parse_btsnoop.py`（Python 标准库 btsnoop 解析器，已用合成日志验证）、`CAPTURE_NOTES.md`（换 ROM 后的操作清单）。脱敏规则见 `captures/README.md`。

## 7. 安全与合规

- 不 root、不改系统分区、不动个人数据；HCI snoop 已关闭（`settings=0`）。
- 不复制官方 APK 的图标/图片/字符串/layout；launcher 图标自绘。
- **clean-room**：不复制、不移植 GPL-3.0 项目（PairFree / SoundCtrl）源码，不把其原始 `commands.json` 入库；只把客观字节事实逐条重新录入并登记出处，见 `protocol-spec/SOURCES.md`。本项目 MIT。
- 抓包、日志、设备标识不上传；app 无 `INTERNET` 权限。
- 恢复出厂 / 关机 / 固件升级 / analytics 命令硬黑名单；查找耳机需提示与自动 stop（目前禁用）。
- 收尾已确认官方 app 恢复正常（90/90/64、EQ=STUDIO），耳机设置未被破坏。

## 8. 构建与验证结果

| 项 | 结果 |
|---|---|
| `gradlew.bat --version` | Gradle 8.9 / JVM 17.0.19 |
| `gradlew.bat :app:assembleDebug` | BUILD SUCCESSFUL |
| `python protocol-spec/verify/check_frames.py` | 25 条命令、31 项黑名单，帧长/元数据自洽，PASS |
| `python protocol-spec/verify/run_unit_tests.py` | `OK (10 tests)`（帧编解码、`06`/`A2` 长度、回复映射、噪声模式、电量未知值、ACK 不采信、黑名单、A2 读/写帧与小端浮点） |
| `aapt dump badging` / `apksigner verify` | 包名 `com.jblt230.ctrl`、无 `INTERNET`、debug 签名有效 |
| `adb install -r` + 真机连通 | 成功，见 `report/VERIFICATION.md` |
| APK | `build/outputs/jbl-t230-ctrl-debug.apk`，约 24 MB |

> `gradlew :app:testDebugUnitTest` 在本机因宿主环境向 Gradle Test Executor 注入异常 classpath 而失败（编译不受影响），改用 `run_unit_tests.py` 直跑 JUnitCore，结果可复现。

## 9. 遗留风险与下一步

1. **写入未打通**（最实质）：ANC 回读不符、EQ A2 回读超时，官方 app 状态未变。原因分析见 `VERIFICATION.md` §6：可能缺官方初始化读序列、缺 design EQ 的 calibration、ANC 被「环境声音控制」总开关 gate、或长帧需分片/确认。
2. **EQ calibration**：官方用 design EQ（category 202）读回值，本 app 暂置 0。
3. **查找耳机命令**：官方 `MyBudsInfo(leftBeeping,rightBeeping)` 取值 -1/0/1，与前人文档的单字节映射不同，故保持禁用。
4. 单次真机验证，未覆盖 Ambient/TalkThru/手势写入/语音助手等全部功能。
5. LE 地址轮换：当前实现每次连接重新扫描，长期后台重连策略未做。
6. 官方 app 版本漂移（5.37.10 → 5.38.2）：本轮以 5.38.2 为准。

**下一轮建议**：先在官方 app 打开「环境声音控制」总开关 → 重试 ANC 写入；补齐官方 `getAllDashboardCommands()` 的十余条初始化读 → 先读 design EQ(202) 拿 calibration → 再写 A2 预设 → 用「本 app 改 → 回读一致 → 官方 app 同步」闭环验收。

**验收门槛**：EQ / ANC / 电量至少 3 项达成「下发 → 回读一致 → 官方 app 状态同步」三项全绿。目前只达成电量只读一项，因此本交付物中所有固件级写入都如实标注为未验证。
