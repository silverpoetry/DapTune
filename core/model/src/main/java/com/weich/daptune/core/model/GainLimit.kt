package com.weich.daptune.core.model

import kotlin.math.floor

/** User-selected application policy, independent of the vendor DSP's accepted range. */
data class GainLimit(
    val enabled: Boolean = true,
    val maximumQ4: Int = DefaultMaximumQ4,
) {
    init { require(maximumQ4 >= 0) { "增益上限不能为负数" } }

    val maximumDb: Double get() = maximumQ4.toDouble() / EqCurve.Q4_PER_DB
    val ceilingQ4: Int? get() = maximumQ4.takeIf { enabled }

    fun allows(curve: EqCurve): Boolean = !enabled || curve.toQ4List().all { it <= maximumQ4 }

    fun constrain(gainQ4: Int): Int = if (enabled) gainQ4.coerceAtMost(maximumQ4) else gainQ4

    companion object {
        const val DefaultMaximumQ4 = 10 * EqCurve.Q4_PER_DB

        fun fromDb(enabled: Boolean, maximumDb: Double): GainLimit {
            require(maximumDb.isFinite() && maximumDb >= 0.0) { "请输入非负的增益上限" }
            val q4 = floor(maximumDb * EqCurve.Q4_PER_DB)
            require(q4 <= Int.MAX_VALUE.toDouble()) { "超出可表示范围" }
            return GainLimit(enabled, q4.toInt())
        }
    }
}
