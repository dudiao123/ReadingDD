package io.legado.app.ui.association

import android.app.Application
import com.google.gson.reflect.TypeToken
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ImportBookSourceFileIntegrationTest {

    // Opt-in test for checking a user-supplied source collection against live sites.
    @Test
    fun checkExternalSourceFile() = runBlocking {
        RuntimeEnvironment.getApplication().injectAsAppCtx()
        val input = System.getenv("LEGADO_SOURCE_TEST_FILE")?.let(::File)
        assumeTrue("Set LEGADO_SOURCE_TEST_FILE to run this network test", input?.isFile == true)
        val output = System.getenv("LEGADO_SOURCE_REPORT")?.let(::File)
            ?: File(input!!.parentFile, "legado-source-check-report.json")
        val keyword = System.getenv("LEGADO_SOURCE_KEYWORD") ?: "斗罗大陆"
        val limit = System.getenv("LEGADO_SOURCE_LIMIT")?.toIntOrNull() ?: Int.MAX_VALUE
        val type = object : TypeToken<List<BookSource>>() {}.type
        val sources: List<BookSource> = GSON.fromJson(input!!.readText(), type)
        val semaphore = Semaphore(16)
        val startedAt = System.currentTimeMillis()
        val results = sources.take(limit).mapIndexed { index, source ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val itemStartedAt = System.currentTimeMillis()
                    val result = runCatching {
                        withTimeout(30_000) {
                            ImportBookSourceChecker.check(source, keyword)
                        }
                    }
                    linkedMapOf<String, Any?>(
                        "index" to index,
                        "bookSourceName" to source.bookSourceName,
                        "bookSourceUrl" to source.bookSourceUrl,
                        "bookSourceType" to source.bookSourceType,
                        "passed" to result.isSuccess,
                        "message" to result.fold({ it }, { it.localizedMessage ?: it.javaClass.simpleName }),
                        "durationMs" to System.currentTimeMillis() - itemStartedAt,
                    )
                }
            }
        }.awaitAll()
        val report = linkedMapOf<String, Any?>(
            "input" to input.absolutePath,
            "keyword" to keyword,
            "tested" to results.size,
            "passed" to results.count { it["passed"] == true },
            "failed" to results.count { it["passed"] == false },
            "durationMs" to System.currentTimeMillis() - startedAt,
            "results" to results,
        )
        withContext(Dispatchers.IO) {
            output.parentFile?.mkdirs()
            output.writeText(GSON.toJson(report))
        }
        println("SOURCE_CHECK_REPORT=${output.absolutePath}")
        println("SOURCE_CHECK_SUMMARY tested=${results.size} passed=${report["passed"]} failed=${report["failed"]}")
    }
}
