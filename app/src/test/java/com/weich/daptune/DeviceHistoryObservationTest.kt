package com.weich.daptune

import android.util.Log
import androidx.lifecycle.ViewModelStore
import com.weich.daptune.core.model.AppliedSnapshot
import com.weich.daptune.core.model.AppSettings
import com.weich.daptune.core.model.DapCapability
import com.weich.daptune.core.model.DapCurveReadbackSupport
import com.weich.daptune.core.model.DeviceBinding
import com.weich.daptune.core.model.EqCurve
import com.weich.daptune.core.model.EqProfile
import com.weich.daptune.core.model.KnownOutputDevice
import com.weich.daptune.core.model.OperationLogAction
import com.weich.daptune.core.model.OperationLogEntry
import com.weich.daptune.core.model.OperationLogOutcome
import com.weich.daptune.core.model.OutputRoute
import com.weich.daptune.core.model.OutputRouteType
import com.weich.daptune.core.model.ProfileSource
import com.weich.daptune.core.model.VerificationState
import com.weich.daptune.domain.ApplyCurveUseCase
import com.weich.daptune.domain.ApplyProfileUseCase
import com.weich.daptune.domain.AudioRouteMonitor
import com.weich.daptune.domain.CheckForUpdateUseCase
import com.weich.daptune.domain.DapGateway
import com.weich.daptune.domain.DeviceRepository
import com.weich.daptune.domain.OperationLogRepository
import com.weich.daptune.domain.ProfileRepository
import com.weich.daptune.domain.ResolveProfileForRouteUseCase
import com.weich.daptune.domain.SaveProfileUseCase
import com.weich.daptune.domain.SelectProfileForRouteUseCase
import com.weich.daptune.domain.SettingsRepository
import com.weich.daptune.feature.automation.AutomationController
import com.weich.daptune.feature.automation.AutomationViewModel
import com.weich.daptune.feature.editor.EditorViewModel
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceHistoryObservationTest {
    @Test
    fun historyWriteInvalidationSettlesWithAllPagesAlive() = withViewModels { fixture ->
        runCurrent()

        assertEquals(listOf(OutputRoute.Speaker), fixture.recorded)
        assertEquals(fixture.history.value, fixture.automation.state.value.devices)
        assertEquals(OutputRoute.Speaker, fixture.editor.state.value.route)
    }

    @Test
    fun databaseAndSettingsEmissionsNeverRecordTheRouteAgain() = withViewModels { fixture ->
        runCurrent()
        repeat(10) { index ->
            fixture.history.value = listOf(KnownOutputDevice(OutputRoute.Speaker, index + 100L))
            fixture.bindings.value = listOf(DeviceBinding("other:$index", "builtin.flat"))
            fixture.settings.value = fixture.settings.value.copy(lastUpdateCheckAtEpochMillis = index + 1L)
            fixture.profiles.value = fixture.profiles.value.map { it.copy(updatedAtEpochMillis = index + 1L) }
            fixture.snapshot.value = AppliedSnapshot(
                OutputRoute.Speaker.key, "builtin.flat", 0, index + 1L, VerificationState.VERIFIED,
            )
            fixture.logs.value = listOf(
                OperationLogEntry(
                    occurredAtEpochMillis = index + 1L,
                    action = OperationLogAction.ROUTE_CHANGED,
                    outcome = OperationLogOutcome.SUCCESS,
                ),
            )
            runCurrent()
        }

        assertEquals(listOf(OutputRoute.Speaker), fixture.recorded)
        assertEquals(fixture.history.value, fixture.automation.state.value.devices)
    }

    @Test
    fun duplicateRoutesAreIgnoredButReconnectAndRenameAreRecorded() = withViewModels { fixture ->
        runCurrent()
        val usb = OutputRoute("usb:test", "USB audio", OutputRouteType.USB)
        val renamed = usb.copy(displayName = "USB headphones")
        for (route in listOf(OutputRoute.Speaker, usb, usb, renamed, OutputRoute.Speaker, usb)) {
            fixture.routes.emit(route)
            runCurrent()
        }

        assertEquals(listOf(OutputRoute.Speaker, usb, renamed, OutputRoute.Speaker, usb), fixture.recorded)
        assertEquals(usb, fixture.automation.state.value.currentRoute)
        assertEquals(usb, fixture.editor.state.value.route)
    }

    @Test
    fun historyIsRecordedWithoutAutomationOrFeaturePages() = withViewModels(pages = false) { fixture ->
        runCurrent()

        assertEquals(false, fixture.settings.value.automationEnabled)
        assertEquals(listOf(OutputRoute.Speaker), fixture.recorded)
    }

    @Test
    fun failedHistoryWriteDoesNotPreventTheNextRoute() = withViewModels { fixture ->
        mockStatic(Log::class.java).use {
            fixture.nextFailure = IOException("Database unavailable")
            runCurrent()
            val usb = OutputRoute("usb:test", "USB audio", OutputRouteType.USB)
            fixture.routes.emit(usb)
            runCurrent()

            assertEquals(listOf(OutputRoute.Speaker, usb), fixture.recorded)
            assertEquals(usb, fixture.automation.state.value.currentRoute)
            assertEquals(listOf(KnownOutputDevice(usb, 2L)), fixture.history.value)
        }
    }

    @Test
    fun cancellationStopsHistoryCollection() = withViewModels(pages = false) { fixture ->
        fixture.nextFailure = CancellationException("Activity destroyed")
        runCurrent()
        fixture.routes.emit(OutputRoute("usb:test", "USB audio", OutputRouteType.USB))
        runCurrent()

        assertEquals(listOf(OutputRoute.Speaker), fixture.recorded)
    }

    @Test
    fun clearingActivityViewModelsReleasesTheRouteSubscription() = withViewModels { fixture ->
        runCurrent()
        fixture.store.clear()
        runCurrent()
        fixture.routes.emit(OutputRoute("usb:test", "USB audio", OutputRouteType.USB))
        runCurrent()

        assertEquals(0, fixture.routes.subscriptionCount.value)
        assertEquals(listOf(OutputRoute.Speaker), fixture.recorded)
    }

    private fun withViewModels(
        pages: Boolean = true,
        block: suspend TestScope.(Fixture) -> Unit,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val fixture = Fixture(store)
            fixture.start(pages)
            block(fixture)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private class Fixture(val store: ViewModelStore) {
        val routes = MutableSharedFlow<OutputRoute>(replay = 1)
        val history = MutableStateFlow<List<KnownOutputDevice>>(emptyList())
        val bindings = MutableStateFlow<List<DeviceBinding>>(emptyList())
        val snapshot = MutableStateFlow<AppliedSnapshot?>(null)
        val settings = MutableStateFlow(AppSettings())
        val profiles = MutableStateFlow(
            listOf(EqProfile("builtin.flat", "Flat", EqCurve.flat(), true, ProfileSource.BUILT_IN, 0L, 0L)),
        )
        val logs = MutableStateFlow<List<OperationLogEntry>>(emptyList())
        val recorded = mutableListOf<OutputRoute>()
        var nextFailure: Exception? = null
        lateinit var automation: AutomationViewModel
        lateinit var editor: EditorViewModel

        suspend fun start(pages: Boolean) {
            val devices = mock(DeviceRepository::class.java)
            val profileRepository = mock(ProfileRepository::class.java)
            val settingsRepository = mock(SettingsRepository::class.java)
            val monitor = mock(AudioRouteMonitor::class.java)
            val operationLogs = mock(OperationLogRepository::class.java)
            val controller = mock(AutomationController::class.java)
            val gateway = mock(DapGateway::class.java)
            `when`(devices.knownDevices).thenReturn(history)
            `when`(devices.bindings).thenReturn(bindings)
            `when`(devices.appliedSnapshot).thenReturn(snapshot)
            `when`(profileRepository.profiles).thenReturn(profiles)
            `when`(profileRepository.getProfile(anyString())).thenAnswer { invocation ->
                profiles.value.firstOrNull { it.id == invocation.getArgument<String>(0) }
            }
            `when`(settingsRepository.settings).thenReturn(settings)
            `when`(monitor.routes).thenReturn(routes)
            `when`(operationLogs.entries).thenReturn(logs)
            `when`(gateway.inspect()).thenReturn(
                DapCapability(true, true, true, true, 1, 0, DapCurveReadbackSupport.SUPPORTED),
            )
            // Every write emits new history, just like Room invalidation with a new last-seen time.
            // Bound a regression so the old feedback loop fails instead of hanging the test runner.
            doAnswer { invocation ->
                check(recorded.size < 20) { "Device history feedback loop" }
                val route = invocation.getArgument<OutputRoute>(0)
                recorded += route
                nextFailure?.let { failure ->
                    nextFailure = null
                    throw failure
                }
                history.value = listOf(KnownOutputDevice(route, recorded.size.toLong()))
                Unit
            }.`when`(devices).rememberRoute(any<OutputRoute>() ?: OutputRoute.Speaker)
            routes.emit(OutputRoute.Speaker)
            store.put(
                "app",
                AppViewModel(
                    profileRepository, settingsRepository, controller,
                    mock(CheckForUpdateUseCase::class.java), monitor, devices,
                ),
            )
            if (!pages) return
            val resolve = ResolveProfileForRouteUseCase(profileRepository, devices, settingsRepository)
            val apply = ApplyProfileUseCase(profileRepository, devices, gateway, operationLogs)
            automation = AutomationViewModel(
                profileRepository, devices, settingsRepository, monitor, resolve, apply, controller, operationLogs,
            )
            editor = EditorViewModel(
                profileRepository, settingsRepository, devices, monitor, gateway,
                ApplyCurveUseCase(devices, gateway, operationLogs), resolve,
                SaveProfileUseCase(profileRepository),
                SelectProfileForRouteUseCase(devices, settingsRepository, apply),
            )
            store.put("automation", automation)
            store.put("editor", editor)
        }
    }
}
