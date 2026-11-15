package com.weich.daptune.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weich.daptune.core.model.DapBandPlan
import com.weich.daptune.core.model.EqCurve
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.roundToLong

val EqBandTrackHeight = 300.dp

@Composable
fun EqCurveOverview(
    curve: EqCurve,
    selectedBand: Int,
    onBandSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val axis = remember(curve) { gainAxisFor(curve) }
    val majorTicksQ4 = remember(axis) { axis.majorTicksQ4() }
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val zero = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val axisStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val textMeasurer = rememberTextMeasurer()
    val currentOnBandSelected by rememberUpdatedState(onBandSelected)
    val plotStartInset = 36.dp
    val plotEndInset = 8.dp
    val plotTopInset = 8.dp
    val plotBottomInset = 24.dp
    val verticalGridBands = listOf(0, 4, 9, 14, 19)

    Canvas(
        modifier = modifier
            .pointerInput(curve, axis) {
                detectTapGestures { tap ->
                    val startInset = with(density) { plotStartInset.toPx() }
                    val endInset = with(density) { plotEndInset.toPx() }
                    val topInset = with(density) { plotTopInset.toPx() }
                    val bottomInset = with(density) { plotBottomInset.toPx() }
                    val hitRadius = with(density) { 24.dp.toPx() }
                    nearestCurveBandAt(
                        curve = curve,
                        tapX = tap.x,
                        tapY = tap.y,
                        widthPx = size.width.toFloat(),
                        heightPx = size.height.toFloat(),
                        horizontalInsetPx = startInset,
                        verticalInsetPx = topInset,
                        hitRadiusPx = hitRadius,
                        horizontalEndInsetPx = endInset,
                        verticalEndInsetPx = bottomInset,
                        axis = axis,
                    )?.let { nearest ->
                        currentOnBandSelected(nearest)
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                    }
                }
            }
            .semantics {
                contentDescription = "20 段均衡器曲线，点按频点以选择"
            },
    ) {
        val startInset = plotStartInset.toPx()
        val endInset = plotEndInset.toPx()
        val topInset = plotTopInset.toPx()
        val bottomInset = plotBottomInset.toPx()
        val plotEndX = size.width - endInset
        val plotEndY = size.height - bottomInset
        val plotWidth = (size.width - startInset - endInset).coerceAtLeast(1f)
        val plotHeight = (size.height - topInset - bottomInset).coerceAtLeast(1f)
        val zeroY = topInset + plotHeight * axis.fractionFor(0)

        fun yFor(gainQ4: Int): Float =
            topInset + plotHeight * axis.fractionFor(gainQ4)

        majorTicksQ4.forEach { gainQ4 ->
            val y = yFor(gainQ4)
            drawLine(
                color = if (gainQ4 == 0) zero else grid,
                start = Offset(startInset, y),
                end = Offset(plotEndX, y),
                strokeWidth = if (gainQ4 == 0) 1.25.dp.toPx() else 0.75.dp.toPx(),
            )
            val label = formatAxisGain(gainQ4)
            val labelLayout = textMeasurer.measure(text = label, style = axisStyle)
            drawText(
                textLayoutResult = labelLayout,
                topLeft = Offset(
                    x = 3.dp.toPx(),
                    y = (y - labelLayout.size.height / 2f)
                        .coerceIn(0f, plotEndY - labelLayout.size.height),
                ),
            )
        }
        verticalGridBands.forEach { index ->
            val x = startInset + plotWidth * index / (DapBandPlan.bandCount - 1)
            drawLine(
                color = grid.copy(alpha = 0.62f),
                start = Offset(x, topInset),
                end = Offset(x, plotEndY),
                strokeWidth = 0.75.dp.toPx(),
            )
            val label = formatFrequency(DapBandPlan.frequenciesHz[index])
            val labelLayout = textMeasurer.measure(text = label, style = axisStyle)
            drawText(
                textLayoutResult = labelLayout,
                topLeft = Offset(
                    x = (x - labelLayout.size.width / 2f).coerceIn(
                        2.dp.toPx(),
                        size.width - labelLayout.size.width - 2.dp.toPx(),
                    ),
                    y = plotEndY + 3.dp.toPx(),
                ),
            )
        }

        val points = List(DapBandPlan.bandCount) { index ->
            curvePointForBand(
                curve = curve,
                index = index,
                widthPx = size.width,
                heightPx = size.height,
                horizontalInsetPx = startInset,
                verticalInsetPx = topInset,
                horizontalEndInsetPx = endInset,
                verticalEndInsetPx = bottomInset,
                axis = axis,
            )
        }
        val linePath = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        val fillPath = Path().apply {
            addPath(linePath)
            lineTo(points.last().x, zeroY)
            lineTo(points.first().x, zeroY)
            close()
        }
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(primary.copy(alpha = 0.24f), primary.copy(alpha = 0.025f)),
                startY = topInset,
                endY = plotEndY,
            ),
        )
        drawPath(
            path = linePath,
            color = primary,
            style = Stroke(width = 2.75.dp.toPx(), cap = StrokeCap.Round),
        )
        points.forEach { point ->
            drawCircle(primary, radius = 2.75.dp.toPx(), center = point)
        }
        points.getOrNull(selectedBand)?.let { point ->
            drawLine(
                color = primary.copy(alpha = 0.18f),
                start = Offset(point.x, topInset),
                end = Offset(point.x, plotEndY),
                strokeWidth = 1.dp.toPx(),
            )
            drawCircle(primary.copy(alpha = 0.16f), radius = 11.dp.toPx(), center = point)
            drawCircle(surface, radius = 6.5.dp.toPx(), center = point)
            drawCircle(primary, radius = 4.dp.toPx(), center = point)
        }
    }
}

