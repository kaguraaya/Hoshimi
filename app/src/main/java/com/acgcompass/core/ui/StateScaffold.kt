package com.acgcompass.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.acgcompass.core.common.AppError

/**
 * 统一页面状态渲染脚手架。
 *
 * 将 [UiState] 的七态（外加 [UiState.Success]）映射到一致的视觉表现，保证全局状态体验统一：
 * - [UiState.Loading] → 居中的加载指示器。
 * - [UiState.Empty] → [EmptyState]（友好说明 + 「下一步」按钮）。
 * - [UiState.Error] → [ErrorCard]（简短原因 + 下一步 + 重试 + 查看文档）。
 * - [UiState.Unauthorized] → 由 [AppError.Unauthorized] 派生的 [ErrorCard]。
 * - [UiState.RateLimited] → 由 [AppError.RateLimited] 派生的 [ErrorCard]。
 * - [UiState.NoNetwork] → 由 [AppError.Network] 派生的 [ErrorCard]。
 * - [UiState.PartialMissing] → 通过 [content] 渲染已有数据；字段级缺失由内容自身显示「暂无数据」
 *   。
 * - [UiState.Success] → 通过 [content] 渲染数据。
 *
 * @param state    当前页面状态。
 * @param onRetry  错误态「重试」回调。
 * @param onCta    空状态「下一步」操作回调，携带触发的 [Cta]。
 * @param onOpenDoc 错误态「查看文档」回调，携带文档 URL。
 * @param content  数据渲染槽，用于 [UiState.Success] 与 [UiState.PartialMissing]。
 *
 */
@Composable
fun <T> StateScaffold(
    state: UiState<T>,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    onCta: (Cta) -> Unit = {},
    onOpenDoc: (String) -> Unit = {},
    content: @Composable (T) -> Unit,
) {
    when (state) {
        is UiState.Loading -> LoadingState(modifier = modifier)

        is UiState.Empty -> EmptyState(
            cta = state.cta,
            onCta = onCta,
            modifier = modifier,
        )

        is UiState.Error -> CenteredError(
            err = state.err,
            onRetry = onRetry,
            onOpenDoc = onOpenDoc,
            modifier = modifier,
        )

        is UiState.Unauthorized -> CenteredError(
            err = AppError.Unauthorized(),
            onRetry = onRetry,
            onOpenDoc = onOpenDoc,
            modifier = modifier,
        )

        is UiState.RateLimited -> CenteredError(
            err = AppError.RateLimited(),
            onRetry = onRetry,
            onOpenDoc = onOpenDoc,
            modifier = modifier,
        )

        is UiState.NoNetwork -> CenteredError(
            err = AppError.Network(),
            onRetry = onRetry,
            onOpenDoc = onOpenDoc,
            modifier = modifier,
        )

        is UiState.PartialMissing -> content(state.data)

        is UiState.Success -> content(state.data)
    }
}

/** 居中的加载指示器。 */
@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}

/** 居中包裹 [ErrorCard]，用于全页错误态。 */
@Composable
private fun CenteredError(
    err: AppError,
    onRetry: () -> Unit,
    onOpenDoc: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ErrorCard(
            error = err,
            onRetry = onRetry,
            onOpenDoc = onOpenDoc,
        )
    }
}

/**
 * 错误卡片包含以下信息：
 * - 简短原因（[AppError.cause]）。
 * - 下一步建议（[AppError.nextStep]）。
 * - 「重试」按钮：仅在 [AppError.retryable] 为 true 时显示，点击触发 [onRetry]。
 * - 「查看文档」按钮：仅在 [AppError.docUrl] 非空时显示，点击触发 [onOpenDoc]。
 *
 */
@Composable
fun ErrorCard(
    error: AppError,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    onOpenDoc: (String) -> Unit = {},
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = error.cause,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = error.nextStep,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (error.retryable) {
                Button(onClick = onRetry) {
                    Text(text = "重试")
                }
            }

            val docUrl = error.docUrl
            if (docUrl != null) {
                OutlinedButton(onClick = { onOpenDoc(docUrl) }) {
                    Text(text = "查看文档")
                }
            }
        }
    }
}

/**
 * 空状态组件。展示友好说明与「下一步」操作按钮，引导用户行动
 * （无数据 / 无网络 / 未配置 key / 无搜索结果 / 无 AI key 等场景）。
 *
 * @param cta     下一步操作；其 [Cta.label] 作为按钮文案。
 * @param message 友好说明文案，默认「暂无内容」。
 * @param onCta   按钮点击回调，回传触发的 [cta]。
 *
 */
@Composable
fun EmptyState(cta: Cta, modifier: Modifier = Modifier, message: String = "暂无内容", onCta: (Cta) -> Unit = {}) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = { onCta(cta) },
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text(text = cta.label)
        }
    }
}
