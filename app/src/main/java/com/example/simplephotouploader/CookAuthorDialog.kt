package com.example.simplephotouploader

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kotlin.math.roundToInt

internal fun showCookAuthorDialog(
    context: Context,
    people: List<Pair<String, String>>,
    selectedId: String?,
    sharedSelected: Boolean,
    onPick: (String) -> Unit,
    onShared: (() -> Unit)?,
    onPizzaShift: (() -> Unit)?
) = with(context) {
    fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    val pink = getColor(R.color.sushi_pink)
    fun surface(color: Int, radius: Int = 14) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    fun tile(label: CharSequence, selected: Boolean = false, accent: Boolean = false) = Button(context).apply {
        text = label
        textSize = 16f
        isAllCaps = false
        isSelected = selected
        sushiFont(R.attr.sushiLabelFont)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        backgroundTintList = null
        background = RippleDrawable(ColorStateList.valueOf(0x35F975B1), surface(0xFF29262A.toInt()).apply {
            if (selected) setStroke(dp(2), pink)
        }, null)
        stateListAnimator = null
        setTextColor(if (accent || selected) pink else Color.WHITE)
    }
    fun rowParams() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }

    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(18))
    }
    val body = ScrollView(context).apply { addView(content) }
    val dialog = AlertDialog.Builder(context).setView(body).create()
    val title = TextView(context).apply {
        text = getString(R.string.cook_author_title)
        setTextColor(Color.WHITE)
        textSize = 23f
        sushiFont(R.attr.sushiHeadingFont)
        androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        setPadding(0, 0, 0, dp(6))
    }
    content.addView(title)
    people.forEach { (id, name) ->
        content.addView(tile(name, selected = !sharedSelected && selectedId == id).apply {
            setOnClickListener { dialog.dismiss(); onPick(id) }
        }, rowParams())
    }
    if (onShared != null) {
        val description = TextView(context).apply {
            setText(R.string.cook_author_shared_detail)
            setTextColor(0xFFDED6DD.toInt())
            textSize = 14f
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = surface(0xFF29262A.toInt())
            visibility = View.GONE
        }
        content.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(tile(getString(R.string.cook_author_shared), selected = sharedSelected, accent = true).apply {
                setOnClickListener { dialog.dismiss(); onShared() }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(Button(context).apply {
                text = "?"
                textSize = 18f
                contentDescription = getString(R.string.cook_author_shared_help)
                setTextColor(0xFFDED6DD.toInt())
                sushiFont(R.attr.sushiLabelFont)
                setPadding(0, 0, 0, 0)
                backgroundTintList = null
                background = RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), surface(0xFF373438.toInt()), null)
                stateListAnimator = null
                setOnClickListener {
                    val expanded = description.visibility != View.VISIBLE
                    description.visibility = if (expanded) View.VISIBLE else View.GONE
                    androidx.core.view.ViewCompat.setStateDescription(this, getString(
                        if (expanded) R.string.cook_author_help_expanded else R.string.cook_author_help_collapsed))
                    if (expanded) description.post {
                        description.requestRectangleOnScreen(android.graphics.Rect(0, 0, description.width, description.height))
                    }
                }
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        }, rowParams().apply { topMargin = dp(16) })
        content.addView(description, rowParams())
    }
    if (onPizzaShift != null) {
        content.addView(tile(getString(R.string.cook_pizza_shift)).apply {
            setOnClickListener { dialog.dismiss(); onPizzaShift() }
        }, rowParams().apply { topMargin = dp(16) })
    }
    content.addView(Button(context).apply {
        setText(R.string.close)
        textSize = 14f
        isAllCaps = false
        setTextColor(0xFFDED6DD.toInt())
        sushiFont(R.attr.sushiLabelFont)
        backgroundTintList = null
        background = RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), null, surface(Color.WHITE))
        stateListAnimator = null
        minimumHeight = dp(48)
        setOnClickListener { dialog.dismiss() }
    }, rowParams())
    dialog.show()
    dialog.window?.apply {
        setBackgroundDrawable(surface(0xFF1B191C.toInt(), 22))
        setLayout(dp((resources.configuration.screenWidthDp - 36).coerceIn(240, 460)), WRAP_CONTENT)
    }
}
