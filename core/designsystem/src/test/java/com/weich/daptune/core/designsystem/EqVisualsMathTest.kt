package com.weich.daptune.core.designsystem

import com.weich.daptune.core.model.EqCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EqVisualsMathTest {
    @Test
    fun `axis and dragging expand above ten without hidden clipping`() {
        val curve = EqCurve.ofQ4(List(20) { 320 })
        val axis = gainAxisFor(curve)
        org.junit.Assert.assertTrue(axis.maximumQ4 > 320)
        assertEquals(axis.maximumQ4, gainAt(-1000f, axis))
        assertEquals(320, gainAxisFor(curve, ceilingQ4 = 320).maximumQ4)
        org.junit.Assert.assertTrue(gainAxisFor(curve, ceilingQ4 = 160).maximumQ4 >= 320)
        assertEquals(0, gainAxisFor(EqCurve.flat(), ceilingQ4 = 0).maximumQ4)
    }

    @Test
    fun `track endpoints and center map to exact gains`() {
        assertEquals(DefaultAxisExtentQ4, gainAt(y = 12f, axis = DefaultAxis))
        assertEquals(0, gainAt(y = 150f, axis = DefaultAxis))
        assertEquals(-DefaultAxisExtentQ4, gainAt(y = 288f, axis = DefaultAxis))
    }

    @Test
    fun `track position is always clamped and snapped to half decibel`() {
        assertEquals(DefaultAxisExtentQ4, gainAt(y = -1_000f, axis = ExpandedAxis))
        assertEquals(-320, gainAt(y = 1_000f, axis = ExpandedAxis))
        assertEquals(0, gainAt(y = 153f, axis = ExpandedAxis) % (EqCurve.Q4_PER_DB / 2))
    }

    @Test
    fun `gain to track position is the inverse across an expanded axis`() {
        for (gainQ4 in ExpandedAxis.minimumQ4..ExpandedAxis.maximumQ4 step EqCurve.Q4_PER_DB / 2) {
            val y = trackYForGainQ4(
                gainQ4 = gainQ4,
                trackHeightPx = TrackHeight,
                verticalInsetPx = TrackInset,
                axis = ExpandedAxis,
            )
            assertEquals(gainQ4, gainAt(y, ExpandedAxis))
        }
    }

    @Test
    fun `axis defaults to plus or minus ten and expands downward`() {
        assertEquals(DefaultAxisExtentQ4, DefaultAxis.maximumQ4)
        assertEquals(-DefaultAxisExtentQ4, DefaultAxis.minimumQ4)
        assertEquals(-320, ExpandedAxis.minimumQ4)
        assertEquals(
            listOf(160, 80, 0, -80, -160, -240, -320),
            ExpandedAxis.majorTicksQ4(),
        )
    }

    @Test
    fun `curve point hit selects its exact band`() {
        val band = 12
        val x = HorizontalInset +
            (ChartWidth - HorizontalInset * 2f) * band / 19f
        assertEquals(
            band,
            nearestCurveBandAt(
                curve = EqCurve.flat(),
                tapX = x,
                tapY = ChartHeight / 2f,
                widthPx = ChartWidth,
                heightPx = ChartHeight,
                horizontalInsetPx = HorizontalInset,
                verticalInsetPx = VerticalInset,
                hitRadiusPx = HitRadius,
            ),
        )
    }

    @Test
    fun `curve tap away from every point does not select`() {
        assertNull(
            nearestCurveBandAt(
                curve = EqCurve.flat(),
                tapX = ChartWidth / 2f,
                tapY = 0f,
                widthPx = ChartWidth,
                heightPx = ChartHeight,
                horizontalInsetPx = HorizontalInset,
                verticalInsetPx = VerticalInset,
                hitRadiusPx = HitRadius,
            ),
        )
    }

    @Test
    fun `curve hit testing supports axis label insets`() {
        val band = 19
        val endInset = 8f
        val bottomInset = 24f
        val x = ChartWidth - endInset
        val y = VerticalInset + (ChartHeight - VerticalInset - bottomInset) / 2f
        assertEquals(
            band,
            nearestCurveBandAt(
                curve = EqCurve.flat(),
                tapX = x,
                tapY = y,
                widthPx = ChartWidth,
                heightPx = ChartHeight,
                horizontalInsetPx = 30f,
                verticalInsetPx = VerticalInset,
                hitRadiusPx = HitRadius,
                horizontalEndInsetPx = endInset,
                verticalEndInsetPx = bottomInset,
            ),
        )
    }

    private fun gainAt(y: Float, axis: GainAxis): Int = gainQ4ForTrackPosition(
        yPx = y,
        trackHeightPx = TrackHeight,
        verticalInsetPx = TrackInset,
        axis = axis,
    )

    private companion object {
        val DefaultAxis = gainAxisForMinimum(0)
        val ExpandedAxis = gainAxisForMinimum(-281)
        const val TrackHeight = 300f
        const val TrackInset = 12f
        const val ChartWidth = 360f
        const val ChartHeight = 212f
        const val HorizontalInset = 4f
        const val VerticalInset = 8f
        const val HitRadius = 24f
    }
}
