package io.legado.app.ui.association

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.jayway.jsonpath.JsonPath
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookSourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.source.SourceHelp
import io.legado.app.ui.config.otherConfig.OtherConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.inputStream
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.isUri
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.mapParallel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext


class ImportBookSourceViewModel(app: Application) : BaseViewModel(app) {
    var isAddGroup = false
    var groupName: String? = null
    val errorLiveData = MutableLiveData<String>()
    val successLiveData = MutableLiveData<Int>()

    val allSources = arrayListOf<BookSource>()
    val checkSources = arrayListOf<BookSourcePart?>()
    val selectStatus = arrayListOf<Boolean>()
    val newSourceStatus = arrayListOf<Boolean>()
    val updateSourceStatus = arrayListOf<Boolean>()
    private var importStarted = false
    private var checkJob: Job? = null
    val checking = MutableLiveData(false)
    val checkProgress = MutableLiveData<Pair<Int, Int>>()
    val checkResultChanged = MutableLiveData<Int>()
    val checkResults = mutableMapOf<Int, Pair<Boolean, String>>()
    val checkReport = arrayListOf<String>()
    var passedCount = 0
        private set

    fun checkSelected(keyword: String) {
        if (checking.value == true) return
        val indices = selectStatus.indices.filter { selectStatus[it] }
        if (indices.isEmpty()) return
        indices.forEach { checkResults.remove(it) }
        checkReport.clear()
        passedCount = 0
        checkProgress.value = 0 to indices.size
        checking.value = true
        checkJob = viewModelScope.launch {
            var completed = 0
            val concurrency = checkConcurrency(OtherConfig.threadCount, indices.size)
            try {
                indices.asFlow().mapParallel(concurrency) { index ->
                    val result = withContext(Dispatchers.IO) {
                        try {
                            withTimeout(30_000L) {
                                val draft = GSON.fromJsonObject<BookSource>(
                                    GSON.toJson(allSources[index])
                                ).getOrThrow()
                                true to ImportBookSourceChecker.check(draft, keyword)
                            }
                        } catch (e: TimeoutCancellationException) {
                            coroutineContext.ensureActive()
                            false to "检测超时（30秒）"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            coroutineContext.ensureActive()
                            false to (e.message ?: e.localizedMessage ?: e.javaClass.simpleName)
                                .take(1000)
                        }
                    }
                    index to result
                }.collect { (index, result) ->
                    checkResults[index] = result
                    if (result.first) passedCount++
                    checkReport.add("${allSources[index].bookSourceName}：${if (result.first) "通过" else "已移除"}\n${result.second}")
                    checkResultChanged.value = index
                    checkProgress.value = ++completed to indices.size
                }
            } finally {
                removeFailedChecks(indices)
                checkResultChanged.value = -1
                checking.value = false
            }
        }
    }

    internal fun checkConcurrency(configured: Int, sourceCount: Int): Int {
        return configured.coerceIn(1, MAX_CHECK_CONCURRENCY).coerceAtMost(sourceCount.coerceAtLeast(1))
    }

    internal fun removeFailedChecks(indices: List<Int>) {
        // Remove only completed failures, preserving unchecked and cancelled sources.
        val failed = indices.filter { checkResults[it]?.first == false }.toSet()
        val retainedResults = mutableMapOf<Int, Pair<Boolean, String>>()
        var newIndex = 0
        allSources.indices.forEach { oldIndex ->
            if (oldIndex !in failed) {
                checkResults[oldIndex]?.let { retainedResults[newIndex] = it }
                newIndex++
            }
        }
        failed.sortedDescending().forEach { index ->
            allSources.removeAt(index)
            checkSources.removeAt(index)
            selectStatus.removeAt(index)
            newSourceStatus.removeAt(index)
            updateSourceStatus.removeAt(index)
        }
        checkResults.clear()
        checkResults.putAll(retainedResults)
    }

    fun stopChecking() {
        checkJob?.cancel()
    }

    fun selectPassed() {
        if (checking.value == true) return
        selectStatus.indices.forEach { index ->
            selectStatus[index] = checkResults[index]?.first == true
        }
    }

    val isSelectAll: Boolean
        get() {
            selectStatus.forEach {
                if (!it) {
                    return false
                }
            }
            return true
        }

    val isSelectAllNew: Boolean
        get() {
            newSourceStatus.forEachIndexed { index, b ->
                if (b && !selectStatus[index]) {
                    return false
                }
            }
            return true
        }

