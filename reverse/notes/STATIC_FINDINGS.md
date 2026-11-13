# 官方 JBL Headphones 5.38.2 静态分析发现（一手证据）

对象：`reverse/apk/base.apk`（187 MB，5023 entries，4 个 dex 共 33 MB，245 个 assets）+ 两个 split APK。
工具：jadx 1.5.6（`--no-res --no-debug-info -j 4`，11840 类，135 个反编译错误但目标类完整）+ Python zipfile 读取 assets + `adb bugreport` 真机 GATT 日志。

> 本文件是分析笔记。官方 APK 与其反编译产物仅保存在本地 `reverse/`（`.gitignore`），不随交付物分发；本项目源码未复制任何官方代码或资源。

## 1. 型号与协议族（已确证）

`assets/product_list.json` → `defaults[25]`：

| 字段 | 值 |
|---|---|
| `name` | `JBL TUNE230NC TWS` |
| `pidS` | `["204B","204C"]`（GLOBAL / CN） |
| `platform` | **`Bes`** |
| `connectType` | **`BLE`** |
| `cmdCtrlList` | `com.harman.cmdctrl.imp.control.u3.bes.Tune230Control` |
| `eqGainRange` | `[-6, 6]` |
| `eqConfig` | `jbl_tune230nc_tws.json` |
| `hasANC` / `hasPowerOff` | `"true"` / `"true"` |
| `hasPersonalFi` | `"false"` |
| `smartAudioVideo` | `{"normal":[197,46,280], "audio":[256,46,280], "video":[197,46,120]}` |
| `voiceAssistantType` | `[1,1]` |
| `uiVersion` | 3.1 |

结论：**Harman/BES 老协议 + BLE 控制通道**，排除 Protocol 4（帧头 `DD00`）与 Airoha RACE（帧头 `05`）。同 control class 也用于 `JBL TUNE235NC TWS`（pid `20E5`）。

## 2. 官方对本型号的功能清单

`assets/config/dash4/jbl_tune230nc_tws.json`（`pid: "204B"`）的 `uiFeatures`：

`ANC`、`AA`（Ambient Aware）、`TT`（TalkThru）、`SetupCard`、`AscCardNoAaLevelOldProduct`、`EQCard`、`GestureCard`、`VoiceAwareCardForRetDevAck`（`supportVersion: "2.7.0"`）、`SmartAudioVideoCard`、`VoiceAssistantCard`、`FindMyBudCard`、`PowerSavingCard`、`SupportCard`、`SoftwareCard`、`ResetToFactoryRest`、`OTA`。

即官方承认本型号支持：降噪/环境感知/自由通话、EQ、手势、VoiceAware、智能音视频、语音助手、查找耳机、自动关机、恢复出厂、OTA。后两项本项目**硬禁止**。

## 3. EQ 命令族裁决修正（关键纠偏：`0x40` + `0x41/0x42/0x43`，`0xA2` 仅作备援）

反编译全链路复查（修正此前仅看父类 `BesEQControl` 的误判）：

1. **`Tune230Control` 重写了 `getEQSettings2()` 与 `setEQSettings2()`**，直接调用 `BesEQControl` 的 BES 分包协议：
   - 预设切换：**`0x40`**（`AA 40 01 <presetByte>`：Off `00`, Jazz `01`, Vocal `02`, Bass `03`, User `04`, Rock `11`, Piano `12`, Club `13`, Studio `14`）。
   - 自定义 EQ 读取：**`0x42`**（`AA 42 01 <cat>`：`00`=Design EQ、`01`=Graphic EQ、`02`=Total EQ），耳机先回 **`AA 43 <totalLen> <chunkCount>`**，App 须回复 `chunkCount + 1` 次 **`AA 01 02 43 00`** 拉取 `<seq:0..n> <payload>` 分块，最后耳机发 **`AA 04 43`** 结束；注意 `HandleParse.parseCustomEQ()` 解码读取载荷时**不含** 4B `calibration` 字段（总计 **175 字节**）。
   - 自定义 EQ 写入：**`0x41`** 5 帧多包传输：`AA 41 B3 03`（179 字节，含 4B `calibration`，3 个分片）→ `00 <79B>` → `01 <79B>` → `02 <21B>` → **`AA 05 41`**（`EqCmdData.getFooter()`）。
2. **`0xA2`（`com.harman.commands.EQCmd`）仅作兼容备援**：
   - 未被 `Tune230Control` 重写的旧分支才走 `AA A2 <len:2 LE> 00 <category> ...`（116 字节 payload）；保留在 `EqCodec` 中仅作 `0x41` 无应答时的次级回退。
   - `BesEQControl` 仅当 `KEY_EQ_CMD_TYPE == "segmentEQ"` 才走 Realtek `A6`，230NC 不走 `A6`。

## 4. 官方 EQ 预设精确值

`assets/eq/jbl_tune230nc_tws.json`，`isUsingPresetEqPayload: "true"`，10 段频率 `32/64/125/250/500/1k/2k/4k/8k/16k` Hz，0.5 dB 精度：

| 预设 | eqID | category | cmdIndex | gains |
|---|---|---|---|---|
| Off | 1 | 0 | 0 | `[0,0,0,0,0,0,0,0,0,0]` |
| JAZZ | 2 | 1 | 1 | `[4,2,1,2.5,-1.5,-1.5,0,1,2,4]` |
| VOCAL | 3 | 2 | 2 | `[-1.5,-3,-3,1,3.5,3.5,3,1.5,0,-1.5]` |
| BASS | 4 | 3 | 3 | `[6,3,3,2.5,0.5,0,0,0,0,0]` |
| CLUB | 8 | 6 | 19 | `[1,0,1,0,0,0,-2,2,0,3]` |
| STUDIO | 9 | 7 | 20 | `[0,-2,-2,0,0,0,0,0,0,0]` |

