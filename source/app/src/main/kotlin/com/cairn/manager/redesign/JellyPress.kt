// Jelly press engine, ported from liquid_design 0.4.3 (`lib/src/liquid_glass.dart`
// and `lib/src/glass_spring.dart`), MIT, Copyright (c) 2026 Mohammed Al-Jaf.
//

// Press engine: spring-driven scale, stretch, and glow.
package com.cairn.manager.redesign

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewParent
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class JellyPress private constructor(private val host: View) {

    private val press = Channel()
    private val glow = Channel()
    private val dx = Channel()
    private val dy = Channel()
    private val hover = Channel()

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val washPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var strength = DEFAULT_STRENGTH
    private var dark = false
    private var reduceMotion = false
    private var downX = 0f
    private var downY = 0f
    private var pointerX = 0f
    private var pointerY = 0f
    private var pointerKnown = false
    private var active = false
    private var running = false
    private var lastFrameMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            running = false
            if (step()) schedule()
            host.invalidate()
        }
    }

    fun strength(value: Float): JellyPress = apply { strength = if (value <= 0f) 0f else value }
    fun dark(value: Boolean): JellyPress = apply { dark = value }
    fun reduceMotion(value: Boolean): JellyPress = apply { reduceMotion = value }

    fun pressAmount(): Float = press.value

    fun glowAmount(): Float = glow.value

    private val sourceLocation = IntArray(2)
    private val hostLocation = IntArray(2)

    fun forward(source: View, event: MotionEvent): Boolean {
        var x = event.x
        var y = event.y
        if (source !== host) {
            source.getLocationInWindow(sourceLocation)
            host.getLocationInWindow(hostLocation)
            x += (sourceLocation[0] - hostLocation[0]).toFloat()
            y += (sourceLocation[1] - hostLocation[1]).toFloat()
        }
        handleTouch(host, event, x, y)
        return false
    }

    fun touchX(): Float = if (pointerKnown) pointerX else Float.NaN

    fun touchY(): Float = if (pointerKnown) pointerY else Float.NaN

    private fun attach() {
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = allowOverdraw(v)
            override fun onViewDetachedFromWindow(v: View) = reset()
        })
        
        host.setOnTouchListener { v, event ->
            
            if (!consumesGesture(v)) return@setOnTouchListener false
            if (active && event.actionMasked != MotionEvent.ACTION_DOWN && !consumesGesture(v)) {
                forceRelease()
                return@setOnTouchListener false
            }
            handleTouch(v, event)
            false
        }
        host.setWillNotDraw(false)
        host.isClickable = host.isClickable || host.hasOnClickListeners()
    }

    private fun allowOverdraw(view: View) {
        var node: ViewParent? = view.parent
        var depth = 0
        while (node != null && depth < MAX_CLIP_DEPTH) {
            if (isScrollContainer(node)) return
            if (node is ViewGroup) {
                node.clipChildren = false
                node.clipToPadding = false
            }
            node = node.parent
            depth++
        }
    }

    private fun isScrollContainer(node: ViewParent): Boolean =
        node is android.widget.AbsListView || node is android.widget.ScrollView ||
            node is android.widget.HorizontalScrollView

    private fun handleTouch(v: View, event: MotionEvent, px: Float = event.x, py: Float = event.y) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = px
                downY = py
                pointerX = downX
                pointerY = downY
                pointerKnown = true
                active = true
                press.springTo(1f, PRESS)
                glow.springTo(1f, PRESS)
                start()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!active) return
                pointerX = px
                pointerY = py
                pointerKnown = true
                if (reduceMotion) { start(); return }

                press.springTo(1f, PRESS)
                glow.springTo(1f, PRESS)
                dx.springTo(rubberBand(pointerX - downX, v.width.toFloat()), FOLLOW)
                dy.springTo(rubberBand(pointerY - downY, v.height.toFloat()), FOLLOW)
                start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!active) return
                active = false
                press.springTo(0f, RELEASE)
                glow.springTo(0f, FADE)
                dx.springTo(0f, RELEASE)
                dy.springTo(0f, RELEASE)
                start()
            }
        }
    }

    private fun rubberBand(delta: Float, dimension: Float): Float {
        if (dimension <= 0f) return 0f
        val limit = clamp(dimension * 0.6f, 10f, 36f) * strength
        if (limit == 0f) return 0f
        val sign = if (delta < 0f) -1f else 1f
        return (1f - 1f / (abs(delta) * 0.5f / limit + 1f)) * limit * sign
    }

    private fun releaseChannels() {
        press.springTo(0f, RELEASE)
        glow.springTo(0f, FADE)
        dx.springTo(0f, RELEASE)
        dy.springTo(0f, RELEASE)
    }

    private fun consumesGesture(v: View): Boolean =
        v.isClickable || v.isLongClickable || v.isContextClickable

    private fun forceRelease() {
        if (!active && press.value == 0f) return
        active = false
        releaseChannels()
        start()
    }

    private fun reset() {
        active = false
        pointerKnown = false
        press.snap(0f); glow.snap(0f); dx.snap(0f); dy.snap(0f); hover.snap(0f)
        host.pivotX = host.width / 2f
        host.pivotY = host.height / 2f
        host.scaleX = 1f
        host.scaleY = 1f
        host.translationX = 0f
        host.translationY = 0f
    }

    private fun start() {
        lastFrameMs = 0L
        schedule()
    }

    private fun schedule() {
        if (running) return
        running = true
        host.postOnAnimation(tick)
    }

    private fun step(): Boolean {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameMs == 0L) MAX_STEP else min((now - lastFrameMs) / 1000.0, MAX_STEP)
        lastFrameMs = now
        var moving = false
        moving = press.advance(dt) or moving
        moving = glow.advance(dt) or moving
        moving = dx.advance(dt) or moving
        moving = dy.advance(dt) or moving
        moving = hover.advance(dt) or moving
        applyTransform()
        return moving || active
    }

    private fun applyTransform() {
        val w = host.width
        val h = host.height
        if (w <= 0 || h <= 0) return

        val growth = clamp(16f / max(w, h), 0.03f, 0.2f) * strength *
                (if (reduceMotion) 0.25f else 1f)
        val scale = 1f + press.value * growth + hover.value * 0.02f * strength

        val tx = dx.value
        val ty = dy.value
        val stretchX = clamp(abs(tx) / w * 0.6f, 0f, 0.3f)
        val stretchY = clamp(abs(ty) / h * 0.6f, 0f, 0.3f)
        val sx = scale * (1f + stretchX - stretchY * 0.45f)
        val sy = scale * (1f + stretchY - stretchX * 0.45f)

        host.pivotX = w / 2f + tx
        host.pivotY = h / 2f + ty
        host.scaleX = sx
        host.scaleY = sy
        host.translationX = tx
        host.translationY = ty
    }

    fun drawGlow(canvas: Canvas): Boolean {
        val amount = clamp(glow.value * strength, 0f, 1.5f)
        if (amount <= 0.001f || !pointerKnown) return false
        val w = host.width
        val h = host.height
        if (w <= 0 || h <= 0) return false

        washPaint.color = Color.argb(((if (dark) 0.05f else 0.08f) * amount * 255f).toInt(), 255, 255, 255)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), washPaint)

        val radius = max(w, h) * 0.75f
        glowPaint.shader = RadialGradient(
            pointerX, pointerY, radius,
            intArrayOf(
                Color.argb(((if (dark) 0.22f else 0.32f) * amount * 255f).toInt(), 255, 255, 255),
                Color.argb(0, 255, 255, 255),
            ),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(pointerX, pointerY, radius, glowPaint)
        return true
    }

    private class Channel {
        var value = 0f
            private set
        private var velocity = 0.0
        private var target = 0f
        private var spec: Spring = PRESS
        private var virtualTime = 0.0
        private var lastTime = 0.0

        fun springTo(to: Float, spring: Spring) {
            target = to
            spec = spring
            
            virtualTime = 0.0
            lastTime = 0.0
        }

        fun snap(to: Float) {
            value = to
            target = to
            velocity = 0.0
        }

        fun advance(dt: Double): Boolean {
            if (dt <= 0.0) return !atRest()
            virtualTime += min(dt, MAX_STEP)
            val t = virtualTime - lastTime
            lastTime = virtualTime
            if (t <= 0.0) return !atRest()

            val x0 = (value - target).toDouble()
            val v0 = velocity
            val omega0 = sqrt(spec.stiffness)
            val zeta = spec.damping / (2.0 * sqrt(spec.stiffness * spec.mass))

            val x: Double
            val v: Double
            if (abs(zeta - 1.0) < 1e-4) {
                val c1 = x0
                val c2 = v0 + omega0 * x0
                val e = Math.exp(-omega0 * t)
                x = (c1 + c2 * t) * e
                v = (c2 - omega0 * (c1 + c2 * t)) * e
            } else if (zeta < 1.0) {
                val omegaD = omega0 * sqrt(1.0 - zeta * zeta)
                val c1 = x0
                val c2 = (v0 + zeta * omega0 * x0) / omegaD
                val e = Math.exp(-zeta * omega0 * t)
                val cos = Math.cos(omegaD * t)
                val sin = Math.sin(omegaD * t)
                x = e * (c1 * cos + c2 * sin)
                v = e * (-zeta * omega0 * (c1 * cos + c2 * sin) + omegaD * (-c1 * sin + c2 * cos))
            } else {
                val gamma = omega0 * sqrt(zeta * zeta - 1.0)
                val r1 = -zeta * omega0 + gamma
                val r2 = -zeta * omega0 - gamma
                val c2 = (v0 - r1 * x0) / (r2 - r1)
                val c1 = x0 - c2
                val e1 = Math.exp(r1 * t)
                val e2 = Math.exp(r2 * t)
                x = c1 * e1 + c2 * e2
                v = c1 * r1 * e1 + c2 * r2 * e2
            }

            value = (x + target).toFloat()
            velocity = v
            if (atRest()) { value = target; velocity = 0.0; return false }
            return true
        }

        private fun atRest(): Boolean =
            abs(value - target) < 0.001f && abs(velocity) < 0.5
    }

    private class Spring(val mass: Double, val stiffness: Double, val damping: Double) {
        constructor(stiffness: Double, damping: Double) : this(1.0, stiffness, damping)
    }

    companion object {
        
        private val CALM = Spring(300.0, 35.0)
        private val PRESS = Spring(420.0, 26.0)
        private val RELEASE = Spring(240.0, 13.0)
        private val FOLLOW = Spring(520.0, 34.0)
        private val FADE = Spring(180.0, 27.0)

        private const val MAX_STEP = 1.0 / 30.0

        private const val DEFAULT_STRENGTH = 1f

        private const val MAX_CLIP_DEPTH = 12
        private const val TAG_KEY = 0x6a656c79 

        @JvmStatic
        fun install(view: View): JellyPress {
            (view.getTag(TAG_KEY) as? JellyPress)?.let { return it }
            val engine = JellyPress(view)
            view.setTag(TAG_KEY, engine)
            engine.attach()
            return engine
        }

        @JvmStatic
        fun of(view: View): JellyPress? = view.getTag(TAG_KEY) as? JellyPress

        @JvmStatic
        fun openOverdraw(view: View) {
            Companion.allowOverdraw(view)
        }

        internal fun allowOverdraw(view: View) {
            var node: ViewParent? = view.parent
            var depth = 0
            while (node != null && depth < MAX_CLIP_DEPTH) {
                if (node is android.widget.AbsListView || node is android.widget.ScrollView ||
                    node is android.widget.HorizontalScrollView
                ) return
                if (node is ViewGroup) {
                    node.clipChildren = false
                    node.clipToPadding = false
                }
                node = node.parent
                depth++
            }
        }

        private fun clamp(v: Float, lo: Float, hi: Float): Float =
            if (v < lo) lo else if (v > hi) hi else v
    }
}
