package com.acgcompass.domain.model

/** 设置里「后台任务」页列出的任务。[key] 是记录里的主键，不能改。 */
enum class BackgroundTaskKind(val key: String) {
    SYNC("bangumi_sync"),
    OUTBOX("bangumi_outbox"),
    FEATURE_ENRICHMENT("feature_enrichment"),
    RATING_BACKFILL("rating_backfill"),
    CACHE_FILL("cache_fill"),
    POOL_EXPAND("local_pool_expand"),
    AUTO_BACKUP("auto_backup"),
    DETAIL_EXTRAS("detail_extras"),
    ;

    companion object {
        fun fromKey(key: String): BackgroundTaskKind? = entries.firstOrNull { it.key == key }
    }
}

/** 一类后台任务现在的状况。时间都是毫秒时间戳，没有就是 null。 */
data class BackgroundTaskStatus(
    val kind: BackgroundTaskKind,
    val running: Boolean = false,
    val lastStartedAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null,
    val lastError: String? = null,
    val lastSummary: String? = null,
    /** 下一次按计划运行的时间；没有计划（只在需要时运行）为 null。 */
    val nextRunAt: Long? = null,
    /** 还没完成的条目（例如待推送的记录、等着重试的补评分），没有为 null。 */
    val pendingNote: String? = null,
    /** 任务是否已关闭（对应设置开关关掉）。 */
    val disabled: Boolean = false,
    /** 能否在页面上手动重试。 */
    val retryable: Boolean = true,
    /**
     * 失败原因是否仍然有效。有的任务（同步、自动备份）成功后会清掉失败原因，原页面只要有原因就显示失败；
     * 这里跟着原页面的口径。为 null 时按失败时间是否晚于成功时间判断。
     */
    val errorCurrent: Boolean? = null,
    /** 补充公共缓存的各来源进度，和「缓存管理」页显示的是同一份记录。 */
    val cacheRuns: List<com.acgcompass.domain.cache.CacheFillRun> = emptyList(),
)