    val isSelectAllUpdate: Boolean
        get() {
            updateSourceStatus.forEachIndexed { index, b ->
                if (b && !selectStatus[index]) {
                    return false
                }
            }
            return true
        }

    val selectCount: Int
        get() {
            var count = 0
            selectStatus.forEach {
                if (it) {
                    count++
                }
            }
            return count
        }

    fun importSelect(finally: () -> Unit) {
        execute {
            val group = groupName?.trim()
            val keepName = AppConfig.importKeepName
            val keepGroup = AppConfig.importKeepGroup
            val keepEnable = AppConfig.importKeepEnable
            val selectSource = arrayListOf<BookSource>()
            selectStatus.forEachIndexed { index, b ->
                if (b) {
                    val source = allSources[index]
                    checkSources[index]?.let {
                        if (keepName) {
                            source.bookSourceName = it.bookSourceName
                        }
                        if (keepGroup) {
                            source.bookSourceGroup = it.bookSourceGroup
                        }
                        if (keepEnable) {
                            source.enabled = it.enabled
                            source.enabledExplore = it.enabledExplore
                        }
                        source.customOrder = it.customOrder
                    }
                    if (!group.isNullOrEmpty()) {
                        if (isAddGroup) {
                            val groups = linkedSetOf<String>()
                            source.bookSourceGroup?.splitNotBlank(AppPattern.splitGroupRegex)?.let {
                                groups.addAll(it)
                            }
                            groups.add(group)
                            source.bookSourceGroup = groups.joinToString(",")
                        } else {
                            source.bookSourceGroup = group
                        }
                    }
                    val category = detectSourceCategory(source)
                    source.bookSourceGroup = source.bookSourceGroup
                        .orEmpty()
                        .splitNotBlank(AppPattern.splitGroupRegex)
                        .toMutableList()
                        .apply {
                            if (none { it.equals(category, ignoreCase = true) }) add(category)
                        }
                        .joinToString(",")
                    source.bookSourceType = when (category) {
                        "漫画" -> BookSourceType.image
                        "动漫" -> BookSourceType.video
                        else -> source.bookSourceType.takeIf {
                            it == BookSourceType.audio || it == BookSourceType.file
                        } ?: BookSourceType.default
                    }
                    selectSource.add(source)
                }
            }
            SourceHelp.insertBookSource(*selectSource.toTypedArray())
            ContentProcessor.upReplaceRules()
        }.onFinally {
            finally.invoke()
        }
    }

    fun importSource(text: String) {
        if (importStarted) return
        importStarted = true
        execute {
            val mText = text.trim()
            when {
                mText.isJsonObject() -> {
                    kotlin.runCatching {
                        val json = JsonPath.parse(mText)
                        json.read<List<String>>("$.sourceUrls")
                    }.onSuccess { listUrl ->
                        listUrl.forEach {
                            importSourceUrl(it)
                        }
                    }.onFailure {
                        GSON.fromJsonObject<BookSource>(mText).getOrThrow().let {
                            if (it.bookSourceUrl.isEmpty()) {
                                throw NoStackTraceException("不是书源")
                            }
                            allSources.add(it)
                        }
                    }
                }

                mText.isJsonArray() -> GSON.fromJsonArray<BookSource>(mText).getOrThrow()
                    .let { items ->
                        val source = items.firstOrNull() ?: return@let
                        if (source.bookSourceUrl.isEmpty()) {
                            throw NoStackTraceException("不是书源")
                        }
                        allSources.addAll(items)
                    }

                mText.isAbsUrl() -> {
                    importSourceUrl(mText)
                }

                mText.isUri() -> {
                    val uri = Uri.parse(mText)
                    uri.inputStream(context).getOrThrow().use { inputS ->
                        GSON.fromJsonArray<BookSource>(inputS).getOrThrow().let {
                            val source = it.firstOrNull() ?: return@let
                            if (source.bookSourceUrl.isEmpty()) {
                                throw NoStackTraceException("不是书源")
                            }
                            allSources.addAll(it)
                        }
                    }
                }

                else -> throw NoStackTraceException(context.getString(R.string.wrong_format))
            }
        }.onError {
            errorLiveData.postValue("ImportError:${it.localizedMessage}")
            AppLog.put("ImportError:${it.localizedMessage}", it)
        }.onSuccess {
            comparisonSource()
        }
    }

