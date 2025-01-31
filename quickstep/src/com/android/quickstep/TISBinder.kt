/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.quickstep

import android.graphics.Region
import android.os.Bundle
import android.os.IRemoteCallback
import android.os.RemoteException
import android.util.Log
import androidx.annotation.BinderThread
import androidx.annotation.VisibleForTesting
import com.android.launcher3.taskbar.TaskbarManager
import com.android.launcher3.testing.TestLogging
import com.android.launcher3.testing.shared.TestProtocol
import com.android.launcher3.util.Executors
import com.android.quickstep.OverviewCommandHelper.CommandType.HIDE
import com.android.quickstep.OverviewCommandHelper.CommandType.KEYBOARD_INPUT
import com.android.quickstep.OverviewCommandHelper.CommandType.SHOW
import com.android.quickstep.OverviewCommandHelper.CommandType.TOGGLE
import com.android.quickstep.util.ActivityPreloadUtil.preloadOverviewForTIS
import com.android.quickstep.util.ContextualSearchInvoker
import com.android.systemui.shared.recents.ILauncherProxy
import com.android.systemui.shared.statusbar.phone.BarTransitions.TransitionMode
import com.android.systemui.shared.system.ActivityManagerWrapper.CLOSE_SYSTEM_WINDOWS_REASON_RECENTS
import com.android.systemui.shared.system.QuickStepContract.SystemUiStateFlags
import java.lang.ref.WeakReference

/** Local ILauncherProxy implementation with some methods for local components */
private const val TAG = "TISBinder"

class TISBinder internal constructor(tis: TouchInteractionService) : ILauncherProxy.Stub() {

    private val mTis = WeakReference(tis)

    /** Returns the [TaskbarManager] or `null` if TouchInteractionService is not connected */
    val taskbarManager: TaskbarManager?
        get() = mTis.get()?.taskbarManager

    /** Returns the [OverviewCommandHelper] or `null` if TouchInteractionService is not connected */
    val overviewCommandHelper: OverviewCommandHelper?
        get() = mTis.get()?.overviewCommandHelper

    private val deviceState: RecentsAnimationDeviceState?
        get() = mTis.get()?.let { RecentsAnimationDeviceState.INSTANCE[it] }

    private inline fun executeForTaskbarManagerOnMain(
        crossinline callback: TaskbarManager.() -> Unit
    ) {
        Executors.MAIN_EXECUTOR.execute { taskbarManager?.let { callback.invoke(it) } }
    }

    @BinderThread
    override fun onInitialize(bundle: Bundle) {
        Executors.MAIN_EXECUTOR.execute {
            mTis.get()?.let {
                SystemUiProxy.INSTANCE[it].setProxy(bundle)
                preloadOverviewForTIS(it, true /* fromInit */)
            }
        }
    }

    @BinderThread
    override fun onTaskbarToggled() {
        executeForTaskbarManagerOnMain { currentActivityContext?.toggleTaskbarStash() }
    }

    @BinderThread
    override fun onOverviewToggle() {
        TestLogging.recordEvent(TestProtocol.SEQUENCE_MAIN, "onOverviewToggle")
        mTis.get()?.let { tis ->
            // If currently screen pinning, do not enter overview
            if (RecentsAnimationDeviceState.INSTANCE[tis].isScreenPinningActive) {
                return@let
            }
            TaskUtils.closeSystemWindowsAsync(CLOSE_SYSTEM_WINDOWS_REASON_RECENTS)
            tis.overviewCommandHelper.addCommand(TOGGLE)
        }
    }

    @BinderThread
    override fun onOverviewShown(triggeredFromAltTab: Boolean) {
        overviewCommandHelper?.apply {
            if (triggeredFromAltTab) {
                TaskUtils.closeSystemWindowsAsync(CLOSE_SYSTEM_WINDOWS_REASON_RECENTS)
                addCommand(KEYBOARD_INPUT)
            } else {
                addCommand(SHOW)
            }
        }
    }

    @BinderThread
    override fun onOverviewHidden(triggeredFromAltTab: Boolean, triggeredFromHomeKey: Boolean) {
        overviewCommandHelper?.apply {
            if (triggeredFromAltTab && !triggeredFromHomeKey) {
                // onOverviewShownFromAltTab hides the overview and ends at the target app
                addCommand(HIDE)
            }
        }
    }

    @BinderThread
    override fun onAssistantAvailable(available: Boolean, longPressHomeEnabled: Boolean) {
        Executors.MAIN_EXECUTOR.execute {
            deviceState?.setAssistantAvailable(available)
            taskbarManager?.onLongPressHomeEnabled(longPressHomeEnabled)
        }
    }

    @BinderThread
    override fun onAssistantVisibilityChanged(visibility: Float) {
        Executors.MAIN_EXECUTOR.execute { deviceState?.assistantVisibility = visibility }
    }

