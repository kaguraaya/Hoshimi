package com.acgcompass.domain.visualnovel

object VisualNovelContentPolicy {
    fun canDisplay(adult: Boolean, allowAdult: Boolean): Boolean = allowAdult || !adult
}
