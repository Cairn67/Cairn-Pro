// Cairn Pro adaptation; original liquid navigation derived from KSU dbb73665d103da40c7ecbd21ba3b5ac76eb95784.
// Adapted from compose-miuix-ui example (IosLiquidGlassNavigationBar) — Apache 2.0.
// Floating bottom bar with drag damping and touch highlight.
package com.cairn.manager.ui.ksu

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.runtime.derivedStateOf
import com.cairn.manager.ui.liquid.rememberCombinedBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cairn.manager.ui.ksu.miuix.animation.DampedDragAnimation
import com.cairn.manager.ui.ksu.miuix.animation.InteractiveHighlight
import com.cairn.manager.ui.liquid.lens
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import kotlin.math.roundToInt

private val GlassTint = Brush.verticalGradient(listOf(Color(0x381E2530), Color(0x5211161E)))
private val GlassEdge = Brush.verticalGradient(listOf(Color(0x70E6EDF5), Color(0x14DCE6F0), Color(0x42B7C5D4)))
private val PillTint = Brush.verticalGradient(listOf(Color(0x42E7EEF5), Color(0x1C718092)))
private val PillEdge = Brush.verticalGradient(listOf(Color(0xA8F1F5FA), Color(0x30D7E1EB)))

private const val BloomVelocitySpan = 10f
private const val BloomGainX = .75f
private const val BloomGainY = .25f
private const val BloomLimit = .2f
private const val PillPressedScale = 1.34f

private fun tabIndexAt(x: Float, width: Float, tabsCount: Int, rtl: Boolean): Int {
    if (tabsCount <= 0 || width <= 0f) return 0
    val cell = width / tabsCount
    if (cell <= 0f) return 0
    val logicalX = if (rtl) width - x else x
    return (logicalX / cell).toInt().coerceIn(0, tabsCount - 1)
}

