/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.launcher3.statehandlers

import android.content.Context
import android.os.Debug
import android.util.Log
import android.window.DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY
import com.android.launcher3.LauncherState
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppComponent
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.statemanager.BaseState
import com.android.launcher3.statemanager.StatefulActivity
import com.android.launcher3.uioverrides.QuickstepLauncher
import com.android.launcher3.util.DaggerSingletonObject
import com.android.launcher3.util.DaggerSingletonTracker
import com.android.launcher3.util.Executors
import com.android.launcher3.util.window.WindowManagerProxy.DesktopVisibilityListener
import com.android.quickstep.GestureState.GestureEndTarget
import com.android.quickstep.SystemUiProxy
import com.android.quickstep.fallback.RecentsState
import com.android.wm.shell.desktopmode.DisplayDeskState
import com.android.wm.shell.desktopmode.IDesktopTaskListener.Stub
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus
import java.io.PrintWriter
import java.lang.ref.WeakReference
import javax.inject.Inject

/**
 * Controls the visibility of the workspace and the resumed / paused state when desktop mode is
 * enabled.
 */
@LauncherAppSingleton
class DesktopVisibilityController
@Inject
constructor(
    @ApplicationContext private val context: Context,
    systemUiProxy: SystemUiProxy,
    lifecycleTracker: DaggerSingletonTracker,
) {
    private val desktopVisibilityListeners: MutableSet<DesktopVisibilityListener> = HashSet()
    private val taskbarDesktopModeListeners: MutableSet<TaskbarDesktopModeListener> = HashSet()

    /** Number of visible desktop windows in desktop mode. */
    var visibleDesktopTasksCount: Int = 0
        /**
         * Sets the number of desktop windows that are visible and updates launcher visibility based
         * on it.
         */
        set(visibleTasksCount) {
            if (DEBUG) {
                Log.d(
                    TAG,
                    ("setVisibleDesktopTasksCount: visibleTasksCount=" +
                        visibleTasksCount +
                        " currentValue=" +
                        field),
                )
            }

            if (visibleTasksCount != field) {
                val wasVisible = field > 0
                val isVisible = visibleTasksCount > 0
                val wereDesktopTasksVisibleBefore = areDesktopTasksVisibleAndNotInOverview()
                field = visibleTasksCount
                val areDesktopTasksVisibleNow = areDesktopTasksVisibleAndNotInOverview()
                if (wereDesktopTasksVisibleBefore != areDesktopTasksVisibleNow) {
                    notifyDesktopVisibilityListeners(areDesktopTasksVisibleNow)
                }

                if (
                    !ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY.isTrue && wasVisible != isVisible
                ) {
                    // TODO: b/333533253 - Remove after flag rollout
                    if (field > 0) {
                        if (!inOverviewState) {
                            // When desktop tasks are visible & we're not in overview, we want
                            // launcher
                            // to appear paused, this ensures that taskbar displays.
                            markLauncherPaused()
                        }
                    } else {
                        // If desktop tasks aren't visible, ensure that launcher appears resumed to
                        // behave normally.
                        markLauncherResumed()
                    }
                }
            }
        }

    private var inOverviewState = false
    private var backgroundStateEnabled = false
    private var gestureInProgress = false

    private var desktopTaskListener: DesktopTaskListenerImpl?

    init {
        desktopTaskListener = DesktopTaskListenerImpl(this, context.displayId)
        systemUiProxy.setDesktopTaskListener(desktopTaskListener)

        lifecycleTracker.addCloseable {
            desktopTaskListener = null
            systemUiProxy.setDesktopTaskListener(null)
        }
    }

    /** Whether desktop tasks are visible in desktop mode. */
    fun areDesktopTasksVisible(): Boolean {
        val desktopTasksVisible: Boolean = visibleDesktopTasksCount > 0
        if (DEBUG) {
            Log.d(TAG, "areDesktopTasksVisible: desktopVisible=$desktopTasksVisible")
        }
        return desktopTasksVisible
    }

    /** Whether desktop tasks are visible in desktop mode. */
    fun areDesktopTasksVisibleAndNotInOverview(): Boolean {
        val desktopTasksVisible: Boolean = visibleDesktopTasksCount > 0
        if (DEBUG) {
            Log.d(
                TAG,
                ("areDesktopTasksVisible: desktopVisible=" +
                    desktopTasksVisible +
                    " overview=" +
                    inOverviewState),
            )
        }
        return desktopTasksVisible && !inOverviewState
    }

    /** Registers a listener for Taskbar changes in Desktop Mode. */
    fun registerTaskbarDesktopModeListener(listener: TaskbarDesktopModeListener) {
        taskbarDesktopModeListeners.add(listener)
    }

    /** Removes a previously registered listener for Taskbar changes in Desktop Mode. */
    fun unregisterTaskbarDesktopModeListener(listener: TaskbarDesktopModeListener) {
        taskbarDesktopModeListeners.remove(listener)
    }

    fun onLauncherStateChanged(state: LauncherState) {
        onLauncherStateChanged(
            state,
            state === LauncherState.BACKGROUND_APP,
            state.isRecentsViewVisible,
        )
    }

    fun onLauncherStateChanged(state: RecentsState) {
        onLauncherStateChanged(
            state,
            state === RecentsState.BACKGROUND_APP,
            state.isRecentsViewVisible,
        )
    }

    /** Process launcher state change and update launcher view visibility based on desktop state */
    fun onLauncherStateChanged(
        state: BaseState<*>,
        isBackgroundAppState: Boolean,
        isRecentsViewVisible: Boolean,
    ) {
        if (DEBUG) {
            Log.d(TAG, "onLauncherStateChanged: newState=$state")
        }
        setBackgroundStateEnabled(isBackgroundAppState)
        // Desktop visibility tracks overview and background state separately
        setOverviewStateEnabled(!isBackgroundAppState && isRecentsViewVisible)
    }

    private fun setOverviewStateEnabled(overviewStateEnabled: Boolean) {
        if (DEBUG) {
            Log.d(
                TAG,
                ("setOverviewStateEnabled: enabled=" +
                    overviewStateEnabled +
                    " currentValue=" +
                    inOverviewState),
            )
        }
        if (overviewStateEnabled != inOverviewState) {
            val wereDesktopTasksVisibleBefore = areDesktopTasksVisibleAndNotInOverview()
            inOverviewState = overviewStateEnabled
            val areDesktopTasksVisibleNow = areDesktopTasksVisibleAndNotInOverview()
            if (wereDesktopTasksVisibleBefore != areDesktopTasksVisibleNow) {
                notifyDesktopVisibilityListeners(areDesktopTasksVisibleNow)
            }

            if (ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY.isTrue) {
                return
            }

            // TODO: b/333533253 - Clean up after flag rollout
            if (inOverviewState) {
                markLauncherResumed()
            } else if (areDesktopTasksVisibleNow && !gestureInProgress) {
                // Switching out of overview state and gesture finished.
                // If desktop tasks are still visible, hide launcher again.
                markLauncherPaused()
            }
        }
    }

    /** Registers a listener for Taskbar changes in Desktop Mode. */
    fun registerDesktopVisibilityListener(listener: DesktopVisibilityListener) {
        desktopVisibilityListeners.add(listener)
    }

    /** Removes a previously registered listener for Taskbar changes in Desktop Mode. */
    fun unregisterDesktopVisibilityListener(listener: DesktopVisibilityListener) {
        desktopVisibilityListeners.remove(listener)
    }

    private fun notifyDesktopVisibilityListeners(areDesktopTasksVisible: Boolean) {
        if (DEBUG) {
            Log.d(TAG, "notifyDesktopVisibilityListeners: visible=$areDesktopTasksVisible")
        }
        for (listener in desktopVisibilityListeners) {
            listener.onDesktopVisibilityChanged(areDesktopTasksVisible)
        }
    }

    private fun notifyTaskbarDesktopModeListeners(doesAnyTaskRequireTaskbarRounding: Boolean) {
        if (DEBUG) {
            Log.d(
                TAG,
                "notifyTaskbarDesktopModeListeners: doesAnyTaskRequireTaskbarRounding=" +
                    doesAnyTaskRequireTaskbarRounding,
            )
        }
        for (listener in taskbarDesktopModeListeners) {
            listener.onTaskbarCornerRoundingUpdate(doesAnyTaskRequireTaskbarRounding)
        }
    }

    /** TODO: b/333533253 - Remove after flag rollout */
    private fun setBackgroundStateEnabled(backgroundStateEnabled: Boolean) {
        if (DEBUG) {
            Log.d(
                TAG,
                ("setBackgroundStateEnabled: enabled=" +
                    backgroundStateEnabled +
                    " currentValue=" +
                    this.backgroundStateEnabled),
            )
        }
        if (backgroundStateEnabled != this.backgroundStateEnabled) {
            this.backgroundStateEnabled = backgroundStateEnabled
            if (this.backgroundStateEnabled) {
                markLauncherResumed()
            } else if (areDesktopTasksVisibleAndNotInOverview() && !gestureInProgress) {
                // Switching out of background state. If desktop tasks are visible, pause launcher.
                markLauncherPaused()
            }
        }
    }

    var isRecentsGestureInProgress: Boolean
        /**
         * Whether recents gesture is currently in progress.
         *
         * TODO: b/333533253 - Remove after flag rollout
         */
        get() = gestureInProgress
        /** TODO: b/333533253 - Remove after flag rollout */
        private set(gestureInProgress) {
            if (gestureInProgress != this.gestureInProgress) {
                this.gestureInProgress = gestureInProgress
            }
        }

    /**
     * Notify controller that recents gesture has started.
     *
     * TODO: b/333533253 - Remove after flag rollout
     */
    fun setRecentsGestureStart() {
        if (DEBUG) {
            Log.d(TAG, "setRecentsGestureStart")
        }
        isRecentsGestureInProgress = true
    }

    /**
     * Notify controller that recents gesture finished with the given
     * [com.android.quickstep.GestureState.GestureEndTarget]
     *
     * TODO: b/333533253 - Remove after flag rollout
     */
    fun setRecentsGestureEnd(endTarget: GestureEndTarget?) {
        if (DEBUG) {
            Log.d(TAG, "setRecentsGestureEnd: endTarget=$endTarget")
        }
        isRecentsGestureInProgress = false

        if (endTarget == null) {
            // Gesture did not result in a new end target. Ensure launchers gets paused again.
            markLauncherPaused()
        }
    }

    /** TODO: b/333533253 - Remove after flag rollout */
    private fun markLauncherPaused() {
        if (ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY.isTrue) {
            return
        }
        if (DEBUG) {
            Log.d(TAG, "markLauncherPaused " + Debug.getCaller())
        }
        val activity: StatefulActivity<LauncherState>? =
            QuickstepLauncher.ACTIVITY_TRACKER.getCreatedContext()
        activity?.setPaused()
    }

    /** TODO: b/333533253 - Remove after flag rollout */
    private fun markLauncherResumed() {
        if (ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY.isTrue) {
            return
        }
        if (DEBUG) {
            Log.d(TAG, "markLauncherResumed " + Debug.getCaller())
        }
        val activity: StatefulActivity<LauncherState>? =
            QuickstepLauncher.ACTIVITY_TRACKER.getCreatedContext()
        // Check activity state before calling setResumed(). Launcher may have been actually
        // paused (eg fullscreen task moved to front).
        // In this case we should not mark the activity as resumed.
        if (activity != null && activity.isResumed) {
            activity.setResumed()
        }
    }

    fun dumpLogs(prefix: String, pw: PrintWriter) {
        pw.println(prefix + "DesktopVisibilityController:")

        pw.println("$prefix\tdesktopVisibilityListeners=$desktopVisibilityListeners")
        pw.println("$prefix\tvisibleDesktopTasksCount=$visibleDesktopTasksCount")
        pw.println("$prefix\tinOverviewState=$inOverviewState")
        pw.println("$prefix\tbackgroundStateEnabled=$backgroundStateEnabled")
        pw.println("$prefix\tgestureInProgress=$gestureInProgress")
        pw.println("$prefix\tdesktopTaskListener=$desktopTaskListener")
        pw.println("$prefix\tcontext=$context")
    }

    /**
     * Wrapper for the IDesktopTaskListener stub to prevent lingering references to the launcher
     * activity via the controller.
     */
    private class DesktopTaskListenerImpl(
        controller: DesktopVisibilityController,
        private val displayId: Int,
    ) : Stub() {
        private val controller = WeakReference(controller)

        // TODO: b/392986431 - Implement the new desks APIs.
        override fun onListenerConnected(
            displayDeskStates: Array<DisplayDeskState>,
        ) {}

        override fun onTasksVisibilityChanged(displayId: Int, visibleTasksCount: Int) {
            if (displayId != this.displayId) return
            Executors.MAIN_EXECUTOR.execute {
                controller.get()?.apply {
                    if (DEBUG) {
                        Log.d(TAG, "desktop visible tasks count changed=$visibleTasksCount")
                    }
                    visibleDesktopTasksCount = visibleTasksCount
                }
            }
        }

        override fun onStashedChanged(displayId: Int, stashed: Boolean) {
            Log.w(TAG, "DesktopTaskListenerImpl: onStashedChanged is deprecated")
        }

        override fun onTaskbarCornerRoundingUpdate(doesAnyTaskRequireTaskbarRounding: Boolean) {
            if (!DesktopModeStatus.useRoundedCorners()) return
            Executors.MAIN_EXECUTOR.execute {
                controller.get()?.apply {
                    Log.d(
                        TAG,
                        "DesktopTaskListenerImpl: doesAnyTaskRequireTaskbarRounding= " +
                            doesAnyTaskRequireTaskbarRounding,
                    )
                    notifyTaskbarDesktopModeListeners(doesAnyTaskRequireTaskbarRounding)
                }
            }
        }

        override fun onEnterDesktopModeTransitionStarted(transitionDuration: Int) {}

        override fun onExitDesktopModeTransitionStarted(transitionDuration: Int) {}

        // TODO: b/392986431 - Implement all the below new desks APIs.
        override fun onCanCreateDesksChanged(displayId: Int, canCreateDesks: Boolean) {}

        override fun onDeskAdded(displayId: Int, deskId: Int) {}

        override fun onDeskRemoved(displayId: Int, deskId: Int) {}

        override fun onActiveDeskChanged(displayId: Int, newActiveDesk: Int, oldActiveDesk: Int) {}
    }

    /** A listener for Taskbar in Desktop Mode. */
    interface TaskbarDesktopModeListener {
        /**
         * Callback for when task is resized in desktop mode.
         *
         * @param doesAnyTaskRequireTaskbarRounding whether task requires taskbar corner roundness.
         */
        fun onTaskbarCornerRoundingUpdate(doesAnyTaskRequireTaskbarRounding: Boolean)
    }

    companion object {
        @JvmField
        val INSTANCE = DaggerSingletonObject(LauncherAppComponent::getDesktopVisibilityController)

        private const val TAG = "DesktopVisController"
        private const val DEBUG = false
    }
}
