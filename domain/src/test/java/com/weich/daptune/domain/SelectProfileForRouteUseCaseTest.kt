package com.weich.daptune.domain

import com.weich.daptune.core.model.AppliedSnapshot
import com.weich.daptune.core.model.AppSettings
import com.weich.daptune.core.model.DapApplyReceipt
import com.weich.daptune.core.model.DapApplyResult
import com.weich.daptune.core.model.DapApplyVerification
import com.weich.daptune.core.model.DapCapability
import com.weich.daptune.core.model.DeviceBinding
import com.weich.daptune.core.model.EqCurve
import com.weich.daptune.core.model.EqProfile
import com.weich.daptune.core.model.GainLimit
import com.weich.daptune.core.model.KnownOutputDevice
import com.weich.daptune.core.model.OutputRoute
import com.weich.daptune.core.model.OutputRouteType
import com.weich.daptune.core.model.OperationLogEntry
import com.weich.daptune.core.model.ProfileSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectProfileForRouteUseCaseTest {
    @Test
    fun applicationUsesCurrentGainPolicyWithoutChangingStoredCurve() = runBlocking {
        val calls = mutableListOf<String>()
        val devices = RecordingDeviceRepository(calls)
        val settings = RecordingSettingsRepository(calls)
        val profile = profile("high").copy(curve = EqCurve.ofDb(List(20) { 20.0 }))
        val apply = ApplyProfileUseCase(
            ProfileRepositoryStub(profile), devices, RecordingDapGateway(calls), RecordingOperationLogRepository(), settings,
        )
        assertEquals(
            com.weich.daptune.core.model.DapFailureReason.GAIN_LIMIT_EXCEEDED,
            (apply(profile.id, OutputRoute.Speaker) as DapApplyResult.Failure).reason,
        )
        org.junit.Assert.assertFalse(calls.any { it.startsWith("apply:") })
        settings.setGainLimit(GainLimit(enabled = false))
        org.junit.Assert.assertTrue(apply(profile.id, OutputRoute.Speaker) is DapApplyResult.Success)
        assertEquals(20.0, profile.curve.gainDb(0), 0.0)
    }

    @Test
    fun selectionUpdatesCurrentRouteBindingBeforeEditorSelection() = runBlocking {
        val calls = mutableListOf<String>()
        val deviceRepository = RecordingDeviceRepository(calls)
        val settingsRepository = RecordingSettingsRepository(calls)
        val profile = profile("custom.reference")
        val profileRepository = ProfileRepositoryStub(profile)
        val dapGateway = RecordingDapGateway(calls)
        val route = OutputRoute(
            key = "bluetooth:headphones",
            displayName = "Headphones",
            type = OutputRouteType.BLUETOOTH,
        )

        val result = SelectProfileForRouteUseCase(
            deviceRepository,
            settingsRepository,
            ApplyProfileUseCase(
                profileRepository,
                deviceRepository,
                dapGateway,
                RecordingOperationLogRepository(),
                settingsRepository,
            ),
        )(
            profileId = "custom.reference",
            route = route,
        )

        require(result is DapApplyResult.Success)
        assertEquals(route, deviceRepository.rememberedRoute)
        assertEquals(route.key to "custom.reference", deviceRepository.binding)
        assertEquals("custom.reference", settingsRepository.selectedProfileId)
        assertEquals(
            listOf(
                "remember:${route.key}",
                "bind:${route.key}:custom.reference",
                "select:custom.reference",
                "apply:${profile.curve.stableHash()}",
                "snapshot:${route.key}:custom.reference",
            ),
            calls,
        )
    }

    private fun profile(id: String) = EqProfile(
        id = id,
        name = id,
        curve = EqCurve.flat(),
        isBuiltIn = false,
        source = ProfileSource.MANUAL,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
    )

    private class ProfileRepositoryStub(
        private val profile: EqProfile,
    ) : ProfileRepository {
        override val profiles: Flow<List<EqProfile>> = MutableStateFlow(listOf(profile))

        override suspend fun ensureBuiltIns() = Unit

        override suspend fun getProfile(id: String): EqProfile? = profile.takeIf { it.id == id }

        override suspend fun saveUserProfile(
            id: String?,
            name: String,
            curve: EqCurve,
            source: ProfileSource,
        ): EqProfile = error("Not used")

        override suspend fun deleteProfile(id: String) = Unit
    }

    private class RecordingDapGateway(
        private val calls: MutableList<String>,
    ) : DapGateway {
        override suspend fun inspect(): DapCapability = error("Not used")

        override suspend fun readAllProfileCurves(): Result<List<EqCurve>> = error("Not used")

        override suspend fun applyCurve(curve: EqCurve): DapApplyResult {
            calls += "apply:${curve.stableHash()}"
            return DapApplyResult.Success(
                DapApplyReceipt(
                    profileCount = 1,
                    currentProfile = 0,
                    verification = DapApplyVerification.CURVE_READBACK,
                    curveHash = curve.stableHash(),
                ),
            )
        }
    }

    private class RecordingDeviceRepository(
        private val calls: MutableList<String>,
    ) : DeviceRepository {
        override val knownDevices: Flow<List<KnownOutputDevice>> = MutableStateFlow(emptyList())
        override val bindings: Flow<List<DeviceBinding>> = MutableStateFlow(emptyList())
        override val appliedSnapshot: Flow<AppliedSnapshot?> = MutableStateFlow(null)

        var rememberedRoute: OutputRoute? = null
        var binding: Pair<String, String?>? = null

        override suspend fun rememberRoute(route: OutputRoute) {
            rememberedRoute = route
            calls += "remember:${route.key}"
        }

        override suspend fun forgetRoute(routeKey: String) = Unit

        override suspend fun bind(routeKey: String, profileId: String?) {
            binding = routeKey to profileId
            calls += "bind:$routeKey:$profileId"
        }

        override suspend fun getBoundProfileId(routeKey: String): String? = null

        override suspend fun updateAppliedSnapshot(snapshot: AppliedSnapshot) {
            calls += "snapshot:${snapshot.routeKey}:${snapshot.profileId}"
        }

        override suspend fun markAppliedStateStale() = Unit
    }

    private class RecordingSettingsRepository(
        private val calls: MutableList<String>,
    ) : SettingsRepository {
        private val mutableSettings = MutableStateFlow(AppSettings())
        override val settings: Flow<AppSettings> = mutableSettings

        var selectedProfileId: String? = null

        override suspend fun setGainLimit(limit: GainLimit) {
            mutableSettings.value = mutableSettings.value.copy(gainLimit = limit)
        }

        override suspend fun setSelectedProfile(profileId: String) {
            selectedProfileId = profileId
            mutableSettings.value = mutableSettings.value.copy(selectedProfileId = profileId)
            calls += "select:$profileId"
        }

        override suspend fun setDefaultProfile(profileId: String) {
            mutableSettings.value = mutableSettings.value.copy(defaultProfileId = profileId)
        }

        override suspend fun setAutomationEnabled(enabled: Boolean) {
            mutableSettings.value = mutableSettings.value.copy(automationEnabled = enabled)
        }

        override suspend fun setApplyAtBoot(enabled: Boolean) {
            mutableSettings.value = mutableSettings.value.copy(applyAtBoot = enabled)
        }

        override suspend fun setAutomaticUpdateChecksEnabled(enabled: Boolean) {
            mutableSettings.value = mutableSettings.value.copy(
                automaticUpdateChecksEnabled = enabled,
            )
        }

        override suspend fun setLastUpdateCheckAtEpochMillis(value: Long) {
            mutableSettings.value = mutableSettings.value.copy(
                lastUpdateCheckAtEpochMillis = value,
            )
        }
    }

    private class RecordingOperationLogRepository : OperationLogRepository {
        override val entries: Flow<List<OperationLogEntry>> = MutableStateFlow(emptyList())

        override suspend fun append(entry: OperationLogEntry) = Unit

        override suspend fun clear() = Unit
    }
}
