package com.acgcompass.domain.funfeature

/**
 * 补番遗书模式——入口预留。
 *
 * 生成一句「中二补番宣言」。当前为最小可用占位实现（确定性、纯函数），后续可接入更丰富的模板与
 * 随机化。绝不伪造作品数据：只在确实有待补作品时引用其数量 / 代表作。
 */
object BingeWill {

    /**
     * 生成中二补番宣言。
     *
     * @param pendingCount 待补作品数量（非负；负数按 0 处理）。
     * @param flagshipTitle 可选的「镇坑之作」标题；为空时不引用具体作品。
     * @return 一句可直接展示的中二宣言，恒为非空。
     */
    fun declaration(pendingCount: Int = 0, flagshipTitle: String? = null): String {
        val count = pendingCount.coerceAtLeast(0)
        val flagship = flagshipTitle?.trim()?.takeIf { it.isNotEmpty() }
        return buildString {
            append("【补番遗书】若我今夜倒在屏幕前，")
            if (count > 0) {
                append("尚有 $count 部番未竟，")
            } else {
                append("此生再无未补之番，")
            }
            if (flagship != null) {
                append("请务必替我看完《$flagship》，")
            }
            append("此坑，吾必填之——纵使天台风大，也挡不住补番之魂。")
        }
    }
}
