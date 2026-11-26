// SPDX-License-Identifier: GPL-3.0-only
// Cairn Pro navigation host. Refraction uses the attributed KSU-derived liquid primitives.
// Liquid-style navigation transitions.
package com.cairn.manager.ui

import kotlin.math.abs
import kotlin.math.roundToInt
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cairn.manager.ui.ksu.FloatingBottomBar
import com.cairn.manager.ui.ksu.FloatingBottomBarItem
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

open class LiquidNavigation(context: Context) : FrameLayout(context) {
    fun interface SelectionListener { fun onSelected(index: Int) }

    private var labels by mutableStateOf(emptyList<String>())
    private var selected by mutableIntStateOf(0)
    private var scrollRevision by mutableIntStateOf(0)
    private var scrollCollapseTarget by mutableFloatStateOf(0f)
    private var swipePosition by mutableFloatStateOf(Float.NaN)
    private var swipeCommit by mutableIntStateOf(-1)
    private var snapshot by mutableStateOf<ImageBitmap?>(null)
    private var listener: SelectionListener? = null
    private var backgroundLayer: View? = null
    private var pageLayer: View? = null
    private var trackedTree: ViewTreeObserver? = null
    private val canRefract = isRuntimeShaderSupported()
    private var captureUntil = 0L
    private var lastCapture = -UpdateInterval
    private var scheduled = false
    private val dockLocation = IntArray(2)
    private val layerLocation = IntArray(2)
    private val scrollObserver = ViewTreeObserver.OnScrollChangedListener { refreshBackdrop(0L) }
    private val layoutObserver = ViewTreeObserver.OnGlobalLayoutListener { refreshBackdrop(0L) }
    private val captureTick = Runnable {
        scheduled = false
        val source = backgroundLayer
        if (source == null || !shouldCapture() || width <= 0 || height <= 0) return@Runnable
        val inset = (OpticalOverscan * resources.displayMetrics.density + .5f).toInt()
        val frame = Bitmap.createBitmap(width + 2 * inset, height + 2 * inset, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(frame)
        
        canvas.translate(inset.toFloat(), inset.toFloat())
        getLocationInWindow(dockLocation)
        drawLayer(source, canvas)
        val page = pageLayer
        if (page != null) {
            try {
                drawLayer(page, canvas)
            } catch (_: IllegalArgumentException) {
                
                drawLayer(source, canvas)
            }
        }
        snapshot = frame.asImageBitmap()
        lastCapture = SystemClock.uptimeMillis()
        if (lastCapture <= captureUntil) scheduleCapture()
    }

    init {
        clipChildren = false
        clipToPadding = false
        val compose = ComposeView(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        compose.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        compose.setContent {
            Navigation(labels, selected, canRefract && isHardwareAccelerated, { snapshot }, { swipePosition }, swipeCommit, scrollCollapseTarget, scrollRevision, { swipePosition = Float.NaN; swipeCommit = -1 }) { index ->
                if (index in labels.indices) {
                    swipePosition = Float.NaN
                    swipeCommit = -1
                    if (index != selected) {
                        selected = index
                        listener?.onSelected(index)
                    }
                }
            }
        }
        addView(compose, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setBackdropLayers(background: View, pages: View) {
        backgroundLayer = background
        pageLayer = pages
        refreshBackdrop(0L)
    }

    fun refreshBackdrop(transitionMs: Long) {
        if (!shouldCapture() || backgroundLayer == null) return
        val now = SystemClock.uptimeMillis()
        captureUntil = maxOf(captureUntil, now + transitionMs.coerceIn(0L, 650L))
        scheduleCapture()
    }

    private fun shouldCapture() = canRefract && isHardwareAccelerated &&
        isAttachedToWindow && isShown && windowVisibility == View.VISIBLE

    private fun scheduleCapture() {
        if (scheduled) return
        scheduled = true
        val wait = (UpdateInterval - (SystemClock.uptimeMillis() - lastCapture)).coerceAtLeast(0L)
        if (wait == 0L) postOnAnimation(captureTick) else postDelayed(captureTick, wait)
    }

    private fun drawLayer(view: View, canvas: AndroidCanvas) {
        view.getLocationInWindow(layerLocation)
        val restore = canvas.save()
        try {
            canvas.translate((layerLocation[0] - dockLocation[0]).toFloat(),
                (layerLocation[1] - dockLocation[1]).toFloat())
            view.draw(canvas)
        } finally {
            canvas.restoreToCount(restore)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        refreshBackdrop(0L)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (canRefract) {
            trackedTree = viewTreeObserver.also {
                it.addOnScrollChangedListener(scrollObserver)
                it.addOnGlobalLayoutListener(layoutObserver)
            }
            refreshBackdrop(0L)
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) refreshBackdrop(0L)
        else stopCapturing()
    }

    private fun stopCapturing() {
        removeCallbacks(captureTick)
        scheduled = false
        captureUntil = 0L
    }

    override fun onDetachedFromWindow() {
        trackedTree?.takeIf { it.isAlive }?.let {
            it.removeOnScrollChangedListener(scrollObserver)
            it.removeOnGlobalLayoutListener(layoutObserver)
        }
        trackedTree = null
        stopCapturing()
        snapshot = null
        super.onDetachedFromWindow()
    }

    fun setLabels(values: Array<String>) { labels = values.toList() }
    
    fun setSelectedIndex(value: Int) {
        if (value in labels.indices) { swipeCommit = -1; selected = value }
    }
    fun setScrollCollapse(value: Float) {
        scrollRevision++
        scrollCollapseTarget = 0f
        refreshBackdrop(280L)
    }
    fun setPageSwipePosition(value: Float) {
        
        if (value.isFinite()) {
            
            swipeCommit = -1
            swipePosition = value
        } else {
            swipePosition = Float.NaN
        }
        refreshBackdrop(0L)
    }
    fun completePageSwipe(index: Int) {
        if (index !in labels.indices) return
        
        swipePosition = Float.NaN
        swipeCommit = index
        selected = index
    }
    fun getSelectedIndex(): Int = selected
    fun setSelectionListener(value: SelectionListener?) { listener = value }

    private companion object { const val UpdateInterval = 34L }
}

private const val OpticalOverscan = 32

@Composable
private fun Navigation(
    labels: List<String>,
    selected: Int,
    refract: Boolean,
    source: () -> ImageBitmap?,
    pagePosition: () -> Float,
    swipeCommit: Int,
    collapse: Float,
    scrollRevision: Int,
    onLocalGesture: () -> Unit,
    onSelected: (Int) -> Unit,
) {
    if (labels.isEmpty()) return
    val compact = animateFloatAsState(collapse, spring(dampingRatio = .74f, stiffness = 460f),
        label = "Dock scroll shape")
    val backdrop = rememberLayerBackdrop(onDraw = {
        val image = source()
        if (image != null) drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        else drawRect(Color(0xFF252B34))
    })
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (refract) {
            
            val overscan = (OpticalOverscan * 2).dp
            Box(Modifier.align(Alignment.Center)
                .wrapContentSize(Alignment.Center, unbounded = true)
                .size(maxWidth + overscan, maxHeight + overscan)
                .layerBackdrop(backdrop))
        }
        val collapseProgress = compact.value.coerceIn(0f, 1f)
        var expandedByTap by androidx.compose.runtime.remember { mutableStateOf(false) }
        LaunchedEffect(scrollRevision) { expandedByTap = false }
        val collapsed = collapseProgress > .72f && !expandedByTap
        AnimatedContent(
            targetState = collapsed,
            modifier = Modifier
                .align(Alignment.Center)
                .offset {
                val shift = if (collapsed) -(maxWidth.toPx() - 78.dp.toPx()) / 2f + 24.dp.toPx() else 0f
                IntOffset(shift.roundToInt(), 0)
            },
            label = "iOS26 compact navigation morph",
        ) { isCollapsed ->
            val barModifier = Modifier
                .offset { IntOffset(0, (collapseProgress * 5.dp.toPx()).roundToInt()) }
                .then(if (isCollapsed) Modifier.widthIn(min = 68.dp, max = 78.dp) else Modifier.fillMaxWidth())
                .height(if (isCollapsed) 52.dp else 64.dp)
                .then(if (isCollapsed) Modifier.clickable { expandedByTap = true } else Modifier)
            FloatingBottomBar(
                modifier = barModifier,
                scrollShape = { collapseProgress },
                selectedIndex = if (isCollapsed) 0 else selected,
                pagePosition = { if (isCollapsed) 0f else pagePosition() },
                onLocalGesture = onLocalGesture,
                swipeCommit = if (isCollapsed) -1 else swipeCommit,
                onSelected = { index -> if (isCollapsed) expandedByTap = true else onSelected(index) },
                backdrop = backdrop,
                tabsCount = if (isCollapsed) 1 else labels.size,
                isBlurEnabled = refract,
            ) { activate ->
                if (isCollapsed) {
                    val tint by animateColorAsState(Color(0xFFF1FFF5), label = "Compact tab tint")
                    FloatingBottomBarItem(selected = true, onClick = { expandedByTap = true }) {
                        TabIcon(selected, true, tint)
                        BasicText(labels[selected], maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(color = tint, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center))
                    }
                } else {
                    labels.forEachIndexed { index, label ->
                        val active = index == selected
                        FloatingBottomBarItem(selected = active, onClick = { activate(index) }) {
                            val tint by animateColorAsState(
                                if (active) Color(0xFFF1FFF5) else Color(0xFFB7C9C0),
                                label = "Dock icon tint",
                            )
                            TabIcon(index, active, tint)
                            BasicText(label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(color = tint, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabIcon(index: Int, active: Boolean, color: Color) {
    val lift = animateFloatAsState(if (active) -1f else 0f,
        spring(dampingRatio = .77f, stiffness = 560f), label = "Dock icon lift")
    Canvas(Modifier.size(21.dp).graphicsLayer { translationY = lift.value.dp.toPx() }) {
        val unit = size.width / 24f
        scale(unit, unit, Offset.Zero) {
            val pen = Stroke(width = 1.75f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(x: Float, y: Float, u: Float, v: Float) =
                drawLine(color, Offset(x, y), Offset(u, v), pen.width, pen.cap)
            fun outline(vararg xy: Float) = drawPath(Path().apply {
                moveTo(xy[0], xy[1]); for (i in 2 until xy.size step 2) lineTo(xy[i], xy[i + 1])
            }, color, style = pen)
            when (index) {
                0 -> { 
                    outline(3f, 18.3f, 8.6f, 10.8f, 12.2f, 15.4f, 16.1f, 6f, 21f, 18.3f, 3f, 18.3f)
                }
                2 -> {
                    for (x in listOf(3.5f, 13.5f)) for (y in listOf(3.5f, 13.5f)) {
                        drawRoundRect(color, Offset(x, y), Size(7f, 7f), CornerRadius(1.65f), style = pen)
                    }
                }
                3 -> {
                    val gear = Path()
                    repeat(32) { point ->
                        val angle = (point - .5) * Math.PI / 16
                        val r = if (point % 4 in 0..1) 9.2 else 7.4
                        val x = (12 + r * kotlin.math.cos(angle)).toFloat()
                        val y = (12 + r * kotlin.math.sin(angle)).toFloat()
                        if (point == 0) gear.moveTo(x, y) else gear.lineTo(x, y)
                    }
                    gear.close()
                    drawPath(gear, color, style = pen)
                    drawCircle(color, 2.8f, Offset(12f, 12f), style = pen)
                }
                4 -> {
                    line(3.5f, 8f, 8f, 8f); line(12f, 8f, 20.5f, 8f)
                    drawCircle(color, 2f, Offset(10f, 8f), style = pen)
                    line(3.5f, 16f, 13f, 16f); line(17f, 16f, 20.5f, 16f)
                    drawCircle(color, 2f, Offset(15f, 16f), style = pen)
                }
                else -> {
                    outline(3f, 9f, 12f, 4f, 21f, 9f, 12f, 14f, 3f, 9f)
                    outline(3f, 13f, 12f, 18f, 21f, 13f)
                    outline(5f, 17.7f, 12f, 21.5f, 19f, 17.7f)
                }
            }
        }
    }
}
