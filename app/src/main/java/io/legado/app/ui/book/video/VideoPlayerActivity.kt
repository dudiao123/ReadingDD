package io.legado.app.ui.book.video

import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.MimeTypes
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.SourceConfig
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.getMediaItem
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoPlayerActivity : BaseComposeActivity(
    fullScreen = true,
    imageBg = false,
), ChangeBookSourceDialog.CallBack {

    private val player by lazy { ExoPlayerHelper.createHttpExoPlayer(this) }
    private var book: Book? = null
    private var source: BookSource? = null
    private var chapters: List<BookChapter> = emptyList()
    private var chapterIndex by mutableIntStateOf(0)
    private var chapterTitle by mutableStateOf("")
    private var loading by mutableStateOf(true)
    private var errorMessage by mutableStateOf<String?>(null)
    private var speed by mutableStateOf(1f)
    private var retryCount = 0
    private var videoLoadStartedAt = 0L
    private var loadJob: Job? = null
    private var progressJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onCreate(savedInstanceState)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                loading = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    retryCount = 0
                    val elapsed = videoLoadStartedAt.takeIf { it > 0 }
                        ?.let { (System.currentTimeMillis() - it).coerceIn(1L, 180000L) }
                    val sourceUrl = source?.bookSourceUrl
                    if (elapsed != null && sourceUrl != null) {
                        SourceConfig.adjustSourceScore(sourceUrl, 1)
                        lifecycleScope.launch(Dispatchers.IO) {
                            appDb.bookSourceDao.recordVideoSuccess(sourceUrl, elapsed)
                        }
                    }
                    videoLoadStartedAt = 0L
                }
                if (playbackState == Player.STATE_ENDED) openChapter(chapterIndex + 1)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (retryCount < 3) {
                    retryCount++
                    lifecycleScope.launch {
                        delay(retryCount * 1_000L)
                        player.prepare()
                        player.playWhenReady = true
                    }
                } else {
                    loading = false
                    source?.bookSourceUrl?.let { sourceUrl ->
                        SourceConfig.adjustSourceScore(sourceUrl, -2)
                        lifecycleScope.launch(Dispatchers.IO) {
                            appDb.bookSourceDao.recordVideoFailure(sourceUrl)
                        }
                    }
                    errorMessage = error.localizedMessage ?: "视频加载失败"
                }
            }
        })
        loadBook()
        progressJob = lifecycleScope.launch {
            while (isActive) {
                delay(5_000)
                saveProgress()
            }
        }
    }

    @Composable
    override fun Content() {
        BackHandler { finish() }
        DisposableEffect(Unit) {
            onDispose { player.pause() }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    PlayerView(context).apply {
                        layoutParams = FrameLayout.LayoutParams(-1, -1)
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        useController = true
                        keepScreenOn = true
                        player = this@VideoPlayerActivity.player
                    }
                },
                update = { it.player = player },
            )

            Text(
                text = chapterTitle,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 18.dp),
            )

            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White,
                )
            }

            errorMessage?.let { message ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Text(message, color = Color.White)
                    Button(
                        modifier = Modifier.padding(top = 12.dp),
                        onClick = {
                            retryCount = 0
                            openChapter(chapterIndex, resume = true)
                        },
                    ) { Text("重试") }
                    Button(
                        modifier = Modifier.padding(top = 8.dp),
                        onClick = {
                            book?.let { showDialogFragment(ChangeBookSourceDialog(it.name, it.author)) }
                        },
                    ) { Text("更换视频源") }
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    enabled = chapterIndex > 0,
                    onClick = { openChapter(chapterIndex - 1) },
                ) { Text("上一集") }
                Button(onClick = { cycleSpeed() }) { Text("${speed}x") }
                Button(
                    enabled = chapterIndex < chapters.lastIndex,
                    onClick = { openChapter(chapterIndex + 1) },
                ) { Text("下一集") }
            }
        }
    }

    private fun loadBook() {
        val bookUrl = intent.getStringExtra("bookUrl").orEmpty()
        lifecycleScope.launch(Dispatchers.IO) {
            val loadedBook = appDb.bookDao.getBook(bookUrl)
                ?: error("找不到该动漫")
            val loadedSource = appDb.bookSourceDao.getBookSource(loadedBook.origin)
                ?: error("找不到对应视频源")
            val loadedChapters = appDb.bookChapterDao.getChapterList(bookUrl)
            withContext(Dispatchers.Main) {
                book = loadedBook
                source = loadedSource
                chapters = loadedChapters
                chapterIndex = loadedBook.durChapterIndex.coerceIn(0, loadedChapters.lastIndex.coerceAtLeast(0))
                if (loadedChapters.isEmpty()) {
                    loading = false
                    errorMessage = "没有可播放的剧集"
                } else {
                    openChapter(chapterIndex, resume = true)
                }
            }
        }.invokeOnCompletion { throwable ->
            if (throwable != null) {
                lifecycleScope.launch {
                    loading = false
                    errorMessage = throwable.localizedMessage ?: "加载动漫信息失败"
                }
            }
        }
    }

    private fun openChapter(index: Int, resume: Boolean = false) {
        if (index !in chapters.indices) return
        saveProgress()
        loadJob?.cancel()
        loading = true
        errorMessage = null
        videoLoadStartedAt = System.currentTimeMillis()
        chapterIndex = index
        chapterTitle = chapters[index].title
        loadJob = lifecycleScope.launch(Dispatchers.IO) {
            val currentBook = book ?: return@launch
            val currentSource = source ?: return@launch
            val chapter = chapters[index]
            val nextUrl = chapters.getOrNull(index + 1)?.url
            val videoContent = WebBook.getContentAwait(
                currentSource,
                currentBook,
                chapter,
                nextUrl,
                false,
            )
            val contentLines = videoContent.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toList()
            // Some video source rules return the fetched HLS manifest followed by
            // the resolved stream URL. Never pass #EXTM3U or a .ts segment to Media3.
            val videoUrl = contentLines.lastOrNull {
                it.startsWith("http", ignoreCase = true) &&
                    it.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
            } ?: contentLines.firstOrNull {
                it.startsWith("http", ignoreCase = true)
            } ?: contentLines.firstOrNull()
                ?: error("视频地址为空")
            val resolvedMediaItem = AnalyzeUrl(
                mUrl = videoUrl,
                baseUrl = chapter.getAbsoluteURL(),
                source = currentSource,
                ruleData = currentBook,
                chapter = chapter,
                coroutineContext = coroutineContext,
            ).getMediaItem()
            val mediaItem = if (
                videoUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
            ) {
                resolvedMediaItem.buildUpon()
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build()
            } else {
                resolvedMediaItem
            }
            withContext(Dispatchers.Main) {
                player.setMediaItem(mediaItem)
                player.prepare()
                if (resume && currentBook.durChapterIndex == index) {
                    player.seekTo(currentBook.durChapterPos.toLong())
                }
                player.playWhenReady = true
            }
        }.also { job ->
            job.invokeOnCompletion { throwable ->
                if (throwable != null) {
                    lifecycleScope.launch {
                        loading = false
                        errorMessage = throwable.localizedMessage ?: "解析视频地址失败"
                    }
                }
            }
        }
    }

    private fun cycleSpeed() {
        speed = when (speed) {
            1f -> 1.25f
            1.25f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        player.setPlaybackSpeed(speed)
    }

    private fun saveProgress() {
        val currentBook = book ?: return
        if (chapters.isEmpty() || chapterIndex !in chapters.indices) return
        val position = player.currentPosition.coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        currentBook.durChapterIndex = chapterIndex
        currentBook.durChapterTitle = chapters[chapterIndex].title
        currentBook.durChapterPos = position
        currentBook.durChapterTime = System.currentTimeMillis()
        lifecycleScope.launch(Dispatchers.IO) { appDb.bookDao.update(currentBook) }
    }

    override val oldBook: Book?
        get() = book

    override fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        if (!book.isVideo) {
            toastOnUi("所选择的源不是视频源")
            return
        }
        saveProgress()
        this.source = source
        this.book = book
        this.chapters = toc
        chapterIndex = book.durChapterIndex.coerceIn(0, toc.lastIndex.coerceAtLeast(0))
        retryCount = 0
        lifecycleScope.launch(Dispatchers.IO) {
            appDb.bookDao.update(book)
            appDb.bookChapterDao.delByBook(book.bookUrl)
            if (toc.isNotEmpty()) appDb.bookChapterDao.insert(*toc.toTypedArray())
        }
        openChapter(chapterIndex, resume = true)
    }

    override fun addToBookshelf(book: Book, toc: List<BookChapter>) {
        toastOnUi("请使用替换当前书籍来切换视频源")
    }

    override fun onStop() {
        saveProgress()
        super.onStop()
    }

    override fun onDestroy() {
        loadJob?.cancel()
        progressJob?.cancel()
        player.release()
        super.onDestroy()
    }
}
