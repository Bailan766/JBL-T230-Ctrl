# JBL T230 Ctrl — 二次开发指南

本文件面向继续开发本项目的工程师。项目已从原始工作区迁移到 `D:\JBL-T230-Ctrl-20261113`，可独立编译、测试、装机。

## 1. 环境要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 17（Eclipse Temurin / Adoptium 17.0.19+） | `gradle.properties` 已配置 `org.gradle.java.home`，若你的 JDK 路径不同需改这一行或设 `JAVA_HOME` 环境变量 |
| Android SDK | compileSdk 35 / minSdk 31 | 需安装 Android SDK 35 + Build-Tools 34.0.0 |
| Gradle | 8.9 | 由 `gradle/wrapper/gradle-wrapper.jar` 自动下载，**无需系统安装 Gradle** |
| Kotlin | 2.0.21 | 由 AGP 8.7.0 插件管理 |

首次运行 `gradlew.bat` 时，Gradle wrapper 会从 `services.gradle.org` 下载 8.9 发行版（约 130 MB）到 `~/.gradle/wrapper/dists/`。**仅首次需要联网**；之后所有依赖（AGP、Kotlin、Compose BOM、AndroidX…）会从 Maven 仓库下载并缓存到 `~/.gradle/caches/`。

> 本机 `repo.maven.apache.org` 有 TLS 握手问题，`settings.gradle.kts` 已配置 `repo1.maven.org` + 阿里云镜像作为替代源。如果你的网络能直连 `repo.maven.apache.org`，可以删掉镜像行。

## 2. 构建命令

```bat
:: 编译 Debug APK
gradlew.bat :app:assembleDebug

:: 编译 + 单元测试
gradlew.bat :app:assembleDebug :app:compileDebugUnitTestKotlin

:: 离线构建（已下载过依赖后）
gradlew.bat :app:assembleDebug --offline

:: 产出 APK 路径
:: app\build\outputs\apk\debug\app-debug.apk
```

Python 工具链（协议校验，不依赖 Android SDK）：

```bat
python protocol-spec\verify\check_frames.py        :: 帧长度/黑名单一致性
python protocol-spec\verify\run_unit_tests.py       :: 10 个 JUnit 单测（需先 compileDebugUnitTestKotlin）
```

## 3. 目录结构

```
D:\JBL-T230-Ctrl-20261113\
├── app/                              Android 应用源码
│   ├── build.gradle.kts              模块构建脚本（AGP 8.7 + Compose）
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml   无 INTERNET 权限（设计原则）
│       │   ├── assets/
│       │   │   ├── commands.json     60 条协议命令表（含 confidence/source）
│       │   │   └── eq_presets.json   6 个官方 EQ 预设曲线
│       │   ├── java/com/jblt230/ctrl/
│       │   │   ├── MainActivity.kt
│       │   │   ├── ble/              BLE 核心：Scanner / GattTransport / FrameCodec / EqCodec / CommandQueue / CommandGuard
│       │   │   ├── data/             AppViewModel / DeviceState / EqPresetStore
│       │   │   ├── logging/          ProtocolLog（脱敏协议日志）
│       │   │   ├── protocol/         CommandTable（commands.json 加载器）
│       │   │   ├── system/           SystemBaseline / BatteryReflection
│       │   │   └── ui/               Screens + StatusBadge
│       │   └── res/                  图标 / 主题 / 字符串
│       └── test/                     FrameCodecTest（17 个单测）
├── build/outputs/                    产出 APK
├── protocol-spec/                    协议规格文档 + 校验工具
│   ├── COMMAND_MATRIX.md             命令置信度与迁移证据矩阵
│   ├── PROTOCOL_SPEC.md              帧格式规格 + 已解决/开放问题
│   ├── DANGEROUS_COMMANDS.md         31 项危险命令黑名单
│   ├── SOURCES.md                    数据来源与 clean-room 声明
│   ├── captures/                     抓包工具 + 说明（parse_btsnoop.py）
│   └── verify/                       check_frames.py / run_unit_tests.py
├── report/                           验证报告
│   ├── ANALYSIS_REPORT.md            完整分析 + 交付报告
│   ├── VERIFICATION.md               真机逐项验证记录
│   └── SYSTEM_BASELINE.md            系统层能力评估
├── reverse/notes/                    官方 APK 静态逆向发现
│   ├── STATIC_FINDINGS.md            EQ 命令族裁决 / 控制类 / 官方配置
│   └── *.xml                         UI dump 快照（真机截图的 XML 版）
├── gradle/wrapper/                   标准 Gradle wrapper（可移植）
├── settings.gradle.kts               仓库配置（含 Maven 镜像）
├── gradle.properties                 JVM 参数 + JDK 路径
├── local.properties                  sdk.dir=C:\Android\Sdk（按你的 SDK 路径改）
└── README.md                         项目总览
```

