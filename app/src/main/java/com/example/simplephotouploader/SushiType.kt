package com.example.simplephotouploader

import android.util.TypedValue
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

internal fun TextView.sushiFont(attr: Int) {
    val value = TypedValue()
    if (context.theme.resolveAttribute(attr, value, true) && value.resourceId != 0)
        typeface = ResourcesCompat.getFont(context, value.resourceId)
}
