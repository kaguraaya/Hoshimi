package com.acgcompass.domain.taste

/**
 * 口味算法参数的来源。现在固定返回内置算法 [TasteAlgorithmConfig.DEFAULT]；
 * 以后开放「上传算法文件整份替换」时由这里读写，口味引擎不需要改。
 */
interface TasteAlgorithmConfigSource {
    /** 当前生效的算法参数。 */
    fun current(): TasteAlgorithmConfig

    /** 导出当前算法为模板文本（[TasteAlgorithmConfig.FORMAT]），供编辑。 */
    fun exportTemplate(): String = TasteAlgorithmConfig.encode(current())

    /**
     * 用一份完整的模板替换当前算法。先解析并校验（[validate]）；替换功能开放前，校验通过也只返回
     * [ImportResult.NotAvailable]，不会改变当前算法。
     */
    suspend fun importReplacement(text: String): ImportResult

    sealed interface ImportResult {
        data object Applied : ImportResult

        data object NotAvailable : ImportResult

        data class Invalid(val problems: List<String>) : ImportResult
    }
}
