package com.signal.tv.voice

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.signal.ui.theme.DarkSlate
import com.signal.ui.theme.SignalSizes
import com.signal.ui.theme.SignalType

/**
 * The assistant session's window (PLAN §4.3.14 → Voice commands): a panel at the bottom of the
 * screen that shows Signal is listening, types out the words as they're heard, then the outcome.
 * Plain views, since the session isn't an activity; sizes are in design units like the screens.
 */
internal class ListeningOverlay(private val context: Context) {
    private val unit = context.resources.displayMetrics.widthPixels / 1920f
    private fun u(n: Number) = (n.toFloat() * unit).toInt()
    private val palette = DarkSlate

    private val dot = View(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(palette.accent.toArgb()) }
    }
    private val pulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply {
        duration = 600
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
    }
    private val status = text(SignalType.BODY, palette.secondaryText.toArgb())
    private val words = text(SignalType.SECTION_TITLE + 8, palette.text.toArgb())

    val view: View = FrameLayout(context).apply {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(u(40), u(28), u(48), u(28))
            background = GradientDrawable().apply { cornerRadius = u(20).toFloat(); setColor(palette.surface.toArgb()) }
            addView(dot, LinearLayout.LayoutParams(u(28), u(28)).apply { marginEnd = u(32) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(status)
                addView(words)
            })
        }
        addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = u(SignalSizes.EDGE_MARGIN)
        })
        minimumWidth = u(720)
    }

    private fun text(size: Int, color: Int) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, u(size).toFloat())
        setTextColor(color)
        maxWidth = u(1200)
    }

    fun listening() {
        dot.visibility = View.VISIBLE
        pulse.start()
        status.text = "Listening…"
        words.text = "Say a channel"
        words.alpha = 0.5f
    }

    /** What's been heard so far; "?" for speech outside the vocabulary. */
    fun heard(text: String) {
        if (text.isBlank()) return
        words.text = text.replace("[unk]", "?")
        words.alpha = 1f
    }

    /**
     * [heard] is the guess acted on and [best] the recognizer's first. When a later guess won, both
     * show: the live text was the first, so the screen would otherwise contradict the channel.
     */
    fun outcome(heard: String, best: String, shown: String, acted: Boolean) {
        pulse.cancel()
        dot.alpha = 1f
        dot.visibility = if (acted) View.VISIBLE else View.GONE
        fun quote(s: String) = "\"${s.replace("[unk]", "?")}\""
        status.text = when {
            heard.isBlank() -> "Heard nothing"
            best.isNotBlank() && best != heard -> "Heard ${quote(heard)} (first guess ${quote(best)})"
            else -> "Heard ${quote(heard)}"
        }
        words.text = shown
        words.alpha = 1f
    }

    fun message(text: String) {
        pulse.cancel()
        dot.visibility = View.GONE
        status.text = "Voice"
        words.text = text
        words.alpha = 1f
    }
}
