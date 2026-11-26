// Frosted blur sampled from the wallpaper.
package com.cairn.manager.redesign

import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import java.util.WeakHashMap

object RedesignFrost {
    private class Frame(val source: Drawable, val width: Int, val height: Int, val bitmap: Bitmap)
    private val cache = WeakHashMap<ImageView, Frame>()

    private fun frame(image: ImageView): Bitmap? {
        val source = image.drawable ?: return null
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0) return null
        cache[image]?.let { if (it.source === source && it.width == w && it.height == h) return it.bitmap }
        val sw = (w / 10).coerceIn(1, 256)
        val sh = (h / 10).coerceIn(1, 256)
        val bitmap = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(RedesignTokens.palette(image.context).background)
            scale(sw.toFloat() / w, sh.toFloat() / h)
            concat(image.imageMatrix)
            source.draw(this)
        }
        val pixels = IntArray(sw * sh)
        val temp = IntArray(pixels.size)
        bitmap.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        val radius = (image.resources.displayMetrics.density * 9f * sw / w).toInt().coerceAtLeast(1)
        repeat(2) {
            blur(pixels, temp, sw, sh, radius, false)
            blur(temp, pixels, sw, sh, radius, true)
        }
        bitmap.setPixels(pixels, 0, sw, 0, 0, sw, sh)
        cache[image] = Frame(source, w, h, bitmap)
        return bitmap
    }

    private fun blur(input: IntArray, output: IntArray, w: Int, h: Int, radius: Int, vertical: Boolean) {
        val lines = if (vertical) w else h
        val length = if (vertical) h else w
        val stride = if (vertical) w else 1
        val count = radius * 2 + 1
        for (line in 0 until lines) {
            val base = if (vertical) line else line * w
            var r = 0; var g = 0; var b = 0
            for (pos in -radius..radius) {
                val color = input[base + pos.coerceIn(0, length - 1) * stride]
                r += Color.red(color); g += Color.green(color); b += Color.blue(color)
            }
            for (pos in 0 until length) {
                output[base + pos * stride] = Color.rgb(r / count, g / count, b / count)
                val past = input[base + (pos - radius).coerceIn(0, length - 1) * stride]
                val next = input[base + (pos + radius + 1).coerceIn(0, length - 1) * stride]
                r += Color.red(next) - Color.red(past)
                g += Color.green(next) - Color.green(past)
                b += Color.blue(next) - Color.blue(past)
            }
        }
    }

    fun drawable(host: View, radiusDp: Int): Drawable = object : FrostDrawable() {
        private val density = host.resources.displayMetrics.density
        private val radius = density * radiusDp
        private val palette = RedesignTokens.palette(host.context)
        private val dark = (host.resources.configuration.uiMode and 0x30) == 0x20
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val card = RectF()
        private val imageRect = RectF()
        private val path = Path()
        private val hostLocation = IntArray(2)
        private val imageLocation = IntArray(2)
        private val lens by lazy(LazyThreadSafetyMode.NONE) { CardGlassLens.create(density, dark, palette.surface) }
        override fun onBoundsChange(bounds: Rect) {
            card.set(bounds)
            path.reset()
            path.addRoundRect(card, radius, radius, Path.Direction.CW)
        }
        override fun draw(canvas: Canvas) {
            val image = host.rootView.findViewById<ImageView>(RedesignFrame.BACKGROUND_ID)
            val bitmap = image?.let { frame(it) }
            if (bitmap != null && image != null) {
                host.getLocationInWindow(hostLocation)
                image.getLocationInWindow(imageLocation)
                val x = (imageLocation[0] - hostLocation[0] - host.scrollX).toFloat()
                val y = (imageLocation[1] - hostLocation[1] - host.scrollY).toFloat()
                imageRect.set(x, y, x + image.width, y + image.height)
            }
            if (canvas.isHardwareAccelerated && lens?.draw(canvas, card, radius, bitmap, imageRect,
                    JellyPress.of(host)) == true) return
            val save = canvas.save()
            canvas.clipPath(path)
            if (bitmap != null) {
                paint.color = Color.WHITE
                canvas.drawBitmap(bitmap, null, imageRect, paint)
                canvas.drawColor(if (dark) 0x991D2128.toInt() else 0xABFFFFFF.toInt())
            } else {
                canvas.drawColor(palette.surface)
            }
            paint.color = if (dark) 0x18FFFFFF else 0x22FFFFFF
            canvas.drawRect(card.left, card.top, card.right, card.top + density, paint)
            canvas.restoreToCount(save)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            paint.color = palette.line
            canvas.drawRoundRect(card.left + density / 2, card.top + density / 2,
                card.right - density / 2, card.bottom - density / 2, radius, radius, paint)
            paint.style = Paint.Style.FILL
        }
        override fun getOutline(outline: Outline) = outline.setRoundRect(bounds, radius)
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    fun refresh(root: View) {
        root.findViewById<ImageView>(RedesignFrame.BACKGROUND_ID)?.let { cache.remove(it) }
        invalidateCards(root)
    }

    @JvmStatic
    fun invalidateCards(view: View) {
        if (view.background is FrostDrawable) view.invalidate()
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) invalidateCards(view.getChildAt(i))
    }

    private abstract class FrostDrawable : Drawable()
}
