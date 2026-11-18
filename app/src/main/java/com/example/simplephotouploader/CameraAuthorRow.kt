package com.example.simplephotouploader

import android.content.Context
import android.util.AttributeSet
import android.view.View.MeasureSpec
import android.widget.LinearLayout

/** Measure wrapped names first, then stretch only the author tiles, not the overflow button. */
class CameraAuthorRow(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val tiles = (0 until childCount).mapNotNull { getChildAt(it) as? LinearLayout }
        val tallest = tiles.maxOfOrNull { it.measuredHeight } ?: return
        for (tile in tiles) if (tile.measuredHeight < tallest) {
            tile.measure(MeasureSpec.makeMeasureSpec(tile.measuredWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(tallest, MeasureSpec.EXACTLY))
        }
    }
}
