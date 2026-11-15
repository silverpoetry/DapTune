package com.weich.daptune.core.model

import kotlin.math.roundToLong

/**
 * Immutable 20-band curve using Dolby's signed Q4 dB representation.
 *
 * Only the signed Int transport range is enforced here. Application gain limits are a separate,
 * user-configurable policy; actual DSP support is determined by the vendor implementation.
 */
class EqCurve private constructor(
    private val gainsQ4: IntArray,
) {
    init {
        require(gainsQ4.size == DapBandPlan.bandCount) {
            "Expected ${DapBandPlan.bandCount} gains, got ${gainsQ4.size}"
        }
    }

    operator fun get(index: Int): Int = gainsQ4[index]

    fun gainDb(index: Int): Double = gainsQ4[index].toDouble() / Q4_PER_DB

    fun toQ4Array(): IntArray = gainsQ4.copyOf()

    fun toQ4List(): List<Int> = gainsQ4.asList()

    fun toDbList(): List<Double> = gainsQ4.map { it.toDouble() / Q4_PER_DB }

    fun withGainQ4(index: Int, gainQ4: Int): EqCurve {
        require(index in 0 until DapBandPlan.bandCount)
        if (gainsQ4[index] == gainQ4) return this
        return ofQ4(gainsQ4.copyOf().also { it[index] = gainQ4 })
    }

    fun stableHash(): Int = gainsQ4.contentHashCode()

    override fun equals(other: Any?): Boolean =
        this === other || other is EqCurve && gainsQ4.contentEquals(other.gainsQ4)

    override fun hashCode(): Int = gainsQ4.contentHashCode()

    override fun toString(): String = "EqCurve(${gainsQ4.joinToString()})"

    companion object {
        const val Q4_PER_DB = 16

        fun flat(): EqCurve = EqCurve(IntArray(DapBandPlan.bandCount))

        fun ofQ4(gains: IntArray): EqCurve = EqCurve(gains.copyOf())

        fun ofQ4(gains: List<Int>): EqCurve = EqCurve(gains.toIntArray())

        fun ofDb(gainsDb: List<Double>): EqCurve {
            require(gainsDb.size == DapBandPlan.bandCount)
            require(gainsDb.all(Double::isFinite)) { "Curve contains a non-finite gain" }
            return EqCurve(
                IntArray(gainsDb.size) { index ->
                    val q4 = (gainsDb[index] * Q4_PER_DB).roundToLong()
                    require(q4 in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                        "Band $index is outside the Q4 gain range"
                    }
                    q4.toInt()
                },
            )
        }
    }
}

object DapBandPlan {
    val frequenciesHz: IntArray = intArrayOf(
        47,
        141,
        234,
        328,
        469,
        656,
        844,
        1031,
        1313,
        1688,
        2250,
        3000,
        3750,
        4688,
        5813,
        7125,
        9000,
        11250,
        13875,
        19688,
    )

    const val id: String = "dolby-dap-20-v1"
    const val bandCount: Int = 20
}
