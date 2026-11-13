# 抓包产物说明与脱敏规则

## 目录

| 路径 | 内容 | 是否入库 |
|---|---|---|
| `raw/btsnoop_hci.log` | 手机原始蓝牙 HCI 日志 | **否**，仅本地保存，`.gitignore`，用户可随时要求删除 |
| `diff_eq.csv` | 官方 app 切换 EQ 预设/自定义曲线时的 ATT write/notify 差分 | 是（脱敏后） |
| `diff_anc.csv` | ANC 开/关/等级/自适应 | 是（脱敏后） |
| `diff_ambient.csv` | Ambient Aware / TalkThru / VoiceAware | 是（脱敏后） |
| `diff_gesture.csv` | 触控手势写入 | 是（脱敏后） |
| `diff_find.csv` | 查找耳机 | 是（脱敏后） |
| `diff_battery.csv` | 电量与全状态读 | 是（脱敏后） |
| `CAPTURE_NOTES.md` | 时间戳 ↔ 手机操作映射 | 是 |

## CSV 字段

`ts_relative, direction, handle, frame_hex, command, length, payload, note`

- `direction`: `W`(ATT Write Command/Request 到 write characteristic) / `N`(Handle Value Notification 来自 notify characteristic)
- `handle`: 脱敏后保留逻辑句柄标签（如 `WRITE_0002` / `NOTIFY_0001`），不记录真实连接句柄
- `frame_hex`: 仅保留 `AA ...` 协议帧；不含链路层地址信息
- `command`: 帧中第二个字节
- `note`: 对应的手机操作（如 `EQ preset Bass`）

## 脱敏规则（导出脚本强制执行）

| 原始值 | 替换为 |
|---|---|
| 耳机 classic MAC（如 `78:5E:A2:EC:A0:CA`） | `<CLASSIC_MAC>` |
| 轮换的 LE 随机地址 | `<LE_MAC>` |
| 手机蓝牙名及其 CRC 值 | `<PHONE_NAME>` / `<PHONE_NAME_CRC>` |
| 设备序列号 / product id 之外的标识 | `<SERIAL>` |
| 配对记录中的其它设备 | `<OTHER_DEVICE>` |

**导出流程**：解析器只从 btsnoop 记录中提取 ATT PDU 的 payload，按 `AA` 帧边界切分，过滤出 `AA <cmd> <len> <payload>` 结构的帧，绝不把完整 HCI 包（含地址、随机数、密钥材料）写入 CSV。原始日志不上网、不上传、不随交付物分发。

## 状态

⚠️ **本 ROM 无法采集字节级 HCI 日志**（2026-11-13 实测）：设备已连接、HCI snoop 已开启并重启蓝牙栈，但 `adb bugreport` 中 122 个 `*.cfa` 全部只有 16 字节 btsnoop 头，没有记录；无 `btsnoop_hci.log`。文本日志 `bluetooth_*.log` 仅含 GATT 事件、不含 ATT payload。

因此 `diff_*.csv` 未生成。字节级证据改由 ① 官方 5.38.2 APK 静态反编译（`reverse/notes/STATIC_FINDINGS.md`）与 ② 本 app 真机主动下发/回读（`report/VERIFICATION.md`）取得，并已把 3 条命令 + GATT 升级为 `RECORDED`。`parse_btsnoop.py` 与 `CAPTURE_NOTES.md` 保留，换 ROM / 换设备时可直接复用。
