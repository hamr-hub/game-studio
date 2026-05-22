# game-studio 引擎层 / App 层职责与 cocos4 对齐契约

本文件用于持续对齐 `cocos4-rust`（引擎核心）与 `game-studio/app`（Android 壳）职责边界。

## 一、职责边界

### 1) 引擎层（`cocos4-rust` + `game-studio/native`）
- 游戏包输入校验与运行时上下文构建。
- Cocos 启动前端 shim 与 API 桩：`cc`, `cc.game`, `cc.director`, `cc.assetManager`, `cc.view`, `cc.sys`, `System`, `__require`, `window` host 相关对象。
- 帧循环、暂停/恢复、销毁生命周期。
- 渲染/更新调度与 FPS 统计。
- 日志采集与错误上报缓冲。
- 平台无关资源策略（加载入口、资源根路径/包路径语义）抽象。
- 真正游戏运行逻辑（脚本引擎、场景树驱动、资源热更）一旦实现，由引擎层提供。

- 引擎侧兼容目标（可验收）：
  - 可识别并区分 `legacy-cocos2d-js` 与 `modern-systemjs`。
  - `modern-systemjs` 必须支持 `game.js -> application.js -> System.import('./application.js')` 的启动链语义。
  - 当入口语义不满足时返回 `RUNTIME_UNAVAILABLE` 与清晰原因，并驱动 App 侧回退。

### 1.1) 按 cocos4 官方接口的分层实现边界（建议）

- 引擎层实现（Rust/C++ 框架面）
  - `Bootstrap` 接口：`Game::start_with_bootstrap`、启动态码、错误码。
  - JS 运行器接入层：`js-runtime-real`、执行沙箱、生命周期回退策略。
  - 最小 cocos API 桩（`cc.game/cc.director/cc.assetManager/cc.view/cc.sys`）用于 `cocos` 脚本入口接入，后续逐步演进为真实实现。
- 资源索引的入口决策：`main_entry`、`settings.*`（含正文内容）、`runtime-style` 映射。
  - Native 场景/显示树/渲染线程模型。
  - 平台 host 对象兼容（`window`, `wx`, `ks`, `GameGlobal`, `canvas`、计时 API）作为启动依赖的最小集合。

- App 层实现（Android 壳面）
  - 存储与安全：`assets://` 与本地路径映射、解压到本地、APK/assets 授权。
  - 设备窗口生命周期：`Activity`、`Surface`、回退策略（启动失败跳转 Web）。
  - 系统行为桥接：网络、权限、保存路径、权限申请、振动/媒体能力。
  - 渲染承载面：窗口大小/方向/权限状态同步给 native。

### 2) App 层（`game-studio/app`）
- Android UI 与 `Activity/SurfaceView` 生命周期管理。
- `assets://` 到本地路径的解析与缓存（当前实现）以及系统权限。
- `NativeEngine` JNI 声明与线程调度（主线程/IO 线程调用边界）。
- 启动失败原因向用户提示（Toast/日志面板）。
- 截图、设置页、游戏列表与最近列表、最近播放持久化。

- App 不负责：
  - `game.js/application.js` 运行语义判定与执行（只向 native 提供上下文）。
  - cocos API 桩（`cc`, `System`, `__require`, `wx/ks`）的行为定义。
  - `runtime-style` 的解析与启动判定。

### 2.1) 下一步落地顺序（引擎优先）
- 第1层（引擎可控）：
  - `GameBootstrapContract` 补齐与 `settings_source` 统一注入。
  - `js-runtime-real` 的入口执行与 settings 注入链路稳定化（含 `settings.js`、`settings.json`）。
  - 补齐 cocos4 兼容桩：`cc.debug.DebugMode`、`cc.assetManager.init`、`cc.AssetManager.BuiltinBundleName` 的 MAIN/START_SCENE、`cc.sys.Platform/WECHAT_GAME`。
- 第2层（无 UI 假设）：
  - `native` 侧错误码与启动上下文不变，保持 `WebGameActivity` 回退逻辑。
  - 统一 `assets://`/zip 主路径元数据只负责透传，不承载脚本语义。
