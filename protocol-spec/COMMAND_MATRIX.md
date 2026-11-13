# 命令置信度矩阵（Not tested → RECORDED 迁移记录）

**背景**：`Omega-Destroyer/PairFree` `docs/devices/jbl.md` 把 `TUNE230NC TWS`（`204B`/`204C`）标为 `Not tested`，含义是「从 JBL app 静态分析推断，无真机验证」；其 `PROTOCOL.md` 中 5.37.10 对 Tune 230NC 的 `AA 82 00` / `AA 81 08 ...` 来自 **emulator 构造回复**。本项目用真机 HCI snoop 差分抓包把这些推断逐项转为 `RECORDED`。

置信度枚举：

| 标记 | 含义 |
|---|---|
| `RECORDED` | 本机官方 app 5.38.2 + 用户自有耳机的真机 HCI 抓包中录到 write 帧，且有对应 notify 回复 |
| `APK_STATIC` | 本机 `jbl.stc.com` 5.38.2 反编译代码/资源中的直接证据 |
| `PRIOR_DOC` | 前人项目文档或真机录制（但非本型号/非本机版本） |
| `INFERRED` | 依据同类型号推断，无直接证据 |

**当前状态（2026-11-14 协议与功能完善后）**：`app/src/main/assets/commands.json` 已按官方 `5.38.2 Tune230Control` + `BesEQControl` 反编译结果补全至 **60 条**命令与 **31 项**黑名单（未连接实机重新录制，`WriteResult.Verified` 仍严格要求真机回读一致）。

已迁移为 `RECORDED`：`battery_read`、`anc_read`、`gesture_read_all`（+ 整套 GATT 配置与 LE 扫描策略）。
已纠正与补全为 `APK_STATIC`：
- **握手与硬件信息**：`AA 11 00` → `0x12`、`AA 10 02 00 00`、`0x26` 充电盒固件、`0x94` S/N、`0x9B` 通话、`0x21 01 41` 入耳佩戴、`0x21 01 42` 贴合度检测。
- **EQ 正确命令族**：预设切换 **`AA 40 01 <preset>`**；自定义 10 段 EQ 读取 **`AA 42 01 <cat>`**（`0x43` 多包 + `AA 01 02 43 00` + `AA 04 43`）；自定义写入 **`AA 41 B3 03`**（3 分片 + `AA 05 41`）。原 `0xA2` 保留为次级备援。
- **降噪与子模式**：`0x91`（`10..13` 主模式、`00..02` ANC SubMode、`20..22` tag `A1` Ambient Aware 1..7 级）+ `0x31/0x32` 旧命令回退 + `AA 21 01 30` 全状态交叉校验。
- **全量官方能力**：`0x77` 手势分组写与重置、`0x81/0x82/0x83` Smart Audio & Video、`0x98` VoiceAware、`0x23`/`0x36` Find My Buds（含强制摘下确认 + 15s 自动停止）、`0x35` 自动播停、`0x9C` 耳塞类型、`0x96` 待机保留降噪、`0x33` 自动关机、`0x93` 语音提示偏好、`0x34` Multi-AI。