## 4. 当前状态与排查修复记录（2026-11-14）

| 能力 | 状态 | 说明 |
|---|---|---|
| GATT 控制链路 & 握手 | ✅ 已修复 | 增加 `CONNECTION_PRIORITY_HIGH`、显式 `WRITE_TYPE_DEFAULT`、CCCD 300ms 延迟与 `AA 11 00` / `AA 10 02 00 00` 初始化序列 |
| EQ 写入链路（`0x40` / `0x41-0x43`） | ✅ 已重做 | 按 `Tune230Control` 修正为 `0x40` 预设切换 + `0x41/0x42/0x43` 多包 10 段传输（175B 读 / 179B 写，保留 `0xA2` 次级备援），待真机复测 |
| ANC 写入链路（`0x91` + `0x31/32`） | ✅ 已修复 | 补齐 `ALL_STATUS` (`0x21 01 30`) / `in_ear` (`0x21 01 41`) / `SubMode` (`0x91 00..02`) / `Ambient Level` (`0x91 20..22`) + `0x31/32` 回退，待真机复测 |
| 全量官方控制功能 | ✅ 已实现 | 手势分组与重置(`0x77`)、Smart A&V(`0x81-83`)、VoiceAware(`0x98`)、自动播停(`0x35`)、贴合度(`0x21/42`)、耳塞(`0x9C`)、待机ANC(`0x96`)、自动关机(`0x33`)、语音包(`0x93`)、Multi-AI(`0x34`)、FindMyBuds(`0x23/36`+安全弹窗+15s停止) |
| 离线协议校验与单测 | ✅ 通过 | `check_frames.py` (60 条命令 / 31 项黑名单) + `FrameCodecTest` |

## 5. 二次开发建议方向

### 5.1 真机联调清单（最高优先）

1. 连接已配对的 JBL Tune 230NC TWS，点「连接控制链路」确认握手与全状态回读；
2. 逐项验证 EQ 预设（`0x40`）与自定义 10 段写入（`0x41` → `0x42/43` 回读一致）以及 ANC/Ambient Aware/TalkThru 切换，并在官方 JBL app 交叉验证同步；
3. 验证新接入的佩戴检测、Best Fit、VoiceAware、Smart A&V 与电源省电写入。

### 5.2 功能扩展

- **手势写入** `0x77`：读已通，写帧格式见 `PROTOCOL_SPEC.md` §5.5
- **查找耳机**：官方语义是 `MyBudsInfo(leftBeeping, rightBeeping)` 取值 -1/0/1（与前人文档不同），需实现自动 stop
- **智能音视频**：`0x81/82/83` 三条写帧已从官方 `product_list.json` 提取，待真机验证
- **自适应 ANC**：`0x91` sub `20-22`，当前 INFERRED

### 5.3 工程改进

- LE 地址轮换的后台重连策略
- 把 `BluetoothAdapter.getDefaultAdapter()` 迁移到 `BluetoothManager.getAdapter()`（消除 deprecation 警告）
- 释放签名：当前用 debug 签名，发布需自建 keystore

## 6. 关键文件速查

| 要改什么 | 看哪个文件 |
|---|---|
| 加新协议命令 | `app/src/main/assets/commands.json` + `protocol-spec/COMMAND_MATRIX.md` |
| 改帧编解码 | `app/src/main/java/com/jblt230/ctrl/ble/FrameCodec.kt` |
| 改 EQ 帧构造 | `app/src/main/java/com/jblt230/ctrl/ble/EqCodec.kt` |
| 改写入流程 | `app/src/main/java/com/jblt230/ctrl/ble/CommandQueue.kt` |
| 改 UI | `app/src/main/java/com/jblt230/ctrl/ui/Screens.kt` |
| 改状态管理 | `app/src/main/java/com/jblt230/ctrl/data/AppViewModel.kt` |
| 加危险命令拦截 | `app/src/main/java/com/jblt230/ctrl/ble/CommandGuard.kt` + `DANGEROUS_COMMANDS.md` |
| 改协议日志脱敏 | `app/src/main/java/com/jblt230/ctrl/logging/ProtocolLog.kt` |

## 7. 注意事项

- **不复制官方 APK 的资源**（图标/字符串/layout），launcher 图标自绘
- **不移植 GPL 项目源码**（PairFree / SoundCtrl），只录入客观字节事实并登记出处，见 `SOURCES.md`
- **危险命令硬黑名单**：`95/97/F0/AA(自指)/13/15` 等在 `CommandGuard` 写入前拦截，不要绕过
- **单连接互斥**：耳机只接受一个控制连接，用官方 app 时先从本 app 断开
- `reverse/apk/` 和 `reverse/jadx-out/` 未包含在交付中（187 MB + 大量类文件），如需继续逆向可从官方 APK 重新生成：`jadx -d reverse/jadx-out --no-res reverse/apk/base.apk`
