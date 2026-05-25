# Game Studio PRD 对齐验收计划

## 版本
- 计划版本：v1.0
- 生效时间：2026-05-25
- 来源：
  - `AGENTS.md`
  - `docs/engine_app_native_contract.md`

## 功能与验收计划

| 功能模块 | 目标 | 验收步骤 | 期望结果 |
|---|---|---|---|
| 零拷贝游戏资源接入 | 支持 `assets://games/*.zip` 直接由 Native 侧读取，不必先解压到本地 | 1) APK 内置带 `games/` 目录 zip；2) 启动游戏时传入 `assets://...` 路径；3) Native 日志输出 `Game path resolved from AssetManager` | 原生初始化不再拒绝 `assets://`，`nativeGetPackageSummary` 与启动状态可展示包元信息 |
| 原生初始化与启动状态 | Native 能返回可观测状态并驱动 App 回退策略 | 1) 在 `GameActivity` 启动后读取 `nativeGetBootstrapStatus`；2) 对 `STARTED`/`STARTED_SIMULATED`/异常码执行不同分支 | `STARTED` 进入原生渲染；其他状态触发 Web 回退 |
| 启动性能与入口识别 | `assets://` 包扫描与入口检测稳定通过 | 1) 检查 `nativeGetPackageSummary` 和日志中是否包含 `analyzed` 信息；2) 检查 main entry 候选与入口语义判定字段 | 游戏包在启动时能识别到 `main_entry`，并给出 bootstrap 状态 |
| FPS 与帧耗时 | 左上角实时显示性能指标 | 进入游戏 > 1 秒后读取左上角文字与更新频率 | 每 1s 更新一次指标，包含 FPS/帳耗时 |
| 原生日志控制台 | 支持查看并按级别过滤日志 | 1) 打开 console 面板；2) 切换 ALL/INFO/WARN/ERROR | 控制台可显示并按颜色/过滤器区分日志 |
| 设置透传与生效 | 设置页修改 FPS 并影响游戏运行 | 1) 在 Settings 修改 FPS；2) 回到运行页观察指标变化 | 游戏统计中的 FPS 变化与设置一致 |
| 截图与相册链路 | 支持游戏内截图并在系统相册可见 | 1) 点击拍照按钮；2) 回到 Profile/My Captures 查看 | 截图可保存到图库并展示 |
| CI 首次打包与下载 | 不在本机执行本地编译，只走 GitHub Actions artifact | 1) 推送触发 `Android Debug APK` 或 `Android Release APK`；2) `scripts/fetch_ci_debug_apk.sh` 自动下载安装 | 成功下载并安装 `game-studio-<type>-arm64-v8a-apk` 到已连接设备 |
| 真机回归 | 真机已授权设备上可运行并进入核心功能 | 1) 安装完成后启动 `com.cocos.gamestudio/.GameListActivity`；2) 启动游戏并完成关键流程 | 列表展示、详情弹窗、启动体验、回退链路、日志与截图均可用 |

## 最近执行记录

- 执行人：`game-studio` 当前流程负责人
- 最后更新：2026-05-25
- 主分支提交：`f4caa5c`
- CI 参考：
  - debug: `26382778922`
  - release: `26382780147`

## 交付要求
- 产出：
  - `docs/acceptance_report_<date>.md`
  - 关键命令与输出（workflow、run-id、下载文件、adb 结果）
  - 如有缺陷，需在报告中标注优先级和修复计划
