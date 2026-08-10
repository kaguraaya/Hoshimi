package com.acgcompass.domain.model

/**
 * 时光机的「某一时刻、某个作品」的收藏状态快照单元。
 *
 * 一次快照（[Snapshot]）由一组 [CollectionState] 构成，捕获当前**收藏 / 评分 / 短评 / 进度**：
 * - [status]：收藏 / 观看状态（如 想看 / 在看 / 看过 / 搁置 / 抛弃）。来源语义自定义，缺失为 `null`。
 * - [rating]：个人评分（如 Bangumi 1–10 整数）。缺失为 `null`，绝不伪造。
 * - [shortReview]：个人短评文本。缺失为 `null`。
 * - [progress]：进度（已看集数 / 已读话数）。缺失为 `null`。
 * - [tags]：用户为自己的收藏记录填写的个人标签；与作品社区标签无关。
 * - [isPrivate]：该条收藏是否「仅自己可见 / 私密」（Bangumi `private`）。默认公开（`false`）。
 *
 * 纯领域模型（无 Android 依赖），供纯函数 [com.acgcompass.domain.usecase.SnapshotDiff] 计算差异。
 */
data class CollectionState(
    val workId: String,
    val status: String? = null,
    val rating: Int? = null,
    val shortReview: String? = null,
    val progress: Int? = null,
    val tags: List<String> = emptyList(),
    val isPrivate: Boolean = false,
)

/**
 * 两个快照状态之间、针对单个作品单个维度的一条**语义变更**。
 *
 * 这是 [SnapshotDiff] 纯函数的输出，仅描述「变了什么」，不含持久化关注点（id / snapshotId /
 * changedAt）。数据层据此生成可持久化的 [ChangeLog]。
 */
data class SnapshotChange(
    val workId: String,
    val changeType: ChangeType,
    val field: String? = null,
    val oldValue: String? = null,
    val newValue: String? = null,
)
