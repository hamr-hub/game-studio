# 验收检测报告（填报）

- 报告时间：2026-05-25 18:17:49 +0800
- 验收人：Game Studio 开发协作流程
- 设备：Google/OnePlus（`R5CR70SRPSD`）
- CI run-id：`26394487916`（debug，提交 `ccba022`，用于本轮故障复现）；待沙箱修复提交后回填新 run-id
- build 类型：debug 已安装复现；release 待 CI 验证
- ABI：arm64-v8a
- 版本/分支：`main`（当前继续修复 Web 沙箱兼容，提交待回填）

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
| 游戏列表分发策略 | 已复现待最终回归 | `26394487916` arm64-v8a artifact 已安装到 `R5CR70SRPSD`。列表页只展示尾号 `13`、`15`、`16`、`17`、`18`、`19`、`24`、`26`、`27`，未展示 `1000000`、`1000001`、`1000006`、`1000007`、`1000031`。
| CI-only 打包安装 | 通过 | 本轮安装使用 GitHub Actions debug artifact `game-studio-debug-arm64-v8a-apk`，artifact id `7195925833`，未执行本地 `assemble*`。
| 真机功能链路 | 待回归 | 待本轮 CI debug APK 安装后重新启动列表页、详情页和游戏页。
| 内建游戏 `assets://games/*.zip` 逐项启动 | 修复中 | `1000013`、`1000016`、`1000019`、`1000024`、`1000026` 已能显示可识别游戏画面或主菜单。`1000015` 黑屏根因为 Cocos 读取 `main/config.e9004.json`，但 ZIP 内实际路径为 `remote/main/config.e9004.json`。`1000027` 卡 1% 根因为 Web 沙箱缺 `wx.getSystemInfo`，触发 `TypeError` 后配置对象为空。`1000017`、`1000018` 已进入 `App` 场景但停在 logo，日志显示无 Cocos 崩溃，卡在 BMS/埋点外部 HTTP 初始化后未继续。已在沙箱层增加 remote bundle 别名、平台 API 补齐、广告跳过、外部 XHR 快速空返回，待新 CI artifact 复测。

## 3. 备注
- 当前变更目标从单纯分发配置扩大为“核心游戏流程优先可运行”：
  - `WebGameActivity` 解包后自动为 `remote/<bundle>` 建立根目录别名，修复 `1000015` 主包路径错配。
  - Web 沙箱补齐 `wx.getSystemInfo`、登录、用户信息、更新管理器、启动场景、加速度等小游戏基础 API，修复 `1000027` 初始化缺口。
  - 广告 API 统一返回已关闭/已结束，去掉激励视频、插屏、Banner、自定义广告和格子广告的展示逻辑。
  - 外部 HTTP/HTTPS XHR 快速返回空结果，避免 BMS、埋点、活动配置和广告配置阻塞 `1000017`、`1000018` 主流程。
  - WebView warning/error console 被吞掉，不再对外展示报错信息；调试期仍保留前置 run 的 logcat 证据。
- 本轮本地校验（未编译 APK）：
  - `git diff --check`：通过。
  - `unzip -t` 抽查问题包：`1000015`、`1000017`、`1000018`、`1000027` 均通过。
  - `jq` 校验默认分发仍启用 `tailNumber` 且内置配置均为 `orientation: portrait`。
- 待 CI/真机回填：
  - Web 沙箱修复提交后的 debug/release workflow run-id。
  - 新 debug arm64-v8a artifact 安装结果。
  - 9 个默认可见 `assets://games/*.zip` 的 clean data 逐项启动截图。
  - `STARTED`/`STARTED_SIMULATED`/Web 回退日志证据。
