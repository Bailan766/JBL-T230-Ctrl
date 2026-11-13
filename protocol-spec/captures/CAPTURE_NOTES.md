# 真机差分抓包操作清单（待设备重新连接后执行）

状态：⚠️ **设备已连接且 snoop 已开启，但本 ROM 不产生字节级 HCI 日志**（122 个 `.cfa` 全为 16 字节空头）。以下清单保留给换 ROM / 换设备的场景；本轮证据改由官方 APK 静态反编译 + 本 app 真机主动回读取得，见 `report/VERIFICATION.md`。

## 前置条件

1. 数据线重新连接手机，解锁屏幕，在「允许 USB 调试？」弹窗点允许。
2. `adb devices -l` 应显示 `device`（非 `unauthorized` / `offline`）。
3. 耳机 `JBL TUNE230...` 保持已配对并连接，最好播放一段音乐（A2DP 活跃但不影响控制通道抓包）。
4. **关闭本 app**（`JBL T230 Ctrl`），确保只有官方 JBL Headphones 持有唯一控制连接。

## 开启 HCI snoop

路径 A（界面，推荐）：设置 → 关于本机 → 连点「版本号」7 次 → 开发者选项 → 打开「启用蓝牙 HCI 信息收集日志」。

路径 B（命令）：
```
adb shell settings put global bluetooth_hci_snoop_log 1
adb shell setprop persist.bluetooth.btsnooplogmode full   # 部分 realme ROM 需要
```
然后**关闭再打开一次蓝牙**使其生效。改动可随时用 `settings put global bluetooth_hci_snoop_log 0` 撤销。

## 操作清单

每一项：**记下手机当前时间（HH:MM:SS）→ 在官方 app 只切换这一项 → 等 2 秒 → 重复 2~3 次去抖 → 记下一项**。全程对用户可见，可随时喊停。

| 序号 | 操作 | 记入 notes 的标签 |
|---|---|---|
| 0 | 打开官方 app，停在首页（触发初始全状态读、电量读） | `initial status read` |
| 1 | EQ 依次切 Jazz → Vocal → Bass → Club → Studio → Extreme Bass → Off | `EQ preset Jazz` … `EQ preset Off` |
| 2 | 自定义 EQ 拖一条曲线并保存 | `EQ custom curve save` |
| 3 | ANC 开 → 关 → 开 | `ANC on` / `ANC off` |
| 4 | ANC 强度 / 自适应降噪（若界面有） | `ANC level` / `adaptive ANC` |
| 5 | Ambient Aware 开 → 关 | `Ambient Aware on` / `Ambient Aware off` |
| 6 | TalkThru 开 → 关 | `TalkThru on` / `TalkThru off` |
| 7 | VoiceAware 调档 | `VoiceAware level` |
| 8 | 改一个手势（例如左耳双击改为音量+） | `gesture write left double` |
| 9 | 查找耳机左 / 右（**先取下耳机，音量较大**） | `Find My Buds left` |
| 10 | 语音助手切换 | `voice assistant` |
| 11 | Smart Audio & Video：Audio → Video → Normal | `Smart AV Audio` / `Smart AV Normal` |
| 12 | 关掉官方 app，再打开一次（触发重连与再次全状态读） | `final status read` |

把实际时间写成如下格式追加到本文件末尾，供 `parse_btsnoop.py --notes` 自动标注：

```
18:03:12 | initial status read
18:03:40 | EQ preset Bass
18:04:05 | ANC on
...
```

## 拉取与导出

```
adb shell ls -l /data/misc/bluetooth/logs/
adb pull /data/misc/bluetooth/logs/btsnoop_hci.log protocol-spec/captures/raw/
# 若路径不存在：
adb bugreport reverse/bugreport.zip   # 从 FS/data/misc/bluetooth/logs/ 取日志

python protocol-spec/captures/parse_btsnoop.py \
  --in  protocol-spec/captures/raw/btsnoop_hci.log \
  --out protocol-spec/captures \
  --notes protocol-spec/captures/CAPTURE_NOTES.md
```

产出 `frames_all.csv` 与 `diff_eq.csv` / `diff_anc.csv` / `diff_ambient.csv` / `diff_gesture.csv` / `diff_find.csv` / `diff_battery.csv`。解析器只保留 ATT 里的 `AA ...` 帧，HCI 地址与配对材料一律丢弃。

## 抓包后必须回答的问题

见 `PROTOCOL_SPEC.md` §7 的 8 个开放问题。重点是：
1. LE 侧是否在 classic MAC 上应答，还是独立轮换地址？
2. **EQ 到底走 `A2` 还是 `41-43`？**（决定能否开放 EQ 写入）
3. `AA 82 00` / `AA 81 08 ...` 真机是否有 `83` 回复？
4. `0x91` 是否应答，还是必须回退 `0x31`/`0x32`？
5. 电量走 `0x25` 还是 `0x06 feature 1A/1B/1C`？
6. Find My Buds 是 `0x36` 还是 `0x23`？

回答后更新 `COMMAND_MATRIX.md`（`Not tested → RECORDED`，附 CSV 行号）、`app/src/main/assets/commands.json`（`enabled` / `confidence` / `frame`），然后重新构建 APK。

## 收尾

- 关闭 HCI snoop：`adb shell settings put global bluetooth_hci_snoop_log 0`
- `raw/` 仅本地保留；用户可随时要求删除。
- 确认耳机设置没有被破坏（尤其不要触发恢复出厂 / 固件升级 / 关机）。
