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
package com.android.launcher3.util

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.lifecycle.Lifecycle.State.RESUMED
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ActivityScenario.ActivityAction
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import androidx.test.uiautomator.UiDevice
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherState
import com.android.launcher3.allapps.AllAppsStore.DEFER_UPDATES_TEST
import com.android.launcher3.tapl.TestHelpers
import com.android.launcher3.util.ModelTestExtensions.loadModelSync
import com.android.launcher3.util.Wait.atMost
import java.util.function.Function
import java.util.function.Supplier
import org.junit.After

/**
 * Base class for tests which use Launcher activity with some utility methods.
 *
 * This should instead be a rule, but is kept as a base class for easier migration from TAPL
 */
open class BaseLauncherActivityTest<LAUNCHER_TYPE : Launcher> {

    private var currentScenario: ActivityScenario<LAUNCHER_TYPE>? = null

    val scenario: ActivityScenario<LAUNCHER_TYPE>
        get() =
            currentScenario
                ?: ActivityScenario.launch<LAUNCHER_TYPE>(
                        TestHelpers.getHomeIntentInPackage(targetContext()),
                        null,
                    )
                    .also { currentScenario = it }

    @After
    fun closeCurrentActivity() {
        currentScenario?.close()
        currentScenario = null
    }

    protected fun loadLauncherSync() {
        LauncherAppState.getInstance(targetContext()).model.loadModelSync()
        scenario.moveToState(RESUMED)
    }

    protected fun targetContext(): Context = getInstrumentation().targetContext

    protected fun goToState(state: LauncherState) = executeOnLauncher {
        it.stateManager.goToState(state, 0)
    }

    protected fun executeOnLauncher(f: ActivityAction<LAUNCHER_TYPE>) = scenario.onActivity(f)

    protected fun <T> getFromLauncher(f: Function<in LAUNCHER_TYPE, out T?>): T? {
        var result: T? = null
        executeOnLauncher { result = f.apply(it) }
        return result
    }

    protected fun isInState(state: Supplier<LauncherState>): Boolean =
        getFromLauncher { it.stateManager.state == state.get() }!!

    protected fun waitForState(message: String, state: Supplier<LauncherState>) =
        waitForLauncherCondition(message) { it.stateManager.currentStableState === state.get() }

    protected fun waitForLauncherCondition(
        message: String,
        condition: Function<LAUNCHER_TYPE, Boolean>,
    ) = atMost(message, { getFromLauncher(condition)!! })

    protected fun <T> getOnceNotNull(message: String, f: Function<LAUNCHER_TYPE, T?>): T? {
        var output: T? = null
        atMost(
            message,
            {
                val fromLauncher = getFromLauncher<T>(f)
                output = fromLauncher
                fromLauncher != null
            },
        )
        return output
    }

    protected fun getAllAppsScroll(launcher: LAUNCHER_TYPE) =
        launcher.appsView.activeRecyclerView.computeVerticalScrollOffset()

    @JvmOverloads
    protected fun injectKeyEvent(keyCode: Int, actionDown: Boolean, metaState: Int = 0) {
        val eventTime = SystemClock.uptimeMillis()
        val event =
            KeyEvent.obtain(
                eventTime,
                eventTime,
                if (actionDown) KeyEvent.ACTION_DOWN else MotionEvent.ACTION_UP,
                keyCode,
                /* repeat= */ 0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                /* scancode= */ 0,
                /* flags= */ 0,
                InputDevice.SOURCE_KEYBOARD,
                /* characters =*/ null,
            )
        executeOnLauncher { it.dispatchKeyEvent(event) }
        event.recycle()
    }

    fun startAppFast(packageName: String) {
        val intent = targetContext().packageManager.getLaunchIntentForPackage(packageName)!!
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        targetContext().startActivity(intent)
        UiDevice.getInstance(getInstrumentation()).waitForIdle()
    }

    fun freezeAllApps() = executeOnLauncher {
        it.appsView.appsStore.enableDeferUpdates(DEFER_UPDATES_TEST)
    }

    fun executeShellCommand(cmd: String) =
        UiDevice.getInstance(getInstrumentation()).executeShellCommand(cmd)
}
