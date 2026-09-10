package com.acgcompass.core.designsystem

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun AppSearchField(
    state: AppSearchFieldState,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    TextField(
        value = state.value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        shape = CircleShape,
        leadingIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "收起搜索")
                }
            } else {
                Icon(Icons.Filled.Search, contentDescription = null)
            }
        },
        trailingIcon = {
            if (state.value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "清除")
                }
            }
        },
        placeholder = { Text(state.placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        colors = TextFieldDefaults.colors(
            // 搜索框是填充的药丸：取比所在表面高一级的颜色（页面上是近白，卡片里是灰）。
            focusedContainerColor = Surfaces.raisedColor,
            unfocusedContainerColor = Surfaces.raisedColor,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )
}

data class AppSearchFieldState(
    val value: String,
    val placeholder: String,
)
