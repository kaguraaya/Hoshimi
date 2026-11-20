package com.acgcompass.data.local

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton

/** 在一个本地数据库事务里执行 [run] 的代码：中途出错全部回滚。抽成接口便于测试注入。 */
interface LocalTransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

@Singleton
class RoomTransactionRunner @Inject constructor(
    private val database: AcgCompassDatabase,
) : LocalTransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = database.withTransaction { block() }
}
