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

package com.android.quickstep.recents.ui.viewmodel

import android.util.Size
import com.android.quickstep.recents.domain.model.DesktopTaskBoundsData
import com.android.quickstep.recents.domain.usecase.OrganizeDesktopTasksUseCase

/** ViewModel used for [com.android.quickstep.views.DesktopTaskView]. */
class DesktopTaskViewModel(private val organizeDesktopTasksUseCase: OrganizeDesktopTasksUseCase) {
    var organizedDesktopTaskPositions = emptyList<DesktopTaskBoundsData>()
        private set

    fun organizeDesktopTasks(desktopSize: Size, defaultPositions: List<DesktopTaskBoundsData>) {
        organizedDesktopTaskPositions =
            organizeDesktopTasksUseCase.run(desktopSize, defaultPositions)
    }
}
