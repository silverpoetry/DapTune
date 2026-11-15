package com.weich.daptune.core.model

data class DapCapability(
    val descriptorFound: Boolean,
    val hasControl: Boolean,
    val effectEnabled: Boolean,
    val dapEnabled: Boolean,
    val profileCount: Int,
    val currentProfile: Int,
    val curveReadbackSupport: DapCurveReadbackSupport,
    val detail: String? = null,
) {
    val isReady: Boolean
        get() = descriptorFound && hasControl && effectEnabled && dapEnabled && profileCount > 0
}

enum class DapCurveReadbackSupport {
    SUPPORTED,
    UNAVAILABLE,
}

data class DapApplyReceipt(
    val profileCount: Int,
    val currentProfile: Int,
    val verification: DapApplyVerification,
    val curveHash: Int,
)

enum class DapApplyVerification {
    CURVE_READBACK,
    WRITE_ACCEPTED,
}

sealed interface DapApplyResult {
    data class Success(val receipt: DapApplyReceipt) : DapApplyResult

    data class Failure(
        val reason: DapFailureReason,
        val detail: String,
        val cause: Throwable? = null,
    ) : DapApplyResult
}

enum class DapFailureReason {
    GAIN_LIMIT_EXCEEDED,
    UNSUPPORTED,
    NO_CONTROL,
    DOLBY_DISABLED,
    INVALID_STATE,
    WRITE_FAILED,
    VERIFICATION_FAILED,
    AUDIO_SERVICE_DIED,
    UNKNOWN,
}
