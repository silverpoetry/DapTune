package com.weich.daptune.domain

import com.weich.daptune.core.eq.CurveFileCodec
import com.weich.daptune.core.eq.CurveImportFormat
import com.weich.daptune.core.eq.EqTransforms
import com.weich.daptune.core.eq.ImportedCurve
import com.weich.daptune.core.eq.OverflowMode
import com.weich.daptune.core.model.AppliedSnapshot
import com.weich.daptune.core.model.AutoEqProfile
import com.weich.daptune.core.model.DapApplyVerification
import com.weich.daptune.core.model.DapApplyResult
import com.weich.daptune.core.model.EqCurve
import com.weich.daptune.core.model.EqProfile
import com.weich.daptune.core.model.GainLimit
import com.weich.daptune.core.model.DapFailureReason
import com.weich.daptune.core.model.OutputRoute
import com.weich.daptune.core.model.OperationLogAction
import com.weich.daptune.core.model.OperationLogEntry
import com.weich.daptune.core.model.OperationLogOutcome
import com.weich.daptune.core.model.ProfileApplySource
import com.weich.daptune.core.model.ProfileSource
import com.weich.daptune.core.model.VerificationState
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class ApplyProfileUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val deviceRepository: DeviceRepository,
    private val dapGateway: DapGateway,
    private val operationLogs: OperationLogRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend operator fun invoke(
        profileId: String,
        route: OutputRoute,
        source: ProfileApplySource = ProfileApplySource.MANUAL_SELECTION,
    ): DapApplyResult {
        val profile = profileRepository.getProfile(profileId)
        if (profile == null) {
            val failure = DapApplyResult.Failure(
                reason = com.weich.daptune.core.model.DapFailureReason.INVALID_STATE,
                detail = "配置不存在",
            )
            operationLogs.appendSafely(
                operationEntry(
                    action = source.toLogAction(),
                    result = failure,
                    route = route,
                    profileId = profileId,
                ),
            )
            return failure
        }
        val result = applyWithGainLimit(profile.curve, settingsRepository, dapGateway)
        val appliedAt = System.currentTimeMillis()
        when (result) {
            is DapApplyResult.Success -> deviceRepository.updateAppliedSnapshot(
                AppliedSnapshot(
                    routeKey = route.key,
                    profileId = profile.id,
                    curveHash = profile.curve.stableHash(),
                    appliedAtEpochMillis = appliedAt,
                    verification = result.receipt.verification.toSnapshotVerification(),
                ),
            )
            is DapApplyResult.Failure -> deviceRepository.markAppliedStateStale()
        }
        operationLogs.appendSafely(
            operationEntry(
                action = source.toLogAction(),
                result = result,
                route = route,
                profileId = profile.id,
                profileName = profile.name,
                occurredAtEpochMillis = appliedAt,
            ),
        )
        return result
    }
}

class ApplyCurveUseCase @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val dapGateway: DapGateway,
    private val operationLogs: OperationLogRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend operator fun invoke(
        profileId: String,
        curve: EqCurve,
        route: OutputRoute,
    ): DapApplyResult {
        val result = applyWithGainLimit(curve, settingsRepository, dapGateway)
        val appliedAt = System.currentTimeMillis()
        when (result) {
            is DapApplyResult.Success -> deviceRepository.updateAppliedSnapshot(
                AppliedSnapshot(
                    routeKey = route.key,
                    profileId = profileId,
                    curveHash = curve.stableHash(),
                    appliedAtEpochMillis = appliedAt,
                    verification = result.receipt.verification.toSnapshotVerification(),
                ),
            )
            is DapApplyResult.Failure -> deviceRepository.markAppliedStateStale()
        }
        operationLogs.appendSafely(
            operationEntry(
                action = OperationLogAction.CURVE_APPLIED,
                result = result,
                route = route,
                profileId = profileId,
                occurredAtEpochMillis = appliedAt,
            ),
        )
        return result
    }
}

private suspend fun applyWithGainLimit(
    curve: EqCurve,
    settings: SettingsRepository,
    gateway: DapGateway,
): DapApplyResult {
    if (!settings.settings.first().gainLimit.allows(curve)) {
        return DapApplyResult.Failure(
            DapFailureReason.GAIN_LIMIT_EXCEEDED,
            "曲线超过设置中的增益上限，请平移曲线或修改增益限制",
        )
    }
    return gateway.applyCurve(curve)
}

internal fun DapApplyVerification.toSnapshotVerification(): VerificationState = when (this) {
    DapApplyVerification.CURVE_READBACK -> VerificationState.VERIFIED
    DapApplyVerification.WRITE_ACCEPTED -> VerificationState.WRITE_ACCEPTED
}

class ResolveProfileForRouteUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val deviceRepository: DeviceRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend operator fun invoke(route: OutputRoute): EqProfile? {
        val exact = deviceRepository.getBoundProfileId(route.key)
        val typeFallback = deviceRepository.getBoundProfileId(OutputRoute.typeFallback(route.type))
        val defaultId = settingsRepository.settings.first().defaultProfileId
        return profileRepository.getProfile(exact ?: typeFallback ?: defaultId)
            ?: profileRepository.getProfile("builtin.flat")
    }
}

/**
 * Selects a profile for editing, makes it the explicit rule for the output
 * route, and immediately applies it to the active Dolby path.
 *
 * The binding is written first so automation can never observe a new editor
 * selection together with the route's previous rule. Applying last preserves
 * the selected rule when the hardware is temporarily unavailable, allowing a
 * later route event to retry it.
 */
