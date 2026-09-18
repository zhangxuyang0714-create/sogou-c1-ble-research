# C1/C18D BLE 调查 — 最终总结与开发交接文档

写这份文档的目的：下一个会话直接从这里开始做 Android App 开发，不需要重新
翻整个调查过程。这是唯一需要通读的文件，其余 `docs/*.md` 是支撑细节。

## 设备身份（已确认，无需再验证）

- 名称：搜狗AI录音笔，型号 C18D/C1
- MAC：`AA:BB:CC:DD:EE:FF`（random static）
- SN：`5200000000000000`
- 固件：`V0127`
- 无 pairing/bonding/SMP/加密，任何客户端都能直接连接读取

## 已经生产级可用的能力（直接抄进 Android 代码）

| 能力 | 通道 | 包格式 |
|---|---|---|
| 连接 | 标准 GATT connect | — |
| 能力握手（**必须最先做，否则后续读写全部无响应**） | 写 `1910/2BB1` | opcode14，账号为空时固定字节：`0e 00 01 01 03 00 00 00 01 01 00 00 00 00 00 00 00 00 00 00` |
| SN | 读 `DD68/D003` | ASCII 字符串 |
| 固件版本 | 读 `DD68/D001` | 首字节ASCII前缀 + 后3字节小端int，如 `56 7f 00 00` = "V127" |
| 电量 | 读 `180F/2A19` | 标准 SIG Battery Level，0-100 |
| 状态 | 读 `DD68/D005` | 前2字节小端int，`0x0001`=空闲，`0x1003`=录音中（已实测确认） |
| 存储容量 | 写`2BB1`opcode28 → 收`2BB0`indicate opcode25 | totalKB/freeKB/bytesPerSecond/isFull，4+4+4+1字节 |
| **会话历史列表** | 写`2BB1`opcode6(sessionId分页起点4B) → 收`2BB0`indicate opcode9 | **每条12字节：sessionId(4B LE)+时长毫秒(4B LE)+常量1(4B)**，需要分页（用上一页最后一个sessionId作为下一次的起点，直到返回sessionId=0或重复） |

会话列表已经和 USB 上 12 个真实 WAV 文件的时长逐条精确匹配，这是整个调查里
证据最扎实的一项能力。

## 卡住的能力（不建议在下一阶段死磕）

- **文件下载**：`getFiles()`（opcode7）协议本身完全正确，但对所有测试过的
  session（含今天新录的）都返回官方代码自己定义的"无文件"哨兵值
  `fileId=65535`。不是协议错误，是设备固件的"文件就绪索引"为空，原因未查明。
  **建议**：v1 直接用 USB 做文件下载（100%可靠，已验证），BLE 只做"发现有
  哪些录音、多长"，不强求纯 BLE 下载。
  （补充：已用最小成本测试过"写B002再试getFiles"这个低置信度猜测——B002
  真实代码角色是OTA上传通道，不是getFiles前置触发器，写入后getFiles依然
  返回同样的0xFFFF无文件哨兵值，无变化。这条路到此为止，不用再试。）
- **远程发起录音**：`startRealtime`（opcode10）在设备空闲时发送会被拒绝
  （返回 sessionId=0）；但在设备**已经处于物理录音状态**时发送，会返回真实
  sessionId。也就是说这个命令更像"确认/挂载当前录音"，不是"凭空开始录音"。
  **建议**：v1 不做"App按钮远程开始录音"这个功能，物理按键仍是录音的唯一
  入口。
- **实时音频监听**：协议里有 AMR 回放相关 opcode（22/23），但参数设计
  （带fileSize）说明这是"播放已有文件"，不是"听麦克风直播"；而且承载它的
  characteristic 在这台设备上物理缺失（对应 `CC68/B003`，未在这台硬件上
  出现）。**结论：这台设备大概率不支持实时监听，不要在需求里画这个饼。**
- **音量控制**：同理，承载的 characteristic（候选`DD68/D009`）在这台设备
  上也缺失。

## 明确禁止的操作（协议已确认是破坏性的，永远不要在没有用户明确操作时发送）

- opcode12（`finishFile`）= **删除单个录音文件**
- opcode13（`finishSession`）= **删除整个会话**
- opcode21（`APP_DISCONNECT_DEVICE_IND`带isDelete=1）= depair并可能删除
- opcode25（`APP_RESTORE_FACTORY_SETTINGS_REQ`）= 恢复出厂
- opcode15/16（FOTA相关）= 固件升级

## 完全没测试过、协议存在但价值未知的能力

opcode18(测速)、19/20(多人会议)、24(KSX，含义未知)、5-8(A/B双通道录音)、
26/27/29/30(用户设置/LED/录音类型模式)。这些不建议现在花时间测，用得上再说。

## GATT 完整地址表

```
Battery Service 180F
  2A19  R,N   电量

Vendor Service 1910
  2BB1  W     命令写入通道（App→Stick，所有opcode都发到这里）
  2BB0  I     命令响应通道（Stick→App，indicate，需要订阅）

Vendor Service DD68
  D001  R     固件版本
  D003  R     SN
  D005  R     状态
  D007  W     疑似同步时间用，写入成功但效果未验证
  D00A  R     恒为0x02，含义未知

Vendor Service CC68
  B001  N     疑似文件数据通道，从未真正收到过数据
  B002  W     疑似文件传输触发通道，从未触发过下载
```

## 给 Android 开发的建议架构

```
Android App
  ↓ BLE：连接 → 能力握手 → 读设备信息(SN/电量/固件/状态) → 拉会话列表(时长)
  ↓ 展示："设备上有N条录音，最新一条是XX分XX秒"
  ↓ 用户想听/导出某条录音 → 提示"请用USB连接电脑/手机传文件"（或者做USB-OTG读取）
```

这个架构不依赖任何还没搞定的东西，现在就可以开始写。

## 复现工具

`c1_local/` 目录下的脚本都是真实可用、已验证的参考实现：
- `full_device_test.py` — 设备信息+存储完整流程
- `e2e_test.py` / `test_getfiles_real_confirmed.py` — 会话列表分页+getFiles测试的完整代码，opcode/参数/解析逻辑可以直接照抄改写成Kotlin

`research/apk/jadx/output/sources/com/sogou/teemo/bluetooth/compatible/` 下是
反编译出的官方Kotlin源码，`C1ActionCreator.java`/`C1ActionParser.java` 是
opcode定义的权威来源，写Android代码时遇到任何参数疑问直接回去看这两个文件。
