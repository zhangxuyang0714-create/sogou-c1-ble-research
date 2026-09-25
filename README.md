# Sogou C1 / C18D BLE Research

对搜狗 AI 录音笔（C1 / C18D，BLE 广播名 `搜狗AI录音笔`）蓝牙协议的逆向研究记录，
包括 Linux + Bleak 的实验脚本，以及一个基于研究结果的 Android 客户端。

本项目与搜狗无关，不是官方 SDK。协议细节来自对真实设备的观测和对官方 App 的分析，
可能不完整或有误。

## 目录

- `docs/` — 协议文档和实验记录。先读 `docs/FINAL-investigation-summary.md`
- `scripts/` — 早期 Bleak 实验脚本（扫描、GATT 枚举、通知监听、单次写入探测）
- `c1_local/` — 基于协议文档的端到端设备测试脚本
- `android/` — Android 客户端（Kotlin / Jetpack Compose）：连接、读取设备信息、
  列出录音、通过 BLE 下载录音并解码为 WAV

## Python 脚本

需要 Linux + BlueZ，Python 3.12，依赖 `bleak`（本地测试用的是 3.0.2）。

```bash
python3 -m venv .venv
.venv/bin/pip install bleak
.venv/bin/python scripts/scan_bleak.py            # 找到设备地址
export C1_ADDRESS=XX:XX:XX:XX:XX:XX               # 其余脚本从这里读取设备地址
.venv/bin/python scripts/connect_and_enumerate.py
```

`c1_local/usb_baseline.py` 和 `scripts/correlate_usb_d005.py` 还需要
`C1_USB_ROOT` 指向设备以 USB 挂载后的目录。

## 会写入设备的操作

项目最初的目标是只读研究，但为了验证协议，部分代码会向设备写入。运行前请逐个确认：

- 所有命令都通过向 `2BB1` 写入 opcode 帧发送。读取类命令（握手 14、存储 28、
  会话列表 6、文件列表 7、下载 8/9）本身也是写入。
- **会改变设备状态**：开始录音（10）、暂停（3）、停止（2）会在设备上产生新录音。
  `c1_local/e2e_test.py`、`test_start_while_recording.py` 等脚本和 App 的能力测试页会发送这些命令。
- **修改设备时钟**：Android 客户端每次连接都会把手机时间写入 `D007`；
  `c1_local/test_synctime.py` 也会写入。
- **任意写入**：`scripts/probe_char.py` 可以向任意特征写入任意字节；
  `scripts/probe_b002.py`、`scripts/probe_d007.py`、`c1_local/test_b002_probe.py`
  会写入单个字节。根据分析，`B002` 是官方 App 的 OTA 上传通道，不要随意对它写入。
- 删除文件（12）、删除会话（13）、恢复出厂（25）只在 `C1Protocol.kt` 里定义为常量
  并标注为破坏性操作，代码中没有任何地方发送它们。没有 OTA、固件升级或格式化功能。

## Android 客户端

用 Gradle 9 / JDK 21 / Android SDK 构建（仓库不含 Gradle wrapper）：

```bash
cd android
gradle testDebugUnitTest assembleDebug
```

音频解码依赖 Airoha 的 CELT 解码库，它是官方 App 里的专有代码，**不包含在本仓库中**。
需要自行从自己合法持有的官方 APK 中取出并放到：

- `android/app/src/main/java/com/airoha/celt2chapi/AirohaCeltApi.java`（JNI 声明）
- `android/app/src/main/jniLibs/<abi>/libairoha-celtapi.so`

缺少这些文件时 `C1AudioDecoder.kt` 无法编译。`androidTest` 里的解码测试还需要
`android/app/src/androidTest/assets/` 下的真实录音样本，同样不随仓库发布。

## 说明

文档中的设备 MAC、序列号和本机信息已替换为占位值
（`AA:BB:CC:DD:EE:FF`、`5200000000000000` 等）。序列号保留了前缀 `520`，
因为音频帧长度由序列号前缀决定。
