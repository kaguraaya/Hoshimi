package com.acgcompass.data.remote.bangumi

/**
 * 搜索 / 榜单返回的完整条目（带社区标签和标注人数）交给它，存进本地作品库当基础资料，不用为每部作品再单独请求一次。
 * 实现应在后台写库，不拖慢页面。
 */
fun interface BangumiSubjectSink {
    fun accept(subjects: List<BangumiSubjectDto>)
}
