package com.acgcompass.feature.detail

import com.acgcompass.core.common.AppResult
import com.acgcompass.domain.detail.BangumiRecordUpdate
import com.acgcompass.domain.detail.PersonalRecord
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.DetailInteractionRepository
import com.acgcompass.domain.undo.UndoController

/** 编辑对话框提交的内容：未选 / 留空即清空该字段（对话框就是完整意图）。 */
internal data class RecordEdit(
    val statusLabel: String?,
    val rating: Int?,
    val progress: Int?,
    val comment: String?,
    val tags: List<String>?,
    val isPrivate: Boolean,
)

/** 进度不超过总集数；用户没选状态时按进度推导：没进度不设状态，看到最后一集为「看过」，否则「在看」。 */
internal fun RecordEdit.resolveProgress(totalEpisodes: Int?): Pair<Int?, String?> {
    val totalEps = totalEpisodes?.takeIf { it > 0 }
    val clampedProgress = progress?.let { p ->
        if (totalEps != null) p.coerceIn(0, totalEps) else p.coerceAtLeast(0)
    }
    val effectiveStatus = statusLabel ?: when {
        clampedProgress == null || clampedProgress <= 0 -> null
        totalEps != null && clampedProgress >= totalEps -> "看过"
        else -> "在看"
    }
    return clampedProgress to effectiveStatus
}

/** 状态标签 → Bangumi 收藏 type（想看1/看过2/在看3/搁置4/抛弃5）。未知返回 null。 */
internal fun bangumiTypeOf(label: String): Int? = when (label) {
    "想看" -> 1
    "看过" -> 2
    "在看" -> 3
    "搁置" -> 4
    "抛弃" -> 5
    else -> null
}

/**
 * 「我的记录」的保存与回写 Bangumi。
 * 流程：本地先写个人收藏（即时反映 UI）→ 回传 Bangumi；未配置 Bangumi 或非 Bangumi 条目则只本地保存并提示。
 * 失败不回滚本地，修改进待推送队列，联网后自动重试。
 */
