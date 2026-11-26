// Settings page.
package com.cairn.manager.redesign

import android.app.Activity
import android.widget.ImageView
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object RedesignSettings {
    private const val PREVIEW_ID = 0x43_52_46_02
    private fun picture(root: View) = root.findViewById<ImageView>(RedesignFrame.BACKGROUND_ID)?.drawable
    @JvmStatic fun refreshPreview(root: View) {
        val preview = root.findViewById<ImageView>(PREVIEW_ID) ?: return
        val source = picture(root)
        preview.setImageDrawable(source?.constantState?.newDrawable(preview.resources)?.mutate())
        preview.visibility = if (preview.drawable == null) View.GONE else View.VISIBLE
    }
    @JvmStatic
    fun create(
        context: Activity, title: String,
        appearance: String, choose: String, reset: String, previewTitle: String, close: String,
        language: String, appLanguage: String,
        management: String, uninstall: String, unavailable: String,
        onChoose: Runnable, onReset: Runnable,
        languageOptions: Array<String>, selectedLanguage: Int, onLanguage: java.util.function.IntConsumer,
    ): View {
        val motion = BackgroundMotion(context)
        val palette = RedesignTokens.palette(context)
        fun dp(v: Int) = (v * context.resources.displayMetrics.density + .5f).toInt()
        fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(24))
        }
        val heading = label(title, 34f, palette.primary, true)
        heading.isAccessibilityHeading = true
        body.addView(heading)
        fun section(name: String, vararg items: Pair<String, ((View) -> Unit)?>) {
            val caption = label(name, 13f, palette.secondary, true).apply {
                setPaddingRelative(dp(4), dp(22), dp(4), dp(8))
                isAccessibilityHeading = true
            }
            body.addView(caption)
            val group = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = RedesignFrost.drawable(this, 20)
                clipToOutline = true
            }
            
            val engine = group.addPressMotion()
            items.forEachIndexed { index, (name, action) ->
                if (index > 0) group.addView(View(context).apply { setBackgroundColor(palette.line) },
                    LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(16); marginEnd = dp(16) })
                val row = LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(56)
                    setPaddingRelative(dp(16), dp(10), dp(16), dp(10))
                    addView(label(name, 16f, if (action == null) palette.secondary else palette.primary),
                        LinearLayout.LayoutParams(0, -2, 1f))
                    val rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
                    addView(label(if (action == null) unavailable else if (rtl) "‹" else "›", 13f, palette.secondary),
                        LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
                    if (action != null) {
                        isFocusable = true
                        setOnTouchListener { v, event -> engine.forward(v, event) }
                        setOnClickListener { action(it) }
                    } else {
                        isEnabled = false
                    }
                }
                group.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
            body.addView(group, LinearLayout.LayoutParams(-1, -2))
        }
        val thumbnail = ImageView(context).apply {
            id = PREVIEW_ID
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(palette.surface) }
            clipToOutline = true
            contentDescription = previewTitle
            isFocusable = true
            visibility = View.GONE
            setOnClickListener { anchor ->
                val image = picture(anchor.rootView)?.constantState?.newDrawable(resources)?.mutate()
                if (image != null) motion.show(anchor, previewTitle, close, image)
            }
        }
        body.addView(thumbnail, LinearLayout.LayoutParams(-1, dp(90)).apply { topMargin = dp(12) })
        thumbnail.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { refreshPreview(v.rootView) }
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
        section(appearance, previewTitle to { anchor: View ->
            motion.show(anchor, appearance, close, actions = listOf(choose to onChoose, reset to onReset))
        })
        section(language, appLanguage to { anchor: View ->
            val options = languageOptions.mapIndexed { index, label ->
                val selectedLabel = if (index == selectedLanguage) "✓  $label" else label
                selectedLabel to Runnable { onLanguage.accept(index) }
            }
            motion.show(anchor, language, close, actions = options)
        })
        section(management, uninstall to null)
        return ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setOnScrollChangeListener { view, _, _, _, _ -> RedesignFrost.invalidateCards(view) }
            clipToPadding = false
            setPadding(0, 0, 0, dp(96))
            addView(body, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
    }
}
