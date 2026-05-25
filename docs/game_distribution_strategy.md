# 游戏分发策略

## 目标

App 游戏列表不再只按本地 ZIP 文件名展示，而是通过分发配置决定：

- 哪些游戏对用户可见。
- 游戏在列表中的展示顺序。
- 游戏对外展示名称、说明、文字图标、图标颜色和图片图标。

## 配置来源

1. APK 内置默认配置：`app/src/main/assets/game_distribution.json`。
2. 可选远端配置：构建时通过 `-PgameDistributionConfigUrl=<url>` 写入 manifest meta-data。
3. 调试覆盖：`game_distribution` SharedPreferences 中的 `remote_url` 优先级最高。

远端配置拉取失败时使用本地默认配置；成功拉取后会缓存 15 分钟，列表结果在进程内短缓存 60 秒，避免每次切换页面都重复扫描资源。

## 当前默认分发

默认配置由服务端风格的 `tailNumber` 开关驱动：`defaultVisible` 为 `true`，`tailNumber.enabled` 为 `true`，客户端取游戏 ID 后 2 位并只渲染 `10` 到 `30` 闭区间内的游戏。当前可见包为 `1000013`、`1000015`、`1000016`、`1000017`、`1000018`、`1000019`、`1000024`、`1000026`、`1000027`；`1000000`、`1000001`、`1000006`、`1000007`、`1000031` 会被尾号规则动态屏蔽。

## 合并规则

- App 先扫描 `/sdcard/game-demo/*.zip`，再扫描 APK 内置 `assets://games/*.zip`，同一游戏 ID 只保留第一份。
- 内置配置作为基础，远端配置按 `id`、`asset`、`path` 或展示名匹配后覆盖字段。
- `visible: false` 或 `hidden: true` 会将游戏从列表和最近游戏中屏蔽。
- `defaultVisible: false` 可切换为服务端白名单模式，未配置的游戏默认不展示。
- `tailNumber.enabled: true` 会启用尾号开关，客户端按游戏 ID 的后 `digits` 位动态过滤，可配 `min`/`max`、`allow`、`deny`。
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
