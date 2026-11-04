package com.weich.daptune.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weich.daptune.core.eq.EqTransforms
import com.weich.daptune.core.model.AppliedSnapshot
import com.weich.daptune.core.model.DapApplyResult
import com.weich.daptune.core.model.DapApplyVerification
import com.weich.daptune.core.model.DapCapability
import com.weich.daptune.core.model.EqCurve
import com.weich.daptune.core.model.EqProfile
import com.weich.daptune.core.model.OutputRoute
import com.weich.daptune.core.model.ProfileSource
import com.weich.daptune.domain.ApplyCurveUseCase
import com.weich.daptune.domain.AudioRouteMonitor
import com.weich.daptune.domain.DapGateway
import com.weich.daptune.domain.DeviceRepository
import com.weich.daptune.domain.ProfileRepository
import com.weich.daptune.domain.ResolveProfileForRouteUseCase
import com.weich.daptune.domain.SaveProfileUseCase
import com.weich.daptune.domain.SelectProfileForRouteUseCase
import com.weich.daptune.domain.SettingsRepository
import com.weich.daptune.domain.runSuspendCatching
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditorUiState(
    val profiles: List<EqProfile> = emptyList(),
    val selectedProfileId: String = "builtin.flat",
    val loadedProfileId: String? = null,
    val curve: EqCurve = EqCurve.flat(),
    val originalCurve: EqCurve = EqCurve.flat(),
    val selectedBand: Int = 0,
    val route: OutputRoute = OutputRoute.Speaker,
    val capability: DapCapability? = null,
    val appliedSnapshot: AppliedSnapshot? = null,
    val isApplying: Boolean = false,
    val isInspecting: Boolean = false,
) {
    val selectedProfile: EqProfile?
        get() = profiles.firstOrNull { it.id == selectedProfileId }

    val isDirty: Boolean
        get() = curve != originalCurve

    val canApply: Boolean
        get() = capability?.isReady == true && !isApplying
}

enum class CurveAction {
    PEAK_TO_ZERO,
    MEAN_TO_ZERO,
    SMOOTH,
    FLATTEN,
}

sealed interface EditorEvent {
    data class Message(val text: String) : EditorEvent
    data class SuggestSaveName(val name: String) : EditorEvent
}

