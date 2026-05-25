# 游戏分发策略

## 目标

App 游戏列表不再只按本地 ZIP 文件名展示，而是通过分发配置决定：

- 哪些游戏对用户可见。
- 游戏在列表中的展示顺序。
- 游戏对外展示名称、说明、文字图标、图标颜色和图片图标。
- 每个游戏启动时使用横屏或竖屏。

## 配置来源

1. APK 内置默认配置：`app/src/main/assets/game_distribution.json`。
2. 可选远端配置：构建时通过 `-PgameDistributionConfigUrl=<url>` 写入 manifest meta-data。
3. 调试覆盖：`game_distribution` SharedPreferences 中的 `remote_url` 优先级最高。

远端配置拉取失败时使用本地默认配置；成功拉取后会缓存 15 分钟，列表结果在进程内短缓存 60 秒，避免每次切换页面都重复扫描资源。

## 当前默认分发

默认配置由服务端风格的 `tailNumber` 开关驱动：`defaultVisible` 为 `true`，`tailNumber.enabled` 为 `true`，客户端取游戏 ID 后 2 位并只渲染 `10` 到 `30` 闭区间内的游戏。当前可见包为 `1000013`、`1000015`、`1000016`、`1000017`、`1000018`、`1000019`、`1000024`、`1000026`、`1000027`；`1000000`、`1000001`、`1000006`、`1000007`、`1000031` 会被尾号规则动态屏蔽。当前内置包 `game.json` 均声明为竖屏，默认分发配置同步设置为 `orientation: "portrait"`。

## Web 运行兼容

内置 Cocos/Kwai 小游戏优先走原生启动，原生返回不支持时回退到 Web 沙箱。沙箱会做以下兼容处理，以保障核心游戏流程优先跑通：

- 自动为 `assets/<bundle>` 和 `remote/<bundle>` 创建根目录别名，兼容小游戏包里 `main/config.*.json`、`remote/main/config.*.json` 混用的路径布局。
- 注入最小 `wx`/`ks`/`tt`/`qg` 平台 API，包括系统信息、登录、用户信息、分包加载、触摸、音频和存储等核心接口。
- 广告 API 统一返回“已跳过并关闭”的空句柄，不展示激励视频、插屏、Banner、自定义广告或格子广告。
- 外部 HTTP/HTTPS XHR 快速返回空结果，避免 BMS、埋点、活动配置、广告配置等网络调用阻塞启动流程；本地 `file://` 资源加载仍走原生 WebView XHR。
- WebView console 的 warning/error 不再对外展示，避免用户看到沙箱兼容层或游戏源码内部错误信息。

## 合并规则

- App 先扫描 `/sdcard/game-demo/*.zip`，再扫描 APK 内置 `assets://games/*.zip`，同一游戏 ID 只保留第一份。
- 内置配置作为基础，远端配置按 `id`、`asset`、`path` 或展示名匹配后覆盖字段。
- `visible: false` 或 `hidden: true` 会将游戏从列表和最近游戏中屏蔽。
- `defaultVisible: false` 可切换为服务端白名单模式，未配置的游戏默认不展示。
- `tailNumber.enabled: true` 会启用尾号开关，客户端按游戏 ID 的后 `digits` 位动态过滤，可配 `min`/`max`、`allow`、`deny`。
- `orientation` 支持 `landscape` 和 `portrait`，用于启动原生运行页和 Web 回退页时设置方向；未配置时默认 `landscape`。
- `order` 越小越靠前；未配置 `order` 时，数组配置会使用数组顺序，对象配置不会改变已有排序。
- 排序兜底规则为 `order`、展示名、资源包名。
- `icon.label` 建议控制在 1 到 3 个字符，App 会截断过长的文字兜底图标。

## 配置示例

```json
{
  "version": 1,
  "defaultVisible": true,
  "tailNumber": {
    "enabled": true,
    "digits": 2,
    "min": 10,
    "max": 30
  },
  "games": [
    {
      "id": "1000006",
      "asset": "1000006_1.9.4.zip",
      "visible": true,
      "order": 10,
      "displayName": "Featured Game",
      "description": "Public copy shown in the game detail sheet.",
      "orientation": "portrait",
      "icon": {
        "label": "FG",
        "color": "#4DB6AC",
        "url": "https://example.com/icons/1000006.png"
      }
    },
    {
      "id": "1000019",
      "hidden": true
    }
  ]
}
```

支持字段别名：`display_name`/`name`/`title`、`sortOrder`/`sort_order`、`iconUrl`/`icon_url`/`iconUri`/`icon_uri`/`iconAsset`/`icon_asset`。
