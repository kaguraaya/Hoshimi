package com.acgcompass.domain.matching

import com.acgcompass.domain.model.SourceRef

/**
 * 一条源链接的解析结果（持久性部分）。
 *
 * 把一个外部源条目引用 [sourceRef] 与「是否被用户手动纠正过」[userOverridden] 绑定为纯领域值，
 * 供 [resolveLink] 在重新同步 / 重新计算匹配时决定是否保留用户的手动选择。
 *
 * 这是 **纯领域模型**，不涉及持久化；落地由仓库层负责。
 *
 * @property sourceRef      指向某数据源中的具体条目。
 * @property userOverridden 该链接是否被用户手动纠正过；为 `true` 时后续同步不再自动改写。
 */
public data class ResolvedLink(
    val sourceRef: SourceRef,
    val userOverridden: Boolean,
)

/**
 * 在重新同步 / 重新匹配时解析一条源链接。纯函数。
 *
 * 规则：若 [current] 已被用户手动纠正（`userOverridden == true`），则 **原样保留**，忽略本次
 * 自动重算的 [recomputed]；否则采用 [recomputed]。这保证「用户手动纠正后，后续自动同步不再覆盖」。
 *
 * @param current    当前已存的链接（可能是用户手动纠正过的）。
 * @param recomputed 本次同步重新计算得到的链接。
 * @return 若 [current] 为手动纠正则返回 [current]，否则返回 [recomputed]。
 */
public fun resolveLink(current: ResolvedLink, recomputed: ResolvedLink): ResolvedLink =
    if (current.userOverridden) current else recomputed
