package com.acgcompass.domain.taste

/**
 * 排序用的轻量分：和完整打分（[ComputeTasteMatchUseCase]，`withReasons = false`）的分数完全相同，只是不带推荐理由。
 * 为你排行的整库排序、探索队列和今晚看什么的粗排共用；画像输入没变时可以存盘、跨重启复用。
 *
 * @property score 口味匹配度（0–100）。
 * @property satisfaction 预计满意度（0–100），探索队列排序用。
 * @property familiarity 画像对这部作品的熟悉度（0–1）。
 * @property highChance 打 9 分以上的可能；没有评分模型时为 null。
 */
data class LightScore(
    val score: Int,
    val satisfaction: Int,
    val familiarity: Double,
    val highChance: Double?,
) {
    companion object {
        fun of(match: TasteMatchResult) = LightScore(
            score = match.score,
            satisfaction = match.satisfaction,
            familiarity = match.familiarity,
            highChance = match.prediction?.highChance,
        )
    }
}
