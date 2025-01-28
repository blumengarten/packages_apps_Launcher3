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

package com.android.quickstep.recents.ui.mapper

import com.android.quickstep.recents.ui.viewmodel.TaskData
import com.android.quickstep.task.thumbnail.TaskThumbnailUiState
import com.android.quickstep.task.thumbnail.TaskThumbnailUiState.BackgroundOnly
import com.android.quickstep.task.thumbnail.TaskThumbnailUiState.LiveTile
import com.android.quickstep.task.thumbnail.TaskThumbnailUiState.Snapshot
import com.android.quickstep.task.thumbnail.TaskThumbnailUiState.SnapshotSplash
import com.android.quickstep.task.thumbnail.TaskThumbnailUiState.ThumbnailHeader
import com.android.quickstep.task.thumbnail.TaskThumbnailUiState.Uninitialized

object TaskUiStateMapper {

    /**
     * Converts a [TaskData] object into a [TaskThumbnailUiState] for display in the UI.
     *
     * This function handles different types of [TaskData] and determines the appropriate UI state
     * based on the data and provided flags.
     *
     * @param taskData The [TaskData] to convert. Can be null or a specific subclass.
     * @param isLiveTile A flag indicating whether the task data represents live tile.
     * @param hasHeader A flag indicating whether the UI should display a header.
     * @return A [TaskThumbnailUiState] representing the UI state for the given task data.
     */
    fun toTaskThumbnailUiState(
        taskData: TaskData?,
        isLiveTile: Boolean,
        hasHeader: Boolean,
    ): TaskThumbnailUiState =
        when {
            taskData !is TaskData.Data -> Uninitialized
            isLiveTile -> createLiveTileState(taskData, hasHeader)
            isBackgroundOnly(taskData) -> BackgroundOnly(taskData.backgroundColor)
            isSnapshotSplash(taskData) ->
                SnapshotSplash(createSnapshotState(taskData, hasHeader), taskData.icon)
            else -> Uninitialized
        }

    private fun createSnapshotState(taskData: TaskData.Data, hasHeader: Boolean): Snapshot =
        if (canHeaderBeCreated(taskData, hasHeader)) {
            Snapshot.WithHeader(
                taskData.thumbnailData?.thumbnail!!,
                taskData.thumbnailData.rotation,
                taskData.backgroundColor,
                ThumbnailHeader(taskData.icon!!, taskData.titleDescription!!),
            )
        } else {
            Snapshot.WithoutHeader(
                taskData.thumbnailData?.thumbnail!!,
                taskData.thumbnailData.rotation,
                taskData.backgroundColor,
            )
        }

    private fun isBackgroundOnly(taskData: TaskData.Data) =
        taskData.isLocked || taskData.thumbnailData == null

    private fun isSnapshotSplash(taskData: TaskData.Data) =
        taskData.thumbnailData?.thumbnail != null && !taskData.isLocked

    private fun canHeaderBeCreated(taskData: TaskData.Data, hasHeader: Boolean) =
        hasHeader && taskData.icon != null && taskData.titleDescription != null

    private fun createLiveTileState(taskData: TaskData.Data, hasHeader: Boolean) =
        if (canHeaderBeCreated(taskData, hasHeader)) {
            // TODO(http://b/353965691): figure out what to do when `icon` or `titleDescription` is
            //  null.
            LiveTile.WithHeader(ThumbnailHeader(taskData.icon!!, taskData.titleDescription!!))
        } else LiveTile.WithoutHeader
}