（本项目先前版本里的「Extreme Bass」和整数近似曲线均属估算，已按此表替换。官方只有 6 个预设。）

## 5. Smart Audio & Video 写帧（与 emulator 逐字节互证）

由 `product_list.json` 的 `[a,b,c]` 三个 16 位小端数 + `FF FF` 构造 `AA 81 08 ...`：

| 模式 | 数值 | 帧 |
|---|---|---|
| normal | `[197,46,280]` | `AA8108C5002E001801FFFF` |
| audio | `[256,46,280]` | `AA810800012E001801FFFF` |
| video | `[197,46,120]` | `AA8108C5002E007800FFFF` |

前两条与 PairFree 记录的 JBL 5.37.10 emulator 帧**完全一致**。读用 `AA 82 00`、回复 `AA 83 08`。真机是否应答仍需回读确认，故保持禁用。

## 6. 全量官方控制命令（dex `Tune230Control` / `BesDeviceStatusControl`）

- **握手与 BLE 链路**：`BleDevice`/`GenServiceDiscovery` 建连后请求 `CONNECTION_PRIORITY_HIGH`，显式设置 `WRITE_TYPE_DEFAULT`，CCCD 订阅后等待稳定再下发 `AA 11 00`（读 `0x12` 名称/PID/MAC/固件/TWS）与 `AA 10 02 00 00`（时间同步）。
- **降噪与子模式**：`Tune230Control` 主走 `0x91`（`10..13` 模式、`00..02` ANC SubMode、`20..22` tag `A1` Ambient Aware 1..7 级），并在无应答时保留 `0x31`/`0x32` 旧命令回退；全状态回读兼容 `AA 21 01 30` → `AA 22 ...`。
- **硬件与状态读**：`0x25`（电量 + mV）、`0x26 01 01`（充电盒固件）、`0x94 01 02`（序列号）、`0x9B`（通话状态）、`0x21 01 41`（左右入耳佩戴）、`0x21 01 42`（贴合度检测 Check My Best Fit）。
- **手势 / 语音 / 查找 / 省电**：
  - 手势 `0x77`（`01 FF` 全读、`00` 分组写左 `06..08,0C`/右 `09..0B,0E`、`00 FF 00` 重置）。
  - `VoiceAwareCmd`：`0x98`（`01` 读、`00 <level> <on>` 新写帧及 2B 旧回退）。
  - `BeepingCmd` / 查找耳机：主命令 `AA 23 03 00 <L> <R>`，备援 `AA 36 01 <code 00..03>`；客户端前置摘下确认并 15 秒自动停止。
  - `Standby`（`0x96`）、`AutoPowerOff`（`0x33`）、`TipsTypeCmd`（`0x9C`）、`VoicePrompt`（`0x93`）、`MultiAI`（`0x34`）、`AutoPlayPause`（`0x35`）。

## 7. 真机 GATT 表（`adb bugreport` → `bluetooth_20261113_195131_08072.log`）

```
Service 0x000a-0x0011 : 65786365-6c70-6f69-6e74-2e636f6d0000
  Char 0x000b/0x000c : ...0001  prop=0x12 (READ|NOTIFY)
  Desc 0x000d        : 00002902 (CCCD)
  Desc 0x000e        : 00002901
  Char 0x000f/0x0010 : ...0002  prop=0x0c (WRITE|WRITE_NO_RESPONSE)
  Desc 0x0011        : 00002901
Service 0x0016-0x0022 : 0000fe2c (Google Fast Pair)
Service 0x0012-0x0015 : 66666666-... （厂商扩展）
```
与本项目 `commands.json` 的 GATT 配置完全一致。日志同时确认官方 app 写过 `handle 0x000d`（CCCD `0x0100`）和 `handle 0x0010`（write char）。

## 8. LE 侧地址（真机 `dumpsys bluetooth_manager`）

classic `78:5E:A2:EC:A0:CA` 为 `JBL TUNE230NC TWS`（`ACL BR/EDR:Y LE:N`），另有多个 `JBL TUNE230NC TWS-LE` 的**独立轮换 LE 地址**。因此控制端必须扫描 `-LE` 广播，不能只直连 classic MAC。本项目 `AppViewModel.connect()` 已据此改为「先扫 LE、再回退 classic」。

## 9. HCI 抓包在本 ROM 不可用

`settings put global bluetooth_hci_snoop_log 1` + `cmd bluetooth_manager disable/enable` 已生效，但 `adb bugreport` 中 `FS/data/misc/bluetooth/logs/bthci/CsLog_*/*.cfa` **122 个文件全部只有 16 字节的 btsnoop 头**，没有任何记录；也没有 `btsnoop_hci.log`。文本日志 `bluetooth_*.log` 记录了 GATT 服务发现与 read/write 事件，但不含 ATT payload 字节。

因此字节级证据改由两条路取得：① 官方 APK 静态分析（本文件）；② 本项目 app 真机主动下发 + 回读（`report/VERIFICATION.md`）。`protocol-spec/captures/parse_btsnoop.py` 保留，换设备/换 ROM 时仍可用。

## 10. 危险命令确认（黑名单依据）

官方 dash4 配置里有 `ResetToFactoryRest` 与 `OTA` 卡片；`Tune230Control.setFactoryReset()` / `stopOTA()` 存在。本项目 `CommandGuard` 对 `95`(恢复出厂)/`97`(关机)/`F0`(固件升级)/`AA`/`13`/`15` 硬拦截，UI 置灰，绝不调用。