@Composable
fun CurveSparkline(
    curve: EqCurve,
    modifier: Modifier = Modifier,
) {
    val axis = remember(curve) { gainAxisFor(curve) }
    val majorTicksQ4 = remember(axis) { axis.majorTicksQ4() }
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val zero = MaterialTheme.colorScheme.outline
    Canvas(
        modifier = modifier.semantics { contentDescription = "均衡器曲线" },
    ) {
        val verticalInset = 3.dp.toPx()
        val plotHeight = (size.height - verticalInset * 2f).coerceAtLeast(1f)
        fun yFor(gainQ4: Int): Float =
            verticalInset + plotHeight * axis.fractionFor(gainQ4)

        majorTicksQ4.forEach { gainQ4 ->
            val y = yFor(gainQ4)
            drawLine(
                color = if (gainQ4 == 0) zero else grid.copy(alpha = 0.72f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = if (gainQ4 == 0) 1.dp.toPx() else 0.65.dp.toPx(),
            )
        }
        val points = List(DapBandPlan.bandCount) { index ->
            Offset(
                x = size.width * index / (DapBandPlan.bandCount - 1),
                y = yFor(curve[index]),
            )
        }
        val linePath = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        val zeroY = yFor(0)
        val fillPath = Path().apply {
            addPath(linePath)
            lineTo(points.last().x, zeroY)
            lineTo(points.first().x, zeroY)
            close()
        }
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(primary.copy(alpha = 0.2f), primary.copy(alpha = 0.015f)),
            ),
        )
        drawPath(
            path = linePath,
            color = primary,
            style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
fun GainScale(
    axis: GainAxis,
    modifier: Modifier = Modifier,
    trackHeight: Dp = EqBandTrackHeight,
) {
    val majorTicksQ4 = remember(axis) { axis.majorTicksQ4() }
    Column(
        modifier = modifier.width(34.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Spacer(Modifier.height(28.dp))
        Box(
            modifier = Modifier
                .height(trackHeight),
        ) {
            majorTicksQ4.forEach { gainQ4 ->
                val y = 12.dp + (trackHeight - 24.dp) * axis.fractionFor(gainQ4)
                Text(
                    text = formatAxisGain(gainQ4),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = y - 7.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun VerticalBandSlider(
    frequencyLabel: String,
    valueQ4: Int,
    onValueChange: (Int) -> Unit,
    selected: Boolean,
    onSelected: () -> Unit,
    axis: GainAxis,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val inactive = MaterialTheme.colorScheme.surfaceContainerHighest
    val tick = MaterialTheme.colorScheme.outline
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val verticalInsetPx = with(density) { 12.dp.toPx() }
    val trackHeightPx = with(density) { EqBandTrackHeight.toPx() }
    val thumbTouchSize = 48.dp
    val thumbTouchSizePx = with(density) { thumbTouchSize.toPx() }
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnSelected by rememberUpdatedState(onSelected)
    val currentValueQ4 by rememberUpdatedState(valueQ4)
    val currentAxis by rememberUpdatedState(axis)
    val majorTicksQ4 = remember(axis) { axis.majorTicksQ4().toSet() }
    val bandInteractionSource = remember { MutableInteractionSource() }
    val thumbInteractionSource = remember { MutableInteractionSource() }
    var lastReportedQ4 by remember { mutableIntStateOf(valueQ4) }
    var dragging by remember { mutableStateOf(false) }
    var dragValueQ4 by remember { mutableDoubleStateOf(valueQ4.toDouble()) }

    SideEffect {
        if (!dragging) {
            lastReportedQ4 = valueQ4
            dragValueQ4 = valueQ4.toDouble()
        }
    }

    fun yFor(gainQ4: Int): Float {
        return trackYForGainQ4(gainQ4, trackHeightPx, verticalInsetPx, axis)
    }

    fun update(rawQ4: Double) {
        val snapped = snapGainQ4(rawQ4.roundToInt())
        if (snapped != lastReportedQ4) {
            lastReportedQ4 = snapped
            haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            currentOnValueChange(snapped)
        }
    }

    Column(
        modifier = modifier
            .width(56.dp)
            .clip(MaterialTheme.shapes.small)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f) else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            )
            .clickable(
                interactionSource = bandInteractionSource,
                indication = null,
                onClick = { currentOnSelected() },
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = formatGain(valueQ4),
            modifier = Modifier.height(24.dp),
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else {
                MaterialTheme.colorScheme.onSurface
            },
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
        Box(
            modifier = Modifier
                .width(48.dp)
                .height(EqBandTrackHeight)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val centerX = size.width / 2f
                val top = verticalInsetPx
                val bottom = size.height - verticalInsetPx
                val usable = bottom - top
                val zeroY = trackYForGainQ4(0, size.height, verticalInsetPx, axis)
                val thumbY = trackYForGainQ4(valueQ4, size.height, verticalInsetPx, axis)
                var tickQ4 = axis.maximumQ4.toLong()
                var tickIndex = 0
                while (tickQ4 >= axis.minimumQ4.toLong()) {
                    val gainQ4 = tickQ4.toInt()
                    val y = trackYForGainQ4(gainQ4, size.height, verticalInsetPx, axis)
                    val isMajor = gainQ4 in majorTicksQ4
                    val length = when {
                        isMajor -> 22.dp.toPx()
                        tickIndex % 2 == 0 -> 14.dp.toPx()
                        else -> 8.dp.toPx()
                    }
                    drawLine(
                        color = tick.copy(alpha = if (isMajor) 0.72f else 0.42f),
                        start = Offset(centerX - length / 2f, y),
                        end = Offset(centerX + length / 2f, y),
                        strokeWidth = if (isMajor) 1.15.dp.toPx() else 0.75.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    tickQ4 -= axis.minorStepQ4.toLong()
                    tickIndex += 1
                }
                drawRoundRect(
                    color = inactive,
                    topLeft = Offset(centerX - 2.5.dp.toPx(), top),
                    size = Size(5.dp.toPx(), usable),
                    cornerRadius = CornerRadius(2.5.dp.toPx()),
                )
                drawRoundRect(
                    color = primary,
                    topLeft = Offset(centerX - 2.5.dp.toPx(), minOf(zeroY, thumbY)),
                    size = Size(5.dp.toPx(), abs(zeroY - thumbY)),
                    cornerRadius = CornerRadius(2.5.dp.toPx()),
                )
                if (selected) {
                    drawCircle(primary.copy(alpha = 0.18f), 14.dp.toPx(), Offset(centerX, thumbY))
                }
                drawCircle(primary, 9.dp.toPx(), Offset(centerX, thumbY))
                drawCircle(onPrimary, 3.25.dp.toPx(), Offset(centerX, thumbY))
            }
            val thumbY = yFor(valueQ4)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset {
                        IntOffset(
                            x = 0,
                            y = (thumbY - thumbTouchSizePx / 2f)
                                .coerceIn(0f, trackHeightPx - thumbTouchSizePx)
                                .roundToInt(),
                        )
                    }
                    .size(thumbTouchSize)
                    .semantics {
                        contentDescription = "$frequencyLabel 均衡器滑块"
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            current = valueQ4.toFloat() / EqCurve.Q4_PER_DB,
                            range = axis.minimumQ4.toFloat() / EqCurve.Q4_PER_DB..
                                axis.maximumQ4.toFloat() / EqCurve.Q4_PER_DB,
                            steps = axis.accessibilitySteps,
                        )
                        setProgress { targetDb ->
                            val targetQ4 = snapGainQ4(
                                (targetDb * EqCurve.Q4_PER_DB).roundToInt(),
                            )
                            currentOnSelected()
                            currentOnValueChange(targetQ4)
                            true
                        }
                    }
                    .clickable(
                        interactionSource = thumbInteractionSource,
                        indication = null,
                        onClick = { currentOnSelected() },
                    )
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                dragging = true
                                dragValueQ4 = currentValueQ4.toDouble()
                                currentOnSelected()
                                haptic.performHapticFeedback(
                                    HapticFeedbackType.GestureThresholdActivate,
                                )
                            },
                            onDragEnd = {
                                dragging = false
                                haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                            },
                            onDragCancel = {
                                dragging = false
                            },
                        ) { change, dragAmount ->
                            // The 48 dp thumb target owns both axes so a horizontal
                            // movement cannot leak into the surrounding LazyRow.
                            change.consume()
                            val usableHeight = (trackHeightPx - verticalInsetPx * 2f)
                                .coerceAtLeast(1f)
                            dragValueQ4 -= dragAmount.y * currentAxis.rangeQ4 / usableHeight
                            update(dragValueQ4)
                        }
                    },
            )
        }
        Text(
            text = frequencyLabel,
            modifier = Modifier.height(20.dp),
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

fun formatFrequency(frequencyHz: Int): String = when {
    frequencyHz >= 1_000 -> {
        val value = "%.1f".format(frequencyHz / 1_000.0).trimEnd('0').trimEnd('.')
        "${value}k"
    }
    else -> frequencyHz.toString()
}

fun formatFrequencyWithUnit(frequencyHz: Int): String = when {
    frequencyHz >= 1_000 -> {
        val decimals = if (frequencyHz >= 10_000) 1 else 2
        val pattern = if (decimals == 1) "%.1f" else "%.2f"
        val value = pattern.format(frequencyHz / 1_000.0)
            .trimEnd('0')
            .trimEnd('.')
        "$value kHz"
    }
    else -> "$frequencyHz Hz"
}

fun formatGain(valueQ4: Int): String {
    val db = valueQ4.toDouble() / EqCurve.Q4_PER_DB
    return when {
        valueQ4 == 0 -> "0"
        valueQ4 % EqCurve.Q4_PER_DB == 0 -> "%+.0f".format(db)
        else -> "%+.1f".format(db)
    }
}

private fun snapGainQ4(valueQ4: Int): Int =
    ((valueQ4.toDouble() / GainStepQ4).roundToLong() * GainStepQ4)
        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
        .toInt()

internal fun gainQ4ForTrackPosition(
    yPx: Float,
    trackHeightPx: Float,
    verticalInsetPx: Float,
    axis: GainAxis,
): Int {
    val usable = (trackHeightPx - verticalInsetPx * 2f).coerceAtLeast(1f)
    val fraction = ((yPx - verticalInsetPx) / usable).coerceIn(0f, 1f)
    val raw = axis.maximumQ4.toDouble() - fraction * axis.rangeQ4
    return snapGainQ4(raw.roundToInt()).coerceIn(axis.minimumQ4, axis.maximumQ4)
}

internal fun trackYForGainQ4(
    gainQ4: Int,
    trackHeightPx: Float,
    verticalInsetPx: Float,
    axis: GainAxis,
): Float {
    val usable = (trackHeightPx - verticalInsetPx * 2f).coerceAtLeast(1f)
    return verticalInsetPx + usable * axis.fractionFor(gainQ4)
}

internal fun nearestCurveBandAt(
    curve: EqCurve,
    tapX: Float,
    tapY: Float,
    widthPx: Float,
    heightPx: Float,
    horizontalInsetPx: Float,
    verticalInsetPx: Float,
    hitRadiusPx: Float,
    horizontalEndInsetPx: Float = horizontalInsetPx,
    verticalEndInsetPx: Float = verticalInsetPx,
    axis: GainAxis = gainAxisFor(curve),
): Int? {
    val nearest = (0 until DapBandPlan.bandCount).minByOrNull { index ->
        val point = curvePointForBand(
            curve = curve,
            index = index,
            widthPx = widthPx,
            heightPx = heightPx,
            horizontalInsetPx = horizontalInsetPx,
            verticalInsetPx = verticalInsetPx,
            horizontalEndInsetPx = horizontalEndInsetPx,
            verticalEndInsetPx = verticalEndInsetPx,
            axis = axis,
        )
        hypot(tapX - point.x, tapY - point.y)
    } ?: return null
    val point = curvePointForBand(
        curve = curve,
        index = nearest,
        widthPx = widthPx,
        heightPx = heightPx,
        horizontalInsetPx = horizontalInsetPx,
        verticalInsetPx = verticalInsetPx,
        horizontalEndInsetPx = horizontalEndInsetPx,
        verticalEndInsetPx = verticalEndInsetPx,
        axis = axis,
    )
    return nearest.takeIf { hypot(tapX - point.x, tapY - point.y) <= hitRadiusPx }
}

private fun curvePointForBand(
    curve: EqCurve,
    index: Int,
    widthPx: Float,
    heightPx: Float,
    horizontalInsetPx: Float,
    verticalInsetPx: Float,
    horizontalEndInsetPx: Float = horizontalInsetPx,
    verticalEndInsetPx: Float = verticalInsetPx,
    axis: GainAxis = gainAxisFor(curve),
): Offset {
    val plotWidth = (widthPx - horizontalInsetPx - horizontalEndInsetPx).coerceAtLeast(1f)
    val plotHeight = (heightPx - verticalInsetPx - verticalEndInsetPx).coerceAtLeast(1f)
    return Offset(
        x = horizontalInsetPx + plotWidth * index / (DapBandPlan.bandCount - 1),
        y = verticalInsetPx + plotHeight * axis.fractionFor(curve[index]),
    )
}

private fun formatAxisGain(valueQ4: Int): String = formatGain(valueQ4).replace('-', '−')
