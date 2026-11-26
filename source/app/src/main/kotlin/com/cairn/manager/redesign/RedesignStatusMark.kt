// Status mark drawing for the redesign.
package com.cairn.manager.redesign

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

class RedesignStatusMark(context: Context) : View(context) {
    enum class Glyph { CHECKING, CHECK, NOTICE, UNAVAILABLE }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var glyph = Glyph.CHECKING
    private var tint = 0xffffffff.toInt()

    fun show(next: Glyph, color: Int) {
        glyph = next
        tint = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val unit = width.coerceAtMost(height) / 24f
        val left = (width - 24f * unit) / 2f
        val top = (height - 24f * unit) / 2f
        canvas.save()
        canvas.translate(left, top)
        paint.color = tint
        when (glyph) {
            Glyph.CHECKING -> {
                paint.style = Paint.Style.STROKE
                canvas.drawCircle(12f * unit, 12f * unit, 7f * unit, paint)
            }
            Glyph.CHECK -> path(canvas, unit, floatArrayOf(6f, 12f, 10f, 16f, 18f, 7f))
            Glyph.NOTICE -> {
                path(canvas, unit, floatArrayOf(12f, 6f, 12f, 13f))
                canvas.drawCircle(12f * unit, 17f * unit, 1f * unit, paint)
            }
            Glyph.UNAVAILABLE -> path(canvas, unit, floatArrayOf(7f, 7f, 17f, 17f, 17f, 7f, 7f, 17f))
        }
        canvas.restore()
    }

    private fun path(canvas: Canvas, unit: Float, points: FloatArray) {
        val path = Path()
        path.moveTo(points[0] * unit, points[1] * unit)
        var i = 2
        while (i < points.size) {
            path.lineTo(points[i] * unit, points[i + 1] * unit)
            i += 2
        }
        canvas.drawPath(path, paint)
    }
}
