package com.weich.daptune.feature.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weich.daptune.core.eq.CurveImportFormat
import com.weich.daptune.core.eq.OverflowMode
import com.weich.daptune.core.model.AutoEqProfile
import com.weich.daptune.core.model.DapApplyResult
import com.weich.daptune.core.model.EqProfile
import com.weich.daptune.core.model.OutputRoute
import com.weich.daptune.domain.AudioRouteMonitor
import com.weich.daptune.domain.AutoEqRepository
import com.weich.daptune.domain.DeviceRepository
import com.weich.daptune.domain.DuplicateProfileUseCase
import com.weich.daptune.domain.ImportCurveUseCase
import com.weich.daptune.domain.ImportAutoEqProfileUseCase
import com.weich.daptune.domain.ProfileRepository
import com.weich.daptune.domain.ResolveProfileForRouteUseCase
import com.weich.daptune.domain.SaveProfileUseCase
import com.weich.daptune.domain.SelectProfileForRouteUseCase
import com.weich.daptune.domain.SettingsRepository
import com.weich.daptune.domain.runSuspendCatching
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfilesUiState(
    val profiles: List<EqProfile> = emptyList(),
    val selectedProfileId: String = "builtin.flat",
    val currentRoute: OutputRoute = OutputRoute.Speaker,
) {
    val builtIns: List<EqProfile> get() = profiles.filter(EqProfile::isBuiltIn)
    val userProfiles: List<EqProfile> get() = profiles.filterNot(EqProfile::isBuiltIn)
}

