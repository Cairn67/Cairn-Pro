// Overview page.
package com.cairn.manager.redesign

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class RedesignHome(
    context: Context,
    version: String,
    private val translate: java.util.function.BiFunction<String, String, String>,
    onModules: Runnable,
    onSuperusers: Runnable,
    onSave: Runnable,
    onClear: Runnable,
) {
    private fun t(en: String, zh: String) = translate.apply(en, zh)
    private val p = RedesignTokens.palette(context)
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density + .5f).toInt()

    @JvmField val root: View
    @JvmField val stateTitle: TextView
    @JvmField val stateMessage: TextView
    @JvmField val stateDetail: TextView
    @JvmField val stateMark: RedesignStatusMark
    @JvmField val keyInput: EditText
    @JvmField val moduleCount: TextView
    @JvmField val superuserCount: TextView
    @JvmField val authSummary: TextView

    private val statusCard: LinearLayout
    private val authPanel: LinearLayout
    private val authHint: TextView
    private val entrySections: List<View>
    private var entered = false

    fun playEntrance() {
        if (entered || !android.animation.ValueAnimator.areAnimatorsEnabled()) return
        entered = true
        val ease = android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)
        entrySections.forEachIndexed { index, view ->
            view.translationY = dp(9).toFloat()
            val entrance = view.animate().translationY(0f)
            if (view !== statusCard) {
                view.alpha = .55f
                entrance.alpha(1f)
            }
            entrance.setStartDelay(index * 34L)
                .setDuration(235L).setInterpolator(ease).start()
        }
    }

    fun setAuthenticationAvailable(available: Boolean) {
        statusCard.isClickable = available
        statusCard.isFocusable = available
        authHint.visibility = if (available) View.VISIBLE else View.GONE
        if (!available) {
            if (authPanel.visibility == View.VISIBLE) hideKeyKeyboard()
            authPanel.visibility = View.GONE
            authHint.text = t("Enter a key", "点击输入密钥")
        }
    }

    init {
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setOnScrollChangeListener { view, _, _, _, _ -> RedesignFrost.invalidateCards(view) }
            clipToPadding = false
            setPadding(0, 0, 0, dp(96))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(28))
        }
        scroll.addView(body, android.widget.FrameLayout.LayoutParams(-1, -2))
        root = scroll

        val mast = row(context)
        val brand = text(context, "Cairn Pro", 34f, p.primary, true)
        mast.addView(brand, LinearLayout.LayoutParams(0, dp(48), 1f))
        val build = text(context, "v$version", 12f, p.secondary, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(11), 0, dp(11), 0)
            background = surface(18, p.surfaceRaised, p.line)
        }
        mast.addView(build, LinearLayout.LayoutParams(-2, dp(34)))
        body.addView(mast)
        body.addView(text(context, t("System overview", "系统概览"), 11f, p.secondary, true).apply {
            letterSpacing = .12f
        }, margin(0, 3, 0, 0))

        val status = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(16))
            background = RedesignFrost.drawable(this, 22)
        }
        statusCard = status
        val statusRow = row(context)
        stateMark = RedesignStatusMark(context).apply { show(RedesignStatusMark.Glyph.CHECKING, p.accent) }
        statusRow.addView(stateMark, LinearLayout.LayoutParams(dp(42), dp(42)))
        val stateTexts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        stateTexts.setPaddingRelative(dp(13), 0, 0, 0)
        stateTexts.addView(text(context, t("Status", "状态"), 10f, p.secondary, true))
        stateTitle = text(context, t("Checking…", "检测中…"), 23f, p.primary, true)
        stateTexts.addView(stateTitle, margin(0, 2, 0, 0))
        statusRow.addView(stateTexts, LinearLayout.LayoutParams(0, -2, 1f))
        status.addView(statusRow)
        stateMessage = text(context, "", 13f, p.secondary, false)
        status.addView(stateMessage, margin(0, 7, 0, 0))
        stateDetail = text(context, "", 12f, p.secondary, false)
        status.addView(stateDetail, margin(0, 5, 0, 0))
        authHint = text(context, t("Enter a key", "点击输入密钥"), 12f, p.accent, true)
        status.addView(authHint, margin(0, 5, 0, 0))
        body.addView(status, margin(0, 14, 0, 0))

        val facts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        facts.addView(sectionLabel(context, t("Quick facts", "概况")), margin(0, 21, 0, 7))
        val counts = row(context)
        val modules = fact(context, t("Modules", "模块"))
        moduleCount = modules.second
        modules.first.addPressMotion()
        modules.first.setOnClickListener { onModules.run() }
        val users = fact(context, t("Superusers", "超级用户"))
        users.first.addPressMotion()
        users.first.setOnClickListener { onSuperusers.run() }
        superuserCount = users.second
        counts.addView(modules.first, LinearLayout.LayoutParams(0, dp(74), 1f))
        val usersLp = LinearLayout.LayoutParams(0, dp(74), 1f)
        usersLp.marginStart = dp(9)
        counts.addView(users.first, usersLp)
        facts.addView(counts)
        body.addView(facts)

        val deviceHeader = sectionLabel(context, t("Device", "设备"))
        body.addView(deviceHeader, margin(0, 22, 0, 7))
        val device = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = RedesignFrost.drawable(this, 18)
            clipToOutline = true
            
            isLongClickable = true
            isFocusable = true
            addPressMotion()
            setOnLongClickListener { true }
        }
        device.addView(factRow(context, t("Model", "型号"), android.os.Build.MODEL, first = true))
        device.addView(divider(context))
        device.addView(factRow(context, t("Android", "Android"), "${android.os.Build.VERSION.RELEASE} · API ${android.os.Build.VERSION.SDK_INT}"))
        device.addView(divider(context))
        device.addView(factRow(context, t("Kernel", "内核"), try { android.system.Os.uname().release } catch (_: Throwable) { "—" }))
        device.addView(divider(context))
        device.addView(factRow(context, t("Architecture", "架构"), android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "—", last = true))
        body.addView(device)
        entrySections = listOf(status, facts, deviceHeader, device)

        val auth = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(15))
            background = RedesignFrost.drawable(this, 20)
        }
        auth.addView(sectionLabel(context, t("Authentication", "认证")))
        authSummary = text(context, t("Key not configured", "未配置密钥"), 14f, p.primary, true)
        auth.addView(authSummary, margin(0, 5, 0, 0))
        keyInput = EditText(context).apply {
            hint = t("32-character hexadecimal key", "32 位十六进制密钥")
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            filters = arrayOf(android.text.InputFilter.LengthFilter(32))
            setHintTextColor(p.secondary)
            setTextColor(p.primary)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or
                android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            transformationMethod = null
            minHeight = dp(48)
            typeface = Typeface.MONOSPACE
            setSingleLine(true)
            setPadding(dp(12), 0, dp(12), 0)
            background = surface(13, p.surfaceRaised, p.line)
        }
        auth.addView(keyInput, margin(0, 12, 0, 0))
        val actions = row(context)
        val save = action(context, t("Save and authenticate", "保存并认证"), true, onSave)
        val clear = action(context, t("Clear", "清除"), false, onClear)
        actions.addView(save, LinearLayout.LayoutParams(0, dp(48), 1f))
        val clearLp = LinearLayout.LayoutParams(0, dp(48), .42f)
        clearLp.marginStart = dp(8)
        actions.addView(clear, clearLp)
        auth.addView(actions, margin(0, 11, 0, 0))
        authPanel = auth
        status.addView(auth, margin(0, 8, 0, 0))
        status.addPressMotion()
        status.setOnClickListener {
            val expanded = auth.visibility == View.VISIBLE
            if (expanded) hideKeyKeyboard()
            val animated = android.animation.ValueAnimator.areAnimatorsEnabled() && body.isLaidOut
            if (animated) {
                val transition = android.transition.TransitionSet().apply {
                    ordering = android.transition.TransitionSet.ORDERING_TOGETHER
                    addTransition(android.transition.ChangeBounds().apply {
                        duration = if (expanded) 190L else 230L
                        startDelay = if (expanded) 35L else 0L
                    })
                    addTransition(android.transition.Fade(
                        if (expanded) android.transition.Fade.OUT else android.transition.Fade.IN
                    ).apply {
                        addTarget(auth)
                        duration = if (expanded) 105L else 155L
                        startDelay = if (expanded) 0L else 65L
                    })
                    interpolator = android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)
                }
                android.transition.TransitionManager.beginDelayedTransition(body, transition)
            }
            auth.visibility = if (expanded) View.GONE else View.VISIBLE
            authHint.text = if (expanded) t("Enter a key", "点击输入密钥") else t("Hide key input", "收起密钥输入")
            if (!expanded) auth.postDelayed({
                if (auth.isShown) {
                    auth.requestRectangleOnScreen(android.graphics.Rect(0, 0, auth.width, auth.height), !animated)
                }
            }, if (animated) 240L else 0L)
        }
        setAuthenticationAvailable(false)
    }

    private fun hideKeyKeyboard() {
        if (!keyInput.hasFocus()) return
        keyInput.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(keyInput.windowToken, 0)
        keyInput.clearFocus()
    }

    private fun text(c: Context, value: String, size: Float, color: Int, medium: Boolean) = TextView(c).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
    }

    private fun row(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun sectionLabel(c: Context, value: String) = text(c, value, 10f, p.secondary, true).apply { letterSpacing = .12f }

    private fun fact(c: Context, label: String): Pair<View, TextView> {
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), 0, dp(13), 0)
            background = RedesignFrost.drawable(this, 17)
        }
        box.addView(text(c, label, 10f, p.secondary, true))
        val value = text(c, "—", 25f, p.primary, true)
        box.addView(value, margin(0, 5, 0, 0))
        return box to value
    }

    private fun factRow(c: Context, label: String, value: String, first: Boolean = false, last: Boolean = false): View {
        val line = row(c).apply {
            minimumHeight = dp(56)
            setPadding(dp(13), dp(10), dp(13), dp(10))
        }
        line.addView(text(c, label, 11f, p.secondary, true), LinearLayout.LayoutParams(dp(112), -2))
        line.addView(text(c, value, 13f, p.primary, false).apply {
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        return line
    }

    private fun divider(c: Context) = View(c).apply {
        setBackgroundColor(p.line)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    private fun action(c: Context, label: String, primary: Boolean, callback: Runnable) = TextView(c).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 13f
        includeFontPadding = false
        setTextColor(if (primary) p.surface else p.primary)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = surface(14, if (primary) p.accent else p.surfaceRaised, p.line)
        addPressMotion()
        setOnClickListener { callback.run() }
    }

    private fun surface(radius: Int, fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun margin(l: Int, t: Int, r: Int, b: Int) = LinearLayout.LayoutParams(-1, -2).apply {
        setMargins(dp(l), dp(t), dp(r), dp(b))
    }
}
