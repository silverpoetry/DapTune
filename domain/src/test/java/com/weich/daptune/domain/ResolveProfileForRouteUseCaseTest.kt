package com.weich.daptune.domain

import com.weich.daptune.core.model.AppliedSnapshot
import com.weich.daptune.core.model.AppSettings
import com.weich.daptune.core.model.DeviceBinding
import com.weich.daptune.core.model.EqCurve
import com.weich.daptune.core.model.EqProfile
import com.weich.daptune.core.model.KnownOutputDevice
import com.weich.daptune.core.model.OutputRoute
import com.weich.daptune.core.model.OutputRouteType
import com.weich.daptune.core.model.ProfileSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ResolveProfileForRouteUseCaseTest {
    private val route = OutputRoute(
        key = "device:bluetooth:headphones",
        displayName = "Headphones",
        type = OutputRouteType.BLUETOOTH,
    )

    @Test
    fun exactDeviceBindingWins() = runBlocking {
        val profiles = profileRepository("builtin.flat", "custom.default", "custom.type", "custom.exact")
        val devices = DeviceRepositoryStub(
            mapOf(
                route.key to "custom.exact",
                OutputRoute.typeFallback(route.type) to "custom.type",
            ),
        )

        val result = ResolveProfileForRouteUseCase(
            profiles,
            devices,
            SettingsRepositoryStub(defaultProfileId = "custom.default"),
        )(route)

        assertEquals("custom.exact", result?.id)
    }

    @Test
    fun routeTypeBindingWinsWhenDeviceHasNoBinding() = runBlocking {
        val profiles = profileRepository("builtin.flat", "custom.default", "custom.type")
        val devices = DeviceRepositoryStub(
            mapOf(OutputRoute.typeFallback(route.type) to "custom.type"),
        )

        val result = ResolveProfileForRouteUseCase(
            profiles,
            devices,
            SettingsRepositoryStub(defaultProfileId = "custom.default"),
        )(route)

        assertEquals("custom.type", result?.id)
    }

    @Test
    fun globalDefaultIsUsedWhenRouteHasNoRule() = runBlocking {
        val profiles = profileRepository("builtin.flat", "custom.default")

        val result = ResolveProfileForRouteUseCase(
            profiles,
            DeviceRepositoryStub(),
            SettingsRepositoryStub(defaultProfileId = "custom.default"),
        )(route)

        assertEquals("custom.default", result?.id)
    }

    @Test
    fun flatProfileIsUsedWhenConfiguredProfileNoLongerExists() = runBlocking {
        val profiles = profileRepository("builtin.flat")

        val result = ResolveProfileForRouteUseCase(
            profiles,
            DeviceRepositoryStub(),
            SettingsRepositoryStub(defaultProfileId = "missing.profile"),
        )(route)

        assertEquals("builtin.flat", result?.id)
    }

    private fun profileRepository(vararg ids: String): ProfileRepository =
        ProfileRepositoryStub(ids.map(::profile))

    private fun profile(id: String) = EqProfile(
        id = id,
        name = id,
        curve = EqCurve.flat(),
        isBuiltIn = id.startsWith("builtin."),
        source = if (id.startsWith("builtin.")) ProfileSource.BUILT_IN else ProfileSource.MANUAL,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
    )

    private class ProfileRepositoryStub(
        profiles: List<EqProfile>,
    ) : ProfileRepository {
        private val values = profiles.associateBy(EqProfile::id)
        override val profiles: Flow<List<EqProfile>> = MutableStateFlow(profiles)

        override suspend fun ensureBuiltIns() = Unit

        override suspend fun getProfile(id: String): EqProfile? = values[id]

        override suspend fun saveUserProfile(
            id: String?,
            name: String,
            curve: EqCurve,
            source: ProfileSource,
        ): EqProfile = error("Not used")

        override suspend fun deleteProfile(id: String) = Unit
    }

    private class DeviceRepositoryStub(
        private val profileIdsByRoute: Map<String, String> = emptyMap(),
    ) : DeviceRepository {
        override val knownDevices: Flow<List<KnownOutputDevice>> = MutableStateFlow(emptyList())
        override val bindings: Flow<List<DeviceBinding>> = MutableStateFlow(emptyList())
        override val appliedSnapshot: Flow<AppliedSnapshot?> = MutableStateFlow(null)

        override suspend fun rememberRoute(route: OutputRoute) = Unit

        override suspend fun forgetRoute(routeKey: String) = Unit

        override suspend fun bind(routeKey: String, profileId: String?) = Unit

        override suspend fun getBoundProfileId(routeKey: String): String? = profileIdsByRoute[routeKey]

        override suspend fun updateAppliedSnapshot(snapshot: AppliedSnapshot) = Unit

        override suspend fun markAppliedStateStale() = Unit
    }

    private class SettingsRepositoryStub(
        defaultProfileId: String,
    ) : SettingsRepository {
        override val settings: Flow<AppSettings> = MutableStateFlow(
            AppSettings(defaultProfileId = defaultProfileId),
        )

        override suspend fun setGainLimit(limit: com.weich.daptune.core.model.GainLimit) = Unit

        override suspend fun setSelectedProfile(profileId: String) = Unit

        override suspend fun setDefaultProfile(profileId: String) = Unit

        override suspend fun setAutomationEnabled(enabled: Boolean) = Unit

        override suspend fun setApplyAtBoot(enabled: Boolean) = Unit

        override suspend fun setAutomaticUpdateChecksEnabled(enabled: Boolean) = Unit

        override suspend fun setLastUpdateCheckAtEpochMillis(value: Long) = Unit
    }
}
