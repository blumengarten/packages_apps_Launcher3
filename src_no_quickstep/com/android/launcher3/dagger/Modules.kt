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

package com.android.launcher3.dagger

import android.content.Context
import android.view.Display.DEFAULT_DISPLAY
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.util.DaggerSingletonTracker
import com.android.launcher3.util.DisplayController
import com.android.launcher3.util.PerDisplayObjectProvider
import com.android.launcher3.util.window.WindowManagerProxy
import dagger.Binds
import dagger.Module
import javax.inject.Inject

private object Modules {}

@Module abstract class WindowManagerProxyModule {}

@Module abstract class ApiWrapperModule {}

@Module abstract class PluginManagerWrapperModule {}

@Module object StaticObjectModule {}

@LauncherAppSingleton
class DefaultPerDisplayObjectProvider
@Inject
constructor(
    @ApplicationContext context: Context,
    wmProxy: WindowManagerProxy,
    launcherPrefs: LauncherPrefs,
    lifecycleTracker: DaggerSingletonTracker,
) : PerDisplayObjectProvider {
    val displayController =
        DisplayController(context, wmProxy, launcherPrefs, lifecycleTracker, DEFAULT_DISPLAY)

    override fun getDisplayController(displayId: Int): DisplayController {
        return displayController
    }
}

@Module
abstract class PerDisplayObjectProviderModule {
    @Binds
    abstract fun bindPerDisplayObjectProvider(
        impl: DefaultPerDisplayObjectProvider
    ): PerDisplayObjectProvider
}

// Module containing bindings for the final derivative app
@Module abstract class AppModule {}
