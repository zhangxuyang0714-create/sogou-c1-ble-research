# C1 Android 硬件能力测试

目的：在已经能工作的 Android 16 App 基础上，逐项确认哪些控制/读取功能在真机上
真的可用。不重新验证已经确认好的功能（扫描/连接/电量/SN/固件/状态/存储/
getSessions）。

## 证据等级说明

每一行结论必须标注下面五个等级之一，不要把"协议存在"写成"功能已实现"：

| 等级 | 含义 |
|---|---|
| 协议存在 | opcode 在 C1ActionApp/C1ActionStick 枚举里有定义，但没有代码真正构造/解析它 |
| 官方代码存在 | 反编译代码里有真实构造该帧/解析该响应的函数，但从未在真机上测试过 |
| Linux 已验证 | 之前用 Bleak/Python 在真机上测试通过 |
| Android 已验证 | 本阶段用 Android BluetoothGatt 在真机上测试通过 |
| 真实设备成功 | 按官方代码自己的成功判据（不是"收到了 response"）确认功能真的发生了 |

## 静态代码依据来源

- `research/apk/jadx/output/sources/com/sogou/teemo/bluetooth/compatible/C1ActionCreator.java` —
  App→Stick 帧构造，`C1ActionApp` 枚举（opcode 2-30）
- `.../C1ActionParser.java` — Stick→App 帧解析，`C1ActionStick` 枚举（opcode 1-26）
- `.../C1TaskCreator.java` — 把 C1ActionCreator 的字节数组包装成 StickTask，
  决定用哪个 protocol（0 = 走 2BB1 写/2BB0 indicate 的 opcode 帧协议；非 0 = 走
  StickProtocol 定义的其他直接特征值读写）
- `.../C1GattCallbackHandler.java` — 决定哪个特征值的数据交给谁处理；
  确认了 B001(FILE_S2A) 的 notify 数据是**裸字节，不经过 opcode 解析**，直接调用
  `event.onFileReceive(bytes)`
- `com/sogou/teemo/translatepen/manager/DownFileRunner.java` +
  `StickManager.downloadFile()` — 下载编排逻辑
- `com/sogou/crc/CRC16Util.java` — CRC16 精确算法
- `com/sogou/teemo/translatepen/room/RecordType.java` — `Common.toInt() == 1`，
  `toOtgDir(Common) == "RECORD"`（和 USB 上已知的 `RECORD/` 目录吻合，交叉验证）

---

## 任务 1：远程开始录音

| 功能 | 官方代码依据 | Android 实测 | response | 结论 |
|---|---|---|---|---|
| startRealtime(Common) | `C1ActionCreator.startRealtime(RecordType)`：opcode **10** (`APP_RECORD_REALTIME_START`)，20 字节帧，仅 1 个参数：`byte[2] = RecordType.toInt(Common) = 1`。**没有 uid/scene/isOnlyOne 字段** | 待测 | 待测 | 待测 |
| 成功判据 | `C1ActionParser` 里 `STICK_RECORD_START_CNF` = opcode **4**（不是我们之前假设的其他值）。`event.ackStart(sessionId=data[2:6](4B LE), field2=data[6:10](4B, 含义未知), recordType=data[10:11])`。**必须 sessionId != 0 才算真正开始**，不能只看"收到了 response" | 待测 | 待测 | 待测 |
| 旁路证据 | `STICK_RECORD_START_IND` = opcode **1**：`statusChanged(sessionId=data[2:6], field2=data[6:8](2B), RECORDING, recordType=data[8:9])`。这是设备主动通知（不管是按键触发还是 App 触发都会发），可以作为第二重验证 | 待测 | 待测 | 待测 |

---

## 任务 2：远程暂停/继续