- 第3层（联调验证）：
  - 在真实游戏包上验证 `main_entry + settings + 资源路径` 三件套。
  - 再补齐输入系统/场景生命周期对齐项（待 run-ready 后续里程碑）。

### 3) 边界规则
- JNI 只承接“控制面 + 状态查询”接口。
- App 不直接实现游戏资源解析、场景启动、脚本运行；其职责仅为把上下文（Surface、路径、设置）传给 native。
- Native 不直接管理 Android UI 和页面行为。

## 二、当前已实现 JNI 兼容接口

| JNI 方法 | 当前 App 层声明 | 当前语义 |
|---|---|---|
| `nativeSetAssetManager(AssetManager)` | 已声明 | 保存 AssetManager，供原生层未来 assets-vfs 使用 |
| `nativeInit(Surface, String): Long` | 已声明 | 校验路径并启动最小运行时，失败返回 `0` |
| `nativeResize(Long, Int, Int)` | 已声明 | 记录画布尺寸 |
| `nativeDestroy(Long)` | 已声明 | 停止线程并释放实例 |
| `nativePause(Long)` | 已声明 | 暂停游戏循环 |
| `nativeResume(Long)` | 已声明 | 恢复游戏循环 |
| `nativeUpdateSettings(Long, Int, Boolean)` | 已声明 | 动态更新 FPS（阴影参数当前暂未落地） |
| `nativeGetPerformanceStats(Long): String` | 已声明 | 返回 FPS / frame ms / 分辨率 / bootstrap 状态 |
| `nativeGetLogs(Long): String[]` | 已声明 | 返回 native 侧日志缓冲 |
| `nativeGetInitError(): String` | 已新增 | 返回最近一次 init 结果文本 |

## 三、计划中的 cocos4 兼容优先级

### 兼容面一览（按 cocos4 开源公开语义）

- Engine-first（cocos4-rust / game-studio/native）必须实现：引擎运行生命周期、启动入口语义、`cc`/`System` 最小兼容桩、统一错误码与回退信号、资源入口扫描与 settings 注入。
- App-only（game-studio/app）必须实现：路径方案解析、APK/assets 访问、窗口承载、权限/生命周期与 UI 回退。
- 禁止 App 模拟 Engine API 行为：App 不能提供 `cc`、`System`、`__require`、`window` 的语义替代。

| 目标对象 | OpenAPI 等价接口 | 责任方 | 当前实现状态 |
|---|---|---|---|
| 启动链 | `bootstrap.startWithEntry(main_entry, settings, runtime_style)` | Engine | ✅ 已覆盖到 `GameBootstrapContract` + `start_with_bootstrap` |
| 入口检查 | modern `game.js -> application.js -> System.import('./application.js')` | Engine | ✅ 已覆盖 mock + real 判定与注入 |
| legacy 启动 | `main.js -> window.boot` 风格 | Engine | ⚠️ 语义检测与模拟通过已完成 |
| 运行时导出 | `cc`, `System`, `__require`, `window` 全局对象 | Engine | ⚠️ shim 已覆盖关键入口，持续补齐中 |
| `cc.game` | `run`, `onStart`, `onPause/Resume`, `end` | Engine | ⚠️ 最小闭环 + 线程周期对齐 |
| `cc.director` | 场景 push/pop/run_scene/end | Engine | ⚠️ run_scene 已接入，待闭环完善 |
| `cc.assetManager` | `init`, `loadBundle`, `Bundle` | Engine | ⚠️ 检测到位，接口闭环待补 |
| `cc.sys` | 平台常量、是否移动端、事件回调 | Engine | ⚠️ 部分补齐 |
| `nativeGetBootstrapStatus` | 状态码通道 | Engine/App | ✅ 已实现，App 按协议回退 |
| `nativeGetInitError` | 错误文本通道 | Engine/App | ✅ 已实现 |

## 四、Open-Source Cocos4 接口兼容清单（分层执行）

### 4.0 判定口径（本次任务边界）

- 本文中的**引擎层**：`cocos4-rust` + `game-studio/native`，包含 `GameBootstrapContract`、`start_with_bootstrap`、JS prelude、状态码与原生回调桥接。
- 本文中的**应用层**：`game-studio/app`，包含 `Activity/Surface`、路径映射、资源解压、Web 回退。
- 规则：`cc`,`System`,`window`,`__require` 的兼容语义只允许在引擎层定义；App 侧只负责传参与回退，不模拟启动 API 语义。
- 目标含义：以下“已交付”只指“启动闭环不会因缺 API 崩溃”，不是“完整 cocos4 引擎行为复刻”。

