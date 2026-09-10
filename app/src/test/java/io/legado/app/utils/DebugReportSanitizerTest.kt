package io.legado.app.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugReportSanitizerTest {
    @Test
    fun removesHeadersJsonSecretsAndQueryTokens() {
        val report = """
            Authorization: Bearer abc123
            Cookie=session=private
            {"access_token":"secret-token","password":"123456"}
            https://example.com/play?id=1&token=url-secret
        """.trimIndent().sanitizeDebugReport()

        assertFalse("abc123" in report)
        assertFalse("private" in report)
        assertFalse("secret-token" in report)
        assertFalse("123456" in report)
        assertFalse("url-secret" in report)
        assertTrue(report.count { it == '*' } >= 5)
    }
}
