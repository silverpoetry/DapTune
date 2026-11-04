package com.weich.daptune.feature.automation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weich.daptune.core.model.AppliedSnapshot
import com.weich.daptune.core.model.AppSettings
import com.weich.daptune.core.model.DapApplyResult
import com.weich.daptune.core.model.DeviceBinding
import com.weich.daptune.core.model.EqProfile
import com.weich.daptune.core.model.KnownOutputDevice
import com.weich.daptune.core.model.OutputRoute
import com.weich.daptune.core.model.OperationLogAction
import com.weich.daptune.core.model.OperationLogEntry
import com.weich.daptune.core.model.OperationLogOutcome
import com.weich.daptune.core.model.ProfileApplySource
import com.weich.daptune.domain.AudioRouteMonitor
import com.weich.daptune.domain.ApplyProfileUseCase
import com.weich.daptune.domain.DeviceRepository
import com.weich.daptune.domain.ProfileRepository
import com.weich.daptune.domain.OperationLogRepository
import com.weich.daptune.domain.ResolveProfileForRouteUseCase
import com.weich.daptune.domain.SettingsRepository
import com.weich.daptune.domain.runSuspendCatching
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class AutomationUiState(
    val settings: AppSettings = AppSettings(),
    val profiles: List<EqProfile> = emptyList(),
    val devices: List<KnownOutputDevice> = emptyList(),
    val bindings: List<DeviceBinding> = emptyList(),
    val currentRoute: OutputRoute = OutputRoute.Speaker,
    val appliedSnapshot: AppliedSnapshot? = null,
    val operationLogs: List<OperationLogEntry> = emptyList(),
) {
    fun profileFor(routeKey: String): EqProfile? {
        val profileId = bindings.firstOrNull { it.routeKey == routeKey }?.profileId
        return profiles.firstOrNull { it.id == profileId }
    }

    val defaultProfile: EqProfile?
        get() = profiles.firstOrNull { it.id == settings.defaultProfileId }
}

private data class AutomationSources(
    val profiles: List<EqProfile>,
    val devices: List<KnownOutputDevice>,
    val bindings: List<DeviceBinding>,
    val settings: AppSettings,
    val route: OutputRoute,
    val snapshot: AppliedSnapshot?,
    val operationLogs: List<OperationLogEntry>,
)

private data class ProfileDeviceSources(
    val profiles: List<EqProfile>,
    val devices: List<KnownOutputDevice>,
    val bindings: List<DeviceBinding>,
    val operationLogs: List<OperationLogEntry>,
)

private data class RuntimeSources(
    val settings: AppSettings,
    val route: OutputRoute,
    val snapshot: AppliedSnapshot?,
)