### 4.1 引擎侧必须先交付（`cocos4-rust` + `game-studio/native`）

| API 类别 | 关键 API | 目标行为 | 当前状态 |
|---|---|---|---|
| 启动契约 | `GameBootstrapContract`, `Game::start_with_bootstrap`, `RuntimeUnavailable` | 统一入口签名与错误码 | ✅ |
| Bootstrap 入口 | `main.js`, `application.js`, `game.js`, `cc.boot/game.start` | 解析/注入并验证入口存在与语义 | ✅ |
| 运行时对象 | `cc`, `window`, `cc.game`, `cc.director`, `cc.assetManager`, `cc.view`, `cc.sys` | 提供最小可运行桩到容错执行 | ✅（持续补齐中） |
| Window/DOM 兼容 | `window.addEventListener/removeEventListener`, `window.dispatchEvent`, `window.parent/top`, `window.performance`, `window.document`, `window.TouchEvent/MouseEvent/DeviceMotionEvent` | 启动期宿主对象兼容：事件模型与文档入口 | ✅（持续补齐中） |
| 模块系统 | `window.__require`, `window.System.register`, `window.System.import`, `System.warmup` | 支持 modern-systemjs 启动链 | ✅（已覆盖关键路径） |
| 资源配置 | `settings.json`, `settings.js`, `_CCSettings` | 注入到 JS 全局并保持兼容性 | ✅ |
| 调试/状态 | `cc.debug.DebugMode`, `cc.log/warn/error` | 基础日志行为 | ✅（持续补齐中） |
| 生命周期 | `cc.game.setFrameRate`, `cc.game.pause/resume`, `director` 场景 API 最小回调 | 生命周期可回调不崩溃 | ✅（持续补齐中） |
| 引擎对象 | `cc.AssetManager.BuiltinBundleName`, `cc.AssetManager.init`, `cc.assetManager.loadBundle` | 常量与入口 API 兼容 | ✅（基础桩已具备） |

### 4.1.1 本轮已补齐（落地证据）

- `cc.macro`：`CLEANUP_IMAGE_CACHE` / `CLEANUP_MATERIAL_CACHE` / `ENABLE_MULTI_TOUCH`
- `cc.resources`：`load` / `loadDir` / `loadRemote` / `preload` / `getAssetInfo` / `release`
- 基础对象：`cc.find`、`cc.instantiate`
- 向量与颜色：`cc.Vec2`、`cc.Vec3`、`cc.v2`、`cc.v3`、`cc.color`、`cc.Color`
- 动画与注册：`cc.tween`、`cc.Tween`、`cc.Class`、`cc._decorator`、`cc.Component`、`cc.Node`
- director / view：`cc.director.on`、`cc.director.off`、`cc.director.emit`、`cc.view.getVisibleSize`、`cc.view.getVisibleOrigin`、`cc.view.onResize`、`cc.view.setFrameSize`、`cc.view.setCanvasSize`、`cc.view.enableRetina`、`cc.view.resizeWithBrowserSize`
- window/document：`window.parent/top`、`window.performance`、`window.addEventListener`、`window.removeEventListener`、`window.dispatchEvent`、`window.document.createElement`、`window.document.querySelector`、`window.document.querySelectorAll`、`window.document.getElementById`、`window.document.getElementsByName`（含 `body`）、`window.document.getElementsByTagName`、`window.document.head`、`window.document.body`、`window.document.documentElement`、`window.TouchEvent`、`window.MouseEvent`、`window.DeviceMotionEvent`、`window.localStorage`（含 `length`、`key`）
- 平台适配桩：`window.wx.env.USER_DATA_PATH`、`window.wx.getSharedCanvas`、`window.wx.createInnerAudioContext`、`window.wx.createVideo`、`window.__globalAdapter`、`window.canvas.id`、`cc.path.mainFileName`

### 4.2 App 层必须先做（`game-studio/app`）

