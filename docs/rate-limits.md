# 各数据源的速率上限

客户端限流在 `core/network/RateLimitPolicy.kt`，每个数据源一套「突发上限 + 每分钟上限 + 并发数」，
收到 429 / 503 后，连续撞限时本地退避按 30 秒 → 2 分钟 → 5 分钟 → 15 分钟递增，但不超过该数据源的退避上限
（默认 1 分钟，Tenrai 与 Jikan 为 15 分钟）；服务端的 `Retry-After` 或 `X-RateLimit-Reset` 更长时以服务端为准。
后台请求（`RequestPriority.BACKGROUND`）只用窗口容量的一半，有前台请求在等时让路；Bangumi 的两个并发名额里后台最多占一个。
实现在 `core/network/interceptor/RateLimitInterceptor.kt` 与 `core/network/RateLimiter.kt`。

| 数据源 | 官方说明 | 本应用的上限 | 说明 |
| --- | --- | --- | --- |
| Bangumi（api.bgm.tv） | 未公布具体数字；要求带可识别的 User-Agent | 4 秒内 5 次、每分钟 45 次、并发 2 | 详情页的条目 / 人物 / 角色可以并行；保守取值 |
| AniList | 正常每分钟 90 次，目前降级为每分钟 30 次（响应头 `X-RateLimit-Limit: 30`） | 每秒 1 次、每分钟 28 次 | 留 2 次余量给窗口对齐误差 |
| Jikan（MAL 非官方） | 每秒 3 次、每分钟 60 次；仍可能被 MyAnimeList 本身限流 | 每 5 秒 1 次、每分钟 10 次 | 只在设置里的连接测试中使用，见 [data-sources.md](data-sources.md) |
| MyAnimeList 官方 API | 未公布；社区经验约每秒 1 次 | 每秒 1 次、每分钟 30 次 | 需要用户自己的 Client ID |
| VNDB（Kana） | 5 分钟 200 次，另有每分钟约 1 秒的执行时间预算 | 每秒 1 次、每分钟 30 次（5 分钟 150 次） | |
| Tenrai | 未公布 | 每秒 1 次、每分钟 30 次，超限后最长退避 15 分钟 | 在接近上限一半时就主动放慢 |

后台批量任务（公共缓存补充、口味特征补齐、补充其它平台评分）一次只处理有限的几十部、按顺序进行，
全部受上面的上限约束；撞到 429 后同一数据源的所有请求一起退避，不会继续重试同一个请求。