private data class EditorSources(
    val profiles: List<EqProfile>,
    val resolvedProfile: EqProfile?,
    val storedSelectedProfileId: String,
    val route: OutputRoute,
    val snapshot: AppliedSnapshot?,
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val deviceRepository: DeviceRepository,
    private val routeMonitor: AudioRouteMonitor,
    private val dapGateway: DapGateway,
    private val applyCurve: ApplyCurveUseCase,
    private val resolveProfileForRoute: ResolveProfileForRouteUseCase,
    private val saveProfile: SaveProfileUseCase,
    private val selectProfileForRoute: SelectProfileForRouteUseCase,
) : ViewModel() {
    private val mutableState = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = mutableState

    private val eventChannel = Channel<EditorEvent>(capacity = Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            profileRepository.ensureBuiltIns()
            combine(
                profileRepository.profiles,
                settingsRepository.settings,
                deviceRepository.bindings,
                routeMonitor.routes,
                deviceRepository.appliedSnapshot,
            ) { profiles, settings, _, route, snapshot ->
                EditorSources(
                    profiles = profiles,
                    resolvedProfile = resolveProfileForRoute(route),
                    storedSelectedProfileId = settings.selectedProfileId,
                    route = route,
                    snapshot = snapshot,
                )
            }.collect { sources ->
                updateSources(sources)
                sources.resolvedProfile
                    ?.id
                    ?.takeIf { it != sources.storedSelectedProfileId }
                    ?.let { settingsRepository.setSelectedProfile(it) }
            }
        }
        refreshCapability()
    }

    fun selectProfile(profileId: String) {
        val profile = mutableState.value.profiles.firstOrNull { it.id == profileId } ?: return
        val route = mutableState.value.route
        mutableState.update {
            it.copy(
                selectedProfileId = profile.id,
                loadedProfileId = profile.id,
                curve = profile.curve,
                originalCurve = profile.curve,
            )
        }
        viewModelScope.launch {
            runSuspendCatching { selectProfileForRoute(profile.id, route) }
                .onSuccess { result ->
                    eventChannel.send(EditorEvent.Message(result.selectionMessage(profile.name)))
                }
                .onFailure { eventChannel.send(EditorEvent.Message(it.message ?: "无法更新设备配置")) }
        }
    }

    fun selectBand(index: Int) {
        mutableState.update { it.copy(selectedBand = index.coerceIn(0, 19)) }
    }

    fun setGain(index: Int, valueQ4: Int) {
        mutableState.update {
            it.copy(
                curve = it.curve.withGainQ4(index, valueQ4.coerceAtMost(EqCurve.MAX_BOOST_Q4)),
                selectedBand = index,
            )
        }
    }

    fun resetChanges() {
        mutableState.update {
            it.copy(curve = it.originalCurve)
        }
    }

    fun transform(action: CurveAction) {
        mutableState.update { current ->
            val transformed = when (action) {
                CurveAction.PEAK_TO_ZERO -> EqTransforms.peakToZero(current.curve)
                CurveAction.MEAN_TO_ZERO -> EqTransforms.meanToZero(current.curve)
                CurveAction.SMOOTH -> EqTransforms.smooth(current.curve)
                CurveAction.FLATTEN -> EqCurve.flat()
            }
            current.copy(curve = transformed)
        }
    }

    fun limitMaximum(thresholdDb: Double) {
        mutableState.update { current ->
            current.copy(curve = EqTransforms.limitMaximum(current.curve, thresholdDb))
        }
    }

    fun save(name: String, overwrite: Boolean) {
        val current = mutableState.value
        viewModelScope.launch {
            runSuspendCatching {
                saveProfile(
                    existingId = if (overwrite) current.selectedProfile?.takeUnless(EqProfile::isBuiltIn)?.id else null,
                    name = name,
                    curve = current.curve,
                    source = current.selectedProfile?.source?.takeIf { overwrite }
                        ?: ProfileSource.MANUAL,
                )
            }.onSuccess { saved ->
                selectProfileForRoute(saved.id, current.route)
                mutableState.update {
                    it.copy(
                        selectedProfileId = saved.id,
                        loadedProfileId = saved.id,
                        curve = saved.curve,
                        originalCurve = saved.curve,
                    )
                }
                eventChannel.send(EditorEvent.Message("已保存“${saved.name}”"))
            }.onFailure { error ->
                eventChannel.send(EditorEvent.Message(error.message ?: "保存失败"))
            }
        }
    }

    fun requestSave() {
        val current = mutableState.value
        val profile = current.selectedProfile
        eventChannel.trySend(
            EditorEvent.SuggestSaveName(
                profile?.name ?: "新配置",
            ),
        )
    }

    fun apply() {
        val current = mutableState.value
        if (current.isApplying) return
        mutableState.update { it.copy(isApplying = true) }
        viewModelScope.launch {
            val result = applyCurve(
                profileId = current.selectedProfileId,
                curve = current.curve,
                route = current.route,
            )
            mutableState.update { it.copy(isApplying = false) }
            when (result) {
                is DapApplyResult.Success -> eventChannel.send(EditorEvent.Message(result.successMessage()))
                is DapApplyResult.Failure -> eventChannel.send(EditorEvent.Message(result.detail))
            }
            refreshCapability()
        }
    }

    fun refreshCapability() {
        if (mutableState.value.isInspecting) return
        mutableState.update { it.copy(isInspecting = true) }
        viewModelScope.launch {
            val capability = dapGateway.inspect()
            mutableState.update { it.copy(capability = capability, isInspecting = false) }
        }
    }

    private fun updateSources(sources: EditorSources) {
        mutableState.update { current ->
            val selected = sources.resolvedProfile
                ?: sources.profiles.firstOrNull { it.id == "builtin.flat" }
                ?: sources.profiles.firstOrNull()
            val selectionChanged = selected != null && current.loadedProfileId != selected.id
            if (selectionChanged) {
                current.copy(
                    profiles = sources.profiles,
                    selectedProfileId = selected.id,
                    loadedProfileId = selected.id,
                    curve = selected.curve,
                    originalCurve = selected.curve,
                    route = sources.route,
                    appliedSnapshot = sources.snapshot,
                )
            } else {
                current.copy(
                    profiles = sources.profiles,
                    selectedProfileId = selected?.id ?: current.selectedProfileId,
                    route = sources.route,
                    appliedSnapshot = sources.snapshot,
                )
            }
        }
    }
}

private fun DapApplyResult.Success.successMessage(): String = when (receipt.verification) {
    DapApplyVerification.CURVE_READBACK ->
        "已写入并验证 ${receipt.profileCount} 个 Dolby 配置"
    DapApplyVerification.WRITE_ACCEPTED ->
        "已写入 ${receipt.profileCount} 个 Dolby 配置 · 此设备不支持曲线回读"
}

private fun DapApplyResult.selectionMessage(profileName: String): String = when (this) {
    is DapApplyResult.Success -> "已切换并应用“$profileName”"
    is DapApplyResult.Failure -> "已切换到“$profileName”，但未能应用：$detail"
}
