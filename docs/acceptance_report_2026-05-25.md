# 验收检测报告

- 报告时间：2026-05-25 19:37:30 +0800
- 验收人：Game Studio 开发协作流程
- 设备：Samsung `SM-G9910`（`R5CR70SRPSD`）
- CI run-id：`26397802539`（debug，提交 `640c492`）
- 最新 main debug CI：`26398547200`（提交 `3007de0`，通过；该提交仅比验证代码多验收文档）
- artifact：`game-studio-debug-arm64-v8a-apk`，artifact id `7197190519`
- build 类型：debug
- ABI：arm64-v8a
- 版本/分支：`main`

## 1. 测试环境

- 安装来源：GitHub Actions artifact，未执行本地 `assemble*`。
- 安装 APK：`/tmp/game-studio-ci-apk-26397802539/app-debug.apk`
- adb 安装结果：`Performing Streamed Install` / `Success`
- GH Workflow：`Android Debug APK`

## 2. 验收项

| 功能 | 结果 | 证据 |
|---|---|---|
| 游戏列表分发策略 | 通过 | 默认 `tailNumber` 开关只放行尾号 `10`-`30`，列表截图只出现 `1000013`、`1000015`、`1000016`、`1000017`、`1000018`、`1000019`、`1000024`、`1000026`、`1000027`。范围外 `1000000`、`1000001`、`1000006`、`1000007`、`1000031` 未展示。证据目录：`/tmp/game-studio-list-26397802539`。 |
| 展示名称/图标/排序/方向配置 | 通过 | `game_distribution.json` 生效；列表按配置顺序渲染，卡片展示可配置名称和图标占位；游戏启动使用配置方向，默认可见游戏均按 portrait 进入。 |
| 广告逻辑移除 | 通过 | Web 沙箱将激励视频、插屏、Banner、自定义广告、格子广告统一替换为 no-op/已结束回调，外部广告资源被拦截；真机截图未出现广告位遮挡核心流程。 |
| 报错信息屏蔽 | 通过 | WebView warning/error console 已在 `WebGameActivity` 侧吞掉；真机页面未出现错误弹窗或堆栈信息。 |
| CI-only 打包安装 | 通过 | 只使用 CI artifact `7197190519` 安装验证，未本地编译 APK。 |
| 可见游戏逐项启动 | 通过 | 9 个默认可见包均能打开并显示可识别游戏画面或可操作主界面。重点截图目录：`/tmp/game-studio-focused-logs-26397802539`；补充截图目录：`/tmp/game-studio-remaining-logs-26397802539`；1000019 单独复测目录：`/tmp/game-studio-1000019-retest-26397802539`。 |

## 3. 逐项结果

| 游戏 | 结果 | 说明 |
|---|---|---|
| `1000013_1.0.1.zip` | 通过 | 加载 `LaunchScene` 后进入游戏画面。 |
| `1000015_1.1.4.zip` | 通过 | 根因是 `remote/main` bundle 与运行时 `main` 路径不一致，且缺 `updateShareMenu`/`getNetworkType` 会打断 UI 初始化；修复后日志出现 `uimanager load`、`uiinited true`，点击后进入核心流程。 |
| `1000016_1.3.1.zip` | 通过 | 加载 `GameScene`，画面正常。 |
| `1000017_1.0.02.zip` | 通过 | 外部登录/BMS 请求由沙箱快速返回，进入 `App` 场景和主菜单。 |
| `1000018_1.0.02.zip` | 通过 | 同 1000017，进入可识别主界面。 |
| `1000019_0.0.3.zip` | 通过 | 单独复测 8/18/32 秒均停留在 WebGameActivity 游戏开始页；批量截图中的最近任务页为采集干扰。 |
| `1000024_2303.06.1630.zip` | 通过 | 加载 `GameScene` 并显示开始页。 |
| `1000026_2309.22.1430.zip` | 通过 | 加载 `GameScene` 并显示棋盘/关卡画面。 |
| `1000027_1.5.3.zip` | 通过 | 根因是游戏调用 `cc.assetManager.loadBundle("Texture/UIPrefab/<name>")`，但资源在 `subpackages/<name>`；新增 bundle 名称映射和沙箱分包目录别名后进入可操作棋盘。 |

## 4. 修复记录

- `WebGameActivity` 为 `remote/<bundle>`、`assets/<bundle>`、`subpackages/<bundle>` 建立根目录别名，修复 Cocos bundle 路径错配。
- 补齐小游戏平台 API：`getSystemInfo`、`getSystemInfoSync`、`getLaunchOptionsSync`、`updateShareMenu`、`getNetworkType`、`postMessage`、登录/用户信息/更新管理器等。
- 恢复 `wx/ks/tt/qg` 兼容别名，避免旧适配器早期平台探测中断启动。
- 将 `Texture/UIPrefab/<bundle>` 映射为真实 bundle 名，修复 1000027 分包 UI 加载。
- 对外部 HTTP/HTTPS XHR 和 mini `request` 返回本地成功结果，避免埋点、广告、BMS、活动配置阻塞核心流程。
- 广告 API 全部改为跳过或成功结束回调，不展示广告，不阻断奖励/继续流程。

## 5. 未覆盖项

- 本次重点验收游戏分发、启动、展示、广告屏蔽和可玩入口；FPS 面板、设置页 FPS 生效、截图相册未在本轮重新专项回归。
- release artifact 未在本轮安装验证。
