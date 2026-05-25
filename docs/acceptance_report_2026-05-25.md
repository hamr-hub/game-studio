# 验收检测报告（填报）

- 报告时间：2026-05-25 17:51:42 +0800
- 验收人：Game Studio 开发协作流程
- 设备：Google/OnePlus（`R5CR70SRPSD`）
- CI run-id：待本轮游戏分发策略推送后回填
- build 类型：待本轮 debug 真机安装；release 待 CI 验证
- ABI：arm64-v8a
- 版本/分支：`main`（本轮游戏分发策略提交待回填）

## 1. 测试环境
- 机器/系统：Android + GitHub Actions artifact + 本地 adb
- 已连接设备序列号：`R5CR70SRPSD`
- adb：Android platform-tools 37.0.0+
- GH Workflow：`Android Debug APK`、`Android Release APK`

## 2. 验收项

| 功能 | 结果 | 证据 |
|---|---|---|
| 零拷贝资源路径（`assets://`） | 待回归 | 本轮继续沿用 `assets://games/*.zip` 路径。修复点不改变原生 VFS 协议，只修复 Web 回退沙箱与内置包完整性。
| 启动性能与入口识别 | 未完成 | 本次未执行脚本化启动耗时统计；仅做页面可启动观察。
| FPS/帧耗时显示 | 未完成 | 未在本次回归专门验证。
| 控制台过滤器 | 未完成 | 未在本次回归专门验证。
| 设置页生效 | 未完成 | 未在本次回归专门验证。
| 截图与相册 | 未完成 | 未在本次回归专门验证。
| 游戏列表分发策略 | 待回归 | 内置 `game_distribution.json` 已改为服务端风格 `tailNumber` 开关，客户端按游戏 ID 后 2 位动态渲染 `10` 到 `30` 闭区间内的现有包，并支持每个游戏配置 `orientation`。本轮实际截图发现内置包 `game.json` 均为竖屏，默认配置已统一修正为 `orientation: portrait`。目标验证：列表不展示 `1000000`、`1000001`、`1000006`、`1000007`、`1000031`，展示并排序 `1000013`、`1000015`、`1000016`、`1000017`、`1000018`、`1000019`、`1000024`、`1000026`、`1000027`，启动方向按配置生效。
| CI-only 打包安装 | 待回归 | 本轮仍只允许通过 GitHub Actions 打包和 `scripts/fetch_ci_debug_apk.sh` 下载安装，不执行本地 `assemble*`。
| 真机功能链路 | 待回归 | 待本轮 CI debug APK 安装后重新启动列表页、详情页和游戏页。
| 内建游戏 `assets://games/*.zip` 逐项启动 | 待回归 | 本轮已定位 3 个失败包是仓库内置 ZIP 截断：`1000015_1.1.4.zip`、`1000019_0.0.3.zip`、`1000024_2303.06.1630.zip`。已替换为 `/mnt/ssd/codespace/kwai-game/package-downloader/last/` 中通过 `unzip -t` 的完整包，并加固 Web 沙箱启动器。CI 真机验证目标为 14/14 可进入 `WebGameActivity` 且不出现沙箱准备失败。

## 3. 备注
- 当前变更目标从单纯 `assets://` 归一化扩大为 Web 回退沙箱可运行性：
  - 替换 3 个截断内置包，所有 `app/src/main/assets/games/*.zip` 已通过本地 `unzip -t`。
  - `WebGameActivity` 增加沙箱完整性标记 `.web-sandbox-ready`，避免半成品缓存被复用。
  - 沙箱缓存 key 纳入 asset/file 大小，包内容更新后会重新解包。
  - 启动 HTML 改为注入 CommonJS `require`、`ks/wx` 基础桩和 `GameCanvas`，用于运行 Kwai/Cocos mini-game 包。
  - WebView console 已接入 Android logcat，便于真机定位 JS 运行错误。
- 本轮本地校验（未编译 APK）：
  - `git diff --check`：通过。
  - `unzip -t app/src/main/assets/games/*.zip`：14/14 通过。
  - `zipinfo -1 app/src/main/assets/games/*.zip | rg '^game\.js$'`：14/14 均包含 root `game.js`。
- 待 CI/真机回填：
  - debug/release workflow run-id。
  - debug arm64-v8a artifact 安装结果。
  - 游戏列表分发策略在默认配置下通过 `tailNumber` 只显示 `1000010` 到 `1000030` 闭区间内的现有游戏，远端覆盖仍可调整尾号规则、屏蔽指定游戏、调整排序并设置横竖屏。
  - 14 个 `assets://games/*.zip` 的 clean data 逐项启动结果。
  - `STARTED`/`STARTED_SIMULATED`/Web 回退日志证据。