data class AutoEqSearchUiState(
    val query: String = "",
    val results: List<AutoEqProfile> = emptyList(),
    val loading: Boolean = false,
    val importingPath: String? = null,
    val lastImportedPath: String? = null,
    val errorMessage: String? = null,
)

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val duplicateProfile: DuplicateProfileUseCase,
    private val importCurve: ImportCurveUseCase,
    private val importAutoEqProfile: ImportAutoEqProfileUseCase,
    private val autoEqRepository: AutoEqRepository,
    private val saveProfile: SaveProfileUseCase,
    private val deviceRepository: DeviceRepository,
    private val routeMonitor: AudioRouteMonitor,
    private val resolveProfileForRoute: ResolveProfileForRouteUseCase,
    private val selectProfileForRoute: SelectProfileForRouteUseCase,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ProfilesUiState())
    val state: StateFlow<ProfilesUiState> = mutableState
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()
    private val mutableAutoEqState = MutableStateFlow(AutoEqSearchUiState())
    val autoEqState: StateFlow<AutoEqSearchUiState> = mutableAutoEqState
    private var autoEqSearchJob: Job? = null

    init {
        viewModelScope.launch {
            profileRepository.ensureBuiltIns()
            combine(
                profileRepository.profiles,
                settingsRepository.settings,
                deviceRepository.bindings,
                routeMonitor.routes,
            ) { profiles, settings, _, route ->
                val selectedProfileId = resolveProfileForRoute(route)?.id
                    ?: settings.selectedProfileId
                ProfilesUiState(profiles, selectedProfileId, route)
            }.collect(mutableState::emit)
        }
    }

    fun select(profile: EqProfile) {
        val route = mutableState.value.currentRoute
        viewModelScope.launch {
            runSuspendCatching { selectProfileForRoute(profile.id, route) }
                .onSuccess { result ->
                    messageChannel.send(result.selectionMessage(profile.name))
                }
                .onFailure { messageChannel.send(it.message ?: "无法更新设备配置") }
        }
    }

    fun duplicate(profile: EqProfile) {
        val route = mutableState.value.currentRoute
        viewModelScope.launch {
            runSuspendCatching { duplicateProfile(profile, profile.name) }
                .onSuccess { copy ->
                    selectProfileForRoute(copy.id, route)
                    messageChannel.send("已创建“${copy.name}”")
                }
                .onFailure { messageChannel.send(it.message ?: "复制失败") }
        }
    }

    fun importText(
        text: String,
        fileName: String,
        format: CurveImportFormat = CurveImportFormat.AUTOMATIC,
        overflowMode: OverflowMode = OverflowMode.FIT,
    ) {
        val route = mutableState.value.currentRoute
        viewModelScope.launch {
            runSuspendCatching {
                val imported = importCurve.parse(text, fileName, format)
                val gainLimit = settingsRepository.settings.first().gainLimit
                val curve = importCurve.convert(imported, overflowMode, gainLimit)
                val saved = saveProfile(
                    existingId = null,
                    name = imported.suggestedName,
                    curve = curve,
                    source = imported.source,
                )
                Triple(saved, imported.exceedsLimit(gainLimit), imported.warnings)
            }.onSuccess { (saved, adjusted, warnings) ->
                selectProfileForRoute(saved.id, route)
                val adjustment = if (!adjusted) "" else when (overflowMode) {
                    OverflowMode.FIT -> "；已按增益上限整体下移"
                    OverflowMode.CLAMP -> "；已按增益上限截平"
                }
                val detail = warnings.joinToString(separator = "；", prefix = if (warnings.isEmpty()) "" else "；")
                messageChannel.send("已导入“${saved.name}”$adjustment$detail")
            }.onFailure { error ->
                messageChannel.send(error.message ?: "无法导入文件")
            }
        }
    }

    fun updateAutoEqQuery(query: String) {
        autoEqSearchJob?.cancel()
        val boundedQuery = query.take(MAXIMUM_AUTO_EQ_QUERY_LENGTH)
        val cleanQuery = boundedQuery.trim()
        mutableAutoEqState.update {
            it.copy(
                query = boundedQuery,
                results = emptyList(),
                loading = cleanQuery.length >= MINIMUM_AUTO_EQ_QUERY_LENGTH,
                lastImportedPath = null,
                errorMessage = null,
            )
        }
        if (cleanQuery.length < MINIMUM_AUTO_EQ_QUERY_LENGTH) return
        autoEqSearchJob = viewModelScope.launch {
            delay(AUTO_EQ_SEARCH_DEBOUNCE_MILLIS)
            searchAutoEq(cleanQuery)
        }
    }

    fun retryAutoEqSearch() {
        val cleanQuery = mutableAutoEqState.value.query.trim()
        if (cleanQuery.length < MINIMUM_AUTO_EQ_QUERY_LENGTH) return
        autoEqSearchJob?.cancel()
        autoEqSearchJob = viewModelScope.launch { searchAutoEq(cleanQuery) }
    }

    fun importAutoEq(profile: AutoEqProfile) {
        if (mutableAutoEqState.value.importingPath != null) return
        viewModelScope.launch {
            mutableAutoEqState.update {
                it.copy(importingPath = profile.relativePath, errorMessage = null)
            }
            try {
                val imported = importAutoEqProfile(profile, gainLimit = settingsRepository.settings.first().gainLimit)
                val route = mutableState.value.currentRoute
                val applyResult = selectProfileForRoute(imported.profile.id, route)
                mutableAutoEqState.update {
                    it.copy(lastImportedPath = profile.relativePath)
                }
                val adjustment = if (imported.adjustedToBoostLimit) {
                    "；已按增益上限整体下移"
                } else {
                    ""
                }
                val warnings = imported.warnings.joinToString(
                    separator = "；",
                    prefix = if (imported.warnings.isEmpty()) "" else "；",
                )
                val applyDetail = when (applyResult) {
                    is DapApplyResult.Success -> ""
                    is DapApplyResult.Failure -> "；已保存，但未能应用：${applyResult.detail}"
                }
                messageChannel.send(
                    "已从 AutoEq 导入“${imported.profile.name}”$adjustment$warnings$applyDetail",
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                messageChannel.send(error.message ?: "无法导入 AutoEq 配置")
            } finally {
                mutableAutoEqState.update { it.copy(importingPath = null) }
            }
        }
    }

    private suspend fun searchAutoEq(query: String) {
        mutableAutoEqState.update { it.copy(loading = true, errorMessage = null) }
        try {
            val results = autoEqRepository.search(query)
            if (mutableAutoEqState.value.query.trim() == query) {
                mutableAutoEqState.update { it.copy(results = results, loading = false) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (mutableAutoEqState.value.query.trim() == query) {
                mutableAutoEqState.update {
                    it.copy(
                        results = emptyList(),
                        loading = false,
                        errorMessage = error.message ?: "无法搜索 AutoEq",
                    )
                }
            }
        }
    }

    fun delete(profile: EqProfile) {
        if (profile.isBuiltIn) return
        val route = mutableState.value.currentRoute
        val wasSelected = mutableState.value.selectedProfileId == profile.id
        viewModelScope.launch {
            runSuspendCatching { profileRepository.deleteProfile(profile.id) }
                .onSuccess {
                    if (wasSelected) {
                        selectProfileForRoute("builtin.flat", route)
                    }
                    messageChannel.send("已删除“${profile.name}”")
                }
                .onFailure { messageChannel.send(it.message ?: "删除失败") }
        }
    }
}

private const val MINIMUM_AUTO_EQ_QUERY_LENGTH = 2
private const val MAXIMUM_AUTO_EQ_QUERY_LENGTH = 120
private const val AUTO_EQ_SEARCH_DEBOUNCE_MILLIS = 300L

private fun DapApplyResult.selectionMessage(profileName: String): String = when (this) {
    is DapApplyResult.Success -> "已切换并应用“$profileName”"
    is DapApplyResult.Failure -> "已切换到“$profileName”，但未能应用：$detail"
}
