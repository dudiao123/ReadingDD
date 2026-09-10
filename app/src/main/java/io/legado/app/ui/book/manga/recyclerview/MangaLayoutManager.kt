package io.legado.app.ui.book.manga.recyclerview

import android.content.Context
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MangaLayoutManager(context: Context) :
    GridLayoutManager(context, 1) {

    private val extraLayoutSpace = context.resources.displayMetrics.heightPixels * 3 / 4

    @Deprecated("Deprecated in Java")
    override fun getExtraLayoutSpace(state: RecyclerView.State?): Int {
        return extraLayoutSpace
    }

}