@Composable
fun RowScope.FloatingBottomBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    Box(
        modifier.weight(1f).fillMaxHeight().clip(CircleShape).selectable(
            selected = selected,
            interactionSource = interactions,
            indication = null,
            role = Role.Tab,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (focused) Box(Modifier.fillMaxSize().padding(2.dp)
            .border(1.dp, Color(0xFFB4EDCE), CircleShape))
        if (pressed) Box(Modifier.fillMaxSize().padding(3.dp)
            .background(Color.White.copy(alpha = .04f), CircleShape))
        Column(
            Modifier.padding(horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

@Composable
fun FloatingBottomBar(
    modifier: Modifier = Modifier,
    selectedIndex: Int,
    pagePosition: () -> Float,
    onLocalGesture: () -> Unit,
    scrollShape: () -> Float,
    swipeCommit: Int,
    onSelected: (Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    isBlurEnabled: Boolean,
    content: @Composable RowScope.((Int) -> Unit) -> Unit,
) {
    if (tabsCount == 0) return
    val selected = selectedIndex.coerceIn(0, tabsCount - 1)
    val selection by rememberUpdatedState(onSelected)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scope = rememberCoroutineScope()
    val currentSelection by rememberUpdatedState(selected)
    var suppressTapUntil by remember { androidx.compose.runtime.mutableStateOf(0L) }
    
    val motion = remember(scope, tabsCount, rtl) {
        DampedDragAnimation(
            animationScope = scope,
            initialValue = selected.toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            visibilityThreshold = .001f,
            initialScale = 1f,
            pressedScale = PillPressedScale,
            onDragStarted = {},
            onDragStopped = {},
            onDrag = { _, _ -> },
        )
    }
    val highlight = remember(scope, isBlurEnabled) {
        if (isBlurEnabled) InteractiveHighlight(scope) else null
    }
    val beginLocalGesture by rememberUpdatedState(onLocalGesture)
    var dragPosition by remember { androidx.compose.runtime.mutableFloatStateOf(Float.NaN) }
    LaunchedEffect(selected, motion, swipeCommit) {
        if (swipeCommit == selected) {
            motion.snapPosition(selected.toFloat())
        } else if (!dragPosition.isFinite() && motion.targetValue != selected.toFloat())
            motion.animateToValue(selected.toFloat())
    }
    val tabInk = rememberLayerBackdrop()
    val capsuleSource = rememberCombinedBackdrop(backdrop, tabInk)
    val interactiveLens by remember(motion, isBlurEnabled) {
        derivedStateOf { isBlurEnabled && motion.pressProgress > .01f }
    }
    val dockSurface = if (isBlurEnabled) Modifier
        .border(1.dp, GlassEdge, CircleShape)
        .drawBackdrop(
            backdrop = backdrop,
            shape = { CircleShape },
            effects = {
                padding = maxOf(padding, 30.dp.toPx())
                blur(9.dp.toPx(), 9.dp.toPx())
                val shape = scrollShape().coerceIn(-.22f, 1f)
                lens(refractionHeight = (24f - 6f * shape).dp.toPx(),
                    refractionAmount = (24f - 8f * shape).dp.toPx())
            },
            onDrawSurface = { drawRect(GlassTint) },
        ) else Modifier.background(Color(0xED1E2530), CircleShape).border(1.dp, GlassEdge, CircleShape)

    val panelDrag = remember { Animatable(0f) }
    var panelReturn by remember { androidx.compose.runtime.mutableStateOf<Job?>(null) }
    fun releasePanel() {
        panelReturn?.cancel()
        panelReturn = scope.launch {
            panelDrag.animateTo(0f, spring(1f, 300f, .5f))
        }
    }
    BoxWithConstraints(modifier.graphicsLayer {
        val fraction = (panelDrag.value / size.width.coerceAtLeast(1f)).coerceIn(-1f, 1f)
        translationX = 4.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
    }) {
        
        Box(Modifier.matchParentSize().then(dockSurface))
        
        if (highlight != null) {
            Box(Modifier.matchParentSize().clip(CircleShape).then(highlight.modifier))
        }
        val inset = 4.dp
        val cellWidth = ((maxWidth - inset * 2) / tabsCount).coerceAtLeast(1.dp)
        val capsuleWidth = (cellWidth - 4.dp).coerceAtLeast(1.dp)
        Box(Modifier.fillMaxSize().padding(inset)) {
            val external = pagePosition()
            
            val capsulePosition = when {
                external.isFinite() -> external
                swipeCommit == selected && abs(motion.value - selected) > .001f -> selected.toFloat()
                else -> motion.value
            }
            val capsule = Modifier.align(Alignment.CenterStart).padding(horizontal = 2.dp)
                .width(capsuleWidth).fillMaxHeight().padding(vertical = 2.dp)
                .graphicsLayer {
                    translationX = cellWidth.toPx() * capsulePosition * if (rtl) -1f else 1f
                    
                    val velocity = motion.velocity / BloomVelocitySpan
                    val bloomX = (velocity * BloomGainX).coerceIn(-BloomLimit, BloomLimit)
                    val bloomY = (velocity * BloomGainY).coerceIn(-BloomLimit, BloomLimit)
                    scaleX = motion.scaleX / (1f - bloomX)
                    scaleY = motion.scaleY * (1f - bloomY)
                }
            
            Box(capsule.graphicsLayer { alpha = if (isBlurEnabled) 1f - motion.pressProgress.coerceIn(0f, 1f) else 1f }
                .background(PillTint, CircleShape).border(1.dp, PillEdge, CircleShape))
            Row(
                Modifier.fillMaxSize()
                    .then(if (isBlurEnabled) Modifier.layerBackdrop(tabInk) else Modifier)
                    .selectableGroup()
                    .pointerInput(motion, rtl) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            motion.press()
                            
                            val indexAtDown = tabIndexAt(down.position.x, size.width.toFloat(), tabsCount, rtl)
                            beginLocalGesture()
                            motion.updateValue(indexAtDown.toFloat())
                            try {
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                } while (event.changes.any { it.id == down.id && it.pressed })
                            } finally {
                                motion.release()
                            }
                        }
                    }
                    .then(highlight?.gestureModifier ?: Modifier)
                    .pointerInput(motion, rtl) {
                        var target = 0f
                        fun position(x: Float): Float {
                            val cell = (size.width.toFloat() / tabsCount).coerceAtLeast(1f)
                            val logicalX = if (rtl) size.width - x else x
                            return (logicalX / cell - .5f).coerceIn(0f, (tabsCount - 1).toFloat())
                        }
                        detectHorizontalDragGestures(
                            onDragStart = {
                                beginLocalGesture()
                                panelReturn?.cancel()
                                scope.launch { panelDrag.snapTo(0f) }
                                suppressTapUntil = Long.MAX_VALUE
                                
                                target = position(it.x)
                                dragPosition = target
                                motion.updateValue(target)
                            },
                            onDragEnd = {
                                dragPosition = Float.NaN
                                releasePanel()
                                suppressTapUntil = android.os.SystemClock.uptimeMillis() + 180L
                                val index = target.roundToInt().coerceIn(0, tabsCount - 1)
                                motion.updateValue(index.toFloat())
                                if (index != currentSelection) selection(index)
                            },
                            onDragCancel = {
                                dragPosition = Float.NaN
                                releasePanel()
                                suppressTapUntil = android.os.SystemClock.uptimeMillis() + 180L
                                motion.updateValue(currentSelection.toFloat())
                            },
                        ) { change, delta ->
                            change.consume()
                            
                            scope.launch { panelDrag.snapTo(panelDrag.value + delta) }
                            val cell = (size.width.toFloat() / tabsCount).coerceAtLeast(1f)
                            
                            target = (target + delta / cell * if (rtl) -1f else 1f)
                                .coerceIn(0f, (tabsCount - 1).toFloat())
                            dragPosition = target
                            motion.updateValue(target)
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                content { index ->
                    if (index in 0 until tabsCount && android.os.SystemClock.uptimeMillis() >= suppressTapUntil) {
                        beginLocalGesture()
                        motion.updateValue(index.toFloat())
                        selection(index)
                    }
                }
            }

            if (interactiveLens) {
                Box(capsule.drawBackdrop(
                    backdrop = capsuleSource,
                    shape = { CircleShape },
                    effects = {
                        val progress = motion.pressProgress.coerceIn(0f, 1f)
                        padding = maxOf(padding, 24.dp.toPx())
                        blur(1.4.dp.toPx() * progress, 1.4.dp.toPx() * progress)
                        lens(10.dp.toPx() * progress, 14.dp.toPx() * progress,
                            depthEffect = true, chromaticAberration = .5f * progress)
                    },
                    onDrawSurface = { drawRect(Color(0x280F181D), alpha = motion.pressProgress.coerceIn(0f, 1f)) },
                ).border(1.dp, PillEdge, CircleShape))
            }
        }
    }
}
