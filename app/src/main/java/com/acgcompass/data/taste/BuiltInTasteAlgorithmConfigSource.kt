package com.acgcompass.data.taste

import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.domain.taste.TasteAlgorithmConfig
import com.acgcompass.domain.taste.TasteAlgorithmConfigSource
import com.acgcompass.domain.taste.TasteAlgorithmConfigSource.ImportResult
import com.acgcompass.domain.taste.validate
import javax.inject.Inject
import javax.inject.Singleton

/** 内置算法。导入替换暂未开放：只做解析和校验，结果不生效。 */
@Singleton
class BuiltInTasteAlgorithmConfigSource @Inject constructor() : TasteAlgorithmConfigSource {
    override fun current(): TasteAlgorithmConfig = TasteAlgorithmConfig.DEFAULT

    override suspend fun importReplacement(text: String): ImportResult {
        val parsed = runCatchingCancellable { TasteAlgorithmConfig.decode(text) }
        val problems = parsed.fold(
            onSuccess = { it.validate() },
            onFailure = { listOf("无法解析：${it.message.orEmpty().take(MAX_MESSAGE)}") },
        )
        return if (problems.isEmpty()) ImportResult.NotAvailable else ImportResult.Invalid(problems)
    }

    private companion object {
        const val MAX_MESSAGE = 200
    }
}
