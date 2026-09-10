package io.legado.app.ui.association

import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.source.exploreKinds
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.model.webBook.WebBook

/** Checks a draft without inserting it into the source database. */
internal object ImportBookSourceChecker {
    suspend fun check(source: BookSource, keyword: String): String =
        SourceVerificationHelp.withoutInteraction(source) { checkRules(source, keyword) }

    private suspend fun checkRules(source: BookSource, keyword: String): String {
        val books = if (!source.searchUrl.isNullOrBlank()) {
            WebBook.searchBookAwait(source, source.getCheckKeyword(keyword))
        } else {
            val url = source.exploreKinds().firstOrNull { !it.url.isNullOrBlank() }?.url
                ?: throw NoStackTraceException("没有可用的搜索或发现规则")
            WebBook.exploreBookAwait(source, url)
        }
        val book = books.firstOrNull()?.toBook()
            ?: throw NoStackTraceException("没有返回书籍，请更换关键词后重试")
        WebBook.getBookInfoAwait(source, book)
        if (source.bookSourceType == BookSourceType.file) {
            return "${book.name}：列表和详情规则通过（文件下载地址需打开书籍后验证）"
        }
        val chapters = WebBook.getChapterListAwait(source, book).getOrThrow()
            .asSequence()
            .filterNot { it.isVolume && it.url.startsWith(it.title) }
            .take(2)
            .toList()
        val chapter = chapters.firstOrNull()
            ?: throw NoStackTraceException("目录没有可读章节")
        if (source.bookSourceType == BookSourceType.default
            && source.getContentRule().content.isNullOrBlank()
        ) {
            throw NoStackTraceException("正文规则为空")
        }
        val content = WebBook.getContentAwait(
            source, book, chapter, chapters.getOrNull(1)?.url ?: chapter.url, needSave = false
        )
        if (content.isBlank()) throw NoStackTraceException("正文或媒体地址为空")
        return "${book.name} / ${chapter.title}：列表、详情、目录、正文规则通过（抽查一本书的一章）"
    }
}
