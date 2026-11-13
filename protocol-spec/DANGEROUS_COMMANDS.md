# 危险命令黑名单（与 `CommandGuard.kt` 同源）

**规则：任何写帧在 `GattTransport.write()` 之前都必须经过 `CommandGuard.assertAllowed(frame)`。命中黑名单直接抛异常、写 `BLOCK` 日志、UI 置灰，绝不发往耳机。**

黑名单的来源：JBL 官方 app 中的破坏性/不可逆操作，以及 `Omega-Destroyer/PairFree` `docs/jbl/PROTOCOL.md` 的 `never_send` 清单（协议事实，非代码复制）。

## Harman / BES 老协议（本项目目标族）

| 命令字节 | 含义 | 为什么禁止 |
|---|---|---|
| `0x95` | Factory reset / AA 95 | 恢复出厂，清空配对与用户设置，不可逆 |
| `0x97` | Power off / shutdown | 让耳机关机；部分项目把它当正常功能，本项目一律禁止 |
| `0xF0` | Firmware update | 固件升级，失败可能变砖 |
| `0xAA` | Preserve settings（保留设置） | 与帧头同字节，语义特殊，误发后果不明 |
| `0x13` | Analytics / 统计上报 | 非控制功能，无必要下发 |
| `0x15` | Analytics / 统计上报 | 同上 |

## Protocol 4（本项目**不**使用，仅登记以防误判）

`0x00FF` factory reset、`0x0101`–`0x0104` firmware update、`0x0201`/`0x02FF` analytics。

## Airoha RACE（本项目**不**使用，仅登记以防误判）

analytics `0x0010`/`0x0011`/`0x0016`；dongle 配对 `0x0030`/`0x0031`；配对设备管理 `0x0056`/`0x0057`/`0x0067`；充电盒动作 `0x0083`；时钟 `0x0090`；Harman 透传 `0x0093`；设备信息 `0x0094`；NV 写 `0x0A01` / reload `0x0A09`；relay `0x0D01`；按键事件 `0x1101` 系列（含 `0x0096` factory reset、`0x0095` power off、`0x0002` pairing）；固件升级 `0x1C00`–`0x1CFF`（版本读除外）。

## 有条件放行（需额外护栏）

| 命令 | 条件 |
|---|---|
| `0x36` / `0x23` Find My Buds | 仅允许 start/stop 值；UI 必须先弹「请取下耳机，音量较大」确认；触发后 **15 秒自动下发 stop**；app 退到后台立即 stop |
| `0x77` 手势写入 | 只允许写已定义在 action 白名单内的值；每次写完强制 `AA 77 02 01 FF` 回读 |
| `0x41` 自定义 EQ 分块写 | **永久禁止**：官方 5.38.2 已裁决 230NC 走 `A2`（`EQCmd.sendCmd` + `combineLen2`），`41-43` 属于其它型号的旧 BES 传输，本项目不实现 |
| `0x91` / `0x31` / `0x32` 降噪与环境音 | 只允许已录制的模式帧；写完强制回读比对 |

## 校验

`protocol-spec/verify/check_frames.py` 会静态检查 `app/.../protocol/commands.json` 中不存在任何黑名单命令，且所有帧的 length 字段与 payload 自洽。
