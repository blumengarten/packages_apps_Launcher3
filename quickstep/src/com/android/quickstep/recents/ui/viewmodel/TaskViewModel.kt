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

import android.annotation.ColorInt
import android.util.Log
import androidx.core.graphics.ColorUtils
import com.android.launcher3.util.coroutines.DispatcherProvider
import com.android.quickstep.recents.domain.model.TaskId
import com.android.quickstep.recents.domain.model.TaskModel
import com.android.quickstep.recents.domain.usecase.GetTaskUseCase
import com.android.quickstep.recents.viewmodel.RecentsViewData
import com.android.quickstep.views.TaskViewType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * ViewModel used for [com.android.quickstep.views.TaskView],
 * [com.android.quickstep.views.DesktopTaskView] and [com.android.quickstep.views.GroupedTaskView].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskViewModel(
    private val taskViewType: TaskViewType,
    recentsViewData: RecentsViewData,
    private val getTaskUseCase: GetTaskUseCase,
    dispatcherProvider: DispatcherProvider,
) {
    private var taskIds = MutableStateFlow(emptySet<Int>())

    private val isLiveTile =
        combine(
                taskIds,
                recentsViewData.runningTaskIds,
                recentsViewData.runningTaskShowScreenshot,
            ) { taskIds, runningTaskIds, runningTaskShowScreenshot ->
                runningTaskIds == taskIds && !runningTaskShowScreenshot
            }
            .distinctUntilChanged()

    val state: Flow<TaskTileUiState> =
        taskIds
            .flatMapLatest { ids ->
                // Combine Tasks requests
                combine(
                    ids.map { id -> getTaskUseCase(id).map { taskModel -> id to taskModel } },
                    ::mapToUiState,
                )
            }
            .combine(isLiveTile) { tasks, isLiveTile ->
                TaskTileUiState(
                    tasks = tasks,
                    isLiveTile = isLiveTile,
                    hasHeader = taskViewType == TaskViewType.DESKTOP,
                )
            }
            .distinctUntilChanged()
            .flowOn(dispatcherProvider.background)

    fun bind(vararg taskId: TaskId) {
        Log.d(TAG, "bind: $taskId")
        taskIds.value = taskId.toSet()
    }

    private fun mapToUiState(result: Array<Pair<TaskId, TaskModel?>>): List<TaskData> =
        result.map { mapToUiState(it.first, it.second) }

    private fun mapToUiState(taskId: TaskId, result: TaskModel?): TaskData =
        result?.let {
            TaskData.Data(
                taskId = taskId,
                title = result.title,
                titleDescription = result.titleDescription,
                icon = result.icon,
                thumbnailData = result.thumbnail,
                backgroundColor = result.backgroundColor.removeAlpha(),
                isLocked = result.isLocked,
            )
        } ?: TaskData.NoData(taskId)

    @ColorInt private fun Int.removeAlpha(): Int = ColorUtils.setAlphaComponent(this, 0xff)

    private companion object {
        const val TAG = "TaskViewModel"
    }
}
