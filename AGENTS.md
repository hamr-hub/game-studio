# Agents Configuration

This file documents the agents, their roles, and the collaborative workflow within the Game Studio project.

## Project Overview
Game Studio is a Cocos-based engine integration with a Rust native core, targeting Android. It leverages agent-based orchestration to maintain high-quality code across different language stacks.

## Core Structure
- `app/`: Android application layer (Java/Kotlin, Gradle).
- `native/`: Core engine logic implemented in Rust.
- `scripts/`: Utility scripts for setup and deployment.

## Agent Roles & Responsibilities

### 🎨 Main Orchestrator (Project Lead)
- **Scope**: Entire workspace management.
- **Tasks**: Feature planning, documentation, dependency management, and high-level integration.
- **Primary Tools**: `icm`, `run_shell_command`, `replace`.

### 🦀 Native Expert (Rust Specialist)
- **Scope**: `game-studio/native/` and `cocos4-rust/`.
- **Tasks**: Rust implementation, JNI bindings, memory safety audits, and performance optimization.
- **Expertise**: `jni-rs`, `ndk-glue`, unsafe code management.

### 🤖 Android Specialist (Platform Lead)
- **Scope**: `game-studio/app/`.
- **Tasks**: Gradle configuration, Jetpack UI implementation, Android Manifest management, and JNI integration (CMake).
- **Expertise**: Kotlin, Gradle, NDK integration.

## 🧠 Knowledge Management (ICM)
All agents MUST use the **Infinite Context Memory (ICM)** to persist decisions and learned patterns.

- **Topic `decisions-game-studio`**: Store architectural decisions, library choices, and breaking changes.
- **Topic `errors-resolved`**: Document fixes for complex JNI or NDK build issues.
- **Topic `context-game-studio`**: Summarize significant progress to prevent context loss.

## Development Workflow
1. **Research**: Use `grep_search` to understand existing JNI boundaries.
2. **Implement Native**: Modify Rust code in `native/src/`.
3. **Generate Bindings**: Ensure JNI signatures match between Kotlin and Rust.
4. **Build & Verify**: Sync Gradle and build the Android app.
5. **Persist**: Store key learnings in ICM before concluding the task.

## ADB 调试环境与真机验证步骤

## 开发/调试工具版本

1. `Android SDK Platform-Tools: 37.0.0+`
2. `ADB: 37.0.0+`
3. `JDK: 26+`
4. `Gradle: 9.4.1`
5. `Android Gradle Plugin（AGP）: 9.2.0`
6. `Kotlin: 2.3.21`
7. `NDK: 28.2.13676358+`

## 本地路径配置 (Local Paths)

为了确保构建一致性，Android SDK 与 JDK 均放在 `~/codespace/` 下并使用固定版本目录：

1. **Android SDK**: `~/codespace/android-sdk`
2. **JDK 26**: `~/codespace/jdk/jdk-26.0.1+8`
3. **NDK**: `~/codespace/android-sdk/ndk/28.2.13676358`

## 🛠 Technical Achievements

The Game Studio project has been optimized with several advanced features:
- **Zero-Copy VFS**: Optimized JNI bridge to stream assets directly from APK using `AAssetManager`.
- **Advanced Diagnostics**: In-app console with log levels and color-coding for real-time native debugging.
- **High-Performance Capture**: Integrated `PixelCopy` for frame-perfect game screenshots.
- **Engine Control**: Dynamic FPS limiting and rendering feature toggles applied via JNI.

## 真机执行步骤

1. 启动 adb 并确认连接。
   ```bash
   adb start-server
   adb devices -l
   ```
2. 在手机上开启开发者模式和 USB 调试并授信，确保 `adb devices -l` 显示 `device`。
3. 执行真机一键流程：
   ```bash
   ./scripts/debug_android.sh
   ./scripts/debug_android.sh --serial=<device_serial>
   ./scripts/debug_android.sh --sdcard
   ./scripts/debug_android.sh --serial=<device_serial> --sdcard
   ./scripts/debug_android.sh --logcat
   ./scripts/debug_android.sh --serial=<device_serial> --sdcard --logcat
   ./scripts/debug_android.sh --logcat=com.cocos.gamestudio:Debug
   ```
