/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.quickstep.fallback.window

import android.content.Context
import android.util.Log
import android.view.Display.INVALID_DISPLAY
import com.android.launcher3.Flags
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.util.DaggerSingletonObject
import com.android.launcher3.util.DaggerSingletonTracker
import com.android.launcher3.util.DisplayController
import com.android.launcher3.util.Executors
import com.android.launcher3.util.PerDisplayObjectProvider
import com.android.launcher3.util.WallpaperColorHints
import com.android.launcher3.util.window.WindowManagerProxy
import com.android.quickstep.DisplayModel
import com.android.quickstep.FallbackWindowInterface
import com.android.quickstep.dagger.QuickstepBaseAppComponent
import com.android.quickstep.fallback.window.RecentsDisplayModel.RecentsDisplayResource
import com.android.quickstep.util.validDisplayId
import javax.inject.Inject

@LauncherAppSingleton
class RecentsDisplayModel
@Inject
constructor(
    @ApplicationContext context: Context,
    private val windowManagerProxy: WindowManagerProxy,
    private val launcherPrefs: LauncherPrefs,
    private val wallpaperColorHints: WallpaperColorHints,
    tracker: DaggerSingletonTracker,
) : DisplayModel<RecentsDisplayResource>(context), PerDisplayObjectProvider {

    companion object {
        private const val TAG = "RecentsDisplayModel"
        private const val DEBUG = false

        @JvmStatic
        val INSTANCE: DaggerSingletonObject<RecentsDisplayModel> =
            DaggerSingletonObject<RecentsDisplayModel>(
                QuickstepBaseAppComponent::getRecentsDisplayModel
            )

        @JvmStatic
        fun enableOverviewInWindow() =
            Flags.enableFallbackOverviewInWindow() || Flags.enableLauncherOverviewInWindow()
    }

    init {
        // Add the display for the context with which we are initialized.
        storeRecentsDisplayResource(context.validDisplayId)

        displayManager.registerDisplayListener(displayListener, Executors.MAIN_EXECUTOR.handler)
        // In the scenario where displays were added before this display listener was
        // registered, we should store the RecentsDisplayResources for those displays
        // directly.
        displayManager.displays
            .filter { getDisplayResource(it.displayId) == null }
            .forEach { storeRecentsDisplayResource(it.displayId) }
        tracker.addCloseable { destroy() }
    }

    override fun createDisplayResource(displayId: Int) {
        if (DEBUG) Log.d(TAG, "createDisplayResource: displayId=$displayId")
        getDisplayResource(displayId)?.let {
            return
        }
        if (displayId == INVALID_DISPLAY) {
            Log.e(TAG, "createDisplayResource: INVALID_DISPLAY")
            return
        }
        storeRecentsDisplayResource(displayId)
    }

    private fun storeRecentsDisplayResource(displayId: Int): RecentsDisplayResource {
        val display = displayManager.getDisplay(displayId)
        if (display == null) {
            if (DEBUG)
                Log.w(
                    TAG,
                    "storeRecentsDisplayResource: could not create display for displayId=$displayId",
                )
        }
        return displayResourceArray[displayId]
            ?: RecentsDisplayResource(
                    displayId,
                    context,
                    windowManagerProxy,
                    launcherPrefs,
                    if (enableOverviewInWindow() && display != null)
                        context.createDisplayContext(display)
                    else null,
                    wallpaperColorHints.hints,
                )
                .also { displayResourceArray[displayId] = it }
    }

    fun getRecentsWindowManager(displayId: Int): RecentsWindowManager? {
        return getDisplayResource(displayId)?.recentsWindowManager
    }

    fun getFallbackWindowInterface(displayId: Int): FallbackWindowInterface? {
        return getDisplayResource(displayId)?.fallbackWindowInterface
    }

    override fun getDisplayController(displayId: Int): DisplayController {
        if (DEBUG) Log.d(TAG, "getDisplayController $displayId")
        return (getDisplayResource(displayId)
                ?: storeRecentsDisplayResource(displayId).also {
                    // We shouldn't get here because the display should already have been
                    // initialized.
                    Log.e(TAG, "getDisplayController no such display: $displayId")
                })
            .displayController
    }

    data class RecentsDisplayResource(
        var displayId: Int,
        val appContext: Context,
        val windowManagerProxy: WindowManagerProxy,
        val launcherPrefs: LauncherPrefs,
        var displayContext: Context?, // null when OverviewInWindow not enabled
        val wallpaperColorHints: Int,
    ) : DisplayResource() {
        val recentsWindowManager =
            displayContext?.let { RecentsWindowManager(it, wallpaperColorHints) }
        val fallbackWindowInterface: FallbackWindowInterface? =
            recentsWindowManager?.let { FallbackWindowInterface(recentsWindowManager) }
        val lifecycle = DaggerSingletonTracker()
        val displayController =
            DisplayController(appContext, windowManagerProxy, launcherPrefs, lifecycle, displayId)

        override fun cleanup() {
            recentsWindowManager?.destroy()
            lifecycle.close()
        }
    }
}