| # | 功能 | 命令 / 帧 | 当前置信度 | 证据来源 | 待补充证据 | 迁移状态 |
|---|---|---|---|---|---|---|
| 1 | 读电量（左右耳 + 充电盒 + mV） | `AA 25 01 00` → `AA 25 ...` | `RECORDED` | 本机真机回读 | — | ✅ **RECORDED** |
| 2 | 读设备信息 / 握手 | `AA 11 00` → `0x12`, `AA 10 02 00 00` | `APK_STATIC` | `Tune230Control` / `GenServiceDiscovery` | 真机链路实测 | ⏳ 待真机复测 |
| 3 | 读全状态 / 佩戴 / 贴合 | `AA 21 01 <30\|41\|42>` → `AA 22 ...` | `APK_STATIC` | `BesDeviceStatusControl` | 真机链路实测 | ⏳ 待真机复测 |
| 4 | 读降噪/环境音模式 | `AA 91 01 11` → `AA 91 07 12 ...` | `RECORDED` | 真机本 app 回读 | — | ✅ **RECORDED(5.38.2)** |
| 5 | 写降噪/环境音/子模式/AA等级 | `AA 91 07 10 ...`, `AA 91 01 13`, `AA 91 02 00 <submode>`, `AA 91 03 20 A1 <1..7>` | `APK_STATIC` | `CmdGen.genSetANCModes` / `AdvancedAncCmd`；已修 `WRITE_TYPE_DEFAULT` + `HIGH` priority + `31/32` 备援 | 真机回读确认 | ⏳ 待真机复测 |
| 6 | 切换官方 EQ 预设 | `AA 40 01 <presetByte>` | `APK_STATIC` | `Tune230Control` / `BesEQControl`（主路径） | 真机回读 `AA 21 01 34` / `AA 42` | ⏳ 待真机复测 |
| 7 | 读自定义 10 段 EQ | `AA 42 01 <00\|01\|02>` → `AA 43` + `AA 01 02 43 00` + `AA 04 43` | `APK_STATIC` | `BesEQControl` + `HandleParse.parseCustomEQ`（175B） | 真机回读确认 | ⏳ 待真机复测 |
| 8 | 写自定义 10 段 EQ | `AA 41 B3 03` + chunks `00..02` + `AA 05 41`（备援 `0xA2`） | `APK_STATIC` | `CmdGen.generateSetCustomEqCmd` + `EqCmdData`（179B） | 真机写入 + `0x42/0x43` 回读 | ⏳ 待真机复测 |
| 9 | 读手势 | `AA 77 02 01 FF` → `AA 77 11 02 ...` | `RECORDED` | 真机本 app 回读 | — | ✅ **RECORDED(5.38.2)** |
| 10 | 写手势分组与重置 | `AA 77 09 00 ...`, `AA 77 03 00 FF 00` | `APK_STATIC` | `GestureCmd` / `Tune230Control` | 真机回读确认 | ⏳ 待真机复测 |
| 11 | Find My Buds | `AA 23 03 00 <L> <R>`（备援 `AA 36 01 <code>`） | `APK_STATIC` | `BeepingCmd` + 安全弹窗 + 15s 自动停止 | 真机蜂鸣实测 | ⏳ 待真机复测 |
| 12 | Smart Audio & Video | `AA 82 00` → `AA 83`, `AA 81 08 ...` | `APK_STATIC` | `product_list.json` + `Tune230Control` | 真机回读确认 | ⏳ 待真机复测 |
| 13 | VoiceAware | `AA 98 01 01` → `AA 98 03 02 <lvl> <on>`, `AA 98 03 00 ...` | `APK_STATIC` | `VoiceAwareCmd` | 真机回读确认 | ⏳ 待真机复测 |
| 14 | 佩戴/电源/语音包/助手 | `0x35`, `0x9C`, `0x96`, `0x33`, `0x93`, `0x34`, `0x26`, `0x94`, `0x9B` | `APK_STATIC` | `Tune230Control` / `TipsTypeCmd` / `CmdGen` | 真机逐项回读确认 | ⏳ 待真机复测 |
| 15 | 危险命令黑名单 | `95/97/F0/AA/13/15` 等 31 项 | `PRIOR_DOC` | `CommandGuard` 编译期 + 写前拦截 | — | ✅ 已实现 |

## 迁移规则

1. 每条命令只有在 `captures/diff_*.csv` 中有**可复现的 write 帧 + 对应 notify 回复**，且在 `report/VERIFICATION.md` 中有「下发 → 回读一致 → 官方 app 状态同步」记录，才可从 `PRIOR_DOC`/`INFERRED` 升为 `RECORDED`。
2. 迁移时在本表追加：`RECORDED(5.38.2)`、`CSV 文件名:行号`、回读比对结论、官方 app 是否同步。
3. 抓包未覆盖或固件不应答的，保持原标记并**写明原因**，UI 置灰。绝不允许为了「看起来完成」而批量改标记。
4. 真机帧与前人文档不一致时，以真机为准，在「证据来源」列记录差异及 `captured_from_version`。

## 已知版本差异

- 前人文档基于 JBL Headphones **5.37.10**（部分为 emulator）；本机为 **5.38.2**。任何不一致以 5.38.2 真机抓包为准。
