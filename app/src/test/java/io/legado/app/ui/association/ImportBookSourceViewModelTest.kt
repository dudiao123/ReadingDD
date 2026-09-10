package io.legado.app.ui.association

import android.app.Application
import io.legado.app.data.entities.BookSource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ImportBookSourceViewModelTest {
    private fun createModel(): ImportBookSourceViewModel {
        return ImportBookSourceViewModel(RuntimeEnvironment.getApplication()).apply {
            repeat(4) { index ->
                allSources.add(BookSource(bookSourceUrl = "https://example.com/$index"))
                checkSources.add(null)
                selectStatus.add(index != 3)
                newSourceStatus.add(index % 2 == 0)
                updateSourceStatus.add(index % 2 != 0)
            }
        }
    }

    @Test
    fun `failed draft removal preserves pending sources and aligned selection metadata`() {
        val model = createModel()
        model.checkResults[0] = false to "login required"
        model.checkResults[2] = true to "passed"

        model.removeFailedChecks(listOf(0, 1, 2))

        assertEquals(listOf("https://example.com/1", "https://example.com/2", "https://example.com/3"),
            model.allSources.map { it.bookSourceUrl })
        assertEquals(listOf(true, true, false), model.selectStatus)
        assertEquals(listOf(false, true, false), model.newSourceStatus)
        assertEquals(listOf(true, false, true), model.updateSourceStatus)
        assertEquals(3, model.checkSources.size)
        assertEquals(mapOf(1 to (true to "passed")), model.checkResults)
        model.selectPassed()
        assertEquals(listOf(false, true, false), model.selectStatus)
    }

    @Test
    fun `cancellation without results leaves every draft untouched`() {
        val model = createModel()
        val original = model.allSources.toList()
        model.removeFailedChecks(listOf(0, 1, 2))
        assertEquals(original, model.allSources)
        assertEquals(listOf(true, true, true, false), model.selectStatus)
    }

    @Test
    fun `multiple failures are removed without shifting the wrong draft`() {
        val model = createModel()
        model.checkResults[0] = false to "timeout"
        model.checkResults[2] = false to "captcha"
        model.removeFailedChecks(listOf(0, 1, 2))
        assertEquals(listOf("https://example.com/1", "https://example.com/3"),
            model.allSources.map { it.bookSourceUrl })
        assertEquals(listOf(true, false), model.selectStatus)
        assertEquals(2, model.checkSources.size)
    }

    @Test
    fun `check concurrency follows user setting but stays within work and safety limits`() {
        val model = createModel()
        assertEquals(1, model.checkConcurrency(1, 100))
        assertEquals(4, model.checkConcurrency(16, 4))
        assertEquals(16, model.checkConcurrency(32, 100))
        assertEquals(1, model.checkConcurrency(0, 0))
    }
}
