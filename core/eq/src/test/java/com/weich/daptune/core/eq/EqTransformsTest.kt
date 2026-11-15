package com.weich.daptune.core.eq

import com.weich.daptune.core.model.DapBandPlan
import com.weich.daptune.core.model.EqCurve
import com.weich.daptune.core.model.GainLimit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EqTransformsTest {
    @Test
    fun peakToZero_translatesWholeCurveWithoutClippingAttenuation() {
        val source = EqCurve.ofQ4(
            intArrayOf(
                160, 160, 160, 130, 49, -34, -50, -7, 35, 40,
                -23, -103, -121, -112, -67, 14, 64, 64, 64, 64,
            ),
        )

        val result = EqTransforms.peakToZero(source)

        assertEquals(0, result.toQ4List().max())
        assertEquals(-281, result.toQ4List().min())
        assertArrayEquals(
            intArrayOf(
                0, 0, 0, -30, -111, -194, -210, -167, -125, -120,
                -183, -263, -281, -272, -227, -146, -96, -96, -96, -96,
            ),
            result.toQ4Array(),
        )
    }

    @Test
    fun meanToZero_usesOneCommonOffsetAndHasAtMostHalfAQ4RoundingError() {
        val source = EqCurve.ofQ4(List(DapBandPlan.bandCount) { index -> (index - 7) * 5 })

        val result = EqTransforms.meanToZero(source)

        assertTrue(kotlin.math.abs(result.toQ4List().sum()) <= DapBandPlan.bandCount / 2)
        assertEquals(1, (0 until DapBandPlan.bandCount).map { result[it] - source[it] }.distinct().size)
    }

    @Test
    fun fit_translatesAllBandsWhenPositiveBoostExceedsLimit() {
        val source = List(DapBandPlan.bandCount) { index -> -20.0 + index * 2.0 }

        val conversion = EqTransforms.quantize(source, OverflowMode.FIT)

        assertTrue(conversion.adjusted)
        assertEquals(-448, conversion.curve.toQ4List().min())
        assertEquals(GainLimit.DefaultMaximumQ4, conversion.curve.toQ4List().max())
        source.indices.forEach { index ->
            assertEquals(-8.0, conversion.curve.gainDb(index) - source[index], 0.0)
        }
    }

    @Test
    fun upwardShiftRejectsOverflowInsteadOfScalingOrClipping() {
        val source = EqCurve.ofDb(List(DapBandPlan.bandCount) { if (it == 0) 9.0 else -20.0 })
        assertThrows(IllegalArgumentException::class.java) { EqTransforms.shift(source, 2.0) }
        assertThrows(IllegalArgumentException::class.java) { EqTransforms.meanToZero(source) }
        assertEquals(9.0, source.gainDb(0), 0.0)
        assertEquals(-20.0, source.gainDb(1), 0.0)
    }

    @Test
    fun fractionalShiftPreservesEveryBandDifferenceAndIsReversible() {
        val source = EqCurve.ofQ4(List(DapBandPlan.bandCount) { (it - 10) * 17 })
        val shifted = EqTransforms.shift(source, -0.1)
        source.toQ4List().indices.forEach { assertEquals(-2, shifted[it] - source[it]) }
        assertEquals(source, EqTransforms.shift(shifted, 0.125))
    }

    @Test
    fun peakToZeroCanRaiseAnEntireNegativeCurve() {
        val source = EqCurve.ofDb(List(DapBandPlan.bandCount) { if (it == 0) -5.0 else -40.0 })
        val shifted = EqTransforms.peakToZero(source)
        assertEquals(0.0, shifted.gainDb(0), 0.0)
        assertEquals(-35.0, shifted.gainDb(1), 0.0)
    }

    @Test
    fun technicalIntegerBoundaryRejectsLossInsteadOfSaturatingIndividualBands() {
        val source = EqCurve.ofQ4(List(DapBandPlan.bandCount) { if (it == 0) 160 else Int.MIN_VALUE })
        assertThrows(IllegalArgumentException::class.java) { EqTransforms.peakToZero(source) }
        assertThrows(IllegalArgumentException::class.java) { EqTransforms.shift(source, -0.0625) }
        val negative = EqCurve.ofQ4(List(DapBandPlan.bandCount) { Int.MIN_VALUE })
        assertEquals(EqCurve.flat(), EqTransforms.peakToZero(negative))
    }

    @Test
    fun scaleAndQuantizeRejectUnrepresentableResults() {
        val source = EqCurve.ofDb(List(DapBandPlan.bandCount) { 6.0 })
        assertEquals(12.0, EqTransforms.scale(source, 2.0).gainDb(0), 0.0)
        assertThrows(IllegalArgumentException::class.java) {
            EqTransforms.quantize(List(DapBandPlan.bandCount) { -1e12 }, OverflowMode.FIT)
        }
        assertThrows(IllegalArgumentException::class.java) { EqTransforms.shift(source, Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { EqTransforms.shift(source, Double.MAX_VALUE) }
    }

    @Test
    fun negativeAttenuationIsNeverAdjustedOrClamped() {
        val source = List(DapBandPlan.bandCount) { index -> if (index == 0) -30.0 else 9.0 }

        val fit = EqTransforms.quantize(source, OverflowMode.FIT)
        val clamp = EqTransforms.quantize(source, OverflowMode.CLAMP)

        assertFalse(fit.adjusted)
        assertEquals(-30.0, fit.curve.gainDb(0), 0.0)
        assertEquals(-30.0, clamp.curve.gainDb(0), 0.0)
        assertEquals(9.0, fit.curve.gainDb(1), 0.0)
    }

    @Test
    fun clampLimitsPositiveBoostAndPreservesDeepCut() {
        val source = List(DapBandPlan.bandCount) { index -> if (index == 0) -30.0 else 15.0 }

        val conversion = EqTransforms.quantize(source, OverflowMode.CLAMP)

        assertTrue(conversion.adjusted)
        assertEquals(-30.0, conversion.curve.gainDb(0), 0.0)
        assertEquals(10.0, conversion.curve.gainDb(1), 0.0)
    }

    @Test
    fun limitMaximum_flattensOnlyValuesAboveThreshold() {
        val source = EqCurve.ofQ4(
            List(DapBandPlan.bandCount) { index ->
                when (index) {
                    0 -> -320
                    1 -> -8
                    2 -> 16
                    else -> 80
                }
            },
        )

        val result = EqTransforms.limitMaximum(source, 1.0)

        assertEquals(-320, result[0])
        assertEquals(-8, result[1])
        assertEquals(16, result[2])
        assertTrue(result.toQ4List().drop(2).all { it == 16 })
    }

    @Test
    fun limitMaximum_acceptsNegativeThresholdWithoutAddingAttenuationFloor() {
        val source = EqCurve.ofQ4(List(DapBandPlan.bandCount) { index -> (index - 10) * 16 })

        val result = EqTransforms.limitMaximum(source, -3.5)

        assertEquals(-160, result[0])
        assertTrue(result.toQ4List().drop(7).all { it == -56 })
    }

    @Test
    fun limitMaximum_q4ValueNeverExceedsRequestedThreshold() {
        val result = EqTransforms.limitMaximum(
            EqCurve.ofQ4(List(DapBandPlan.bandCount) { 16 }),
            0.1,
        )

        assertEquals(1, result.toQ4List().max())
        assertTrue(result.toDbList().max() <= 0.1)
    }

    @Test
    fun customAndDisabledCeilingsAllowGenuineUpwardTranslation() {
        val source = EqCurve.ofDb(List(DapBandPlan.bandCount) { if (it == 0) 10.0 else -20.0 })
        val raised = EqTransforms.shift(source, 10.0, GainLimit.fromDb(true, 20.0))
        assertEquals(20.0, raised.gainDb(0), 0.0)
        assertEquals(-10.0, raised.gainDb(1), 0.0)
        val normalized = EqTransforms.meanToZero(source, GainLimit(enabled = false))
        assertEquals(0.0, normalized.toDbList().average(), 0.0)
        assertEquals(30.0, normalized.gainDb(0) - normalized.gainDb(1), 0.0)
        val unlimited = EqTransforms.quantize(raised.toDbList(), OverflowMode.FIT, GainLimit(enabled = false))
        assertFalse(unlimited.adjusted)
        assertEquals(raised, unlimited.curve)
        assertEquals(15.0, EqTransforms.limitMaximum(raised, 15.0).gainDb(0), 0.0)
    }
}
