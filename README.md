# JBL T230 Ctrl

针对 **JBL Tune 230NC TWS** 的非官方独立控制 App（Android APK），用于研究与替代手机上的官方 `jbl.stc.com` JBL Headphones app。与 JBL / Harman International / Samsung 无任何关联、授权或背书。

> ⚠️ **当前状态（2026-11-14 协议修复与功能补全后）**：已修正 EQ（`0x40` 预设 + `0x41/0x42/0x43` 多包自定义 EQ，`0xA2` 仅备援）与 ANC/BLE 写入链路（`CONNECTION_PRIORITY_HIGH`、显式 `WRITE_TYPE_DEFAULT`、CCCD 300ms 等待、`AA 11 00`/`AA 10 02 00 00` 握手、双路径 `0x91` + `0x31/0x32`），并实现官方 `Tune230Control` 全量功能（含手势改写与重置、Smart Audio & Video、VoiceAware、佩戴贴合度检测、待机 ANC/自动关机/提示音偏好/Multi-AI、安全确认 + 15s 自动停止的查找耳机）。当前无连接手机，写入真机回读尚待上机实测。

## 快速开始

```bat
:: 构建（使用本机已有的 JDK 17 + 缓存的 Gradle 8.9，不需要联网下载 JDK）
gradlew.bat --version
gradlew.bat :app:assembleDebug

:: 离线校验命令表
python protocol-spec\verify\check_frames.py

:: 单元测试（绕开本机 Gradle Test Executor 的环境问题）
python protocol-spec\verify\run_unit_tests.py

:: 装机（本轮已在真机执行，Success）
adb install -r build\outputs\jbl-t230-ctrl-debug.apk
```

已构建产物：`build/outputs/jbl-t230-ctrl-debug.apk`（约 24 MB，debug 签名，包名 `com.jblt230.ctrl`）。

## 目录

```
app/                      自研 Kotlin + Compose 源码
  src/main/assets/        commands.json（60 条命令 + 31 项黑名单）、eq_presets.json
  ble/                    BleScanner / GattTransport / FrameCodec / EqCodec / CommandQueue / CommandGuard
  protocol/               CommandTable（加载命令表与置信度）
  system/                 SystemBaseline / BatteryReflection（标准 API 保底）
  data/                   DeviceState / AppViewModel / EqPresetStore
  ui/                     概览 / 声音 / 手势 / 更多 / 设置 + StatusBadge 四态徽标
protocol-spec/            协议规格、来源与许可证、置信度矩阵、危险命令、抓包与校验工具
  captures/               btsnoop 解析脚本、操作清单、脱敏规则、raw/（本地，不入库）
  verify/                 帧校验、单测运行脚本
reverse/                   官方 APK 与反编译产物（本地，不入库）
report/                    分析报告、真机验证清单、系统层评估
build/outputs/             可安装 APK
```

## 三条不能破的原则

1. **绝不假装功能生效**：ACK 帧不采信；每个 set 必须回读并比对期望值，一致才标「真实」，否则置灰并写明原因。
2. **前人标 `Not tested` 的不算已验证**：`TUNE230NC TWS` 在 PairFree 中就是 `Not tested`，5.37.10 的 emulator 回复是构造的。只有本机 5.38.2 + 用户耳机的真机回读才算 `RECORDED`。
3. **危险命令硬拦截**：恢复出厂 `95`、关机 `97`、固件升级 `F0` 等在 `CommandGuard` 中于 `write()` 前抛异常，且 UI 置灰；多包 EQ 续传分片经 `assertAllowedEqChunk` 顺序号校验。

## 已知限制

- 同一时刻只允许一条 BLE 控制连接；本 app 持有期间官方 app 无法连（A2DP 音频不受影响），退出即释放。
- 本轮新增与修复的所有写入路径均已对齐 `5.38.2 Tune230Control` / `BesEQControl` 字节定义，但在无实机连接前仍坚持「写入未回读即标未验证」原则，待真机连通并回读一致后自动升为 `真实`。
- 系统层 `getBatteryLevel()` 是隐藏 API，Android 16 可能拒绝；失败时显示「不可用」，绝不填假值。
- app 不申请 `INTERNET`，无账号、无统计、无数据回传；抓包仅在本地保存且脱敏。

## 把「未验证」变成「真实」

见 `protocol-spec/captures/CAPTURE_NOTES.md`（抓包操作清单）与 `report/ANALYSIS_REPORT.md` §10（6 步闭环）。核心是：设备重新连接 → 开 HCI snoop → 在官方 app 逐项切换设置 → 导出脱敏 `diff_*.csv` → 裁决命令族并更新 `commands.json` → 重建装机 → 双向验证官方 app 状态是否同步。

## 许可证与来源

本项目 MIT；仅取客观互操作事实，未复制任何 GPL-3.0 源码或官方资源。逐条出处与 clean-room 声明见 `protocol-spec/SOURCES.md`。
