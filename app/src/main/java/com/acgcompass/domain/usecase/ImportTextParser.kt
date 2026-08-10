package com.acgcompass.domain.usecase

/**
 * 导入文本解析器。
 *
 * 纯 Kotlin、无 Android 依赖，是一个**纯函数**式解析器：给定一段文本，返回拆分出的候选作品
 * 列表（标题 + 可选备注 + 原始来源片段）。无 IO、无可变全局状态、可单元 / 属性测试，为后续
 * （导入解析 round-trip）提供基础。
 *
 * 支持的拆分方式：
 * - **书名号** `《》`：一段文本中所有 `《...》` 内的内容视为标题。
 * - **顿号** `、`、**逗号** `,` / `，`：作为分隔符（位于书名号内部时不拆分，保留为标题的一部分）。
 * - **换行** `\n`（兼容 `\r\n`）：按行拆分。
 * - **编号列表**：行首的 `1.` / `1、` / `1)` / `(1)` / `（1）` / `①` 等序号前缀会被剥离。
 *
 * 处理规则：剥离序号前缀 → trim → 丢弃空白条目（"dedupe blanks"）。解析器**不**对重复标题去重，
 * 重复加入待补池时的去重由后续用例负责。
 *
 * 文件来源由 [parseLineBased] 处理：按行拆分，每行第一列为标题、第二列为可选备注
 * （CSV 支持双引号包裹与 `""` 转义）。Android 侧的文件 / 剪贴板读取是另一层薄封装，本类只接收
 * 已读出的纯文本，保持可测试性。
 */
class ImportTextParser {

    /**
     * 解析粘贴的推荐清单文本，保持候选的原始出现顺序。
     */
    fun parsePastedText(raw: String): List<ParsedCandidate> {
        val out = ArrayList<ParsedCandidate>()
        forEachLine(raw) { lineText, lineOffset ->
            parseFreeformLine(lineText, lineOffset, out)
        }
        return out
    }

    /**
     * 解析剪贴板文本，与 [parsePastedText] 使用同一解析逻辑。
     * 剪贴板与粘贴只是输入来源不同，拆分规则一致。
     */
    fun parseClipboardText(raw: String): List<ParsedCandidate> = parsePastedText(raw)

    /**
     * 解析以行为单位的 TXT 或 CSV 文件内容。
     *
     * 每个非空行产出一个候选：第一列为标题、第二列（若存在）为备注。CSV 字段以逗号分隔，
     * 支持双引号包裹（字段内逗号 / 换行需被包裹）与 `""` 转义为一个 `"`。纯 TXT（无逗号）
     * 时整行即标题、无备注。行首的编号前缀同样会被剥离。
     */
    fun parseLineBased(raw: String): List<ParsedCandidate> {
        val out = ArrayList<ParsedCandidate>()
        forEachLine(raw) { lineText, lineOffset ->
            if (lineText.isBlank()) return@forEachLine
            val fields = splitCsvLine(lineText)
            val rawTitleField = fields.firstOrNull().orEmpty()
            val title = stripNumberingPrefix(rawTitleField).first.trim()
            if (title.isEmpty()) return@forEachLine
            val note = fields.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            out += ParsedCandidate(
                title = title,
                note = note,
                rawSpan = TextSpan(lineOffset, lineOffset + lineText.length, lineText),
            )
        }
        return out
    }