    private suspend fun importSourceUrl(url: String) {
        okHttpClient.newCallResponseBody {
            if (url.endsWith("#requestWithoutUA")) {
                url(url.substringBeforeLast("#requestWithoutUA"))
                header(AppConst.UA_NAME, "null")
            } else {
                url(url)
            }
        }.decompressed().byteStream().use {
            GSON.fromJsonArray<BookSource>(it).getOrThrow().let { list ->
                val source = list.firstOrNull() ?: return@let
                if (source.bookSourceUrl.isEmpty()) {
                    throw NoStackTraceException("不是书源")
                }
                allSources.addAll(list)
            }
        }
    }

    private fun comparisonSource() {
        execute {
            allSources.forEach {
                val source = appDb.bookSourceDao.getBookSourcePart(it.bookSourceUrl)
                checkSources.add(source)
                selectStatus.add(source == null || source.lastUpdateTime < it.lastUpdateTime)
                newSourceStatus.add(source == null)
                updateSourceStatus.add(source != null && source.lastUpdateTime < it.lastUpdateTime)
            }
            successLiveData.postValue(allSources.size)
        }
    }

    private fun detectSourceCategory(source: BookSource): String {
        if (source.bookSourceType == BookSourceType.image) return "漫画"
        if (source.bookSourceType == BookSourceType.video) return "动漫"
        if (source.bookSourceType == BookSourceType.audio ||
            source.bookSourceType == BookSourceType.file
        ) return "小说"

        val identity = listOf(
            source.bookSourceName,
            source.bookSourceUrl,
            source.bookSourceComment.orEmpty()
        ).joinToString(" ").lowercase()
        val group = source.bookSourceGroup.orEmpty().lowercase()
        val contentRule = source.ruleContent
        val rules = listOf(
            source.searchUrl.orEmpty(),
            source.exploreUrl.orEmpty(),
            contentRule?.content.orEmpty(),
            contentRule?.subContent.orEmpty(),
            contentRule?.nextContentUrl.orEmpty(),
            contentRule?.webJs.orEmpty(),
            contentRule?.sourceRegex.orEmpty(),
            contentRule?.replaceRegex.orEmpty(),
            source.ruleToc?.chapterUrl.orEmpty(),
            source.ruleBookInfo?.tocUrl.orEmpty(),
            source.ruleBookInfo?.downloadUrls.orEmpty()
        ).joinToString(" ").lowercase()

        var novelScore = 0
        var mangaScore = 0
        var videoScore = 0

        novelScore += identity.keywordScore("小说", "阅读", "文学", "书城", "novel") * 3
        mangaScore += identity.keywordScore("漫画", "manhua", "manga", "comic") * 3
        videoScore += identity.keywordScore("视频", "动画", "番剧", "影视", "anime", "video") * 3

        novelScore += group.keywordScore("小说", "文本", "阅读")
        mangaScore += group.keywordScore("漫画", "manga", "comic")
        videoScore += group.keywordScore("动漫", "动画", "番剧", "视频", "影视")

        novelScore += rules.keywordScore(
            "content@text", "textnodes", "正文", "chaptercontent", "article"
        ) * 2
        mangaScore += rules.keywordScore(
            "img@src", "imageurl", "imagelist", "chapterimage", ".jpg", ".jpeg", ".webp"
        ) * 2
        if (!contentRule?.imageStyle.isNullOrBlank()) mangaScore += 3
        if (!contentRule?.imageDecode.isNullOrBlank()) mangaScore += 3
        videoScore += rules.keywordScore(
            ".m3u8", ".mp4", "hls", "videourl", "playerurl", "playlist", "<video"
        ) * 3

        // “动漫之家”等站点名称通常指漫画；单独出现“动漫”只作为弱提示。
        if (identity.contains("动漫之家")) mangaScore += 6
        if (identity.contains("漫画小说") || identity.contains("动漫小说")) novelScore += 4

        return when {
            videoScore >= 3 && videoScore > mangaScore && videoScore > novelScore -> "动漫"
            mangaScore >= 3 && mangaScore > videoScore && mangaScore > novelScore -> "漫画"
            else -> "小说"
        }
    }

    private fun String.keywordScore(vararg keywords: String): Int =
        keywords.count { contains(it, ignoreCase = true) }

    companion object {
        private const val MAX_CHECK_CONCURRENCY = 16
    }

}
