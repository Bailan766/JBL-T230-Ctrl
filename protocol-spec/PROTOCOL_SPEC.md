# JBL Tune 230NC TWS 控制协议规格（工作稿）

> 状态：本文是**实施起点**，不是最终结论。每条命令的验证状态见 `COMMAND_MATRIX.md`。
> 目标设备：JBL Tune 230NC TWS，JBL app 内 product id `0x204B` / `0x204C`。
> 官方 app 版本（本机）：`jbl.stc.com` 5.38.2。设备侧配对名 `JBL TUNE230...`。

## 1. 协议族判定

Tune 230NC TWS 属于 **Harman / BES 老协议（AA 帧，BLE GATT）**。

判定依据：
- `Omega-Destroyer/PairFree` `docs/devices/jbl.md`：`TUNE230NC TWS`，Product `204B` `204C`，Connection `Bluetooth LE`，Status `Not tested`。
- `AhmadRun/jbl-control`（MIT）把 `Tune 230NC/255NC` 与 Wave Buds 2 / Tune Flex 2 归为同一协议族，并说明 Gen-3（Tune 680/780NC、Live x80NC）使用**另一套尚未解码的新协议**。
- PairFree `docs/jbl/PROTOCOL.md` 记录 JBL Headphones 5.37.10 在 emulator 中对 **Tune 230NC** 发出的 Smart Audio & Video 帧是 BES 变体 `AA 82 00` / `AA 81 08 ...`，而非 Protocol 3 的 `80`。

**排除**：Protocol 4（帧头 `DD00`/`DD01`，Tune 680/780NC 等）、Airoha RACE（帧头 `05`，Live Buds 3 等）、Realtek segment EQ `A6`（Tune 520BT 系）。

✅ 2026-11-13 更新：本机官方 5.38.2 APK 已反编译，`Tune230Control` / `EQCmd` / `BesEQControl` 等类提供了 `APK_STATIC` 一手证据；真机控制链路已连通并读到电量/降噪/手势帧（`RECORDED`）。详见 `reverse/notes/STATIC_FINDINGS.md` 与 `report/VERIFICATION.md`。**写入类命令仍未验证**。

## 2. 传输层

| 项 | 值 |
|---|---|
| 控制通道 | Bluetooth LE GATT（与 A2DP/HFP 音频的经典蓝牙链路分离） |
| Service UUID | `65786365-6c70-6f69-6e74-2e636f6d0000`（ASCII "excelpoint.com"） |
| Notify characteristic | `65786365-6c70-6f69-6e74-2e636f6d0001`，CCCD `00002902-...` |
| Write characteristic | `65786365-6c70-6f69-6e74-2e636f6d0002`，**write-with-response** |
| Raw characteristic | `...0003`，仅 Protocol 4 安全模式使用，本项目不用 |
| MTU | 官方 app 请求 512 |
| 加密 / 握手 | 无（JblTray 在 Tune Flex 2 上观测） |
| 每条 notification | 一个完整帧 |
| 并发连接 | **同一时刻只允许一条控制连接**；本 app 持有期间官方 app 无法连，音频不受影响 |
| 命令节奏 | 一次只发一条，最多等 1000 ms，收到任一 parsed reply 即结束等待 |

## 3. LE 侧发现（rotating address 问题）

音频走 classic 配对，控制端 BLE 侧可能是**独立且会轮换的随机地址**。

1. **优先直连 classic MAC**：`JblTray` 观测到 Tune Flex 2 的 LE 侧直接在 classic 地址上应答。先 `connectGatt(classicMac)`，10 秒不通再扫描。
2. **扫描兜底（最多 10 秒）**：
   - manufacturer data：Harman company id `0x0ECB`
   - service data：`FDDF`
   - 广播名形如 `"<name>-LE"`
   - classic advert 小端布局：bytes 0–1 product id（`204B`/`204C`）、byte 2 colour、bytes 3–6 手机蓝牙名 CRC-16/ARC、bytes 7–8 classic address CRC、byte 9 flag
   - JBL app 选择「product id 已知 **且** 带本手机 name CRC」的广告
3. **Fast Pair 兜底**：若地址已轮换且扫描不到，通过 Google Fast Pair 消息流读取左右耳/充电盒电量与当前 LE 地址（思路来自 `ncr/omarchy-headphones`，MIT）。
4. Android 权限：`BLUETOOTH_SCAN`（声明 `neverForLocation`）、`BLUETOOTH_CONNECT`；仅在用户打开本型号控制页时申请 Nearby devices。

## 4. 帧格式

```
AA <command> <length> <payload>
```

