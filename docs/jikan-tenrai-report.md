# Jikan 与 Tenrai 接入报告

核验日期：2026-08-09。

## 结论

Hoshimi 默认使用 Tenrai 补充 MAL 评分，Jikan 只保留独立连接诊断，不参与详情、探索、画像、榜单或缓存填充的自动回退。Bangumi 仍是作品资料和放送时间表的主源；Tenrai 不覆盖已有 Bangumi 标题、简介、标签或封面。

## 当前接口实测

在同一网络环境下以无凭据的只读请求测试：

| 服务 | 搜索 | 本季 | 榜单 | 观察耗时 |
| --- | --- | --- | --- | --- |
| Tenrai `v1` | 200 | 200 | 200 | 约 2.3-4.8 秒 |
| Jikan `v4` | 504 | 504 | 504 | 约 1.7-2.0 秒后返回网关错误 |

Tenrai 文档入口为 <https://api.tenrai.org/documentation>。应用适配的端点为 `anime`、`anime/{id}`、`top/anime` 和 `seasons/now`，响应通过兼容 DTO 解析，并允许服务新增未知字段。

Jikan 的 `504` 响应表示其服务无法连接 MyAnimeList，并非 Android 本地 DNS、TLS 或请求队列超时。社区 issue [jikan-me/jikan#596](https://github.com/jikan-me/jikan/issues/596) 记录了同类失败和端点可用性不一致；其中社区转述的时间表为 2026-09-01 进入 brownout、2026-10-01 停止公共 API。该时间表不是仓库公告，发布前仍应再次核验服务方正式通知。

## 请求与缓存策略

- Tenrai 单并发，最多约每秒 1 次、每分钟 30 次，并服从服务端 `Retry-After` 与冷却。
- 用户打开详情时，主源缓存先显示，Tenrai 只补充缺失或过期的 MAL 评分。
- 放送时间表先查询 Bangumi，再用 AniList 做高置信时刻匹配；只有仍缺少具体时刻时才查询 Tenrai 本季数据。
- Tenrai 本季结果在进程内缓存 6 小时。首页缓存 30 分钟，因此正常启动、返回首页和页面切换不会反复请求 Tenrai。
- `429`、`503`、`504`、DNS、TLS、连接或读取失败只进入冷却并保留旧值，不写入“无评分”负缓存。

## 替代策略

Tenrai 不可用时，详情保留 Bangumi、AniList 和本地缓存并渐进展示，不自动请求 Jikan。时间表缺少精确时刻时仍展示 Bangumi 的星期信息。这样可以避免不稳定的补充源阻塞主功能，也不会因双重回退扩大公共 API 压力。

本报告不包含抓包原文、Cookie、Authorization、Token、私有代理地址或其他敏感字段。
