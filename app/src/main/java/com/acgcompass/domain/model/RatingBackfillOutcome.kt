package com.acgcompass.domain.model

/** 后台补一部作品其它平台评分的结果。 */
enum class RatingBackfillOutcome {
    /** 各平台都给了确定答复（找到评分，或确认没有）。 */
    CHECKED,

    /** 有请求因为网络、限流或服务端错误没拿到结果，过几小时再试。 */
    TRANSIENT_FAILURE,

    /** 其它错误（例如本地数据异常），按较长间隔再试。 */
    FAILED,
}
