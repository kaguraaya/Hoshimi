package com.acgcompass.domain.tastecode

/** 生成口味码的草稿：除代表作外都已算好；[candidates] 是默认入选的代表作，生成页让用户逐部去掉。 */
data class TasteCodeDraft(val base: TasteCode, val candidates: List<RepresentativeWork>)

/** 界面展示一部代表作用的信息；本地没有这部作品时 [title] 为 null。 */
data class TasteCodeWorkInfo(val subjectId: Int, val title: String?, val coverUrl: String?)

interface TasteCodeRepository {
    /** 用当前口味画像和收藏生成草稿；画像还没算出来（没有评分数据）时返回 null。 */
    suspend fun draft(): TasteCodeDraft?

    /** 本地作品库里这些条目的标题和封面。 */
    suspend fun workInfo(subjectIds: Collection<Int>): Map<Int, TasteCodeWorkInfo>

    /** 我的收藏里所有 Bangumi 条目 id（对方推荐的作品要去掉我已经收藏过的）。 */
    suspend fun collectedSubjectIds(): Set<Int>
}
