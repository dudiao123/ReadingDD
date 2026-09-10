package io.legado.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchIdentityTest {

    @Test
    fun normalizesSpacingWidthCaseAndPunctuation() {
        assertEquals(
            "狐妖小红娘season1",
            " 狐妖小红娘：ＳＥＡＳＯＮ １ ".normalizedSearchIdentity(),
        )
        assertEquals(
            "onepiece",
            "One-Piece".normalizedSearchIdentity(),
        )
    }

    @Test
    fun keepsMeaningfulLettersAndNumbers() {
        assertEquals("斗罗大陆2", "斗罗大陆 2".normalizedSearchIdentity())
        assertEquals("斗罗大陆3", "斗罗大陆 3".normalizedSearchIdentity())
    }


    @Test
    fun recognizesMissingAndPlaceholderAuthors() {
        listOf("", "  ", "佚名", "未知", "不详", "Anonymous").forEach {
            assertEquals(true, it.isUnknownSearchAuthor())
        }
        assertEquals(false, "唐家三少".isUnknownSearchAuthor())
    }
}
