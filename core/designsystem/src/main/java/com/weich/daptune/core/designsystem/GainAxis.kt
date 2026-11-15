package com.weich.daptune.core.designsystem

import com.weich.daptune.core.model.EqCurve
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** Shared vertical scale for the overview and all 20 band controls. */
class GainAxis internal constructor(
    val maximumQ4: Int,
    val minimumQ4: Int,
    val majorStepQ4: Int,
    val minorStepQ4: Int,
) {
    init {
        require(maximumQ4 > minimumQ4)
        require(majorStepQ4 > 0)
        require(minorStepQ4 > 0)
    }

    val rangeQ4: Long
        get() = maximumQ4.toLong() - minimumQ4.toLong()

    val accessibilitySteps: Int
        get() = (rangeQ4 / GainStepQ4 - 1L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    fun fractionFor(gainQ4: Int): Float =
        ((maximumQ4.toDouble() - gainQ4.toDouble()) / rangeQ4.toDouble())
            .coerceIn(0.0, 1.0)
            .toFloat()

    fun majorTicksQ4(): List<Int> = buildList {
        var positive = maximumQ4.toLong()
        while (positive > 0L) {
            add(positive.toInt())
            positive -= majorStepQ4.toLong()
        }
        if (lastOrNull() != 0) add(0)

        var negative = -majorStepQ4.toLong()
        while (negative > minimumQ4.toLong()) {
            add(negative.toInt())
            negative -= majorStepQ4.toLong()
        }
        if (lastOrNull() != minimumQ4) add(minimumQ4)
    }
}

fun gainAxisFor(curve: EqCurve, ceilingQ4: Int? = null): GainAxis =
    gainAxisForRange(curve.toQ4List().min(), curve.toQ4List().max(), ceilingQ4)

internal fun gainAxisForMinimum(minimumQ4: Int): GainAxis =
    gainAxisForRange(minimumQ4, 0, DefaultAxisExtentQ4)

private fun gainAxisForRange(minimumQ4: Int, maximumQ4: Int, ceilingQ4: Int?): GainAxis {
    val defaultMinimumQ4 = -DefaultAxisExtentQ4
    val needsHeadroom = minimumQ4 < defaultMinimumQ4 + AxisHeadroomQ4
    val requiredMinimumQ4 = if (needsHeadroom) {
        (minimumQ4.toLong() - AxisHeadroomQ4)
            .coerceAtLeast(Int.MIN_VALUE.toLong())
    } else {
        defaultMinimumQ4.toLong()
    }
    val positiveHeadroom = if (maximumQ4 > DefaultAxisExtentQ4 - AxisHeadroomQ4) {
        (maximumQ4.toLong() + AxisHeadroomQ4).coerceAtMost(Int.MAX_VALUE.toLong())
    } else DefaultAxisExtentQ4.toLong()
    // Existing curves must remain visible even if the user subsequently lowers the edit ceiling.
    val requiredMaximumQ4 = maxOf(
        maximumQ4.toLong(),
        positiveHeadroom.coerceAtMost(ceilingQ4?.toLong() ?: Int.MAX_VALUE.toLong()),
    )
    val requiredRangeQ4 = requiredMaximumQ4 - requiredMinimumQ4
    val majorStepQ4 = niceMajorStepQ4(requiredRangeQ4)
    val alignedMinimumQ4 =
        (Math.floorDiv(requiredMinimumQ4, majorStepQ4.toLong()) * majorStepQ4.toLong())
            .coerceAtLeast(Int.MIN_VALUE.toLong())
            .toInt()
    val minorStepQ4 =
        (ceil(majorStepQ4.toDouble() / 10.0 / GainStepQ4) * GainStepQ4)
            .roundToInt()
            .coerceAtLeast(GainStepQ4)
    return GainAxis(
        maximumQ4 = (ceil(requiredMaximumQ4.toDouble() / majorStepQ4) * majorStepQ4)
            .toLong().coerceAtMost(Int.MAX_VALUE.toLong())
            .coerceAtMost(maxOf(maximumQ4.toLong(), ceilingQ4?.toLong() ?: Int.MAX_VALUE.toLong()))
            .toInt(),
        minimumQ4 = alignedMinimumQ4,
        majorStepQ4 = majorStepQ4,
        minorStepQ4 = minorStepQ4,
    )
}

private fun niceMajorStepQ4(rangeQ4: Long): Int {
    val rawStepDb = rangeQ4.toDouble() / EqCurve.Q4_PER_DB / TargetMajorIntervals
    val magnitude = 10.0.pow(floor(log10(rawStepDb)))
    val normalized = rawStepDb / magnitude
    val factor = when {
        normalized <= 1.0 -> 1.0
        normalized <= 2.0 -> 2.0
        normalized <= 2.5 -> 2.5
        normalized <= 5.0 -> 5.0
        else -> 10.0
    }
    return (factor * magnitude * EqCurve.Q4_PER_DB)
        .coerceIn(MinimumMajorStepQ4.toDouble(), Int.MAX_VALUE.toDouble())
        .roundToInt()
}

internal const val GainStepQ4 = EqCurve.Q4_PER_DB / 2
internal const val DefaultAxisExtentQ4 = 10 * EqCurve.Q4_PER_DB
private const val AxisHeadroomQ4 = EqCurve.Q4_PER_DB
private const val MinimumMajorStepQ4 = 5 * EqCurve.Q4_PER_DB
private const val TargetMajorIntervals = 7.0