@HiltViewModel
class AutomationViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val deviceRepository: DeviceRepository,
    private val settingsRepository: SettingsRepository,
    private val routeMonitor: AudioRouteMonitor,
    private val resolveProfile: ResolveProfileForRouteUseCase,
    private val applyProfile: ApplyProfileUseCase,
    private val controller: AutomationController,
    private val operationLogRepository: OperationLogRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AutomationUiState())
    val state: StateFlow<AutomationUiState> = mutableState
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            profileRepository.ensureBuiltIns()
            val profileDeviceSources = combine(
                profileRepository.profiles,
                deviceRepository.knownDevices,
                deviceRepository.bindings,
                operationLogRepository.entries,
            ) { profiles, devices, bindings, operationLogs ->
                ProfileDeviceSources(profiles, devices, bindings, operationLogs)
            }
            val runtimeSources = combine(
                settingsRepository.settings,
                routeMonitor.routes,
                deviceRepository.appliedSnapshot,
            ) { settings, route, snapshot ->
                RuntimeSources(settings, route, snapshot)
            }
            combine(profileDeviceSources, runtimeSources) { profileSources, runtime ->
                AutomationSources(
                    profiles = profileSources.profiles,
                    devices = profileSources.devices,
                    bindings = profileSources.bindings,
                    settings = runtime.settings,
                    route = runtime.route,
                    snapshot = runtime.snapshot,
                    operationLogs = profileSources.operationLogs,
                )
            }.collect { sources ->
                mutableState.emit(
                    AutomationUiState(
                        settings = sources.settings,
                        profiles = sources.profiles,
                        devices = sources.devices,
                        bindings = sources.bindings,
                        currentRoute = sources.route,
                        appliedSnapshot = sources.snapshot,
                        operationLogs = sources.operationLogs,
                    ),
                )
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runSuspendCatching {
                settingsRepository.setAutomationEnabled(enabled)
                if (enabled) controller.start() else controller.stop()
            }.onSuccess {
                appendOperationLog(
                    OperationLogEntry(
                        occurredAtEpochMillis = System.currentTimeMillis(),
                        action = if (enabled) {
                            OperationLogAction.AUTOMATION_ENABLED
                        } else {
                            OperationLogAction.AUTOMATION_DISABLED
                        },
                        outcome = OperationLogOutcome.SUCCESS,
                    ),
                )
            }.onFailure { error ->
                appendOperationLog(
                    OperationLogEntry(
                        occurredAtEpochMillis = System.currentTimeMillis(),
                        action = if (enabled) {
                            OperationLogAction.AUTOMATION_ENABLED
                        } else {
                            OperationLogAction.AUTOMATION_DISABLED
                        },
                        outcome = OperationLogOutcome.FAILURE,
                        detail = error.message ?: error.javaClass.simpleName,
                    ),
                )
                messageChannel.send(error.message ?: "无法更新自动切换")
            }
        }
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        if (!granted) {
            messageChannel.trySend("通知已关闭；自动切换仍会在系统的活动应用中运行")
        }
    }

    fun setApplyAtBoot(enabled: Boolean) {
        viewModelScope.launch {
            runSuspendCatching { settingsRepository.setApplyAtBoot(enabled) }
                .onSuccess {
                    appendOperationLog(
                        OperationLogEntry(
                            occurredAtEpochMillis = System.currentTimeMillis(),
                            action = if (enabled) {
                                OperationLogAction.START_AT_BOOT_ENABLED
                            } else {
                                OperationLogAction.START_AT_BOOT_DISABLED
                            },
                            outcome = OperationLogOutcome.SUCCESS,
                        ),
                    )
                }
                .onFailure { error ->
                    messageChannel.send(error.message ?: "无法更新重启恢复设置")
                }
        }
    }

    fun setDefaultProfile(profileId: String) {
        viewModelScope.launch {
            runSuspendCatching {
                settingsRepository.setDefaultProfile(profileId)
                applyResolvedProfileToCurrentRoute(ProfileApplySource.DEFAULT_RULE_CHANGE)
            }.onSuccess { application ->
                if (application == null) {
                    messageChannel.send("默认配置已更新")
                } else {
                    reportApplication(application, savedLabel = "默认配置已更新")
                }
            }.onFailure { error ->
                messageChannel.send(error.message ?: "无法更新默认配置")
            }
        }
    }

    fun bind(routeKey: String, profileId: String?) {
        viewModelScope.launch {
            runSuspendCatching {
                deviceRepository.bind(routeKey, profileId)
                val currentRoute = routeMonitor.currentRoute()
                if (routeKey == currentRoute.key) {
                    applyResolvedProfile(currentRoute, ProfileApplySource.DEVICE_RULE_CHANGE)
                } else {
                    val route = mutableState.value.devices
                        .firstOrNull { it.route.key == routeKey }
                        ?.route
                    val profile = profileId?.let { selectedId ->
                        mutableState.value.profiles.firstOrNull { it.id == selectedId }
                    }
                    appendOperationLog(
                        OperationLogEntry(
                            occurredAtEpochMillis = System.currentTimeMillis(),
                            action = OperationLogAction.DEVICE_RULE_CHANGED,
                            outcome = OperationLogOutcome.SUCCESS,
                            routeKey = routeKey,
                            routeName = route?.displayName,
                            profileId = profileId,
                            profileName = profile?.name,
                        ),
                    )
                    null
                }
            }.onSuccess { application ->
                val savedLabel = if (profileId == null) "已改为跟随默认配置" else "设备配置已更新"
                if (application == null) {
                    messageChannel.send(savedLabel)
                } else {
                    reportApplication(application, savedLabel)
                }
            }.onFailure { error ->
                messageChannel.send(error.message ?: "无法更新设备配置")
            }
        }
    }

    fun forget(routeKey: String, displayName: String) {
        viewModelScope.launch {
            val currentRoute = routeMonitor.currentRoute()
            if (routeKey == currentRoute.key) {
                messageChannel.send("当前设备无法从历史记录中删除")
                return@launch
            }
            runSuspendCatching { deviceRepository.forgetRoute(routeKey) }
                .onSuccess {
                    appendOperationLog(
                        OperationLogEntry(
                            occurredAtEpochMillis = System.currentTimeMillis(),
                            action = OperationLogAction.DEVICE_FORGOTTEN,
                            outcome = OperationLogOutcome.SUCCESS,
                            routeKey = routeKey,
                            routeName = displayName,
                        ),
                    )
                    messageChannel.send("已忘记“$displayName”")
                }
                .onFailure { error -> messageChannel.send(error.message ?: "无法删除设备") }
        }
    }

    fun clearOperationLogs() {
        viewModelScope.launch {
            runSuspendCatching { operationLogRepository.clear() }
                .onFailure { error -> messageChannel.send(error.message ?: "无法清空记录") }
        }
    }

    private suspend fun appendOperationLog(entry: OperationLogEntry) {
        runSuspendCatching { operationLogRepository.append(entry) }
    }

    private suspend fun applyResolvedProfileToCurrentRoute(
        source: ProfileApplySource,
    ): ProfileApplication? = applyResolvedProfile(routeMonitor.currentRoute(), source)

    private suspend fun applyResolvedProfile(
        route: OutputRoute,
        source: ProfileApplySource,
    ): ProfileApplication? {
        val profile = resolveProfile(route) ?: return null
        return ProfileApplication(profile, applyProfile(profile.id, route, source))
    }

    private suspend fun reportApplication(application: ProfileApplication, savedLabel: String) {
        when (val result = application.result) {
            is DapApplyResult.Success -> messageChannel.send("已应用“${application.profile.name}”")
            is DapApplyResult.Failure -> messageChannel.send("$savedLabel，但未能应用：${result.detail}")
        }
    }
}

private data class ProfileApplication(
    val profile: EqProfile,
    val result: DapApplyResult,
)
