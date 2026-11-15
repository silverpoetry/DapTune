package com.weich.daptune.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EqCurveTest {
    @Test
    fun decimalConstructionRejectsOverflowInsteadOfSilentlyClipping() {
        assertThrows(IllegalArgumentException::class.java) {
            EqCurve.ofDb(List(DapBandPlan.bandCount) { 11.0 })
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
    fun boostAboveTenDecibelsIsRejected() {
        val gains = IntArray(DapBandPlan.bandCount)
        gains[0] = EqCurve.MAX_BOOST_Q4 + 1

        assertThrows(IllegalArgumentException::class.java) {
            EqCurve.ofQ4(gains)
        }
    }
}