4. 环境检查（脚本也会做一次）：
   ```bash
   java -version
   adb version
   ```
5. 如需手动推送演示包，可单独运行 `./scripts/setup_demo.sh --sdcard`。
6. 如果环境缺少 Gradle，先执行：
   ```bash
   ./scripts/bootstrap_gradle.sh
   ```
   说明：脚本会自动在 `services.gradle.org` 与 `downloads.gradle.org` 间切换重试，降低下载失败概率。

## 流程要求：集成测试与发布统一走 GitHub Actions（禁用本地编译）

在集成测试和真机调试场景中，要求**不在本地编译 APK**，只使用 GitHub Actions 产物进行安装验证，流程如下：

1. 在 CI 中触发对应工作流（或推送到分支触发）：
   ```bash
   gh workflow run "Android Debug APK" --ref main
   gh workflow run "Android Release APK" --ref main
   ```
2. 等待 `main` 分支流水线成功后，取最新成功 `run-id`：
   ```bash
   gh run list --workflow "android-debug.yml" --limit 5 --json databaseId,status,conclusion
   gh run list --workflow "android-release.yml" --limit 5 --json databaseId,status,conclusion
   ```
3. 使用下载脚本安装到真机（默认不会触发本地编译）：
   ```bash
   ./scripts/fetch_ci_debug_apk.sh --type=debug --abi=arm64-v8a --serial=<device_serial>
   ./scripts/fetch_ci_debug_apk.sh --type=release --abi=arm64-v8a --serial=<device_serial>
   ```
   - 发布验证建议：`--type=release`
   - 仅 64 位产物参与打包：`arm64-v8a`、`x86_64`
4. 安装完成后直接启动验证：
   - `com.cocos.gamestudio/.GameListActivity`
   - 日志观察可用 `adb logcat`

### 发布流程（Release）

1. 合并/提交到 `main` 后触发 `Android Release APK`
2. 确认 `game-studio-release-<abi>-apk` 上传成功
3. 按类型和 ABI 下载安装验证，再进行版本记录与标签发布

## 最终验证清单 (Final Checklist)
1. **启动性能**：内置游戏（Asset）应通过 Zero-Copy VFS 瞬间加载，无解压过程。
2. **详情交互**：点击游戏展示 BottomSheet，显示准确的元数据。
3. **性能监控**：进入游戏后左上角显示 FPS 和帧耗时。
4. **日志控制台**：点击左下角按钮开启日志，点击过滤器（INFO/WARN/ERROR）验证配色与过滤。
5. **设置中心**：在 Profile -> Settings 中修改 FPS 限制，返回游戏观察 FPS 监控变化。
6. **相册系统**：在游戏内截图，返回 Profile -> My Captures 查看预览图。

## 交付与验收约束

- 每次主流程验收前必须更新：
  - `docs/acceptance_plan.md`
  - `docs/acceptance_report_*.md`
- 集成调试统一走 GitHub Actions：
  - 真机验证前不得执行本地 `assemble*` 构建。
  - 仅使用 `gh workflow run ...` 与 `./scripts/fetch_ci_debug_apk.sh` 拉取 artifact 安装。
- 任何 `assets://` 游戏路径变更须在验收报告记录：
  - 原生是否返回 `STARTED`，
  - 是否成功触发回退分支（如为 `STARTED_SIMULATED`）。

## 最近执行更新

- 2026-05-25：默认游戏分发改为 `1000010` 到 `1000030` 闭区间白名单，仅展示该范围内已有游戏，其他游戏默认屏蔽。
- 2026-05-25：新增 App 游戏列表分发策略，支持通过 `game_distribution.json` 与远端 JSON 控制游戏可见性、排序、展示名称、说明和图标；相关说明见 `docs/game_distribution_strategy.md`。
- 2026-05-25：完成 `assets://` 路径归一化修复后的 CI 真机验收记录，新增 `docs/acceptance_report_2026-05-25.md`，并将 `docs/acceptance_plan.md` 生效时间更新到当前版本日期。