    /**
     * Sent when the assistant has been invoked with the given type (defined in AssistManager) and
     * should be shown. This method is used if SystemUiProxy#setAssistantOverridesRequested was
     * previously called including this invocation type.
     */
    override fun onAssistantOverrideInvoked(invocationType: Int) {
        mTis.get()?.let { tis ->
            if (!ContextualSearchInvoker(tis).tryStartAssistOverride(invocationType)) {
                Log.w(TAG, "Failed to invoke Assist override")
            }
        }
    }

    @BinderThread
    override fun onSystemUiStateChanged(@SystemUiStateFlags stateFlags: Long) {
        Executors.MAIN_EXECUTOR.execute { deviceState?.systemUiStateFlags = stateFlags }
    }

    @BinderThread
    override fun onActiveNavBarRegionChanges(region: Region) {
        Executors.MAIN_EXECUTOR.execute { deviceState?.setDeferredGestureRegion(region) }
    }

    @BinderThread
    override fun enterStageSplitFromRunningApp(leftOrTop: Boolean) {
        mTis.get()?.let { tis ->
            OverviewComponentObserver.INSTANCE[tis]
                .containerInterface
                .createdContainer
                ?.enterStageSplitFromRunningApp(leftOrTop)
        }
    }

    @BinderThread
    override fun onDisplayAddSystemDecorations(displayId: Int) {
        executeForTaskbarManagerOnMain { onDisplayAddSystemDecorations(displayId) }
    }

    @BinderThread
    override fun onDisplayRemoved(displayId: Int) {
        executeForTaskbarManagerOnMain { onDisplayRemoved(displayId) }
    }

    @BinderThread
    override fun onDisplayRemoveSystemDecorations(displayId: Int) {
        executeForTaskbarManagerOnMain { onDisplayRemoveSystemDecorations(displayId) }
    }

    @BinderThread
    override fun updateWallpaperVisibility(displayId: Int, visible: Boolean) {
        executeForTaskbarManagerOnMain { setWallpaperVisible(displayId, visible) }
    }

    @BinderThread
    override fun checkNavBarModes(displayId: Int) {
        executeForTaskbarManagerOnMain { checkNavBarModes(displayId) }
    }

    @BinderThread
    override fun finishBarAnimations(displayId: Int) {
        executeForTaskbarManagerOnMain { finishBarAnimations(displayId) }
    }

    @BinderThread
    override fun touchAutoDim(displayId: Int, reset: Boolean) {
        executeForTaskbarManagerOnMain { touchAutoDim(displayId, reset) }
    }

    @BinderThread
    override fun transitionTo(displayId: Int, @TransitionMode barMode: Int, animate: Boolean) {
        executeForTaskbarManagerOnMain { transitionTo(displayId, barMode, animate) }
    }

    @BinderThread
    override fun appTransitionPending(pending: Boolean) {
        executeForTaskbarManagerOnMain { appTransitionPending(pending) }
    }

    override fun onRotationProposal(rotation: Int, isValid: Boolean) {
        executeForTaskbarManagerOnMain { onRotationProposal(rotation, isValid) }
    }

    override fun disable(displayId: Int, state1: Int, state2: Int, animate: Boolean) {
        executeForTaskbarManagerOnMain { disableNavBarElements(displayId, state1, state2, animate) }
    }

    override fun onSystemBarAttributesChanged(displayId: Int, behavior: Int) {
        executeForTaskbarManagerOnMain { onSystemBarAttributesChanged(displayId, behavior) }
    }

    override fun onTransitionModeUpdated(barMode: Int, checkBarModes: Boolean) {
        executeForTaskbarManagerOnMain { onTransitionModeUpdated(barMode, checkBarModes) }
    }

    override fun onNavButtonsDarkIntensityChanged(darkIntensity: Float) {
        executeForTaskbarManagerOnMain { onNavButtonsDarkIntensityChanged(darkIntensity) }
    }

    override fun onNavigationBarLumaSamplingEnabled(displayId: Int, enable: Boolean) {
        executeForTaskbarManagerOnMain { onNavigationBarLumaSamplingEnabled(displayId, enable) }
    }

    override fun onUnbind(reply: IRemoteCallback) {
        // Run everything in the same main thread block to ensure the cleanup happens before
        // sending the reply.
        Executors.MAIN_EXECUTOR.execute {
            taskbarManager?.destroy()
            try {
                reply.sendResult(null)
            } catch (e: RemoteException) {
                Log.w(TAG, "onUnbind: Failed to reply to LauncherProxyService", e)
            }
        }
    }

    @VisibleForTesting
    fun injectFakeTrackpadForTesting() = mTis.get()?.injectFakeTrackpadForTesting()

    @VisibleForTesting fun ejectFakeTrackpadForTesting() = mTis.get()?.ejectFakeTrackpadForTesting()

    @VisibleForTesting fun refreshOverviewTarget() = mTis.get()?.refreshOverviewTarget()
}
