package com.weich.daptune.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EqCurveTest {
    @Test
    fun decimalConstructionRejectsOverflowInsteadOfSilentlyClipping() {
        assertThrows(IllegalArgumentException::class.java) {
            EqCurve.ofDb(List(DapBandPlan.bandCount) { 1e12 })
        }
        assertThrows(IllegalArgumentException::class.java) {
            EqCurve.ofDb(List(DapBandPlan.bandCount) { -1e12 })
        }
    }

    @Test
    fun deepAttenuationIsAcceptedWithoutClamping() {
        val gains = IntArray(DapBandPlan.bandCount) { -640 }

        val curve = EqCurve.ofQ4(gains)

        assertEquals(-40.0, curve.gainDb(0), 0.0)
    }

    @Test
    fun boostAboveTenDecibelsIsRepresentableAndPolicyIsSeparate() {
        val gains = IntArray(DapBandPlan.bandCount)
        gains[0] = 320
        val curve = EqCurve.ofQ4(gains)
        assertEquals(20.0, curve.gainDb(0), 0.0)
        assertEquals(false, GainLimit().allows(curve))
        assertEquals(true, GainLimit(enabled = false).allows(curve))
        assertEquals(true, GainLimit.fromDb(true, 24.0).allows(curve))
        assertEquals(1, GainLimit.fromDb(true, 0.1).maximumQ4)
    }
}
