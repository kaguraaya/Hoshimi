package com.acgcompass.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.VisualTransformation

/**
 * 输入框。描不描边看它放在哪里（照 FlClash 的规则：该描的地方描，不该描的不描）：
 *
 * - **单独放在页面上**：描边，浮动标签嵌在线框里。这里填充色表示「结构」，
 *   一个填了色的输入框会被读成又一张卡片。
 * - **放在卡片里**、和其它填色控件在一起：不描边，取比卡片深一级的填充色。
 *
 * 两种都是圆角 16。参数与 Material 的 `OutlinedTextField` 同名，可原地替换。
 */
@Suppress("LongParameterList") // 与 Material 的 OutlinedTextField 参数一一对应，便于原地替换
@Composable
fun FlatTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
) {
    if (Surfaces.level == SurfaceLevel.Page) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            readOnly = readOnly,
            label = label,
            placeholder = placeholder,
            trailingIcon = trailingIcon,
            supportingText = supportingText,
            isError = isError,
            visualTransformation = visualTransformation,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            shape = PrismCorner.Input,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )
    } else {
        val fill = Surfaces.raisedColor
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            readOnly = readOnly,
            label = label,
            placeholder = placeholder,
            trailingIcon = trailingIcon,
            supportingText = supportingText,
            isError = isError,
            visualTransformation = visualTransformation,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            shape = PrismCorner.Input,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = fill,
                unfocusedContainerColor = fill,
                disabledContainerColor = fill,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )
    }
}
