package com.acgcompass.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BangumiOAuthSecurityTest {
    @Test
    fun `redirect must match scheme host and path exactly`() {
        assertTrue(BangumiOAuthSecurity.isExactRedirect("hoshimi://oauth/bangumi/callback?code=ok&state=s"))
        assertFalse(BangumiOAuthSecurity.isExactRedirect("hoshimi://oauth/bangumi/callback/evil?code=ok"))
        assertFalse(BangumiOAuthSecurity.isExactRedirect("acgcompass://oauth.evil/bangumi/callback?code=ok"))
        assertFalse(BangumiOAuthSecurity.isExactRedirect("hoshimi://oauth/bangumi/callback#code=ok"))
    }

    @Test
    fun `authorization navigation only allows official https hosts`() {
        assertTrue(BangumiOAuthSecurity.isAllowedAuthorizationNavigation("https://bangumi.tv/oauth/authorize"))
        assertTrue(BangumiOAuthSecurity.isAllowedAuthorizationNavigation("https://bgm.tv/login"))
        assertFalse(BangumiOAuthSecurity.isAllowedAuthorizationNavigation("http://bgm.tv/login"))
        assertFalse(BangumiOAuthSecurity.isAllowedAuthorizationNavigation("https://bgm.tv.evil.example/login"))
        assertFalse(BangumiOAuthSecurity.isAllowedAuthorizationNavigation("https://user@bgm.tv/login"))
    }

    @Test
    fun `authorization navigation allows only the configured proxy origin`() {
        val authorizationUrl = "https://oauth.example/oauth/authorize?state=s"
        assertTrue(
            BangumiOAuthSecurity.isAllowedAuthorizationNavigation(
                "https://oauth.example/oauth/session",
                authorizationUrl,
            ),
        )
        assertFalse(
            BangumiOAuthSecurity.isAllowedAuthorizationNavigation(
                "https://oauth.example.evil.test/oauth/session",
                authorizationUrl,
            ),
        )
        assertFalse(
            BangumiOAuthSecurity.isAllowedAuthorizationNavigation(
                "http://oauth.example/oauth/session",
                authorizationUrl,
            ),
        )
    }

    @Test
    fun `state is required on both sides`() {
        assertTrue(BangumiOAuthSecurity.stateMatches("expected", "expected"))
        assertFalse(BangumiOAuthSecurity.stateMatches(null, "expected"))
        assertFalse(BangumiOAuthSecurity.stateMatches("expected", null))
        assertFalse(BangumiOAuthSecurity.stateMatches("expected", "different"))
    }

    @Test
    fun `generated state has adequate entropy and url-safe encoding`() {
        val first = BangumiOAuthSecurity.newState()
        val second = BangumiOAuthSecurity.newState()
        assertTrue(first.length >= 43)
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]+")))
        assertFalse(first == second)
    }
}
