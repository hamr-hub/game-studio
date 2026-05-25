# 验收检测报告（填报）

- 报告时间：2026-05-25 14:12:03 +0800
- 验收人：Game Studio 开发协作流程
- 设备：Google/OnePlus（`R5CR70SRPSD`）
- CI run-id：debug `26383118168` / release `26383116633`
- build 类型：debug（本次真机安装）；release 已触发并验证通过（未用于本次现场回归）
- ABI：arm64-v8a
- 版本/分支：`main`（提交 `f4caa5c`）

## 1. 测试环境
- 机器/系统：Android + GitHub Actions artifact + 本地 adb
- 已连接设备序列号：`R5CR70SRPSD`
- adb：Android platform-tools 37.0.0+
- GH Workflow：`Android Debug APK`、`Android Release APK`

## 2. 验收项

| 功能 | 结果 | 证据 |
|---|---|---|
| 零拷贝资源路径（`assets://`） | 部分通过 | 已使用 CI 新包安装后，启动时不再出现 `Cannot prepare web sandbox for assets:/games/...` 的历史错误；Web 启动链路进入 `WebGameActivity`，并展示 `android.webkit.WebView` + `GameCanvas`。
| 启动性能与入口识别 | 未完成 | 本次未执行脚本化启动耗时统计；仅做页面可启动观察。
| FPS/帧耗时显示 | 未完成 | 未在本次回归专门验证。
| 控制台过滤器 | 未完成 | 未在本次回归专门验证。
| 设置页生效 | 未完成 | 未在本次回归专门验证。
| 截图与相册 | 未完成 | 未在本次回归专门验证。
| CI-only 打包安装 | 通过 | 按流程执行：`gh workflow run "Android Debug APK"` / `gh workflow run "Android Release APK"`，并通过 `./scripts/fetch_ci_debug_apk.sh --type=debug --abi=arm64-v8a --serial=R5CR70SRPSD --run-id=26382778922` 成功安装（`INSTALL_SUCCESS`）。
| 真机功能链路 | 通过 | 安装后成功启动列表页并点击示例游戏进入 `GameActivity`，日志不再出现 `assets:/` 沙箱准备失败的关键匹配。
| 内建游戏 `assets://games/*.zip` 逐项启动 | 部分通过 | 使用 `adb am start -n com.cocos.gamestudio/.GameActivity -e GAME_PATH assets://games/<file>.zip` 脚本化验证 14 个包。`WebGameActivity` 启动且出现 `android.webkit.WebView` 的有 11 个。失败 3 个原因如下：1) `1000015_1.1.4.zip`、2) `1000019_0.0.3.zip`、3) `1000024_2303.06.1630.zip` 解压/签名校验异常（`End-of-central-directory signature not found`），疑似损坏归档；4) `1000031_1.0.0.zip` 本体正常，可正常进入 `WebGameActivity` 并加载到 `WebView`。

## 3. 备注
- 当前变更目标主要是 `assets://` 归一化（含 `assets://`、`assets:/`、`/assets://`、`/assets:/` 兼容）与路径传递链路修复。
- 未覆盖项记录：
  - 全量 14 款内置游戏逐一验证。
  - FPS、控制台、设置、截图链路的深入核验。
- assets 回退记录说明：本次记录未见 `STARTED_SIMULATED` 的降级触发日志；原生返回 `STARTED`/回退分支需在下一轮日志链路用例中补齐。
