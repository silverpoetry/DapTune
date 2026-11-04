package com.weich.daptune.feature.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.weich.daptune.core.model.DapApplyResult
import com.weich.daptune.core.model.EqProfile
import com.weich.daptune.core.model.OperationLogAction
import com.weich.daptune.core.model.OperationLogEntry
import com.weich.daptune.core.model.OperationLogOutcome
import com.weich.daptune.core.model.OutputRoute
import com.weich.daptune.core.model.OutputRouteIdentityKind
import com.weich.daptune.core.model.ProfileApplySource
import com.weich.daptune.domain.ApplyProfileUseCase
import com.weich.daptune.domain.AudioRouteMonitor
import com.weich.daptune.domain.DeviceRepository
import com.weich.daptune.domain.OperationLogRepository
import com.weich.daptune.domain.ProfileRepository
import com.weich.daptune.domain.ResolveProfileForRouteUseCase
import com.weich.daptune.domain.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

@AndroidEntryPoint
class EqAutomationService : Service() {
    @Inject lateinit var profileRepository: ProfileRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var deviceRepository: DeviceRepository
    @Inject lateinit var routeMonitor: AudioRouteMonitor
    @Inject lateinit var resolveProfile: ResolveProfileForRouteUseCase
    @Inject lateinit var applyProfile: ApplyProfileUseCase
    @Inject lateinit var operationLogs: OperationLogRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val routeProcessingMutex = Mutex()
    private var monitorJob: Job? = null
    private var restoreJob: Job? = null
    private var commandGeneration = 0L
    private var lastAppliedRouteKey: String? = null
    private var lastAppliedCurveHash: Int? = null
    private var awaitingFirstPlayback = true
    private var playbackCallbackRegistered = false
    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            if (!awaitingFirstPlayback || configs.none { config ->
                    config.audioAttributes.usage in MediaPlaybackUsages
                }
            ) return
            awaitingFirstPlayback = false
            // Vendor DAP initialization can finish after BOOT_COMPLETED and the first write.
            // Reapply once when media actually starts; subsequent playback callbacks do no work.
            reapplyCurrentRoute(force = true, source = ProfileApplySource.AUTOMATION_RECOVERY)
        }
    }
    private val dolbyStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            reapplyCurrentRoute(
                force = true,
                source = ProfileApplySource.DOLBY_RESTORED,
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ContextCompat.registerReceiver(
            this,
            dolbyStateReceiver,
            IntentFilter(XiaomiDolbyStateAction),
            ContextCompat.RECEIVER_EXPORTED,
        )
        playbackCallbackRegistered = runCatching {
            getSystemService(AudioManager::class.java)
                .registerAudioPlaybackCallback(playbackCallback, mainHandler)
        }.onFailure { Log.w("DapTune", "Unable to observe initial media playback", it) }.isSuccess
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = automationServiceCommand(intent?.action)
        when (command) {
            AutomationServiceCommand.STOP -> {
                commandGeneration += 1
                restoreJob?.cancel()
                restoreJob = null
                monitorJob?.cancel()
                monitorJob = null
                serviceScope.launch {
                    try {
                        settingsRepository.setAutomationEnabled(false)
                    } finally {
                        mainHandler.post(::shutdown)
                    }
                }
                return START_NOT_STICKY
            }
            AutomationServiceCommand.IGNORE -> {
                return if (monitorJob?.isActive == true || restoreJob?.isActive == true) {
                    START_STICKY
                } else {
                    stopSelf(startId)
                    START_NOT_STICKY
                }
            }
            AutomationServiceCommand.START -> {
                commandGeneration += 1
                restoreJob?.cancel()
                restoreJob = null
                val source = if (monitorJob == null) {
                    ProfileApplySource.AUTOMATION_START
                } else {
                    ProfileApplySource.AUTOMATION_REFRESH
                }
                promoteToForeground("正在检查播放设备")
                replaceMonitoringSession(source)
            }
            AutomationServiceCommand.SYSTEM_RESTART -> {
                commandGeneration += 1
                val generation = commandGeneration
                promoteToForeground("正在恢复自动切换")
                restoreJob?.cancel()
                restoreJob = serviceScope.launch {
                    val enabled = try {
                        settingsRepository.settings.first().automationEnabled
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        recordMonitorFailure(error)
                        false
                    }
                    mainHandler.post {
                        if (generation != commandGeneration) return@post
                        restoreJob = null
                        if (enabled) {
                            replaceMonitoringSession(ProfileApplySource.AUTOMATION_RECOVERY)
                        } else {
                            shutdown()
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * MIUI removes this process' MediaRouter2 registration when the app task is swiped away,
     * even though the started foreground service and its process remain alive. Recreate the
     * single route-monitor session after the task-removal transaction has finished. This is a
     * lifecycle repair for the existing listener, not a second recovery mechanism.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        mainHandler.post {
            if (!serviceScope.isActive) return@post
            restoreJob?.cancel()
            restoreJob = null
            replaceMonitoringSession(ProfileApplySource.AUTOMATION_RECOVERY)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(dolbyStateReceiver) }
        if (playbackCallbackRegistered) {
            runCatching {
                getSystemService(AudioManager::class.java).unregisterAudioPlaybackCallback(playbackCallback)
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Replaces the complete route-listener session for every platform start or recovery command.
     *
     * A foreground ServiceRecord and notification prove that the service started, not that its
     * app-owned callbacks remain registered. Reusing a readiness flag therefore creates a
     * false-alive state. Replacing the single session is cheap and makes callback ownership
     * identical to service startup.
     */
    private fun replaceMonitoringSession(initialSource: ProfileApplySource) {
        monitorJob?.cancel()
        lastAppliedRouteKey = null
        lastAppliedCurveHash = null
        monitorJob = serviceScope.launch {
            coroutineScope {
                launch {
                    routeMonitor.failures.collect { error ->
                        recordMonitorFailure(error)
                        notify("播放设备监听恢复中")
                    }
                }
                AutomationRouteSession(
                    routes = routeMonitor.routes,
                    prepare = {
                        withTimeout(MonitorStartupTimeoutMillis) {
                            profileRepository.ensureBuiltIns()
                        }
                    },
                    processRoute = { route, force, source ->
                        recoverRouteApplication { processRouteSafely(route, force, source) }
                    },
                    onFailure = { error ->
                        recordMonitorFailure(error)
                        notify("播放设备监听恢复中")
                    },
                ).run(initialSource)
            }
        }
    }

    private fun reapplyCurrentRoute(force: Boolean, source: ProfileApplySource) {
        serviceScope.launch {
            recoverRouteApplication { processCurrentRouteSafely(force = force, source = source) }
        }
    }

    /** A bounded startup/device-change operation; the platform subscription stays registered. */
    private suspend fun recoverRouteApplication(initialAttempt: suspend () -> Boolean) {
        if (initialAttempt()) return
        for (delayMillis in RouteRecoveryDelaysMillis) {
            delay(delayMillis)
            // Resolve both route and profile again: a delayed attempt must not restore stale input.
            if (processCurrentRouteSafely(force = false, source = ProfileApplySource.AUTOMATION_RECOVERY)) return
        }
    }

    private suspend fun processCurrentRouteSafely(
        force: Boolean,
        source: ProfileApplySource,
    ): Boolean = runRouteOperation {
        processRoute(
            route = routeMonitor.currentRoute(),
            force = force,
            source = source,
        )
    }

    private suspend fun processRouteSafely(
        route: OutputRoute,
        force: Boolean,
        source: ProfileApplySource,
    ): Boolean = runRouteOperation {
        processRoute(route = route, force = force, source = source)
    }

    private suspend fun runRouteOperation(operation: suspend () -> Boolean): Boolean = try {
        withTimeout(RouteOperationTimeoutMillis) {
            operation()
        }
    } catch (timeout: TimeoutCancellationException) {
        recordMonitorFailure(timeout)
        notify("播放设备切换超时 · 等待下一次事件")
        false
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        recordMonitorFailure(error)
        notify("自动切换恢复中")
        false
    }

    private suspend fun processRoute(
        route: OutputRoute,
        force: Boolean,
        source: ProfileApplySource,
    ): Boolean = routeProcessingMutex.withLock {
        if (!settingsRepository.settings.first().automationEnabled) {
            mainHandler.post(::shutdown)
            return@withLock true
        }
        if (route.identityKind != OutputRouteIdentityKind.PERSISTENT) {
            notify("需要附近设备权限 · 未切换配置")
            return@withLock true
        }
        deviceRepository.rememberRoute(route)
        val profile = resolveProfile(route) ?: return@withLock false
        val curveHash = profile.curve.stableHash()
        if (!force && route.key == lastAppliedRouteKey && curveHash == lastAppliedCurveHash) {
            return@withLock true
        }

        notify("正在应用 ${profile.name} · ${route.displayName}")
        when (val result = applyProfile(profile.id, route, source)) {
            is DapApplyResult.Success -> {
                lastAppliedRouteKey = route.key
                lastAppliedCurveHash = curveHash
                notify("${profile.name} · ${route.displayName}")
                true
            }
            is DapApplyResult.Failure -> {
                lastAppliedRouteKey = null
                lastAppliedCurveHash = null
                notify(failureLabel(result, profile, route))
                false
            }
        }
    }

    private fun failureLabel(
        failure: DapApplyResult.Failure,
        profile: EqProfile,
        route: OutputRoute,
    ): String = when (failure.reason) {
        com.weich.daptune.core.model.DapFailureReason.DOLBY_DISABLED -> "杜比已关闭 · 等待恢复"
        com.weich.daptune.core.model.DapFailureReason.NO_CONTROL -> "音效控制权被占用"
        else -> "${profile.name} 未应用 · ${route.displayName}"
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NotificationId, notification(text))
    }

    private fun promoteToForeground(text: String) {
        startForeground(NotificationId, notification(text))
    }

    private fun notification(text: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, EqAutomationService::class.java).setAction(ActionStop),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NotificationChannelId)
            .setSmallIcon(R.drawable.ic_notification_equalizer)
            .setContentTitle("DapTune 自动切换")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NotificationChannelId,
                "自动切换",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "播放设备变化时应用对应的均衡器配置"
                setShowBadge(false)
            },
        )
    }

    private fun shutdown() {
        commandGeneration += 1
        restoreJob?.cancel()
        restoreJob = null
        monitorJob?.cancel()
        monitorJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Diagnostic persistence must never hold the only route-listener session. */
    private fun recordMonitorFailure(error: Throwable) {
        serviceScope.launch(Dispatchers.IO) {
            withTimeoutOrNull(FailureLogTimeoutMillis) {
                try {
                    operationLogs.append(
                        OperationLogEntry(
                            occurredAtEpochMillis = System.currentTimeMillis(),
                            action = OperationLogAction.AUTOMATION_START_FAILED,
                            outcome = OperationLogOutcome.FAILURE,
                            detail = error.message ?: error.javaClass.simpleName,
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Logging is best-effort and cannot participate in monitor liveness.
                }
            }
        }
    }

    companion object {
        const val ActionStart = "com.weich.daptune.action.START_AUTOMATION"
        const val ActionStop = "com.weich.daptune.action.STOP_AUTOMATION"
        private const val XiaomiDolbyStateAction =
            "miui.intent.action.ACTION_SYSTEM_UI_DOLBY_EFFECT_SWITCH"
        private const val NotificationChannelId = "eq_automation"
        private const val NotificationId = 0xDA20
        private const val MonitorStartupTimeoutMillis = 6_000L
        private const val RouteOperationTimeoutMillis = 10_000L
        private const val FailureLogTimeoutMillis = 1_000L
        private val RouteRecoveryDelaysMillis = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L)
        private val MediaPlaybackUsages = setOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME)
    }
}

internal enum class AutomationServiceCommand {
    START,
    SYSTEM_RESTART,
    STOP,
    IGNORE,
}

internal fun automationServiceCommand(action: String?): AutomationServiceCommand = when (action) {
    null,
    LegacyRecoveryAction,
    -> AutomationServiceCommand.SYSTEM_RESTART
    EqAutomationService.ActionStart -> AutomationServiceCommand.START
    EqAutomationService.ActionStop -> AutomationServiceCommand.STOP
    else -> AutomationServiceCommand.IGNORE
}

// Accept an already-scheduled PendingIntent from 0.3.1 once after an in-place update. New code
// never schedules this action; all future process recovery is owned by START_STICKY.
private const val LegacyRecoveryAction =
    "com.weich.daptune.action.RECOVER_AFTER_TASK_REMOVAL"
