// Portions adapted from QWEA0/Liquid-Glass-Android, GlassLensRenderer.kt
// Upstream revision: ea7908990247c56d6a9635e89ed36cb022434508
// Copyright (c) 2025-2026 pandadog — MIT; see assets/licenses/QWEA0-Liquid-Glass-Android.txt.
// Refraction shader for card surfaces.
package com.cairn.manager.redesign

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

internal interface CardGlassLens {
    fun draw(canvas: Canvas, card: RectF, radius: Float, bitmap: Bitmap?, image: RectF, press: JellyPress?): Boolean

    companion object {
        private var unavailable = false

        fun create(density: Float, dark: Boolean, surface: Int): CardGlassLens? {
            if (Build.VERSION.SDK_INT < 33 || unavailable) return null
            return try {
                AgslCardGlassLens(density, dark, surface)
            } catch (error: RuntimeException) {
                unavailable = true
                Log.w("CairnGlass", "Using frosted cards: shader unavailable", error)
                null
            }
        }
    }
}

@RequiresApi(33)
private class AgslCardGlassLens(private val density: Float, dark: Boolean, surface: Int) : CardGlassLens {
    private val shader = RuntimeShader(SOURCE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = this@AgslCardGlassLens.shader }
    private val fallback = LinearGradient(0f, 0f, 1f, 0f, surface, surface, Shader.TileMode.CLAMP)
    private val matrix = Matrix()
    private val lastImage = RectF()
    private var lastBitmap: Bitmap? = null
    private var bitmapShader: BitmapShader? = null
    private val lastCard = RectF()
    private var lastRadius = -1f
    private var failed = false
    private var lastDepth = -1f
    private var lastTouchX = Float.NaN
    private var lastTouchY = Float.NaN

    init {
        shader.setInputShader("wallpaper", fallback)
        val tint = if (dark) 0xFF1D2128.toInt() else Color.WHITE
        shader.setFloatUniform("tint", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f)
        shader.setFloatUniform("veil", if (dark) .60f else .66f)
        shader.setFloatUniform("highlight", if (dark) .20f else .23f)
        shader.setFloatUniform("pixelScale", density)
    }

    override fun draw(canvas: Canvas, card: RectF, radius: Float, bitmap: Bitmap?, image: RectF, press: JellyPress?): Boolean {
        if (failed || !canvas.isHardwareAccelerated || card.isEmpty) return false
        return try {
            val r = radius.coerceAtMost(minOf(card.width(), card.height()) * .5f)
            if (lastCard != card || lastRadius != r) {
                shader.setFloatUniform("center", card.centerX(), card.centerY())
                shader.setFloatUniform("halfSize", card.width() * .5f, card.height() * .5f)
                shader.setFloatUniform("corner", r)
                lastCard.set(card)
                lastRadius = r
            }
            val changed = lastBitmap !== bitmap
            if (changed) {
                bitmapShader = bitmap?.let {
                    BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                        setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                    }
                }
                lastBitmap = bitmap
                shader.setFloatUniform("hasWallpaper", if (bitmap != null) 1f else 0f)
                if (bitmap == null) shader.setInputShader("wallpaper", fallback)
            }
            if (bitmap != null && (changed || lastImage != image)) {
                val input = checkNotNull(bitmapShader)
                matrix.setScale(image.width() / bitmap.width, image.height() / bitmap.height)
                matrix.postTranslate(image.left, image.top)
                input.setLocalMatrix(matrix)
                shader.setInputShader("wallpaper", input)
                lastImage.set(image)
            }
            val depth = press?.pressAmount() ?: 0f
            val x = press?.touchX()?.takeIf { depth > 0f && it.isFinite() } ?: card.centerX()
            val y = press?.touchY()?.takeIf { depth > 0f && it.isFinite() } ?: card.centerY()
            if (lastDepth != depth || lastTouchX != x || lastTouchY != y) {
                shader.setFloatUniform("press", depth)
                shader.setFloatUniform("touchPos", x, y)
                lastDepth = depth
                lastTouchX = x
                lastTouchY = y
            }
            canvas.drawRoundRect(card, r, r, paint)
            true
        } catch (error: RuntimeException) {
            failed = true
            Log.w("CairnGlass", "Using frosted card after a rendering failure", error)
            false
        }
    }

    private companion object {
        
        const val SOURCE = """
            uniform shader wallpaper;
            uniform float2 center;
            uniform float2 halfSize;
            uniform float corner;
            uniform float pixelScale;
            uniform float3 tint;
            uniform float veil;
            uniform float highlight;
            uniform float hasWallpaper;

            float sdRoundedBox(float2 p, float2 b, float r) {
                float2 q = abs(p) - b + r;
                return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
            }
            float lensSDF(float2 p) {
                return sdRoundedBox(p - center, halfSize, corner);
            }
            uniform float press;
            uniform float2 touchPos;
            half4 main(float2 p) {
                float d = lensSDF(p);
                float bevel = min(pixelScale * 12.0, halfSize.y * 0.60);
                float refr = min(pixelScale * 8.0, bevel) * (1.0 + 0.6 * press);
                float2 offset = float2(0.0);
                float spec = 0.0;
                if (d >= -bevel) {
                    float2 n = float2(
                        lensSDF(p + float2(1.0, 0.0)) - lensSDF(p - float2(1.0, 0.0)),
                        lensSDF(p + float2(0.0, 1.0)) - lensSDF(p - float2(0.0, 1.0))
                    );
                    n /= max(length(n), 0.0001);
                    float t = clamp(-d / max(bevel, 1.0), 0.0, 1.0);
                    float edge = 1.0 - t;
                    float slope = edge * edge;
                    offset = -n * slope * refr;
                    float facing = dot(n, float2(-0.60, -0.80));
                    float lobeF = pow(max(facing, 0.0), 4.5);
                    float lobeB = pow(max(-facing, 0.0), 4.5);
                    float dist = max(-d, 0.0) / pixelScale;
                    float hair = clamp(1.0 - abs(dist - 0.75) / 1.6, 0.0, 1.0);
                    float glow = clamp(1.0 - dist / 4.0, 0.0, 1.0);
                    spec = highlight * (hair * (lobeF + 0.60 * lobeB) + 0.24 * glow * lobeF)
                         * (1.0 - 0.35 * press);
                }
                // Upstream touch bump: refract toward the finger, not a painted ripple.
                if (press > 0.001) {
                    float2 tp = p - touchPos;
                    float tr = length(tp);
                    float sigma = max(max(halfSize.x, halfSize.y), 1.0);
                    float bump = press * exp(-(tr * tr) / (sigma * sigma * 0.30));
                    if (tr > 1.0) {
                        offset -= (tp / tr) * (bump * refr * 0.5);
                    }
                }
                float3 col = float3(wallpaper.eval(p + offset).rgb);
                col = mix(col, tint, veil * hasWallpaper) + float3(spec);
                return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
            }
        """
    }
}