    /**
     * 按换行（兼容 `\r\n` / `\r`）遍历每一行，回调收到「行文本（不含换行符）」与「该行在原始
     * 文本中的起始偏移」。纯遍历，不分配中间列表。
     */
    private inline fun forEachLine(text: String, action: (lineText: String, lineOffset: Int) -> Unit) {
        var lineStart = 0
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                action(text.substring(lineStart, i), lineStart)
                // 处理 \r\n：跳过紧随的 \n。
                if (c == '\r' && i + 1 < n && text[i + 1] == '\n') i++
                lineStart = i + 1
            }
            i++
        }
        // 最后一行（无尾随换行）。
        if (lineStart < n) {
            action(text.substring(lineStart, n), lineStart)
        }
    }

    // region freeform parsing -------------------------------------------------

    /** 解析自由文本的一行：剥离序号前缀，再按书名号 / 顿号 / 逗号拆分（书名号内不拆分）。 */
    private fun parseFreeformLine(line: String, lineOffset: Int, out: MutableList<ParsedCandidate>) {
        val (content, consumed) = stripNumberingPrefix(line)
        val baseOffset = lineOffset + consumed
        extractSegments(content, baseOffset, out)
    }

    /**
     * 将一行内容按顶层分隔符（顿号 / 逗号）切成片段；位于 `《》` 内的分隔符不切分。
     * 每个片段若含 `《...》` 则提取其中内容作为标题，否则整段 trim 后作为标题。
     */
    private fun extractSegments(content: String, baseOffset: Int, out: MutableList<ParsedCandidate>) {
        var segStart = 0
        var depth = 0
        var i = 0
        while (i < content.length) {
            when (val c = content[i]) {
                '《' -> depth++
                '》' -> if (depth > 0) depth--
                else -> if (depth == 0 && isDelimiter(c)) {
                    emitSegment(content, segStart, i, baseOffset, out)
                    segStart = i + 1
                }
            }
            i++
        }
        emitSegment(content, segStart, content.length, baseOffset, out)
    }

    /** 处理单个片段：含书名号则提取每个 `《...》`，否则整段作为标题。 */
    private fun emitSegment(
        content: String,
        start: Int,
        endExclusive: Int,
        baseOffset: Int,
        out: MutableList<ParsedCandidate>,
    ) {
        if (start >= endExclusive) return
        val segment = content.substring(start, endExclusive)
        if (segment.contains('《')) {
            val matches = BRACKET_TITLE.findAll(segment).toList()
            if (matches.isNotEmpty()) {
                for (m in matches) {
                    val title = m.groupValues[1].trim()
                    if (title.isEmpty()) continue
                    val absStart = baseOffset + start + m.range.first
                    val absEnd = baseOffset + start + m.range.last + 1
                    out += ParsedCandidate(
                        title = title,
                        note = null,
                        rawSpan = TextSpan(absStart, absEnd, m.value),
                    )
                }
                return
            }
            // 未闭合书名号等异常：退化为整段处理，避免丢数据（不崩溃）。
        }
        val title = segment.trim()
        if (title.isEmpty()) return
        out += ParsedCandidate(
            title = title,
            note = null,
            rawSpan = TextSpan(baseOffset + start, baseOffset + endExclusive, segment),
        )
    }

    // endregion

    companion object {
        /** 顶层分隔符：顿号、半角逗号、全角逗号。 */
        private fun isDelimiter(c: Char): Boolean = c == '、' || c == ',' || c == '，'

        /** 匹配一对书名号及其内容（不含嵌套书名号）。 */
        private val BRACKET_TITLE = Regex("《([^《》]*)》")

        /**
         * 行首编号前缀。覆盖：`(1)` / `（1）`、`1.` / `1．` / `1、` / `1)` / `1）`、
         * 以及带圈 / 带括号的数字字符（如 `①`）。半角 / 全角数字与空白均兼容。
         */
        private val NUMBERING_PREFIX = Regex(
            "^[\\s\u3000]*(?:" +
                "[(\uFF08]\\s*[0-9\uFF10-\uFF19]+\\s*[)\uFF09]" + // (1) （1）
                "|[0-9\uFF10-\uFF19]+\\s*[.\uFF0E、)\uFF09]" + //   1. 1． 1、 1) 1）
                "|[\u2460-\u24FF\u2776-\u2793]" + //                ① 等带圈/带括号数字
                ")[\\s\u3000]*",
        )

        /**
         * 剥离行首编号前缀。返回 `(剩余文本, 已消耗的字符数)`；无前缀时消耗为 0。
         * 纯函数，幂等地处理「序号 + 标题」。
         */
        fun stripNumberingPrefix(line: String): Pair<String, Int> {
            val match = NUMBERING_PREFIX.find(line) ?: return line to 0
            val consumed = match.range.last + 1
            return line.substring(consumed) to consumed
        }

        /**
         * 极简 CSV 单行字段拆分：逗号分隔，支持双引号包裹与 `""` 转义。给定的是**单行**文本
         * （不含真正换行），引号未闭合时按已读内容兜底返回，不抛异常。
         */
        fun splitCsvLine(line: String): List<String> {
            val fields = ArrayList<String>()
            val current = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    inQuotes -> when {
                        c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                            current.append('"')
                            i++ // 转义的双引号
                        }
                        c == '"' -> inQuotes = false
                        else -> current.append(c)
                    }
                    c == '"' -> inQuotes = true
                    c == ',' -> {
                        fields += current.toString()
                        current.setLength(0)
                    }
                    else -> current.append(c)
                }
                i++
            }
            fields += current.toString()
            return fields
        }
    }
}

/**
 * 解析得到的候选作品。
 *
 * @property title 已剥离序号 / 书名号、trim 后的标题（非空）。
 * @property note 可选备注（仅 CSV/TXT 第二列提供；自由文本解析恒为 `null`）。
 * @property rawSpan 该候选在原始输入文本中的来源片段，便于 UI 高亮与 round-trip 校验。
 */
data class ParsedCandidate(
    val title: String,
    val note: String? = null,
    val rawSpan: TextSpan,
)

/**
 * 原始文本中的字符区间及其原文，用于追溯候选来源。
 */
data class TextSpan(
    val start: Int,
    val endExclusive: Int,
    val raw: String,
)

/**
 * 截图 OCR 导入预留接口；当前不执行识别。
 *
 * 设计意图：UI 在导入页提供「截图识别」入口；点击时先用 [permissionRationale] 向用户说明所需
 * 权限与隐私（图片仅在本机处理、不上传），获得授权并取得 OCR 文本后，再交由
 * [ImportTextParser.parsePastedText] 走与粘贴文本一致的拆分逻辑。
 *
 * OCR 尚未接入。实现方应优先使用端侧识别方案（如 ML Kit Text Recognition），并提供
 * 权限说明，[recognize] 在 OCR 能力落地前应抛出 [NotImplementedError]。
 */
interface ScreenshotOcrImporter {

    /** 是否已具备 OCR 能力（未实现时为 `false`，UI 据此展示「即将推出」）。 */
    val isAvailable: Boolean

    /** 向用户展示的权限与隐私说明（图片仅在本机处理、不上传第三方）。 */
    val permissionRationale: String

    /**
     * 对截图执行 OCR 并返回识别出的纯文本（随后交给 [ImportTextParser] 拆分）。
     * 当前为预留接口，未实现 OCR。
     *
     * @param imageBytes 截图的原始字节。
     */
    fun recognize(imageBytes: ByteArray): String

    companion object {
        /** 默认权限说明文案，实现方可覆盖以本地化。 */
        const val DEFAULT_PERMISSION_RATIONALE: String =
            "截图识别需要访问你选择的图片，识别仅在本机完成，不会上传任何图片或文本到第三方。"
    }
}