| 功能 | 官方代码依据 | Android 实测 | response | 结论 |
|---|---|---|---|---|
| pauseRecord() | `C1ActionCreator.pauseRecord()`：opcode **3** (`APP_RECORD_PAUSE_IND`)，无参数（20 字节帧只有 opcode） | 待测 | 待测 | 待测 |
| 暂停确认 | `STICK_RECORD_PAUSE_IND` = opcode **2**：`statusChanged(sessionId=data[2:6], field2=data[6:8], PAUSED, Common)` | 待测 | 待测 | 待测 |
| **resume 的真相** | `C1TaskCreator.resumeRealtime(recordType, sessionId, event, wait)` **直接调用 `startRealtime(recordType, event, wait)`**——官方代码里**没有独立的 resume opcode**，"继续"就是重新发一次 opcode10 (`startRealtime`)。不要发明新 opcode | （不需要单独测试，等价于任务1） | — | 结论明确：resume = 重发 startRealtime |
| D005 在暂停时的值 | 用户假设是 `0x1111`，官方代码没有直接证据支持这个具体数值——D005 是独立的 GATT 特征值，不由 `C1ActionParser` 维护，只能真机实测确认 | 待测 | 待测 | 待测，不要预设结论 |

---

## 任务 3：远程停止

| 功能 | 官方代码依据 | Android 实测 | response | 结论 |
|---|---|---|---|---|
| stopRecord()/stop() | `C1ActionCreator.stopRecord()` 和 `C1ActionCreator.stop()` **是同一个实现**：都是 opcode **2** (`APP_RECORD_STOP_IND`)，无参数 | 待测 | 待测 | 待测 |
| 停止确认 | `STICK_RECORD_STOP_IND` = opcode **3**：`statusChanged(sessionId=data[2:6], field2=data[6:8], STOP, Common)` | 待测 | 待测 | 待测 |

**opcode 编号提醒**：App→Stick 的 stopRecord 用的是 opcode 2，Stick→App 的
STOP_IND confirm 用的也是 3——这是两个独立的编号空间（App 请求 vs Stick 响应），
数字相近纯属巧合，不要混淆成同一个 opcode。

---

## 任务 4：远程关机 — 静态调查结论

**不支持 / 没有证据。**

搜索范围：`com/sogou/teemo/bluetooth/` 与 `com/sogou/teemo/translatepen/manager/`
全目录，关键词 `shutdown|poweroff|power_off|standby`（大小写不敏感）。

- 唯一命中：`A2DPManager.java` 里的 `scheduledExecutorService.shutdownNow()`——
  这是标准 Java 线程池关闭，与设备电源无关
- `C1ActionApp` 枚举（App→Stick，opcode 2-30）逐条核对，没有任何 power/shutdown
  相关成员
- `C1ActionCreator.java` 没有任何构造关机帧的方法
- 唯一相关但无关的命中：`StickManager` 里 `onC2BuriedPoint()` 的
  `manualPowoff`/`lowPowoff` 参数——这是 **C2（另一款笔）的开关机历史统计上报**
  字段（埋点数据，设备主动上报"什么时候被手动关过机"），不是 App 可以下发的
  关机指令，而且是 C2 专属，不是 C1 协议的一部分

结论：**协议层面没有远程关机能力，不要猜 opcode，不做真机测试。**

---

## 任务 5：BLE 下载录音

### 完整调用链（官方代码路径）

```
getSessions (opcode 6) → 拿到真实 sessionId
  ↓
getFiles(sessionId, Common)  — opcode 7
  参数：sessionId(4B LE)@[2:6] + recordType(1B)@[6:7]
  ↓ 响应
STICK_RECORD_GET_FILES_CONFIRM (opcode 10)
  entries: 6字节一组，从offset 2开始，最多2组：fileId(2B LE)+size(4B LE)
  fileId == 65535(0xFFFF) 是官方定义的"无文件"哨兵值，此时 event.ackFile 不会被调用
  ↓ 如果 fileId != 65535
download(sessionId, fileId, start, end, recordType)  — opcode 8 (APP_RECORD_DOWNLOAD_FILE)
  非TR2型号参数：sessionId(4B)@[2:6] + fileId(2B)@[6:8] + start(4B)@[8:12]
                + end(4B)@[12:16] + recordType(1B)@[16:17]
  ↓ 响应（都走 2BB0 indicate）
STICK_RECORD_FILE_HEADER (opcode 11)
  event.onHeader(data[2] == 1)  —— 只有 1 个有意义字节：是否成功，不含文件大小等元数据
  ↓
B001 (UUID_CHAR_FILE_S2A) notify —— 裸数据，不走 opcode 解析！
  C1GattCallbackHandler.onCharacteristicChanged 对 B001 直接调用
  event.onFileReceive(rawBytes)，没有任何帧头
  ↓ （可能多次 notify，累积拼接）
STICK_RECORD_FILE_TAIL (opcode 12)
  eod = data[2]（1字节，end-of-data标志）
  crcLen = data[3]（1字节，CRC字段的字节数——不是固定2字节！）
  crc16 = toInt(data[4 : 4+crcLen])
  event.onTail(crc16, eod)
  ↓
stopDownload() — opcode 9 (APP_RECORD_DOWNLOAD_STOP)，无参数，下载完成后发送清理状态
```

