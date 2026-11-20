package com.acgcompass.data.sync

import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.SourceId as CredentialSourceId
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.entity.UserCollectionEntity
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.DustStatusSync
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** 吃灰馆与收藏状态的联动（本地「搁置」+ 回写 Bangumi），见 [DustStatusSync]。 */
@Singleton
class DustStatusSyncImpl @Inject constructor(
    private val backlogRepository: BacklogRepository,
    private val userCollectionDao: UserCollectionDao,
    private val bangumi: BangumiRemoteDataSource,
    private val credentialStore: CredentialStore,
) : DustStatusSync {
    /**
     * 把作品移入吃灰馆时，本地标记为「搁置」并回写 Bangumi（type=4），同时保存归档前状态
     * 到待补条目（prevStatus），以便移出吃灰馆时还原。
     * 已「看过」的作品不因吃灰回退状态；非数字 id（非 Bangumi 来源）仅本地标记。失败忽略（绝不崩溃）。
     */
    override suspend fun archive(workId: String) {
        runCatchingCancellable {
            val now = System.currentTimeMillis()
            val existing = userCollectionDao.getByWork(workId)
            // 先记住归档前原状态（即便无收藏记录也归档，prevStatus=null）。
            backlogRepository.archiveToDust(workId, existing?.status)
            if (existing?.status == "看过") return@runCatchingCancellable
            userCollectionDao.upsert(
                UserCollectionEntity(
                    id = existing?.id ?: "BANGUMI:$workId",
                    source = "BANGUMI",
                    sourceItemId = existing?.sourceItemId ?: workId,
                    localWorkId = workId,
                    status = "搁置",
                    rating = existing?.rating,
                    progress = existing?.progress,
                    comment = existing?.comment,
                    tags = existing?.tags.orEmpty(),
                    updatedAt = now,
                    syncedAt = existing?.syncedAt ?: now,
                    sourceUpdatedAt = existing?.sourceUpdatedAt,
                    // 归档到吃灰馆时保留既有可见性。
                    isPrivate = existing?.isPrivate ?: false,
                ),
            )
            val subjectId = workId.toIntOrNull() ?: return@runCatchingCancellable
            val configured =
                credentialStore.observeStatus().first()[CredentialSourceId.BANGUMI]?.configured == true
            if (!configured) return@runCatchingCancellable
            bangumi.updateUserCollection(subjectId = subjectId, type = 4)
        }
    }

    /**
     * 移出吃灰馆时在本地还原归档前的 Bangumi 状态，并尽力同步到云端。
     * 主操作先把条目移出吃灰区并取回 prevStatus；仅当当前本地状态确为「搁置」（由吃灰造成）且原状态非空非搁置时
     * 才还原，避免覆盖用户在吃灰期间的手动改动。Bangumi 无删除收藏端点，原状态为空时无法还原（保持现状）。
     */
    override suspend fun restore(workId: String) {
        val prev = runCatchingCancellable { backlogRepository.restoreFromDust(workId) }.getOrNull()
        if (prev == null || prev == "搁置") return
        runCatchingCancellable {
            val existing = userCollectionDao.getByWork(workId) ?: return@runCatchingCancellable
            if (existing.status != "搁置") return@runCatchingCancellable
            val now = System.currentTimeMillis()
            userCollectionDao.upsert(existing.copy(status = prev, updatedAt = now))
            val subjectId = workId.toIntOrNull() ?: return@runCatchingCancellable
            val configured =
                credentialStore.observeStatus().first()[CredentialSourceId.BANGUMI]?.configured == true
            if (!configured) return@runCatchingCancellable
            val type = bangumiTypeOf(prev) ?: return@runCatchingCancellable
            bangumi.updateUserCollection(subjectId = subjectId, type = type)
        }
    }

    /** Bangumi 收藏状态名 → type 编码（1想看/2看过/3在看/4搁置/5抛弃）；未知返回 null。 */
    private fun bangumiTypeOf(status: String): Int? = when (status) {
        "想看" -> 1
        "看过" -> 2
        "在看" -> 3
        "搁置" -> 4
        "抛弃" -> 5
        else -> null
    }
}