internal class DetailRecordEditor(
    private val detailInteractions: DetailInteractionRepository,
    private val backlogRepository: BacklogRepository,
    private val undoController: UndoController,
) {
    /** 保存一次编辑，返回给用户的提示；全部清空时提供撤销、返回 `null`。 */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    suspend fun save(workId: String, work: Work?, edit: RecordEdit): String? {
        val (clampedProgress, effectiveStatus) = edit.resolveProgress(work?.units?.episodes)
        val rating = edit.rating
        val comment = edit.comment
        val tags = edit.tags
        // 本地先写——采用「编辑对话框即完整意图」的覆盖语义：未选状态/留空即清空该字段
        // （不回退既有值），从而支持清空状态/评分/进度/短评。
        val now = System.currentTimeMillis()
        val existing = detailInteractions.personalRecord(workId)
        // 解析真实 Bangumi subjectId（非 Bangumi 作品的裸数字 id 绝不当 subjectId）。
        val subjectId = work
            ?.takeUnless { it.primarySource == SourceId.VNDB }
            ?.let { detailInteractions.resolveBangumiSubjectId(it) }
        val configured = detailInteractions.isBangumiConfigured()
        // 全字段为空（无状态/评分/进度/短评/标签）→ 删除该收藏行，避免残留一条「暂无数据」空记录
        // 空白短评不写入，避免污染时光机基线和个人收藏记录。
        val fullyCleared = effectiveStatus == null && rating == null && clampedProgress == null &&
            comment.isNullOrBlank() && tags.isNullOrEmpty()
        if (fullyCleared) {
            // 清空记录表示停止追踪：记录和待补池条目一起移进回收站（可撤销）。Bangumi 上的收藏不动——
            // 公开接口不能删除收藏，网页上的记录在回收站里提供入口，由用户自己移除。
            val batchId = java.util.UUID.randomUUID().toString()
            val trashed = detailInteractions.trashPersonalRecord(workId, batchId)
            backlogRepository.removeToTrash(listOf(workId), batchId)
            undoController.offer(if (trashed) "已清空记录，可在回收站恢复" else "已清空记录", batchId)
            return null
        }
        detailInteractions.savePersonalRecord(
            PersonalRecord(
                id = existing?.id ?: "${work?.primarySource?.name ?: "BANGUMI"}:$workId",
                source = existing?.source ?: work?.primarySource?.name ?: "BANGUMI",
                sourceItemId = existing?.sourceItemId ?: workId,
                workId = workId,
                status = effectiveStatus,
                rating = rating,
                progress = clampedProgress,
                comment = comment,
                tags = tags ?: emptyList(),
                updatedAt = now,
                syncedAt = existing?.syncedAt ?: now,
                sourceUpdatedAt = existing?.sourceUpdatedAt,
                // 本地持久化可见性，供编辑对话框回显。
                isPrivate = edit.isPrivate,
            ),
        )
        // 应用级画像协调器会观察这次本地写入并防抖更新，避免详情页重复计算。
        val tasteNote = if (rating != null) "；口味画像将在后台更新" else ""

        if (subjectId == null) return "已本地保存（该作品在 Bangumi 无对应词条，仅本地，不同步）$tasteNote"
        if (!configured) return "已本地保存；未配置 Bangumi Token，未同步到云端$tasteNote"
        // 写回前确认条目在 Bangumi 存在，避免对无词条作品反复 PATCH/POST 报错。
        if (!detailInteractions.bangumiSubjectExists(subjectId)) {
            return "已本地保存；该作品在 Bangumi 无词条，无法同步到云端$tasteNote"
        }
        val result = detailInteractions.updateBangumiRecord(
            subjectId,
            BangumiRecordUpdate(
                type = effectiveStatus?.let { bangumiTypeOf(it) },
                rating = rating ?: 0,
                comment = comment ?: "",
                tags = tags ?: emptyList(),
                isPrivate = edit.isPrivate,
            ),
        )
        // 动画进度通过专用章节端点上传（书籍 / 漫画不上传进度，仅本地）。
        val isAnime = work?.mediaType == MediaType.ANIME
        val progressResult: AppResult<Unit>? =
            if (isAnime && clampedProgress != null && clampedProgress > 0) {
                detailInteractions.markEpisodesWatched(subjectId, clampedProgress)
            } else {
                null
            }
        return when (result) {
            is AppResult.Success -> {
                detailInteractions.markPersonalRecordSynced(workId, System.currentTimeMillis())
                val progressNote = when (progressResult) {
                    is AppResult.Failure -> "（进度同步失败：${progressResult.error.cause}）"
                    else -> ""
                }
                "已保存并同步到 Bangumi$progressNote$tasteNote"
            }
            is AppResult.Failure -> {
                // 失败的修改进待推送队列，联网后自动重试。
                detailInteractions.schedulePendingPush()
                "本地已保存，同步 Bangumi 失败，联网后会自动重试：${result.error.cause}"
            }
        }
    }

    /** 把视觉小说的本地记录同步到高置信匹配的 Bangumi 词条，返回给用户的提示。 */
    @Suppress("ReturnCount")
    suspend fun syncVndbToBangumi(work: Work): String {
        val existing = detailInteractions.personalRecord(work.id) ?: return "当前没有可同步的本地记录"
        val subjectId = detailInteractions.resolveBangumiSubjectId(work)
            ?: return "没有达到自动关联门槛的 Bangumi 词条，未同步"
        val configured = detailInteractions.isBangumiConfigured()
        if (!configured) return "请先登录 Bangumi，再手动同步这条记录"
        if (!detailInteractions.bangumiSubjectExists(subjectId)) return "匹配的 Bangumi 词条当前不可用，未同步"
        val result = detailInteractions.updateBangumiRecord(
            subjectId,
            BangumiRecordUpdate(
                type = existing.status?.let(::bangumiTypeOf),
                rating = existing.rating ?: 0,
                comment = existing.comment.orEmpty(),
                tags = existing.tags,
                isPrivate = existing.isPrivate,
            ),
        )
        return when (result) {
            is AppResult.Success -> {
                detailInteractions.markPersonalRecordSynced(work.id, System.currentTimeMillis())
                "已将这条本地记录同步到 Bangumi"
            }
            is AppResult.Failure -> "同步失败：${result.error.nextStep ?: "请稍后重试"}"
        }
    }
}
