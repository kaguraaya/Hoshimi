package com.acgcompass.domain.taste

/**
 * 建画像的全部输入的指纹：评分（含短评和评分时间）、各作品资料的更新时间、校准池、AI 标签分类、隐式兴趣信号、模型选项。
 * 两次输入指纹相同，画像和由它算出的分数（为你排行、探索队列的打分）就完全一样，可以直接沿用上次的结果。
 *
 * 只用内容决定的值（字符串、数字、枚举名），不用对象哈希，所以跨进程、跨重启稳定。
 */
object TasteInputsKey {

    fun of(inputs: TasteBuildInputs): String {
        val h = Fnv()
        h.add("opts").add(inputs.options.toString())
        inputs.samples.sortedBy { it.subjectId }.forEach { s ->
            h.add("s").add(s.subjectId).add(s.rating ?: -1).add(s.updatedAtMillis ?: -1L)
                .add(s.comment.orEmpty()).feature(s.feature)
        }
        inputs.calibrationPool.sortedBy { it.subjectId }.forEach { h.add("p").feature(it) }
        inputs.tagOverrides.entries.sortedBy { it.key }.forEach { (tag, category) ->
            h.add("t").add(tag).add(category.key)
        }
        inputs.implicitSignals.sortedBy { it.feature.subjectId }.forEach { signal ->
            h.add("i").add(signal.kind.name).feature(signal.feature)
        }
        return h.hex()
    }

    /** 64 位 FNV-1a，逐字段喂入（字段之间加分隔，避免「ab|c」和「a|bc」撞在一起）。 */
    private class Fnv {
        private var hash = OFFSET

        fun add(text: String): Fnv {
            for (ch in text) {
                hash = (hash xor ch.code.toLong()) * PRIME
            }
            hash = (hash xor SEPARATOR) * PRIME
            return this
        }

        fun add(value: Long): Fnv = add(value.toString())

        fun add(value: Int): Fnv = add(value.toString())

        fun feature(f: WorkFeature): Fnv =
            add(f.subjectId).add(f.updatedAt).add(f.baseDataUpdatedAt).add(f.extendedDataUpdatedAt ?: -1L)

        fun hex(): String = java.lang.Long.toUnsignedString(hash, RADIX)

        private companion object {
            const val OFFSET = -0x340d631b7bdddcdbL
            const val PRIME = 0x100000001b3L
            const val SEPARATOR = 0x1fL
            const val RADIX = 16
        }
    }
}
