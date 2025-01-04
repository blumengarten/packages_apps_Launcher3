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

package com.android.launcher3

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppSingleton
import java.io.File
import javax.inject.Inject

/** Emulates Launcher preferences for a test environment. */
@LauncherAppSingleton
class FakeLauncherPrefs @Inject constructor(@ApplicationContext context: Context) :
    LauncherPrefs(context) {

    private val backingPrefs =
        context.getSharedPreferences(
            File.createTempFile("fake-pref", ".xml", context.filesDir),
            MODE_PRIVATE,
        )

    override val Item.sharedPrefs: SharedPreferences
        get() = backingPrefs
}