- 起始字节 `0xAA`，**无校验和**。
- `length` 通常为 **1 字节**，表示 payload 长度。
- 例外 A：command `0x06` 与 **`0xA2`（非 `subReq=0x01`）** 使用 **2 字节小端** length。
- 例外 B：BES 多包自定义 EQ 头帧 `AA 41 <totalLen> <chunkCount>` / `AA 43 <totalLen> <chunkCount>` 固定 4 字节，紧随 `<seq 00..0F> <79B分片>` 无 `AA` 头数据包，并以固定 3 字节 `AA 04 43`（耳机读完）/ `AA 05 41`（App 写完）收尾。
- 回复编号映射：
  - `0x11` → `0x12`
  - `0x21` → `0x22`
  - `0x42` → `0x43`
  - `0x82` → `0x83`
  - 其余（`0x25`、`0x91`、`0x06`、`0x77`、`0x98`、`0x96`、`0x9C`、`0x93`、`0x23` …）**用同编号回复**
- ACK 帧：`AA 00 <len> <command> <status>`。**status 语义未知，本项目不采信 ACK**，一律用「写后回读」判定是否生效。
- BLE 上每个 notification 即一帧；需按 command id 匹配，因为可能出现非请求的主动 notification。

## 5. 命令速查

完整机器可读版本见 `app/src/main/java/com/jblt230/ctrl/protocol/commands.json`（Kotlin 侧同名 `CommandTable.kt` 消费）。

### 5.1 电量与设备信息

| 命令 | 帧 | 说明 |
|---|---|---|
| Battery read `0x25` | `AA 25 01 00` | 回复同为 `25`。payload：`01`、secondary/primary 是否充电与电量、case 是否充电与电量、电压；>100 表示未知。JBL app 把 secondary 当左、primary 当右 |
| Feature records `0x06` | `AA 06 <len:2 LE> <type> <records>` | `type`：`00` set / `01` read / `02` reply。read 列出 2 字节大端 feature id；set/reply 每 feature 给 id、length、value |
| 常用 feature id | `02` product id、`05` firmware、`0B` auto power-off、`0C` spatial sound、`11` auto play/pause、`1A`/`1B` 左右耳电量、`1C` 充电盒电量 | 电量值为 `(charging, level)` |
| 示例 | `AA 06 03 00 01 00 0C` | 读 spatial sound（JblTray 录制） |

### 5.2 状态读

| 命令 | 帧 | 说明 |
|---|---|---|
| Status read `0x21` → `0x22` | `AA 21 01 <type>` | `30`=all、`31`=降噪、`32`=环境音、`33`=自动关机、`34`=EQ 预设、`35`=语音助手、`38`=auto play/pause、`39`=TWS、`41`=in-ear test、`42`=seal test |
| 旧型号注意 | 优先读 `30`（all） | FajkPes 发现 Tune 720BT 忽略 single-type reads |
| 全状态回复示例 | `AA 22 0D 30 00 00 9E E6 00 01 00 00 00 01 1E 00` | 降噪 off、环境音 off、自动关机 30 分钟、auto play/pause off |

### 5.3 降噪 / 环境音

主用 `0x91`，首个 payload byte 是 sub-command：`10` set、`11` read、`12` reply、`13` all off。随后是 mode/value 对：`01` noise cancelling、`02` Ambient Aware、`03` TalkThru。

| 动作 | 帧（JblTray 在 Tune Flex 2 上录制） |
|---|---|
| read | `AA 91 01 11` |
| ANC on | `AA 91 07 10 01 01 02 00 03 00` |
| Ambient Aware on | `AA 91 07 10 01 00 02 01 03 00` |
| TalkThru on | `AA 91 07 10 01 00 02 00 03 01` |
| all off | `AA 91 01 13` |

- 回复为 `12`（也见过 `10`），带相同 mode/value 对；非 0 的 mode 为 on，全 0 为 off。
- `0x20`–`0x22`：ANC level / adaptive / auto compensation（`AA 91 01 21` read、`AA 91 03 20 <type> <0|1>` write）。类型 `01` adaptive ANC、`08` auto compensation、`04` manual level、`A1` Ambient Aware level。**本项目标记为未验证，先只做只读探测。**
- 更老的 `0x31` / `0x32`：
  - `AA 31 01 <00 off | 01 on>` noise cancelling
  - `AA 32 01 <00 off | 01 TalkThru | 02 Ambient Aware>` ambient
  - 若真机对 `91` 无应答，回退尝试这两条（仍需回读确认）。

### 5.4 EQ（**已纠偏：`0x40` + `0x41/0x42/0x43`，`0xA2` 仅作备援**）

2026-11-14 完整核查官方 5.38.2 `Tune230Control` + `BesEQControl` + `HandleParse`：

