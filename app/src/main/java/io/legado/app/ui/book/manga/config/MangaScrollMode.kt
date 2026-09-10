package io.legado.app.ui.book.manga.config

object MangaScrollMode {
    const val PAGE_LEFT_TO_RIGHT = 1      // 单页式（从左到右）
    const val PAGE_RIGHT_TO_LEFT = 2      // 单页式（从右到左）
    const val PAGE_TOP_TO_BOTTOM = 3      // 单页式（从上到下）
    const val WEBTOON = 4                 // 条漫
    const val WEBTOON_WITH_GAP = 5        // 条漫（页面有空隙）
    const val DOUBLE_PAGE_LEFT_TO_RIGHT = 6 // 横屏双页（从左到右）
    const val DOUBLE_PAGE_RIGHT_TO_LEFT = 7 // 横屏双页（从右到左）

    val ALL = listOf(
        PAGE_LEFT_TO_RIGHT,
        PAGE_RIGHT_TO_LEFT,
        PAGE_TOP_TO_BOTTOM,
        WEBTOON,
        WEBTOON_WITH_GAP,
        DOUBLE_PAGE_LEFT_TO_RIGHT,
        DOUBLE_PAGE_RIGHT_TO_LEFT
    )

    fun labelOf(mode: Int): String = when (mode) {
        PAGE_LEFT_TO_RIGHT -> "单页式（从左到右）"
        PAGE_RIGHT_TO_LEFT -> "单页式（从右到左）"
        PAGE_TOP_TO_BOTTOM -> "单页式（从上到下）"
        WEBTOON -> "条漫"
        WEBTOON_WITH_GAP -> "条漫（页面有空隙）"
        DOUBLE_PAGE_LEFT_TO_RIGHT -> "横屏双页（从左到右）"
        DOUBLE_PAGE_RIGHT_TO_LEFT -> "横屏双页（从右到左）"
        else -> "未知"
    }
}

