# 视觉一致性与交互反馈验收报告

- 报告时间：2026-05-28 17:07:28 +0800
- 验收人：Game Studio 开发协作流程
- 设备状态：Samsung `SM-G9910`（`R5CR70SRPSD`）已通过 `adb devices -l` 检测为 `device`
- 安装来源约束：本轮未执行本地 `assemble*`，真机安装仍要求使用 GitHub Actions artifact
- 本轮性质：视觉资源、主界面 UI 一致性、基础交互反馈、Profile 激励体系、鲜艳 Launcher 图标、游戏内横竖屏锁定与 CI artifact 通道验证

## 1. 本轮改造范围

| 模块 | 结果 | 说明 |
|---|---|---|
| 设计 token | 已完成静态落地 | 新增统一 `colors.xml`、`dimens.xml`、`styles.xml`，主界面颜色、文本层级、圆角、间距改为资源引用。 |
| 图标与 UI 切图资源 | 已完成静态落地 | 新增底部导航、设置、播放、空态、截图占位等 vector 资源；新增搜索框、卡片、图标容器、统计块、弹层把手等 shape 资源。 |
| 游戏列表 | 已完成静态落地 | 搜索框、结果计数、加载状态、空状态、两/三列自适应网格、统一卡片高度和图片兜底已接入。 |
| 游戏详情弹层 | 已完成静态落地 | 统一圆角图标容器、标题/说明样式、主按钮样式和启动 Snackbar 反馈。 |
| Profile | 已完成静态落地 | 头像、统计块、最近游戏空态、截图空态和截图读屏描述已接入，移除系统默认图标混搭。 |
| Settings | 已完成静态落地 | 统一 Toolbar、卡片、Spinner 外观、Switch 文本样式；FPS/开关修改后 Snackbar + accessibility announce。 |
| CI artifact 通道 | 已修复配置 | `android-debug.yml` 与 `android-release.yml` 在打包前执行 `scripts/bootstrap_gradle.sh`；同步修复 Gradle 9.4.1 wrapper main/shared/cli/files jar 拆分问题。 |
| 内置游戏资源 | 已恢复 | 真机首轮安装显示 `0 games available`，取证发现当前 HEAD 缺失历史内置 `assets/games/*.zip`；已从历史成功提交 `3007de0` 恢复 14 个内置包，目录约 71 MB。 |
| App 图标体系 | 已完成静态落地 | adaptive foreground 改为游戏手柄/徽章组合，mdpi 到 xxxhdpi legacy 与 round PNG 已重新生成并校验尺寸。 |
| 鲜艳 Launcher 图标 | 已完成静态落地 | adaptive background 改为高饱和粉橙色，foreground 与 legacy PNG 统一为白色游戏手柄、紫/青/黄按钮和星形徽章。 |
| 游戏卡片视觉 | 已完成静态落地 | 列表和详情页图标容器改为统一游戏手柄水印与右下角标签徽章，减少纯数字占位感。 |
| 游戏内横竖屏锁定 | 已完成静态落地 | `GameOrientationLock` 在游戏内使用 `SENSOR_LANDSCAPE`/`SENSOR_PORTRAIT`，列表、Profile、Settings 退出游戏后恢复 `FULL_USER`。 |
| Web 回退方向优先级 | 已完成静态落地 | 显式分发/启动方向优先，包内 `game.json` 仅在没有显式方向时作为默认值，避免横屏游戏被包内默认值覆盖。 |
| Profile 玩家资料 | 已完成静态落地 | Profile 改为资料头、等级、积分、XP 进度、游玩时长、连续天数、奖章、奖励任务、历史游戏、排行榜和截图图库组合。 |
| 激励体系数据 | 已完成静态落地 | 新增 `PlayerProgressRepository`，按游戏路径记录累计时长、session 次数、最近游玩时间、积分、等级、奖章解锁、奖励任务进度和排行榜排序。 |
| 游戏会话记录 | 已完成静态落地 | `GameActivity` 与 `WebGameActivity` 退出时记录有效会话；原生回退到 Web 时避免重复计入；Profile 返回时刷新。 |