| 路径 | 帧与协议流程 | 用途与说明 |
|---|---|---|
| `0x40` 预设切换 | `AA 40 01 <presetByte>` | 切换 Built-in / User EQ 槽位（`00`=Off, `01`=Jazz, `02`=Vocal, `03`=Bass, `04`=User, `11`=Rock, `12`=Piano, `13`=Club, `14`=Studio）；回读 `AA 21 01 34` 或 `0x42` |
| `0x42` / `0x43` 多包读 | `AA 42 01 <catOrdinal>`（`00`=Design, `01`=Graphic, `02`=Total） | 收到 `AA 43 <totalLen> <chunkCount>` 后，App 发送 `chunkCount + 1` 次 **`AA 01 02 43 00`** 触发分片接收，最后收 `AA 04 43`；解码 `175` 字节 payload（读布局无 4B calibration） |
| `0x41` 多包写 | `AA 41 B3 03` + chunks `00..02`（79/79/21B）+ `AA 05 41` | 179 字节写布局（含 4B `calibration`），10 段 `32..16k Hz` ±6 dB（0.5 dB 步进），写后通过 `0x42/0x43` 回读曲线比对确认 |
| `0xA2`（备援） | `AA A2 02 01 <cat>` / `AA A2 <len:2LE> 00 ...` | 仅作为 `0x40`/`0x41` 无应答时的兼容备援 |

### 5.5 手势

`0x77`，一次写某一侧全部 gestures：

```
写:   AA 77 <len> 00 (<gesture> <action>)...
全读: AA 77 02 01 FF
回复: AA 77 <len> <01|02> (<gesture> <action>)...
```

| 侧 | tap | double | triple | hold |
|---|---|---|---|---|
| 左 | `06` | `07` | `08` | `0C` |
| 右 | `09` | `0A` | `0B` | `0E` |

action 白名单：`0A` play/pause、`05` next、`06` prev、`A1` 手机助手、`04` TalkThru、`01` vol up、`02` vol down、`0B` noise cycle、`00` none。

### 5.6 全量扩展功能（`Tune230Control`）

| 功能 | 命令 | 说明 |
|---|---|---|
| 握手与时间同步 | `AA 11 00` → `0x12`, `AA 10 02 00 00` | 订阅 CCCD 并延迟稳定后下发，读取名称/PID/MAC/固件/TWS |
| 硬件与佩戴状态 | `0x26 01 01`（盒固件）、`0x94 01 02`（S/N）、`0x9B`（通话）、`AA 21 01 41`（入耳）、`AA 21 01 42`（贴合度） | Best Fit：`0`=良好，非 0=需调整 |
| ANC SubMode & AA 等级 | `AA 91 02 00 <0..2>` / `AA 91 03 20 A1 <1..7>` | 读取 `AA 91 01 02` 与 `AA 91 01 21` |
| Find My Buds | 主 `AA 23 03 00 <L:0\|1> <R:0\|1>`，备援 `AA 36 01 <00..03>` | 客户端强制弹出摘下确认，并 15 秒自动停止 |
| Smart Audio & Video | 读 `AA 82 00` → `AA 83 06 ...`；写 `AA 81 08 ...` | Normal `[197,46,280]` / Audio `[256,46,280]` / Video `[197,46,120]` |
| VoiceAware | 读 `AA 98 01 01`；写 `AA 98 03 00 <lvl 0..3> <on 0\|1>` | 含 2 字节旧固件写备援 `AA 98 02 00 <lvl>` |
| 佩戴与省电/语音 | `0x35` 自动播停、`0x9C` 耳塞类型、`0x96` 待机保留 ANC、`0x33` 自动关机、`0x93` 提示音偏好、`0x34` Multi-AI | 所有写入经 `CommandGuard` 校验并在回读一致后才标记 `REAL` |

## 6. 安全约束

- 硬黑名单见 `DANGEROUS_COMMANDS.md`，在 `CommandGuard` 中同源实现。
- **绝不因收到 ACK 就判定成功**：每个 set 必须回读并比对期望值，一致才标 `真实`；超时/不应答/不符 → `未验证` + UI 置灰。
- 同一时刻只维持一条 GATT 控制连接；进入控制页才连，退出/后台立即 `disconnect() + close()`。
- 抓包与日志脱敏：真实 MAC、LE 地址、手机名 CRC、序列号统一替换为占位符；原始日志仅本地保存、不上传、可随时删除。

## 7. 开放问题（需真机抓包闭环）

1. LE 侧是在 classic MAC 上应答，还是独立轮换地址？
2. Tune 230NC 的 EQ 到底走 `A2` 还是 `41-43`？
3. `AA 82 00` / `AA 81 08 ...` 在真机固件上是否有回复？
4. `0x91` 是否有应答，还是必须回退 `0x31`/`0x32`？
5. `0x25` 与 `0x06 feature 1A/1B/1C` 哪个能读到左右耳 + 充电盒？
6. Find My Buds 是 `0x36` 还是 `0x23`？
7. 手势 `0x77` 是否可读写？
8. VoiceAware、Smart Audio & Video、自适应 ANC 在本固件上是否被支持？
