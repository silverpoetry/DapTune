package com.weich.daptune.core.eq

import com.weich.daptune.core.model.DapBandPlan
import com.weich.daptune.core.model.EqCurve
import kotlin.math.floor
import kotlin.math.roundToLong

enum class OverflowMode {
    /** Translate the entire curve down when its positive peak exceeds +10 dB. */
    FIT,

    /** Clamp only values above +10 dB; attenuation remains unchanged. */
    CLAMP,
}

data class CurveConversion(
    val curve: EqCurve,
    val adjusted: Boolean,
    val sourceMinimumDb: Double,
    val sourceMaximumDb: Double,
)

object EqTransforms {
    fun eliminatePositiveGain(curve: EqCurve): EqCurve =
        shiftQ4(curve, -maxOf(0L, curve.toQ4List().max().toLong()))

    fun peakToZero(curve: EqCurve): EqCurve = shift(curve, peakToZeroOffsetDb(curve))

    fun meanToZero(curve: EqCurve): EqCurve = shift(curve, meanToZeroOffsetDb(curve))

    fun peakToZeroOffsetDb(curve: EqCurve): Double = -curve.toDbList().max()

    /** Round the common offset once, so every pairwise Q4 difference remains exact. */
    fun meanToZeroOffsetDb(curve: EqCurve): Double =
        (-curve.toQ4List().average()).roundToLong().toDouble() / EqCurve.Q4_PER_DB

    fun shiftBoundsQ4(curve: EqCurve): LongRange =
        (Int.MIN_VALUE.toLong() - curve.toQ4List().min())..
            (EqCurve.MAX_BOOST_Q4.toLong() - curve.toQ4List().max())

    fun quantizedOffsetDb(offsetDb: Double): Double {
        require(offsetDb.isFinite()) { "请输入有效的平移量" }
        return (offsetDb * EqCurve.Q4_PER_DB).roundToLong().toDouble() / EqCurve.Q4_PER_DB
    }

    fun shift(curve: EqCurve, offsetDb: Double): EqCurve {
        require(offsetDb.isFinite()) { "请输入有效的平移量" }
        return shiftQ4(curve, (offsetDb * EqCurve.Q4_PER_DB).roundToLong())
    }

    private fun shiftQ4(curve: EqCurve, offsetQ4: Long): EqCurve {
        val bounds = shiftBoundsQ4(curve)
        require(offsetQ4 <= bounds.last) { "平移后峰值超过 +${EqCurve.MAX_BOOST_DB} dB，未修改曲线" }
        require(offsetQ4 >= bounds.first) { "平移后增益超出可表示范围，未修改曲线" }
        return EqCurve.ofQ4(curve.toQ4List().map { (it.toLong() + offsetQ4).toInt() })
    }

    fun scale(curve: EqCurve, factor: Double): EqCurve {
        require(factor.isFinite() && factor >= 0.0)
        return EqCurve.ofDb(curve.toDbList().map { it * factor })
    }

    /**
     * Hard-limits every band above [thresholdDb] without changing lower bands.
     *
     * The product has no artificial attenuation floor, so negative thresholds are valid.
     * Dolby's +10 dB boost ceiling remains the only user-facing upper bound.
     */
    fun limitMaximum(curve: EqCurve, thresholdDb: Double): EqCurve {
        require(thresholdDb.isFinite()) { "Threshold must be finite" }
        require(thresholdDb <= EqCurve.MAX_BOOST_DB) {
            "Threshold exceeds +${EqCurve.MAX_BOOST_DB} dB"
        }
        // Round toward attenuation so the represented Q4 value never exceeds the requested cap.
        val thresholdQ4 = floor(thresholdDb * EqCurve.Q4_PER_DB)
        require(thresholdQ4 >= Int.MIN_VALUE.toDouble()) { "阈值超出可表示范围" }
        return EqCurve.ofQ4(curve.toQ4List().map { it.coerceAtMost(thresholdQ4.toInt()) })
    }

    fun smooth(curve: EqCurve, passes: Int = 1): EqCurve {
        require(passes in 1..8)
        var values = curve.toDbList().toDoubleArray()
        repeat(passes) {
            val next = values.copyOf()
            for (index in values.indices) {
                val left = values[maxOf(0, index - 1)]
                val center = values[index]
                val right = values[minOf(values.lastIndex, index + 1)]
                next[index] = (left + center * 2.0 + right) / 4.0
            }
            values = next
        }
        return EqCurve.ofDb(values.asList())
    }

    fun quantize(gainsDb: List<Double>, overflowMode: OverflowMode): CurveConversion {
        require(gainsDb.size == DapBandPlan.bandCount) {
            "Expected ${DapBandPlan.bandCount} gains, got ${gainsDb.size}"
        }
        require(gainsDb.all(Double::isFinite)) { "Curve contains a non-finite gain" }

        val minimum = gainsDb.min()
        val maximum = gainsDb.max()
        val limit = EqCurve.MAX_BOOST_DB.toDouble()
        val exceedsLimit = maximum > limit
        val adjusted = if (!exceedsLimit) {
            gainsDb
        } else {
            when (overflowMode) {
                OverflowMode.CLAMP -> gainsDb.map { it.coerceAtMost(limit) }
                OverflowMode.FIT -> gainsDb.map { (it - maximum) + limit }
            }
        }

        return CurveConversion(
            curve = EqCurve.ofDb(adjusted),
            adjusted = exceedsLimit,
            sourceMinimumDb = minimum,
            sourceMaximumDb = maximum,
        )
    }
}
