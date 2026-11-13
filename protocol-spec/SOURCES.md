# 来源与许可证登记（clean-room 证据）

本项目为个人设备互操作性研究，非 JBL / Harman 官方产品，与 JBL、Harman International、Samsung 无任何关联、授权或背书。

## 使用原则

1. **只取客观互操作性事实**：BLE GATT UUID、帧起始字节、命令号、参数编码、设备 product id、EQ 频段定义等，属于协议事实，而非受著作权保护的表达。
2. **不复制、不移植、不粘贴任何 GPL 源码**；不把 GPL 项目的原始 `commands.json` / `devices.json` 文件整体纳入本仓库。所有 Kotlin 实现均为独立重写，字节常量在 `protocol/commands.json` 中逐条重新录入并登记出处。
3. 优先引用 **MIT / BSD** 许可的项目做交叉验证；GPL 项目仅用于核对协议事实与了解架构取舍。
4. app 不复制官方 APK 的图标、图片、字符串、layout；界面与图标为自绘/自写。

## 参考项目

| 项目 | 许可证 | 本项目从它取了什么 | 是否复制代码 |
|---|---|---|---|
| `Omega-Destroyer/PairFree` | GPL-3.0-or-later | `docs/devices/jbl.md` 中 `TUNE230NC TWS` product id `204B/204C`、BLE 通道与功能清单；`docs/jbl/PROTOCOL.md` 中 Harman/BES 的 GATT UUID、帧格式、命令号与危险命令清单；`data/jbl/devices.json` 的 EQ 族字段（只读核对） | **否** |
| `AhmadRun/jbl-control` | MIT | 明确列出 `Tune 230NC/255NC` 同协议；功能面（左右耳/充电盒电量、ANC/Ambient/TalkThru、10 段 EQ、VoiceAware、Find My Buds）与「功能按设备应答动态发现」的设计思想；BLE 控制服务与单一控制连接约束 | 否 |
| `Asklios/jbl-vibe-buds-2-gnome` | BSD-3 | vendor GATT service `65786365-6c70-6f69-6e74-2e636f6d0000`、notify `...0001`、write `...0002`、write-with-response、`AA <cmd> <len> <payload>` 帧格式与 `0x06` 两字节长度特例 | 否 |
| `NeoHBz/JBLProtocol4` | MIT | 分阶段逆向方法论（recon → HCI 抓包 → 静态交叉验证 → spec → 离线校验 → 只读原型 → 低风险写入）、逐帧置信度标注、危险命令白名单、抓包转表格思路。**注意：其 Protocol 4 不适用于 Tune 230NC** | 否 |
| `emperorbug46/JblTray` | MIT | Tune Flex 2 上「LE 侧在 classic 地址应答、命令无加密无握手」的观测；`AA 91 ...` 模式帧 | 否 |
| `FajkPes/JBL-Headphones-App` | 见仓库 | VoiceAware / 语音提示语义、`AA 22 0D 30 ...` 全状态回复实例 | 否 |
| `ncr/omarchy-headphones` | MIT | 通过 Google Fast Pair 消息流读取电量与当前 LE 地址的思路 | 否 |
| `HellzYeahh/earshot` | 见仓库 | Android 端多品牌耳机的 Kotlin/Compose 架构取向 | 否 |
| `VoidedKN0X/SoundCtrl` | GPL-3.0 | JBL 专用 Android app 的功能组织方式（仅概念参考） | 否 |

## 一手证据（优先级最高）

- 本机手机 realme RMX3888 / Android 16 上安装的官方 `jbl.stc.com` **v5.38.2** APK 静态分析（`reverse/notes/STATIC_FINDINGS.md`）。
- 官方 `jbl.stc.com` 5.38.2 的 **dex 反编译**（jadx 1.5.6）与 assets 配置，见 `reverse/notes/STATIC_FINDINGS.md`。据此裁决了 EQ 命令族（`A2`）、拿到官方精确预设曲线与 Smart A&V 帧。
- 用户自有 JBL Tune 230NC TWS 的 **真机主动下发/回读**（本 app 协议日志 + `adb bugreport` 文本 GATT 日志），见 `report/VERIFICATION.md`。据此修正了电量帧布局并确认 LE 侧地址策略。
- 注：本 realme ROM 的 HCI snoop 二进制日志不可用（`.cfa` 空文件），因此没有 `diff_*.csv`；凡与前人文档冲突，一律以官方 5.38.2 反编译 + 本机真机回读为准，并在 `COMMAND_MATRIX.md` 记录差异。

## 本项目许可证

MIT（见 `LICENSE`，若未附则以本声明为准）。上述第三方项目各自保留其原许可证；本声明不构成对它们的再许可。
