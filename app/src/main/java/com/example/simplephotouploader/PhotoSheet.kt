package com.example.simplephotouploader

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import kotlin.math.roundToInt

internal fun Context.photoDp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

private fun Context.photoTile(primary: Boolean): RippleDrawable {
    fun tile(color: Int, outlined: Boolean = false) = GradientDrawable().apply {
        cornerRadius = photoDp(14).toFloat()
        setColor(getColor(color))
        if (outlined) setStroke(photoDp(2), getColor(R.color.sushi_pink))
    }

    val fill = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), tile(R.color.photo_tile))
        if (!primary) addState(intArrayOf(android.R.attr.state_selected), tile(R.color.photo_selected, outlined = true))
        addState(intArrayOf(), tile(if (primary) R.color.sushi_pink else R.color.photo_tile))
    }
    return RippleDrawable(ColorStateList.valueOf(0x28F975B1), fill, null)
}

internal fun Button.stylePhotoButton(primary: Boolean = false) {
    val ink = context.getColor(if (primary) R.color.photo_on_pink else R.color.photo_text)
    isAllCaps = false
    letterSpacing = 0f
    sushiFont(R.attr.sushiLabelFont)
    minimumHeight = context.photoDp(48)
    setPadding(context.photoDp(14), context.photoDp(8), context.photoDp(14), context.photoDp(8))
    setTextColor(ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(context.getColor(R.color.photo_muted), ink)))
    backgroundTintList = null
    background = context.photoTile(primary)
    stateListAnimator = null
}

internal fun LinearLayout.addPhotoChoice(
    label: CharSequence,
    selected: Boolean = false,
    enabled: Boolean = true,
    onPick: (Button) -> Unit
): Button = Button(context).apply {
    text = label
    textSize = 16f
    isSelected = selected
    isEnabled = enabled
    gravity = Gravity.START or Gravity.CENTER_VERTICAL
    stylePhotoButton()
    setOnClickListener { onPick(this) }
}.also { addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.photoDp(7) }) }

internal fun AlertDialog.Builder.showPhotoSheet() = create().showPhotoSheet()

internal fun AlertDialog.showPhotoSheet(): AlertDialog {
    val clean = BuildConfig.FLAVOR == "clean"
    if (clean) window?.apply {
        setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setBackgroundDrawable(GradientDrawable().apply {
            val radius = context.photoDp(24).toFloat()
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            setColor(context.getColor(R.color.photo_panel))
        })
        if (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()) {
            setWindowAnimations(R.style.PhotoSheetAnimation)
        } else setWindowAnimations(0)
    }
    show()
    if (clean) {
        window?.setLayout(context.photoDp(minOf(context.resources.configuration.screenWidthDp, 580)),
            WindowManager.LayoutParams.WRAP_CONTENT)
        getButton(AlertDialog.BUTTON_POSITIVE)?.takeIf { it.visibility == View.VISIBLE }?.stylePhotoButton(primary = true)
        getButton(AlertDialog.BUTTON_NEGATIVE)?.takeIf { it.visibility == View.VISIBLE }?.stylePhotoButton()
    }
    return this
}

internal fun Context.showPhotoChoices(
    title: CharSequence,
    labels: List<String>,
    selectedIndex: Int = -1,
    onPick: (Int) -> Unit
) {
    if (BuildConfig.FLAVOR != "clean") {
        AlertDialog.Builder(this).setTitle(title).setItems(labels.toTypedArray()) { _, index -> onPick(index) }
            .setNegativeButton(R.string.close, null).show()
        return
    }
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(photoDp(18), photoDp(14), photoDp(18), photoDp(12))
    }
    val dialog = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(body) })
        .setNegativeButton(R.string.close, null).create()
    body.addView(TextView(this).apply {
        text = title
        setTextColor(getColor(R.color.photo_text))
        textSize = 24f
        sushiFont(R.attr.sushiHeadingFont)
        ViewCompat.setAccessibilityHeading(this, true)
        setPadding(0, 0, 0, photoDp(8))
    })
    labels.forEachIndexed { index, name ->
        body.addPhotoChoice(name, selected = index == selectedIndex) {
            dialog.dismiss()
            onPick(index)
        }
    }
    dialog.showPhotoSheet()
}
