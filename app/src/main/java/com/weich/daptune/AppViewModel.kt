package com.weich.daptune

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weich.daptune.core.model.AppRelease
import com.weich.daptune.domain.CheckForUpdateUseCase
import com.weich.daptune.domain.AudioRouteMonitor
import com.weich.daptune.domain.DeviceRepository
import com.weich.daptune.domain.ProfileRepository
import com.weich.daptune.domain.SettingsRepository
import com.weich.daptune.domain.runSuspendCatching
import com.weich.daptune.feature.automation.AutomationController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppUiState(
    val automaticUpdateChecksEnabled: Boolean = true,
    val lastUpdateCheckAtEpochMillis: Long = 0L,
    val updateCheckInProgress: Boolean = false,
    val updateCheckCompleted: Boolean = false,
    val latestRelease: AppRelease? = null,
    val updateAvailable: Boolean = false,
    val updateCheckError: String? = null,
    val pendingUpdateAnnouncement: AppRelease? = null,
)

@HiltViewModel
class AppViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val automationController: AutomationController,
    private val checkForUpdate: CheckForUpdateUseCase,
    private val audioRouteMonitor: AudioRouteMonitor,
    private val deviceRepository: DeviceRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    private var updateCheckJob: Job? = null

    init {
        viewModelScope.launch { profileRepository.ensureBuiltIns() }
        viewModelScope.launch {
            // History is an effect of route events, never of database/UI state emissions.
            // The foreground service owns recording when no Activity exists.
            audioRouteMonitor.routes.distinctUntilChanged().collect { route ->
                runSuspendCatching { deviceRepository.rememberRoute(route) }
                    .onFailure { Log.w("DapTune", "Unable to record output device", it) }
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                mutableState.update {
                    it.copy(
                        automaticUpdateChecksEnabled =
                            settings.automaticUpdateChecksEnabled,
                        lastUpdateCheckAtEpochMillis =
                            settings.lastUpdateCheckAtEpochMillis,
                    )
                }
            }
        }
    }

    fun restoreAutomation() {
        viewModelScope.launch {
            if (settingsRepository.settings.first().automationEnabled) automationController.start()
        }
    }

    fun onBluetoothPermissionGranted() {
        audioRouteMonitor.refresh()
    }

    fun checkForUpdatesAutomatically(currentVersionName: String) {
        startUpdateCheck(currentVersionName, automatic = true)
    }

    fun checkForUpdatesNow(currentVersionName: String) {
        startUpdateCheck(currentVersionName, automatic = false)
    }

    fun setAutomaticUpdateChecksEnabled(enabled: Boolean, currentVersionName: String) {
        viewModelScope.launch {
            settingsRepository.setAutomaticUpdateChecksEnabled(enabled)
            if (enabled) checkForUpdatesAutomatically(currentVersionName)
        }
    }

    fun consumeUpdateAnnouncement(tagName: String) {
        mutableState.update { current ->
            if (current.pendingUpdateAnnouncement?.tagName == tagName) {
                current.copy(pendingUpdateAnnouncement = null)
            } else {
                current
            }
        }
    }

    private fun startUpdateCheck(currentVersionName: String, automatic: Boolean) {
        if (updateCheckJob?.isActive == true) return
        updateCheckJob = viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val now = System.currentTimeMillis()
            if (automatic && !shouldAutomaticallyCheckForUpdates(
                    enabled = settings.automaticUpdateChecksEnabled,
                    lastCheckAtEpochMillis = settings.lastUpdateCheckAtEpochMillis,
                    nowEpochMillis = now,
                )
            ) {
                return@launch
            }

            mutableState.update {
                it.copy(
                    updateCheckInProgress = true,
                    updateCheckCompleted = false,
                    updateCheckError = null,
                )
            }
            try {
                settingsRepository.setLastUpdateCheckAtEpochMillis(now)
                mutableState.update { it.copy(lastUpdateCheckAtEpochMillis = now) }
                val result = checkForUpdate(currentVersionName)
                mutableState.update {
                    it.copy(
                        latestRelease = result.latestRelease,
                        updateAvailable = result.updateAvailable,
                        updateCheckCompleted = true,
                        updateCheckError = null,
                        pendingUpdateAnnouncement = if (automatic && result.updateAvailable) {
                            result.latestRelease
                        } else {
                            it.pendingUpdateAnnouncement
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        updateCheckCompleted = true,
                        updateCheckError = error.message?.takeIf(String::isNotBlank)
                            ?: "检查更新失败",
                    )
                }
            } finally {
                mutableState.update { it.copy(updateCheckInProgress = false) }
            }
        }
    }
}

internal const val AutomaticUpdateCheckIntervalMillis = 24L * 60L * 60L * 1_000L

internal fun shouldAutomaticallyCheckForUpdates(
    enabled: Boolean,
    lastCheckAtEpochMillis: Long,
    nowEpochMillis: Long,
): Boolean = enabled && (
    lastCheckAtEpochMillis <= 0L ||
        nowEpochMillis < lastCheckAtEpochMillis ||
        nowEpochMillis - lastCheckAtEpochMillis >= AutomaticUpdateCheckIntervalMillis
    )