## 2. 已执行检查

| 检查项 | 结果 | 证据 |
|---|---|---|
| XML 语法与资源引用 | 通过 | `XML/reference OK: 32 XML files`。 |
| 本轮 XML 语法 | 通过 | `python3` 解析 `app/src/main/res/**/*.xml` 输出 `xml-ok`。 |
| 空白与补丁格式 | 通过 | `git diff --check` 无输出。 |
| Launcher PNG | 通过 | `file` 检查 mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi launcher 与 round PNG 均为对应尺寸 RGBA PNG。 |
| Gradle wrapper 启动 | 通过 | `./gradlew --version` 在 JDK 26 下输出 `Gradle 9.4.1`。 |
| Gradle 配置级检查 | 通过 | `./gradlew :app:tasks --all` 成功，未执行 `assemble*`。 |
| ADB 设备 | 通过 | `R5CR70SRPSD device usb:1-1 product:o1qzcx model:SM_G9910`。 |
| CI 外部依赖稳定性 | 通过 | 外部 `Dave-he/cocos4-rust` 依赖已固定到历史成功期提交 `19d05d96359978d1bbdf81157e9573124cb47aa3`；本轮 run `26561542364` 验证通过。 |
| CI debug artifact 安装 | 部分通过 | run `26557705462` 成功产出 arm64 artifact 并安装启动；首屏截图路径 `/tmp/game-studio-ui-2026-05-28.png`，但因内置游戏包缺失显示 0 个游戏，已恢复资源后需重新打包安装。 |
| 本轮 CI debug | 通过 | run `26561542364`（提交 `82f05d6`）arm64-v8a 与 x86_64 均成功。arm64 artifact `7260352346`，zip 大小 `79420752`，`unzip -t` 通过，解出 `/tmp/game-studio-ci-apk/app-debug.apk`。 |
| 本轮真机安装 | 通过 | 设备 `R5CR70SRPSD` 上旧包因签名不一致无法覆盖安装，已卸载旧包后安装本轮 CI artifact 并启动 `com.cocos.gamestudio/.GameListActivity`。 |
| 图标与横竖屏 CI debug | 通过 | run `26564442336`（提交 `01eb61e`）arm64-v8a 与 x86_64 均成功。arm64 artifact `7261582557`，zip 大小 `79433468`，`unzip -t` 通过，解出 `/tmp/game-studio-ci-apk-01eb61e/app-debug.apk`。 |
| 图标与横竖屏真机安装 | 通过 | 旧 debug 包签名不一致，已卸载 `com.cocos.gamestudio` 后安装 run `26564442336` arm64 artifact；`dumpsys package` 显示安装时间 `2026-05-28 16:59:58`。 |

## 3. Profile 激励体系真机回归

| 场景 | 结果 | 证据 |
|---|---|---|
| 列表首屏 | 通过 | UI dump 显示 `9 games available`，首屏含 `Game 1000013`、`Game 1000015` 等卡片；截图 `/tmp/game-studio-2026-05-28-profile-list.png`。 |
| Profile 首次空态 | 通过 | 首次进入显示 `Cocos Expert`、`New Challenger`、`Level 1`、`Level 1 · 0/500 XP`、`0 min`、`Badges`、`Reward Board`；截图 `/tmp/game-studio-2026-05-28-profile-empty.png`。 |
| 历史与排行榜空态 | 通过 | 下滑后显示 `Play History`、`Play a game to build history, medals, and rankings.`、`Game Leaderboard`、`Rankings appear after your first completed game session.`；截图 `/tmp/game-studio-2026-05-28-profile-empty-history.png`。 |
| 游戏会话写入 | 通过 | 以 `assets://games/1000013_1.0.1.zip` 启动，焦点进入 `WebGameActivity`，停留后返回 Profile；截图 `/tmp/game-studio-2026-05-28-game-session.png`。 |
| 会话后激励刷新 | 通过 | Profile 显示 `Game Explorer`、`Level 1 · 215/500 XP`、`215` points、`<1 min`、`Day Streak 1`、`First Run Unlocked`；截图 `/tmp/game-studio-2026-05-28-profile-after-session-top.png`。 |
| 历史与排行榜刷新 | 通过 | 下滑后显示 `Game 1000013`、`<1 min · 1 sessions`、排行榜 `#1 Game 1000013 1 sessions <1 min`；截图 `/tmp/game-studio-2026-05-28-profile-after-session-history.png`。 |