| API 类别 | 关键 API | 目标行为 | 责任归属 |
|---|---|---|---|
| 路径策略 | `assets://`、本地路径映射、缓存目录 | 把包路径转成本地可访问路径 | App |
| 生命周期 | `Activity`, `Surface`, 方向/窗口变化 | 传给 native 并管理线程生命周期 | App |
| 回退策略 | `STARTED_SIMULATED`/异常码 | 进入 `WebGameActivity` 并展示同包 Web 运行 | App |
| 配置持久化 | 最近列表、设置页、截图 | UI/体验层，不参与 JS 运行时 | App |
| 权限与系统交互 | 文件、媒体、权限、网络、振动 | 平台层能力映射 | App |

### 4.3 不应在 App 侧代理的能力

- 不应该在 App 层实现 `System.register`、`cc`、`window.__require`、`window.System.import` 的语义。
- 不应在 App 层做 `game.js -> application.js` 的语义重写（仅透传入口与状态）。
- 不应拦截 `nativeGetBootstrapStatus` 的判定逻辑（由引擎层决定可否继续原生启动）。

### 4.4 对齐到代码路径（本阶段）

- 引擎 shim 与运行态桩：`cocos4-rust/src/game/game.rs`
- 原生 preflight / 状态码与回退条件：`game-studio/native/src/lib.rs`
- App 回退决策：`game-studio/app/src/main/java/com/cocos/gamestudio/GameActivity.kt`

### P0（启动闭环必需）
1. 明确 `nativeInit` 的 path/schema：当前仅接受本地可访问路径。
2. `assets://` 在 App 层展开并传本地路径（已实现）。
3. init 失败可见化：`nativeGetInitError` + App 层提示（已实现）。
4. `nativeDestroy` 幂等且安全（当前 `surfaceDestroyed` 已调用）。

### P1（完整运行时）
1. 游戏入口加载接口（至少把 `gamePath` 里的主入口加载并构建场景）。
2. 资源定位策略统一（本地文件目录/Zip 与 APK assets）。
3. 与 cocos4 官方输入系统、尺寸变化、生命周期回调对齐。
4. 设置项能力对齐（阴影、渲染开关、debug 信息等）。

### P2（增强）
1. zero-copy assets VFS：在 native 中直接从 AssetManager 读取。
2. 错误码标准化（可结构化返回码+文本）。
3. 更细颗粒度日志级别与标签约定。

## 四、运行时注意事项
- `nativeInit` 返回 `0` 视为失败，App 必须读取 `nativeGetInitError()` 并停止游戏页。
- 当前仍为最小运行闭环；如要“能跑完整 game-demo”，先补齐 P1 的入口加载能力，再补齐 P1-2 资源定位，最后补齐 P1-3~P1-4。
- 当前 app/Web 回退链路支持 `assets://` 路径：`GameActivity` 遇到 native preflight 不通过时，直接跳转 `WebGameActivity`，`WebGameActivity` 已可解压并运行 apk-assets 中的 zip 游戏包。

## 五、状态码到 App 的回退策略（已在 Native/Android 侧对齐）

| `nativeGetBootstrapStatus` `code` | App 决策 |
|---|---|
| `STARTED` | 继续走原生渲染（真实运行态） |
| `STARTED_SIMULATED` | 回退到 `WebGameActivity`（当前 mock 接受态，仅用于接口联调） |
| 其他（含 `RUNTIME_UNAVAILABLE`/`UNSUPPORTED_RUNTIME`/`MISSING_MAIN_ENTRY`） | 自动跳转 `WebGameActivity` |

该策略可确保：
- 引擎层只负责启动能力判断，不负责 Web/HTML 回退；
- App 层仅在“原生不可运行”时接管并展示 Web 运行路径；
- 未来原生运行时接入后无需改 App 侧策略，只需在引擎层返回 `STARTED`。

## 六、引擎里尚未补齐（run-ready）清单

1. `Game::start_with_bootstrap` 已支持两种路径：`js-runtime-mock` 的语义可达性模拟和 `js-runtime-real` 的最小运行时执行；仍未接入完整 cocos4 场景调度与引擎绑定。
2. `modern-systemjs` / `legacy-cocos2d-js` 仍需接入真实执行器（含入口文件加载、模块系统、场景创建）。
3. `game-demo` 的原生可运行需要在步骤 2 落地前先通过 Web 回退兜底给出稳定体验。

