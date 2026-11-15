package com.weich.daptune.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.weich.daptune.core.model.AppSettings
import com.weich.daptune.core.model.GainLimit
import com.weich.daptune.domain.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.dapTuneDataStore by preferencesDataStore(name = "daptune_settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SettingsRepository {
    override val settings: Flow<AppSettings> = context.dapTuneDataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        }
        .map { preferences ->
            AppSettings(
                gainLimit = GainLimit(
                    enabled = preferences[GainLimitEnabled] ?: true,
                    maximumQ4 = preferences[GainLimitMaximumQ4]?.takeIf { it >= 0 }
                        ?: GainLimit.DefaultMaximumQ4,
                ),
                automationEnabled = preferences[AutomationEnabled] ?: false,
                selectedProfileId = preferences[SelectedProfile] ?: "builtin.flat",
                defaultProfileId = preferences[DefaultProfile] ?: "builtin.flat",
                applyAtBoot = preferences[ApplyAtBoot] ?: false,
                automaticUpdateChecksEnabled = preferences[AutomaticUpdateChecksEnabled] ?: true,
                lastUpdateCheckAtEpochMillis = preferences[LastUpdateCheckAt] ?: 0L,
            )
        }

    override suspend fun setGainLimit(limit: GainLimit) {
        context.dapTuneDataStore.edit {
            it[GainLimitEnabled] = limit.enabled
            it[GainLimitMaximumQ4] = limit.maximumQ4
        }
    }

    override suspend fun setSelectedProfile(profileId: String) {
        context.dapTuneDataStore.edit { it[SelectedProfile] = profileId }
    }

    override suspend fun setDefaultProfile(profileId: String) {
        context.dapTuneDataStore.edit { it[DefaultProfile] = profileId }
    }

    override suspend fun setAutomationEnabled(enabled: Boolean) {
        context.dapTuneDataStore.edit { it[AutomationEnabled] = enabled }
    }

    override suspend fun setApplyAtBoot(enabled: Boolean) {
        context.dapTuneDataStore.edit { it[ApplyAtBoot] = enabled }
    }

    override suspend fun setAutomaticUpdateChecksEnabled(enabled: Boolean) {
        context.dapTuneDataStore.edit { it[AutomaticUpdateChecksEnabled] = enabled }
    }

    override suspend fun setLastUpdateCheckAtEpochMillis(value: Long) {
        context.dapTuneDataStore.edit { it[LastUpdateCheckAt] = value }
    }

    private companion object {
        val GainLimitEnabled = booleanPreferencesKey("gain_limit_enabled")
        val GainLimitMaximumQ4 = intPreferencesKey("gain_limit_maximum_q4")
        val AutomationEnabled = booleanPreferencesKey("automation_enabled")
        val SelectedProfile = stringPreferencesKey("selected_profile_id")
        val DefaultProfile = stringPreferencesKey("default_profile_id")
        val ApplyAtBoot = booleanPreferencesKey("apply_at_boot")
        val AutomaticUpdateChecksEnabled = booleanPreferencesKey("automatic_update_checks_enabled")
        val LastUpdateCheckAt = longPreferencesKey("last_update_check_at")
    }
}
