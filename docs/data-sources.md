# 数据源

本文说明每个数据源在应用里的用途、回退方式和缓存策略。限流数值见 [rate-limits.md](rate-limits.md)。

## 1. 各数据源的用途

| 数据源 | 用途 | 远端代码 |
| --- | --- | --- |
| Bangumi（`api.bgm.tv`） | 主数据源：作品标题、简介、标签、封面、Staff / 角色 / 声优、短评、关联作品；每日放送；收藏与评分同步；待补池写回「想看」；OAuth 登录 | `data/remote/bangumi` |
| AniList | 评分；放送表的精确时刻匹配；公共缓存补充 | `data/remote/anilist` |
| Tenrai | 默认的 MyAnimeList 评分来源；放送表仍缺时刻时查询本季数据；公共缓存补充 | `data/remote/tenrai` |
| MyAnimeList 官方 API | 可选，需要用户自己的 Client ID；评分与搜索 | `data/remote/mal` |
| VNDB（Kana API） | 视觉小说详情；匹配到 Bangumi 条目后同时显示 Bangumi 资料与评分 | `data/remote/vndb` |
| Jikan | 只在「设置」的连接测试里使用，不参与详情、探索、画像、榜单或缓存补充 | `data/remote/jikan` |
| AI 服务商 | 无剧透评价、AI 口味匹配、标签细分；只发往用户配置的服务商 | `data/remote/ai` |

- Bangumi 是作品资料的唯一来源。其它平台的数据只补充评分和时刻，不覆盖 Bangumi 的标题、简介、标签或封面。
- 跨源匹配在 `domain/matching`：按规范化标题与媒介类型聚类，按可信度与评分人数选代表条目，避免同名小说或视觉小说抢占动画的评分。
- 每个数据源都可以在设置里单独关闭。

## 2. 详情页

1. 先显示数据库里的缓存（作品、评分、Bangumi 扩展资料），过期后在后台刷新（`DetailRepositoryImpl.observeBangumiExtras`）。
2. 条目、人物、角色请求并行发出；同一作品 60 秒内的重复请求合并（`RequestCoalescer`），前台请求不等待后台请求。
3. 其它平台的评分只补缺失或过期的部分：已关联的平台直接按 id 取，未关联的按标题去 AniList、Tenrai（以及配置了的 MAL）搜索匹配。

## 3. 放送表

先查 Bangumi 每日放送，再用 AniList 做高置信度的时刻匹配；仍缺具体时刻时才查 Tenrai 本季数据。
首页的放送数据缓存 30 分钟，周视图 6 小时，Tenrai 本季结果在进程内缓存 6 小时（`BroadcastScheduleRepositoryImpl`）。
缺少精确时刻时显示 Bangumi 的星期信息。

## 4. Bangumi 收藏同步

`BangumiSyncManager.syncCollections`：

- 首页、我的、时光机、口味分析和后台自动同步（`BangumiSyncWorker`）可能同时触发，同一时间只跑一次，后来的调用共享正在进行的那次结果；
  同步在管理器自己的作用域里运行，发起页面离开不会中断它。
- 开始前按需续期 OAuth token（`BangumiTokenRefresher`，剩余少于 3 天时续期），后台自动同步也能保持登录。
- 分页拉取全部收藏（每页 50 条，最多 1 万条）；`getMe` 和每一页遇到网络、超时、服务端错误或限流时，间隔 2 秒、5 秒各重试一次。
- 网络类失败的提示会说明 API 与网页走不同线路，并指向「设置 → 数据源 → Bangumi → API 地址」。
- 自动同步默认关闭，在「设置 → 同步」选择间隔（WorkManager 下限 15 分钟，需联网）；
  临时失败最多重试 3 次，鉴权失效等再试也不会成功的失败不重试，等下一个周期。
- 收藏在 Bangumi 上删除后，本地记录目前不会随之删除。

**线路**：Bangumi 官方 API 只有 `api.bgm.tv` 一个生产域名（经 Cloudflare），没有官方备用线路；`api.bgm38.tv` 是数据会重置的开发环境，
`next.bgm.tv` 是新版网页的私有接口，没有兼容性保证。网页 `bangumi.tv` 不经 Cloudflare、也不提供 API，所以会出现网页能打开而 API 连不上的情况。
应用支持在设置里换成社区反代或自定义地址；非官方地址默认不发送个人 Token，需要用户确认风险。
HTTP 客户端开启了 15 秒一次的 HTTP/2 心跳，长连接被中途掐断时会关闭重建，避免请求一直卡在死连接上。

## 5. 缓存与失败处理

- HTTP 缓存在 OkHttp 层，限流拦截器排在缓存之后（`NetworkModule.networkInterceptors`），命中缓存的请求不占限流额度。
- 收到 `429` / `503` 后，同一数据源的全部请求按 `Retry-After` 一起退避，不重试同一个请求。
- `429`、`503`、`504`、DNS、TLS、连接或读取失败只进入冷却并保留旧值，不写入「无评分」负缓存。
- 补充源不可用时，详情页保留 Bangumi、AniList 和本地缓存，渐进展示，不自动换用 Jikan。
- 作品资料（`work_features`）长期保存，「缓存管理」里的清理不会删除；它是口味算法的输入，见 [taste-algorithm.md](taste-algorithm.md) §2。

## 6. 后台任务

都用 WorkManager，执行时间由系统决定；请求都标为 `RequestPriority.BACKGROUND`，给前台让路。

| 任务 | 作用 | 规模 |
| --- | --- | --- |
| `WorkFeatureEnrichmentWorker` | 先把收藏与待补池的作品补成完整资料，再给本地作品库里没有资料的动画补基础资料 | 每批 20 / 40 部，有进展且有剩余才排下一批 |
| `RatingBackfillWorker` | 给只有一个平台评分的动画补其它平台评分（开关在「设置 → 推荐与口味」，默认开启） | 每次最多 20 部，约 6 小时一次；同一部 7 天内不重复查 |
| `BangumiWishSyncWorker` | 把加入待补池的作品写成 Bangumi「想看」：先查询已有状态，已有任何状态就跳过 | 队列式，失败指数退避重试 |
| 公共缓存补充 | 从榜单分页补充公共作品缓存（可在缓存管理里关闭） | 分页，受限流约束 |
| `TasteProfileAutoUpdater` | 收藏变化后只读缓存重建口味画像，缺资料的作品后台补齐 | 每批 40 部 |

## 7. Jikan 与 Tenrai 的选择依据

2026-08-09 在同一网络下以只读请求实测：

| 服务 | 搜索 | 本季 | 榜单 | 耗时 |
| --- | --- | --- | --- | --- |
| Tenrai `v1` | 200 | 200 | 200 | 约 2.3–4.8 秒 |
| Jikan `v4` | 504 | 504 | 504 | 约 1.7–2.0 秒后返回网关错误 |

Jikan 的 `504` 表示它连不上 MyAnimeList，不是本地网络问题；社区 issue [jikan-me/jikan#596](https://github.com/jikan-me/jikan/issues/596) 记录了同类失败。
因此 MAL 评分默认取自 Tenrai（文档 <https://api.tenrai.org/documentation>，适配端点 `anime`、`anime/{id}`、`top/anime`、`seasons/now`，DTO 允许服务新增字段）。
如果以后要恢复 Jikan，先重新核验它的服务状态。
