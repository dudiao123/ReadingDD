package io.legado.app.help.source

import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceVerificationHelpTest {
    @Test
    fun `draft check rejects captcha before opening an activity or waiting`() = runBlocking {
        val source = BookSource(bookSourceUrl = "https://example.com")
        val result = runCatching {
            SourceVerificationHelp.withoutInteraction(source) {
                SourceVerificationHelp.getVerificationResult(
                    source, "https://example.com/captcha", "Captcha", false
                )
            }
        }
        assertTrue(result.exceptionOrNull() is NoStackTraceException)
        assertEquals("书源需要登录或验证码", result.exceptionOrNull()?.message)
    }

    @Test
    fun `script cannot hide a login request by catching the exception`() = runBlocking {
        val source = BookSource(bookSourceUrl = "https://example.com")
        val result = runCatching {
            SourceVerificationHelp.withoutInteraction(source) {
                runCatching {
                    SourceVerificationHelp.startBrowser(source, "https://example.com/login", "Login")
                }
                "apparently valid content"
            }
        }
        assertEquals("书源需要登录或验证码", result.exceptionOrNull()?.message)
    }

    @Test
    fun `checks of separate drafts with the same URL do not share verification state`() = runBlocking {
        val first = BookSource(bookSourceUrl = "https://example.com")
        val second = BookSource(bookSourceUrl = first.bookSourceUrl)
        val result = SourceVerificationHelp.withoutInteraction(first) {
            val inner = runCatching {
                SourceVerificationHelp.withoutInteraction(second) {
                    SourceVerificationHelp.startBrowser(second, "https://example.com/login", "Login")
                }
            }
            assertTrue(inner.exceptionOrNull() is NoStackTraceException)
            "passed"
        }
        assertEquals("passed", result)
    }
}