class SelectProfileForRouteUseCase @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val settingsRepository: SettingsRepository,
    private val applyProfile: ApplyProfileUseCase,
) {
    suspend operator fun invoke(profileId: String, route: OutputRoute): DapApplyResult {
        deviceRepository.rememberRoute(route)
        deviceRepository.bind(route.key, profileId)
        settingsRepository.setSelectedProfile(profileId)
        return applyProfile(
            profileId = profileId,
            route = route,
            source = ProfileApplySource.MANUAL_SELECTION,
        )
    }
}

private fun ProfileApplySource.toLogAction(): OperationLogAction = when (this) {
    ProfileApplySource.AUTOMATION_START -> OperationLogAction.AUTOMATION_STARTED
    ProfileApplySource.AUTOMATION_RECOVERY -> OperationLogAction.AUTOMATION_RECOVERED
    ProfileApplySource.ROUTE_CHANGE -> OperationLogAction.ROUTE_CHANGED
    ProfileApplySource.DOLBY_RESTORED -> OperationLogAction.DOLBY_RESTORED
    ProfileApplySource.AUTOMATION_REFRESH -> OperationLogAction.AUTOMATION_REFRESHED
    ProfileApplySource.MANUAL_SELECTION -> OperationLogAction.PROFILE_SELECTED
    ProfileApplySource.DEFAULT_RULE_CHANGE -> OperationLogAction.DEFAULT_RULE_CHANGED
    ProfileApplySource.DEVICE_RULE_CHANGE -> OperationLogAction.DEVICE_RULE_CHANGED
}

private suspend fun OperationLogRepository.appendSafely(entry: OperationLogEntry) {
    try {
        append(entry)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        // Operation logging is diagnostic and must not change the audio operation result.
    }
}

private fun operationEntry(
    action: OperationLogAction,
    result: DapApplyResult,
    route: OutputRoute,
    profileId: String,
    profileName: String? = null,
    occurredAtEpochMillis: Long = System.currentTimeMillis(),
): OperationLogEntry = when (result) {
    is DapApplyResult.Success -> OperationLogEntry(
        occurredAtEpochMillis = occurredAtEpochMillis,
        action = action,
        outcome = OperationLogOutcome.SUCCESS,
        routeKey = route.key,
        routeName = route.displayName,
        profileId = profileId,
        profileName = profileName,
        verification = result.receipt.verification.toSnapshotVerification(),
    )
    is DapApplyResult.Failure -> OperationLogEntry(
        occurredAtEpochMillis = occurredAtEpochMillis,
        action = action,
        outcome = OperationLogOutcome.FAILURE,
        routeKey = route.key,
        routeName = route.displayName,
        profileId = profileId,
        profileName = profileName,
        detail = result.detail,
    )
}

class SaveProfileUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
) {
    suspend operator fun invoke(
        existingId: String?,
        name: String,
        curve: EqCurve,
        source: ProfileSource,
    ): EqProfile {
        val cleanName = name.trim()
        val resolvedName = if (existingId == null) {
            ProfileNames.uniqueCopyName(
                preferredName = cleanName,
                existingNames = profileRepository.profiles.first().map(EqProfile::name),
            )
        } else {
            cleanName
        }
        return profileRepository.saveUserProfile(
            id = existingId,
            name = resolvedName,
            curve = curve,
            source = source,
        )
    }
}

class ImportCurveUseCase @Inject constructor() {
    fun parse(
        text: String,
        fileName: String,
        format: CurveImportFormat = CurveImportFormat.AUTOMATIC,
    ): ImportedCurve = CurveFileCodec.import(text, fileName, format)

    fun convert(imported: ImportedCurve, overflowMode: OverflowMode, gainLimit: GainLimit = GainLimit()): EqCurve =
        EqTransforms.quantize(imported.gainsDb, overflowMode, gainLimit).curve
}

data class AutoEqImportResult(
    val profile: EqProfile,
    val adjustedToBoostLimit: Boolean,
    val warnings: List<String>,
)

class ImportAutoEqProfileUseCase @Inject constructor(
    private val autoEqRepository: AutoEqRepository,
    private val importCurve: ImportCurveUseCase,
    private val saveProfile: SaveProfileUseCase,
) {
    suspend operator fun invoke(
        profile: AutoEqProfile,
        overflowMode: OverflowMode = OverflowMode.FIT,
        gainLimit: GainLimit = GainLimit(),
    ): AutoEqImportResult {
        val text = autoEqRepository.downloadGraphicEq(profile)
        val imported = importCurve.parse(
            text = text,
            fileName = "${profile.name} GraphicEQ.txt",
            format = CurveImportFormat.GRAPHIC_EQ,
        )
        val saved = saveProfile(
            existingId = null,
            name = profile.name,
            curve = importCurve.convert(imported, overflowMode, gainLimit),
            source = ProfileSource.AUTO_EQ,
        )
        return AutoEqImportResult(
            profile = saved,
            adjustedToBoostLimit = imported.exceedsLimit(gainLimit),
            warnings = imported.warnings,
        )
    }
}

class DuplicateProfileUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
) {
    suspend operator fun invoke(profile: EqProfile, newName: String): EqProfile {
        val candidate = ProfileNames.uniqueCopyName(
            preferredName = newName,
            existingNames = profileRepository.profiles.first().map(EqProfile::name),
        )
        return profileRepository.saveUserProfile(
            id = UUID.randomUUID().toString(),
            name = candidate,
            curve = profile.curve,
            source = ProfileSource.MANUAL,
        )
    }
}
