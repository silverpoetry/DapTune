package com.weich.daptune.domain

import com.weich.daptune.core.model.AutoEqForm
import com.weich.daptune.core.model.AutoEqProfile
import com.weich.daptune.core.model.EqCurve
import com.weich.daptune.core.model.EqProfile
import com.weich.daptune.core.model.ProfileSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportAutoEqProfileUseCaseTest {
    @Test
    fun downloadsConvertsAndSavesAsAutoEqProfile() = runBlocking {
        val profiles = ProfileRepositoryStub()
        val upstream = AutoEqRepositoryStub(
            "GraphicEQ: 20 0.0; 20000 -12.0\n",
        )
        val useCase = ImportAutoEqProfileUseCase(
            autoEqRepository = upstream,
            importCurve = ImportCurveUseCase(),
            saveProfile = SaveProfileUseCase(profiles),
        )

        val result = useCase(upstream.profile)

        assertEquals("Sennheiser HD 600", result.profile.name)
        assertEquals(ProfileSource.AUTO_EQ, result.profile.source)
        assertEquals(upstream.profile, upstream.downloaded)
        assertTrue(result.profile.curve.toDbList().max() <= 0.0)
        assertTrue(result.profile.curve.toDbList().min() < 0.0)
    }

    @Test
    fun appliesTheStandardPositiveBoostLimit() = runBlocking {
        val profiles = ProfileRepositoryStub()
        val upstream = AutoEqRepositoryStub(
            "GraphicEQ: 20 20.0; 20000 -20.0\n",
        )
        val useCase = ImportAutoEqProfileUseCase(
            autoEqRepository = upstream,
            importCurve = ImportCurveUseCase(),
            saveProfile = SaveProfileUseCase(profiles),
        )

        val result = useCase(upstream.profile)

        assertTrue(result.adjustedToBoostLimit)
        assertEquals(10.0, result.profile.curve.toDbList().max(), 0.0)
    }

    private class AutoEqRepositoryStub(
        private val graphicEq: String,
    ) : AutoEqRepository {
        val profile = AutoEqProfile(
            name = "Sennheiser HD 600",
            relativePath = "./oratory1990/over-ear/Sennheiser%20HD%20600",
            measurementSource = "oratory1990",
            form = AutoEqForm.OVER_EAR,
        )
        var downloaded: AutoEqProfile? = null

        override suspend fun search(query: String, limit: Int): List<AutoEqProfile> = listOf(profile)

        override suspend fun downloadGraphicEq(profile: AutoEqProfile): String {
            downloaded = profile
            return graphicEq
        }
    }

    private class ProfileRepositoryStub : ProfileRepository {
        private val mutableProfiles = MutableStateFlow<List<EqProfile>>(emptyList())
        override val profiles: Flow<List<EqProfile>> = mutableProfiles

        override suspend fun ensureBuiltIns() = Unit

        override suspend fun getProfile(id: String): EqProfile? =
            mutableProfiles.value.firstOrNull { it.id == id }

        override suspend fun saveUserProfile(
            id: String?,
            name: String,
            curve: EqCurve,
            source: ProfileSource,
        ): EqProfile = EqProfile(
            id = id ?: "imported",
            name = name,
            curve = curve,
            isBuiltIn = false,
            source = source,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        ).also { mutableProfiles.value = mutableProfiles.value + it }

        override suspend fun deleteProfile(id: String) = Unit
    }
}
