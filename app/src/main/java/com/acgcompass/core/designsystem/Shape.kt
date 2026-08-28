package com.acgcompass.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material 3 rounded shape scale for ACG Compass .
 *
 * Slightly rounder than the M3 defaults to give the unified [WorkCard] and state scaffolds a soft,
 * modern feel. The scale runs from `extraSmall` (chips/tags) up to `extraLarge` (bottom sheets and
 * the "今晚看什么" hero card on the Home screen).
 */
val AcgShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
