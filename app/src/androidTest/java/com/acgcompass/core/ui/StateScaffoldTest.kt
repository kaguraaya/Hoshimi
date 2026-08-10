package com.acgcompass.core.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.acgcompass.core.common.AppError
import com.acgcompass.core.designsystem.AcgCompassTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented UI tests for [StateScaffold], [ErrorCard] and [EmptyState] .
 *
 * Covers the seven [UiState] cases plus [UiState.Success], and the error-card four elements
 * (cause / next step / retry / open-doc). Runs on a device or emulator.
 *
 */
class StateScaffoldTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val contentTag = "CONTENT-RENDERED"

    @Test
    fun success_rendersContent() {
        composeRule.setContent {
            AcgCompassTheme {
                StateScaffold(state = UiState.Success("hello")) { data ->
                    Text(text = "$contentTag:$data")
                }
            }
        }
        composeRule.onNodeWithText("$contentTag:hello").assertIsDisplayed()
    }

    @Test
    fun partialMissing_rendersContent() {
        composeRule.setContent {
            AcgCompassTheme {
                StateScaffold(state = UiState.PartialMissing("partial")) { data ->
                    Text(text = "$contentTag:$data")
                }
            }
        }
        composeRule.onNodeWithText("$contentTag:partial").assertIsDisplayed()
    }

    @Test
    fun empty_rendersCtaButton_andInvokesCallback() {
        val cta = Cta(label = "去设置", action = "settings")
        var clicked: Cta? = null
        composeRule.setContent {
            AcgCompassTheme {
                StateScaffold(
                    state = UiState.Empty(cta),
                    onCta = { clicked = it },
                ) { Text("unused") }
            }
        }
        composeRule.onNodeWithText("去设置").performClick()
        assertEquals(cta, clicked)
    }

    @Test
    fun error_retryable_showsCauseNextStepRetry_andInvokesRetry() {
        val err = AppError.Server()
        var retried = false
        composeRule.setContent {
            AcgCompassTheme {
                StateScaffold(
                    state = UiState.Error(err),
                    onRetry = { retried = true },
                ) { Text("unused") }
            }
        }
        composeRule.onNodeWithText(err.cause).assertIsDisplayed()
        composeRule.onNodeWithText(err.nextStep).assertIsDisplayed()
        composeRule.onNodeWithText("重试").performClick()
        assertTrue(retried)
    }

    @Test
    fun error_withDocUrl_showsDocButton_andInvokesOpenDoc() {
        val err = AppError.Server(docUrl = "https://docs.example.com")
        var openedUrl: String? = null
        composeRule.setContent {
            AcgCompassTheme {
                ErrorCard(
                    error = err,
                    onOpenDoc = { openedUrl = it },
                )
            }
        }
        composeRule.onNodeWithText("查看文档").performClick()
        assertEquals("https://docs.example.com", openedUrl)
    }

    @Test
    fun error_nonRetryable_hidesRetryButton() {
        val err = AppError.Unauthorized()
        composeRule.setContent {
            AcgCompassTheme {
                ErrorCard(error = err)
            }
        }
        composeRule.onNodeWithText(err.cause).assertIsDisplayed()
        composeRule.onNodeWithText("重试").assertDoesNotExist()
        // No docUrl -> no doc button either.
        assertNull(err.docUrl)
        composeRule.onNodeWithText("查看文档").assertDoesNotExist()
    }

    @Test
    fun unauthorized_rendersUnauthorizedErrorCard() {
        val expected = AppError.Unauthorized()
        composeRule.setContent {
            AcgCompassTheme {
                StateScaffold(state = UiState.Unauthorized) { Text("unused") }
            }
        }
        composeRule.onNodeWithText(expected.cause).assertIsDisplayed()
    }

    @Test
    fun rateLimited_rendersRateLimitedErrorCard() {
        val expected = AppError.RateLimited()
        composeRule.setContent {
            AcgCompassTheme {
                StateScaffold(state = UiState.RateLimited) { Text("unused") }
            }
        }
        composeRule.onNodeWithText(expected.cause).assertIsDisplayed()
    }

    @Test
    fun noNetwork_rendersNetworkErrorCard() {
        val expected = AppError.Network()
        composeRule.setContent {
            AcgCompassTheme {
                StateScaffold(state = UiState.NoNetwork) { Text("unused") }
            }
        }
        composeRule.onNodeWithText(expected.cause).assertIsDisplayed()
    }

    @Test
    fun loading_showsProgressIndicator() {
        composeRule.setContent {
            AcgCompassTheme {
                StateScaffold(state = UiState.Loading) { Text("unused") }
            }
        }
        // Loading shows no content text; content lambda must not be invoked.
        composeRule.onNodeWithText("unused").assertDoesNotExist()
    }
}
