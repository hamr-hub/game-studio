# game-studio 引擎层 / App 层职责与 cocos4 对齐契约

本文件用于持续对齐 `cocos4-rust`（引擎核心）与 `game-studio/app`（Android 壳）职责边界。

## 一、职责边界

### 1) 引擎层（`cocos4-rust` + `game-studio/native`）
- 游戏包输入校验与运行时上下文构建。
- 帧循环、暂停/恢复、销毁生命周期。
- 渲染/更新调度与 FPS 统计。
- 日志采集与错误上报缓冲。
- 平台无关资源策略（加载入口、资源根路径/包路径语义）抽象。
- 真正游戏运行逻辑（脚本引擎、场景树驱动、资源热更）一旦实现，由引擎层提供。

### 2) App 层（`game-studio/app`）
- Android UI 与 `Activity/SurfaceView` 生命周期管理。
- `assets://` 到本地路径的解析与缓存（当前实现）以及系统权限。
- `NativeEngine` JNI 声明与线程调度（主线程/IO 线程调用边界）。
- 启动失败原因向用户提示（Toast/日志面板）。
- 截图、设置页、游戏列表与最近列表、最近播放持久化。

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
- 当前仍为最小运行闭环；如要“能跑完整 game-demo”，优先补齐 P1 的入口加载能力。