## 4. 图标与横竖屏真机回归

| 场景 | 结果 | 证据 |
|---|---|---|
| 鲜艳 Launcher 图标进包 | 通过 | APK 内含 `res/drawable/ic_launcher_foreground.xml`、adaptive icon XML 和各密度 `ic_launcher*.png`；本地 xxxhdpi PNG 预览为粉橙渐变白色手柄图标。 |
| 列表卡片视觉 | 通过 | 真机截图 `/tmp/game-studio-01eb61e-list-loaded.png` 显示 9 个游戏，卡片使用统一圆角、间距、手柄水印与右下角数字徽章。 |
| Profile 激励视觉 | 通过 | 真机截图 `/tmp/game-studio-01eb61e-profile-clean.png` 显示资料头、等级、积分、时长、连续天数、奖章和奖励区域。 |
| 显式横屏游戏 | 通过 | `adb am start ... -e GAME_ORIENTATION landscape` 后，`WebGameActivity` 连续 6 秒保持 `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`，`DisplayFrames w=2400 h=1080 r=1`；截图 `/tmp/game-studio-01eb61e-explicit-landscape.png`。 |
| 显式竖屏游戏 | 通过 | `adb am start ... -e GAME_ORIENTATION portrait` 后，`WebGameActivity` 保持 `SCREEN_ORIENTATION_SENSOR_PORTRAIT`，截图尺寸 `1080 x 2400`；截图 `/tmp/game-studio-01eb61e-explicit-portrait.png`。 |
| 游戏外恢复方向 | 通过 | 启动 `GameListActivity` 后，`mCurrentAppOrientation=SCREEN_ORIENTATION_FULL_USER`，截图尺寸 `1080 x 2400`；截图 `/tmp/game-studio-01eb61e-list.png`。 |
| 内置游戏方向配置说明 | 通过 | 当前 `game_distribution.json` 和内置包 `game.json` 均声明竖屏，因此从列表点击内置游戏会按配置竖屏；横屏游戏需由远端/分发配置或启动参数声明 `landscape`。 |

## 5. 未完成/待真机回归

| 项目 | 当前状态 | 后续验收方式 |
|---|---|---|
| 本轮 UI 真机截图比对 | 通过 | 已安装 run `26561542364` 的 arm64 debug artifact 并截图检查列表和 Profile。 |
| Profile 激励体系真机回归 | 通过 | 已验证空态、游戏会话写入、积分、历史、奖励进度和排行榜刷新。 |
| 字号放大与 TalkBack | 待真机专项 | 真机开启字体放大和 TalkBack，检查卡片、按钮、设置项、截图项读屏顺序。 |
| Release 安装验证 | 待 CI artifact | `Android Release APK` 成功后下载安装 `game-studio-release-arm64-v8a-apk`。 |

## 6. 风险记录

- 本轮未在本地生成 APK，遵守 CI-only 安装约束。
- `gh run download` 在本机下载大 artifact 时长时间无落盘；最终改用同一 artifact 的 Range 请求分段下载并校验 zip 大小与 `unzip -t`。
- 本地 `aapt2` 二进制无法直接运行，报 `qemu-x86_64: Could not open '/lib64/ld-linux-x86-64.so.2'`；已用 XML/引用检查和 Gradle 配置检查替代，最终资源编译仍以 GitHub Actions 为准。
- 旧 debug CI run `26557379625` 失败点为外部 `Dave-he/cocos4-rust` 最新 HEAD 语法错误；已固定到历史成功期提交，并由本轮 run `26561542364` 确认恢复。
- 当前内置游戏元数据均为竖屏，横屏展示需要分发配置明确声明；本轮已用显式 `landscape` 真机路径验证锁定链路。
