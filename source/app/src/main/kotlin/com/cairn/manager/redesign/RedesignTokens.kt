// Colour and type palette resolved from the current theme.
package com.cairn.manager.redesign

import android.content.Context
import android.graphics.Color

object RedesignTokens {
    data class Palette(
        @JvmField val background: Int,
        @JvmField val surface: Int,
        @JvmField val surfaceRaised: Int,
        @JvmField val line: Int,
        @JvmField val primary: Int,
        @JvmField val secondary: Int,
        @JvmField val accent: Int,
        @JvmField val positive: Int,
    )

    @JvmStatic
    fun palette(context: Context): Palette {
        val dark = (context.resources.configuration.uiMode and 0x30) == 0x20
        return if (dark) Palette(
            background = Color.rgb(18, 20, 23),
            surface = Color.argb(220, 28, 31, 36),
            surfaceRaised = Color.argb(235, 38, 42, 48),
            line = Color.argb(70, 255, 255, 255),
            primary = Color.rgb(242, 243, 245),
            secondary = Color.rgb(184, 189, 197),
            accent = Color.rgb(221, 226, 232),
            positive = Color.rgb(107, 217, 154),
        ) else Palette(
            background = Color.rgb(246, 247, 248),
            surface = Color.argb(232, 255, 255, 255),
            surfaceRaised = Color.argb(245, 255, 255, 255),
            line = Color.argb(38, 20, 24, 28),
            primary = Color.rgb(22, 25, 29),
            secondary = Color.rgb(91, 98, 107),
            accent = Color.rgb(37, 43, 50),
            positive = Color.rgb(30, 133, 83),
        )
    }
}
