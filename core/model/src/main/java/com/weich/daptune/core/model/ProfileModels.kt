package com.weich.daptune.core.model

data class EqProfile(
    val id: String,
    val name: String,
    val curve: EqCurve,
    val isBuiltIn: Boolean,
    val source: ProfileSource,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

enum class ProfileSource {
    BUILT_IN,
    MANUAL,
    AUTO_EQ,
    GRAPHIC_EQ,
    PARAMETRIC_EQ,
    CSV,
    DAPTUNE_FILE,
}

/** A recommended result published by the upstream AutoEq project. */
data class AutoEqProfile(
    val name: String,
    /** Percent-encoded path relative to AutoEq's `results` directory. */
    val relativePath: String,
    val measurementSource: String,
    val form: AutoEqForm,
)

enum class AutoEqForm {
    IN_EAR,
    OVER_EAR,
    EARBUD,
    UNKNOWN,
}

data class DeviceBinding(
    val routeKey: String,
    val profileId: String,
)

enum class OutputRouteType {
    BUILT_IN_SPEAKER,
    WIRED_HEADSET,
    BLUETOOTH,
    BLE_AUDIO,
    USB,
    HDMI,
    REMOTE,
    UNKNOWN,
}

data class OutputRoute(
    val key: String,
    val displayName: String,
    val type: OutputRouteType,
    val rawAddressPresent: Boolean = false,
    val identityKind: OutputRouteIdentityKind = OutputRouteIdentityKind.PERSISTENT,
    /** Exact keys emitted by older identity algorithms and safe to migrate to [key]. */
    val legacyKeys: Set<String> = emptySet(),
) {
    companion object {
        val Speaker = OutputRoute(
            key = "builtin:speaker",
            displayName = "手机扬声器",
            type = OutputRouteType.BUILT_IN_SPEAKER,
        )

        fun typeFallback(type: OutputRouteType): String = "type:${type.name.lowercase()}"
    }
}

enum class OutputRouteIdentityKind {
    PERSISTENT,
    TRANSIENT,
}

data class KnownOutputDevice(
    val route: OutputRoute,
    val lastSeenAtEpochMillis: Long,
)

data class AppliedSnapshot(
    val routeKey: String,
    val profileId: String,
    val curveHash: Int,
    val appliedAtEpochMillis: Long,
    val verification: VerificationState,
)

enum class VerificationState {
    VERIFIED,
    WRITE_ACCEPTED,
    STALE,
    FAILED,
}

data class AppSettings(
    val gainLimit: GainLimit = GainLimit(),
    val automationEnabled: Boolean = false,
    val selectedProfileId: String = "builtin.flat",
    val defaultProfileId: String = "builtin.flat",
    val applyAtBoot: Boolean = false,
    val automaticUpdateChecksEnabled: Boolean = true,
    val lastUpdateCheckAtEpochMillis: Long = 0L,
    val darkThemeMode: DarkThemeMode = DarkThemeMode.SYSTEM,
)

enum class DarkThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}