## 七、原生侧 preflight 规则（新增）

- 对 `legacy-cocos2d-js` 和 `modern-systemjs`，`native` 会在调用引擎前做入口存在性校验：`bootstrap.main_entry` 必须在扫描到的候选入口列表中命中，否则返回 `MISSING_MAIN_ENTRY`（非启动错误）。
- 当入口可见时，`Game::start_with_bootstrap` 会先做 JS 入口语义探测：`legacy` 需要检测到 `window.boot`，`modern` 需要检测到 `System.register` 或 `__require`。
  - 若入口语义不符，返回 `RUNTIME_UNAVAILABLE` 且 reason 包含 `javascript bootstrap entrypoint could not be detected...`
- 当入口语义可见但 `js-runtime-real` 或 `js-runtime-mock` 未命中时，返回 `RUNTIME_UNAVAILABLE`，`message` 中会带上对应 reason（分别为执行拒绝或未接入原因）。
- `js-runtime-real` 和 `js-runtime-mock` 同时开启时，优先走 `js-runtime-real`。仅在只启用 `js-runtime-mock` 时，才会返回 `STARTED_SIMULATED`。
- 在 `cocos4-rust` 开启 `js-runtime-mock` 且未开启 `js-runtime-real` 时，若 `main_entry_source` 命中的语义检测通过（legacy 需 `window.boot` + `cc`；modern 需 `System.register` 或 `__require` + `cc`），将返回 `STARTED_SIMULATED`。该模式为模拟启动：主要用于预发布联调，不代表完整 JS 运行器就绪。  
- 在 `cocos4-rust` 开启 `js-runtime-real` 特性时，`execute_bootstrap_real` 会在最小 JS 沙箱里执行主入口并要求命中入口标记；否则返回 `RUNTIME_UNAVAILABLE`，`message` 中会带上执行拒绝原因 `javascript runtime rejected bootstrap entrypoint during execution`。
- App 已按 `STARTED_SIMULATED` 回退到 Web 路线，避免把模拟通过误判为“原生已可运行”。
  - 当前 `game-studio/native` 默认启用了 `js-runtime-mock + js-runtime-real`（`native` crate feature）；若需要回到严格未执行模式，可在构建时仅保留模拟器模式（如 `cargo build --no-default-features --features js-runtime-mock`）。
- `UNSUPPORTED_RUNTIME` 仍保留用于样式类型不归一化到原生可解析集合的场景。
- `build_bootstrap_contract` 现已附带 `main_entry_source`（可读的主入口文本）与 `settings_source`（settings.js/json 正文）；若 entry/ settings 存在但文本读取失败，则状态机仍会按可用性继续判定（通常落到 `RUNTIME_UNAVAILABLE`）。
- `execute_bootstrap_real` 在执行主入口前会先注入 `_CCSettings`，优先解析 `settings.json`；若是 `settings.js` 则执行其内容（均为容错模式，不阻断主流程）。
- `cocos4-rust` 预留 `js-runtime-probe` feature：开启后将对主入口源码做更严格的“语法探针”（平衡符号/注释/字符串）并在失败时返回细分 reason；未开启时仅保留入口语义命中检查。

## 八、现代链路验收责任（本周）

- [ ] `game-studio/native` 保证：
  - `main_entry` 选择与 `game.js` + `application.js` 注入链稳定复用；
  - `settings.json`/`settings.js` 作为 `settings_source` 注入；
  - 返回码与回退策略与文档一致，不将模拟通过误判为原生可运行。
- [ ] `cocos4-rust`（引擎核心）保 证：
  - modern-systemjs `System.register` / `System.import` / `System.warmup` 可执行；
  - `__require` 覆盖 `kwaiadapter/web-adapter/engine-adapter/system.bundle/polyfills.bundle/import-map/first-screen/firstScreen/cocos2d-adapter/main/settings/main.*`;
  - `Application` 可通过匿名/命名 `System.register` 成功提取。
- [ ] App 层保 证：
  - 不在 Android 侧实现上述启动语义；
  - 只按原生侧状态码做 Web 回退与生命周期管理。
