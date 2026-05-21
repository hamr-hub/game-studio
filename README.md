# Game Studio

Game Studio is a high-performance, Android-based development environment for the Cocos Engine, featuring a native core implemented in Rust for maximum efficiency and security.

## 🚀 Overview

This project integrates the flexibility of the Android platform with the power of Rust. It serves as a studio tool for managing, previewing, and building Cocos-based games directly on Android devices.

### Key Features

- **Zero-Copy VFS**: Direct asset streaming from APK/Assets without extraction, saving storage and improving startup speed.
- **Native Developer Console**: Real-time log viewer for Rust engine output with multi-level filtering (INFO, WARN, ERROR) and color-coding.
- **Performance Monitoring**: On-device overlay showing real-time FPS and frame time (ms).
- **Engine Settings**: Customizable rendering parameters, including FPS limits and shadow toggles.
- **Game Gallery**: Built-in screenshot tool utilizing `PixelCopy` to capture and manage game moments.
- **Async Discovery**: Fast, non-blocking game cataloging with smart caching.

## 🏗 Architecture

The project is divided into three main layers:

- **Android App (`/app`)**: The UI layer built with Kotlin and Jetpack components, providing the user interface for game management.
- **Native Core (`/native`)**: The engine logic written in Rust, utilizing `cocos4-rust` for high-performance rendering and logic processing.
- **Bridging Layer**: JNI (Java Native Interface) and CMake bridge the communication between the Android JVM and the Rust binary.

## 🛠 Tech Stack

- **UI**: Kotlin, Android SDK (API 21+)
- **Logic & Rendering**: Rust (Edition 2021)
- **Integration**: JNI, CMake, Cargo
- **Engine Foundation**: `cocos4-rust`

## 🧰 开发工具版本

- **JDK**: `26+`
- **Gradle**: `9.4.1`
- **Android Gradle Plugin（AGP）**: `9.2.0`
- **Kotlin**: `2.3.21`
- **Android SDK**: API 21+（推荐 34+）
- **Android SDK Platform-Tools**: `37.0.0+`
- **ADB**: `37.0.0+`
- **NDK**: `28.2.13676358+`
- **Rust**: `rustup` + `cargo`（按 `rustup` 当前默认链）

## 🏁 Getting Started

### Prerequisites

- Android Studio (Electric Eel or newer recommended)
- Android NDK (r25+ recommended)
- Rust Toolchain (`rustup`, `cargo`)
- `cargo-ndk` for cross-compiling Rust to Android targets

### Setup

1. **Clone the repository**:
   ```bash
   git clone <repository-url>
   cd game-studio
   ```

2. **Initialize Rust Core**:
   ```bash
   cd native
   cargo build
   ```

3. **Open in Android Studio**:
   Open the `game-studio` root folder as a project in Android Studio.

4. **Build and Run**:
   Select your device and click "Run". Android Studio will automatically invoke CMake to build the Rust library via the JNI bridge.

## 📱 真机调试（推荐）

### GitHub Actions 打包 + 真机安装

项目提供 `.github/workflows/android-debug-ci.yml` 自动化打包。

1. 在 GitHub 仓库 Actions 页面触发 `Android Debug Package`（支持手动触发 `workflow_dispatch`）或推送到 `main`/`master` 分支自动触发。
2. 等待执行成功并确认产物名 `game-studio-debug-apk` 已上传。
3. 使用脚本下载最新成功产物并安装到真机（需先 `gh auth login`）：

```bash
./scripts/fetch_ci_debug_apk.sh
./scripts/fetch_ci_debug_apk.sh --serial=<device_serial>
./scripts/fetch_ci_debug_apk.sh --repo=<owner/repo> --run-id=<run_id>
```

脚本行为：
- 自动下载最近一次成功构建中的 `app-debug.apk`
- 自动选择连接中的设备（或使用 `--serial` 指定）
- 安装后启动 `com.cocos.gamestudio/.GameListActivity`

项目已准备好后，使用一键脚本完成构建、安装、启动：

```bash
./scripts/debug_android.sh
./scripts/debug_android.sh --serial=<device_serial>
./scripts/debug_android.sh --sdcard
./scripts/debug_android.sh --serial=<device_serial> --sdcard
./scripts/debug_android.sh --logcat
./scripts/debug_android.sh --serial=<device_serial> --sdcard --logcat
./scripts/debug_android.sh --logcat=com.cocos.gamestudio:Debug
```

如需手动推送演示包，可先运行：

```bash
./scripts/setup_demo.sh --sdcard
```

如果你是首次在新环境启动，本地无 gradle/gradlew，先执行：

```bash
./scripts/bootstrap_gradle.sh
./scripts/debug_android.sh
```
可先确认工具链是否满足要求：

`bootstrap_gradle.sh` 内置主站与备用下载源（`services.gradle.org` -> `downloads.gradle.org`）自动重试，适配国内/企业网络抖动场景。

```bash
java -version   # 需显示 26+
adb version     # 需 >= 37.0.0
```

## 📂 Project Structure

```text
game-studio/
├── app/              # Android application source code
├── native/           # Rust native engine core
├── scripts/          # Utility scripts for automation
├── AGENTS.md         # Agent-specific documentation
└── LICENSE           # MIT License
```

## 🤝 Contributing

We welcome contributions! Please refer to `AGENTS.md` to understand our development workflow and agent-based orchestration.

## 📜 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
