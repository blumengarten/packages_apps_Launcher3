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
import android.os.Handler
import android.util.Log
import android.view.Display
import com.android.launcher3.Flags
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.util.DaggerSingletonObject
import com.android.launcher3.util.DaggerSingletonTracker
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.quickstep.DisplayModel
import com.android.quickstep.FallbackWindowInterface
import com.android.quickstep.dagger.QuickstepBaseAppComponent
import com.android.quickstep.fallback.window.RecentsDisplayModel.RecentsDisplayResource
import javax.inject.Inject

@LauncherAppSingleton
class RecentsDisplayModel
@Inject
constructor(@ApplicationContext context: Context, tracker: DaggerSingletonTracker) :
    DisplayModel<RecentsDisplayResource>(context) {

    companion object {
        private const val TAG = "RecentsDisplayModel"
        private const val DEBUG = false

        @JvmStatic
        val INSTANCE: DaggerSingletonObject<RecentsDisplayModel> =
            DaggerSingletonObject<RecentsDisplayModel>(
                QuickstepBaseAppComponent::getRecentsDisplayModel
            )
    }

    init {
        if (Flags.enableFallbackOverviewInWindow() || Flags.enableLauncherOverviewInWindow()) {
            MAIN_EXECUTOR.execute {
                displayManager.registerDisplayListener(displayListener, Handler.getMain())
                // In the scenario where displays were added before this display listener was
                // registered, we should store the RecentsDisplayResources for those displays
                // directly.
                displayManager.displays
                    .filter { getDisplayResource(it.displayId) == null }
                    .forEach { storeRecentsDisplayResource(it.displayId, it) }
            }
            tracker.addCloseable { destroy() }
        }
    }

    override fun createDisplayResource(displayId: Int) {
        if (DEBUG) Log.d(TAG, "createDisplayResource: displayId=$displayId")
        getDisplayResource(displayId)?.let {
            return
        }
        val display = displayManager.getDisplay(displayId)
        if (display == null) {
            if (DEBUG)
                Log.w(
                    TAG,
                    "createDisplayResource: could not create display for displayId=$displayId",
                    Exception(),
                )
            return
        }
        storeRecentsDisplayResource(displayId, display)
    }

    private fun storeRecentsDisplayResource(displayId: Int, display: Display) {
        displayResourceArray[displayId] =
            RecentsDisplayResource(displayId, context.createDisplayContext(display))
    }

    fun getRecentsWindowManager(displayId: Int): RecentsWindowManager? {
        return getDisplayResource(displayId)?.recentsWindowManager
    }

    fun getFallbackWindowInterface(displayId: Int): FallbackWindowInterface? {
        return getDisplayResource(displayId)?.fallbackWindowInterface
    }

    data class RecentsDisplayResource(var displayId: Int, var displayContext: Context) :
        DisplayResource() {
        val recentsWindowManager = RecentsWindowManager(displayContext)
        val fallbackWindowInterface: FallbackWindowInterface =
            FallbackWindowInterface(recentsWindowManager)

        override fun cleanup() {
            recentsWindowManager.destroy()
        }
    }
}
