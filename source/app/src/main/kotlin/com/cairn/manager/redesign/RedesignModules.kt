// Module page layout helpers.
package com.cairn.manager.redesign

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

object RedesignModules {
    @JvmStatic
    fun pageHeader(context: Context, title: String, subtitle: String): LinearLayout {
        val palette = RedesignTokens.palette(context)
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(text(context, title, 32f, palette.primary, true))
        if (subtitle.isNotBlank()) column.addView(text(context, subtitle, 13f, palette.secondary, false), lp(0, 5, 0, 0))
        return column
    }

    @JvmStatic
    fun refreshButton(context: Context, label: String, action: Runnable): TextView {
        val palette = RedesignTokens.palette(context)
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 13f
            includeFontPadding = false
            setTextColor(palette.primary)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = surface(context, 13, palette.surfaceRaised, palette.line)
            isFocusable = true
            addPressMotion()
            setOnClickListener { action.run() }
        }
    }

    @JvmStatic
    fun moduleRow(
        context: Context,
        name: String,
        state: String,
        stateColor: Int,
        description: String,
        actions: View,
    ): LinearLayout {
        val palette = RedesignTokens.palette(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 15), dp(context, 13), dp(context, 15), dp(context, 13))
            background = surface(context, 16, palette.surface, palette.line)
        }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nameView = text(context, name, 16f, palette.primary, true)
        top.addView(nameView, LinearLayout.LayoutParams(0, -2, 1f))
        val status = text(context, state, 12f, stateColor, true).apply { gravity = Gravity.CENTER }
        top.addView(status, LinearLayout.LayoutParams(-2, dp(context, 28)))
        root.addView(top)
        root.addView(text(context, description, 12f, palette.secondary, false), lp(0, 7, 0, 0))
        root.addView(actions, lp(0, 11, 0, 0))
        return root
    }

    @JvmStatic
    fun action(context: Context, label: String, primary: Boolean, action: Runnable): TextView {
        val palette = RedesignTokens.palette(context)
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 12.5f
            includeFontPadding = false
            setTextColor(if (primary) palette.surface else palette.primary)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = surface(context, 12, if (primary) palette.accent else palette.surfaceRaised, palette.line)
            isFocusable = true
            addPressMotion()
            setOnClickListener { action.run() }
        }
    }

    @JvmStatic
    fun actionRow(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    @JvmStatic
    fun empty(context: Context, title: String, message: String): LinearLayout {
        val palette = RedesignTokens.palette(context)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 15), dp(context, 17), dp(context, 15), dp(context, 17))
            background = surface(context, 16, palette.surface, palette.line)
            addView(text(context, title, 17f, palette.primary, true))
            addView(text(context, message, 13f, palette.secondary, false), lp(0, 6, 0, 0))
        }
    }

    private fun text(c: Context, value: String, size: Float, color: Int, medium: Boolean) = TextView(c).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }

    private fun surface(c: Context, radius: Int, fill: Int, line: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(c, radius).toFloat()
        setStroke(dp(c, 1), line)
    }

    private fun lp(l: Int, t: Int, r: Int, b: Int) = LinearLayout.LayoutParams(-1, -2).apply {
        setMargins(l, t, r, b)
    }

    private fun dp(c: Context, value: Int) = (value * c.resources.displayMetrics.density + .5f).toInt()
}