### 关键澄清（避免走弯路）

- **B002 (UUID_CHAR_FILE_A2S) 不是下载触发通道**：`C1GattCallbackHandler` 里
  唯一对它的处理是 `characteristic.setWriteType(1)`（WRITE_TYPE_NO_RESPONSE），
  整个 `C1ActionCreator`/`C1TaskCreator` 没有任何方法把帧写向 B002。之前 Linux
  阶段"写B002再试getFiles"的探测已经确认无效，这次静态代码分析进一步证实
  B002 在 C1 协议里没有被官方代码实际使用（可能是其他笔型号遗留的特征值）
- **getFiles/download/stopDownload 走的是标准 2BB1/2BB0 通道**，不是 CC68 service，
  与 getSessions 完全同构（`C1TaskCreator` 里这几个方法的 protocol 参数都是默认值0，
  和 startRealtime/getSessions/pauseRealtime 一样）
- **CRC16 算法**（`CRC16Util.calcCRC`，byte-swap-XOR 变体，init=0xFFFF）：
  ```
  crc = 0xFFFF
  for b in data:
      step1 = ((crc << 8) & 0xFFFF) | ((crc >> 8) & 0xFF)
      i4 = step1 xor (b & 0xFF)
      i5 = i4 xor ((i4 & 0xFF) >> 4)
      i6 = i5 xor ((i5 << 8) << 4)
      crc = (i6 xor (((i6 & 0xFF) << 4) << 1)) & 0xFFFF
  ```

### 测试结果

| 步骤 | 官方代码依据 | Android 实测 | response | 结论 |
|---|---|---|---|---|
| getFiles(最新真实session) | 见上 | 待测 | 待测 | 待测 |
| download(...) | 见上 | 待测 | 待测 | 待测 |
| HEADER | 见上 | 待测 | 待测 | 待测 |
| B001 DATA | 见上 | 待测 | 待测 | 待测 |
| TAIL/CRC | 见上 | 待测 | 待测 | 待测 |

失败时会明确区分（不写笼统的"下载失败"）：
1. getFiles 返回 0xFFFF（无文件哨兵）
2. getFiles 超时
3. startSyncRecord(download) 失败
4. HEADER 收到但 B001 没有数据
5. B001 收到数据但 CRC 不匹配

---

## 任务 6：录音时间戳

**结论：已确认（不需要真机测试，纯数据交叉验证）——`sessionId` 本身就是
Unix 时间戳（UTC 秒数），精确到秒，不需要从设备再查一次时间。**

证据：Phase 6 真机测试（`docs/FINAL-investigation-summary.md`）拿到的 7 个真实
sessionId，直接当 Unix 时间戳按 UTC+8 转换，和 `c1_local/full_device_test.py`
里已知的 USB 文件路径（`RECORD/YYYYMMDD/HH_MM_SS`）逐条精确匹配，7/7 全部
吻合，秒级精确，不是巧合：

