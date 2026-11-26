// Anchored morph used by the settings pickers.
package com.cairn.manager.redesign

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import kotlin.math.roundToInt

internal class BackgroundMotion(private val activity: Activity) {
    private var active: Dialog? = null
    private val p = RedesignTokens.palette(activity)
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).roundToInt()

    fun show(anchor: View, title: String, close: String, picture: android.graphics.drawable.Drawable? = null,
             actions: List<Pair<String, Runnable>> = emptyList()) {
        if (active != null || activity.isFinishing || activity.isDestroyed || !anchor.isAttachedToWindow) return
        val origin = IntArray(2)
        val sourceRect = Rect()
        fun readAnchor(): Boolean {
            if (!anchor.isShown || !anchor.getLocalVisibleRect(sourceRect)) return false
            anchor.getLocationOnScreen(origin)
            sourceRect.offset(origin[0], origin[1])
            return !sourceRect.isEmpty
        }
        if (!readAnchor()) return
        val overlay = FrameLayout(activity)
        val dim = View(activity).apply { alpha = 0f; setBackgroundColor(0x99000000.toInt()) }
        overlay.addView(dim, FrameLayout.LayoutParams(-1, -1))
        val card = FrameLayout(activity)
        val shape = GradientDrawable().apply { setColor(p.surfaceRaised or 0xff000000.toInt()) }
        card.background = shape
        card.clipToOutline = true
        card.isClickable = true
        overlay.addView(card, FrameLayout.LayoutParams(0, 0))
        val image = if (picture != null) ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(picture)
            card.addView(this, FrameLayout.LayoutParams(-1, -1))
        } else null
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            if (image != null) setBackgroundColor(p.surfaceRaised)
        }
        val controlsViewport = if (image == null) ScrollView(activity).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(controls, FrameLayout.LayoutParams(-1, -2))
        } else controls
        card.addView(controlsViewport, FrameLayout.LayoutParams(-1, if (image == null) -1 else -2, Gravity.BOTTOM))
        var animator: ValueAnimator? = null
        var progress = 0f
        var closing = false
        var ready = false
        var dismissAction: Runnable? = null
        var afterDismiss: Runnable? = null
        val dialog = object : Dialog(activity) {
            override fun onBackPressed() { dismissAction?.run() ?: super.onBackPressed() }
        }
        fun row(text: String, bold: Boolean, click: (() -> Unit)? = null) {
            val view = TextView(activity).apply {
                this.text = text
                textSize = if (bold) 19f else 16f
                setTextColor(p.primary)
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                maxLines = 2
                if (bold) isAccessibilityHeading = true
                if (click != null) {
                    isFocusable = true
                    addPressMotion()
                    setOnClickListener { if (ready && progress >= .95f && !closing) click() }
                }
            }
            controls.addView(view, LinearLayout.LayoutParams(-1, -2))
        }
        row(title, true)
        actions.forEach { (name, action) -> row(name, false) {
            afterDismiss = action
            dismissAction?.run()
        } }
        row(close, false) { dismissAction?.run() }
        val start = Rect(sourceRect)
        val end = Rect()
        val overlayLocation = IntArray(2)
        fun render(value: Float) {
            progress = value
            fun mix(a: Int, b: Int) = (a + (b - a) * value).roundToInt()
            val left = mix(start.left, end.left)
            val top = mix(start.top, end.top)
            val width = mix(start.width(), end.width()).coerceAtLeast(1)
            val height = mix(start.height(), end.height()).coerceAtLeast(1)
            card.layoutParams = FrameLayout.LayoutParams(width, height).apply {
                leftMargin = left
                topMargin = top
                gravity = Gravity.TOP or Gravity.LEFT
            }
            shape.cornerRadius = dp(14) + dp(12) * value.coerceIn(0f, 1f)
            card.invalidateOutline()
            dim.alpha = value.coerceIn(0f, 1f)
            
            card.alpha = (value / .28f).coerceIn(0f, 1f)
            controlsViewport.alpha = if (closing) ((value - .60f) / .40f).coerceIn(0f, 1f)
                else ((value - .24f) / .68f).coerceIn(0f, 1f)
            controlsViewport.translationY = dp(10) * (1f - value.coerceIn(0f, 1f))
            controlsViewport.importantForAccessibility = if (value >= .95f) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        fun animate(target: Float, duration: Long, finish: () -> Unit) {
            animator?.removeAllListeners()
            animator?.cancel()
            if (!ValueAnimator.areAnimatorsEnabled() || progress == target) {
                render(target); finish(); return
            }
            animator = ValueAnimator.ofFloat(progress, target).apply {
                this.duration = duration
                interpolator = if (target == 1f) PathInterpolator(.22f, .77f, .28f, 1.06f)
                    else PathInterpolator(.35f, 0f, .35f, 1f)
                addUpdateListener { render(it.animatedValue as Float) }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) { finish() }
                })
                start()
            }
        }
        fun closePanel() {
            if (closing) return
            closing = true
            if (!ready) { dialog.dismiss(); return }
            if (progress >= 1f && readAnchor()) {
                start.set(sourceRect)
                start.offset(-overlayLocation[0], -overlayLocation[1])
            }
            if (image != null) {
                animate(0f, 290L) { dialog.dismiss() }
                return
            }
            
            animator?.removeAllListeners()
            animator?.cancel()
            if (!ValueAnimator.areAnimatorsEnabled()) { dialog.dismiss(); return }
            controlsViewport.animate().alpha(0f).translationY(dp(4).toFloat()).setDuration(100L).start()
            val initialCardAlpha = card.alpha
            val initialDimAlpha = dim.alpha
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 225L
                interpolator = PathInterpolator(.25f, 0f, .2f, 1f)
                addUpdateListener {
                    val f = it.animatedValue as Float
                    card.alpha = initialCardAlpha * (1f - f)
                    card.scaleX = 1f - .04f * f
                    card.scaleY = 1f - .04f * f
                    card.translationY = dp(10) * f
                    dim.alpha = initialDimAlpha * (1f - f)
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) { dialog.dismiss() }
                })
                start()
            }
        }
        dismissAction = Runnable { closePanel() }
        dim.setOnClickListener { closePanel() }
        val detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { dialog.dismiss() }
        }
        dialog.setOnDismissListener {
            animator?.removeAllListeners(); animator?.cancel()
            controlsViewport.animate().cancel()
            anchor.removeOnAttachStateChangeListener(detach)
            active = null
            if (anchor.isAttachedToWindow && !activity.isFinishing && !activity.isDestroyed) afterDismiss?.run()
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(overlay)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(0)
            setLayout(-1, -1)
        }
        active = dialog
        anchor.addOnAttachStateChangeListener(detach)
        overlay.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int,
                                        ol: Int, ot: Int, or: Int, ob: Int) {
                if (overlay.width <= 0 || overlay.height <= 0 || !dialog.isShowing) return
                overlay.removeOnLayoutChangeListener(this)
                overlay.getLocationOnScreen(overlayLocation)
                start.offset(-overlayLocation[0], -overlayLocation[1])
                val w = (overlay.width - dp(32)).coerceIn(1, dp(560))
                controls.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                val limit = (overlay.height - dp(32)).coerceAtLeast(1)
                val h = if (image != null) limit else controls.measuredHeight.coerceIn(1, limit)
                val left = (overlay.width - w) / 2
                val top = (overlay.height - h) / 2
                end.set(left, top, left + w, top + h)
                ready = true
                render(0f)
                animate(1f, 340L) { }
            }
        })
        dialog.show()
        dialog.window?.setLayout(-1, -1)
    }
}