| sessionId (hex) | 当作 Unix 时间戳按 UTC+8 转换 | USB 文件路径（独立来源） | 匹配 |
|---|---|---|---|
| 0x67cad1d0 | 2025-03-07 19:00:32 | RECORD/20250307/19_00_32 | ✅ |
| 0x67cad41d | 2025-03-07 19:10:21 | RECORD/20250307/19_10_21 | ✅ |
| 0x67d1acb0 | 2025-03-12 23:48:00 | RECORD/20250312/23_48_00 | ✅ |
| 0x67d1add4 | 2025-03-12 23:52:52 | RECORD/20250312/23_52_52 | ✅ |
| 0x67d1af53 | 2025-03-12 23:59:15 | RECORD/20250312/23_59_15 | ✅ |
| 0x67d6b124 | 2025-03-16 19:08:20 | RECORD/20250316/19_08_20 | ✅ |
| 0x682baca6 | 2025-05-20 06:11:50 | RECORD/20250520/06_11_50 | ✅ |

结论：`sessionId = floor(录音开始时间的 Unix 时间戳)`，设备本地时区是
**UTC+8**（与 USB 文件系统的目录/文件名時区一致）。Android 端显示
`YYYY-MM-DD HH:mm:ss` 时，直接 `Instant.ofEpochSecond(sessionId)` 转到
`Asia/Shanghai`（或固定 UTC+8）即可，**不需要**额外查询 BLE 或依赖 USB。

**旁路发现（未使用，仅记录）**：`C1ActionStick` 里有 `STICK_GET_STAT_CNF` =
opcode 26，`C1ActionApp` 里没有找到直接构造它的请求方法（`getStatus()` 用的是
不同的 `APP_RECORD_GET_STATUS`=opcode5，且 `C1TaskCreator.getStatus()` 实际上
不走 opcode 协议，而是走 `StickProtocol.GET_TYPE_STATUS` 的直接特征值读——很
可能对应的就是已经在用的 D005 读取，不是新通道）。`STICK_GET_STAT_CNF` 的 8
个字段可能与 `STAT/*.DAT` 相关，但没有找到触发它的请求代码，且已经不需要
它来解决时间戳问题，不再深入。

---

## 任务 7：录音名称/文件名

**结论：C1 没有 BLE 端重命名协议，只有 App 本地数据库改名。**

搜索 `rename|setname|modifyname` 等关键词后，找到唯一真正相关的代码：
`HomeService$editTitle$positiveListener$1.java`。核实其 `onClick()` 实现：

```kotlin
this.$viewModel.saveTitle(this.$remoteId, inputText)
```

`saveTitle` 是 `HomeViewModel` 上的方法，只更新本地 Room 数据库（`Session` 表的
标题字段），整条调用链里**没有任何 `StickManager`/BLE 调用**。

区分：
- A. 修改 C1 内部录音名称：**没有协议，不存在**
- B. 下载到 Android 后修改本地记录标题：**是的，但这只是 App 自己数据库里的
  显示名称，不涉及 BLE，也不会改变 C1 设备本身存储的任何东西**

不设计新 opcode，不做真机测试（本来就没有可测的 BLE 功能）。

---

## 安全限制（本阶段严格遵守）

绝对不发送：`finishFile`(12)、`finishSession`(13)、
`disconnectDevice(isDelete=1)`(21)、`restoreSettings`(25)、
`ORDER_APP_FOTA_PUSH_IND`(15)/`uploadOTA`/`uploadOTAFinish`。

所有录音测试只允许产生一条新的短测试录音，不删除任何已有录音。

---

## 最终能力矩阵

（测试完成后填写，区分"协议存在/官方代码存在/Linux已验证/Android已验证/真实设备成功"）

### 读取

| 能力 | 状态 |
|---|---|
| （已在 Phase 1-6 完成，不在本文档重复） | ✅ Android 已验证 |

### 控制

| 能力 | 状态 |
|---|---|
| start | 待测 |
| pause | 待测 |
| resume | 官方代码存在（= 重发 start，无独立协议） |
| stop | 待测 |
| power off | ❌ 无协议证据 |

### 文件

| 能力 | 状态 |
|---|---|
| list（getSessions） | ✅ Android 已验证（Phase 6） |
| metadata（getFiles） | 待测 |
| date/time | ✅ 已确认（sessionId 本身就是 UTC 时间戳，秒级精确，7/7 交叉验证） |
| download | 待测 |
| rename（设备端） | ❌ 无协议证据 |
| rename（本地标题） | 官方代码存在，不涉及 BLE |
