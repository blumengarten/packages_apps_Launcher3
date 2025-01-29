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
 * See the License for the specific language goveryning permissions and
 * limitations under the License.
 */

package com.android.quickstep.task.viewmodel

import android.app.ActivityTaskManager.INVALID_TASK_ID
import android.graphics.Matrix
import android.util.Log
import com.android.launcher3.util.coroutines.DispatcherProvider
import com.android.quickstep.recents.usecase.GetThumbnailPositionUseCase
import com.android.quickstep.recents.usecase.ThumbnailPositionState
import com.android.quickstep.task.thumbnail.SplashAlphaUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn

@OptIn(ExperimentalCoroutinesApi::class)
class TaskThumbnailViewModelImpl(
    dispatcherProvider: DispatcherProvider,
    private val getThumbnailPositionUseCase: GetThumbnailPositionUseCase,
    private val splashAlphaUseCase: SplashAlphaUseCase,
) : TaskThumbnailViewModel {
    private val splashProgress = MutableStateFlow(flowOf(0f))
    private var taskId: Int = INVALID_TASK_ID

    override val splashAlpha =
        splashProgress.flatMapLatest { it }.flowOn(dispatcherProvider.background)

    override fun bind(taskId: Int) {
        Log.d(TAG, "bind taskId: $taskId")
        this.taskId = taskId
        splashProgress.value = splashAlphaUseCase.execute(taskId)
    }

    override fun getThumbnailPositionState(width: Int, height: Int, isRtl: Boolean): Matrix =
        when (
            val thumbnailPositionState =
                getThumbnailPositionUseCase.run(taskId, width, height, isRtl)
        ) {
            is ThumbnailPositionState.MatrixScaling -> thumbnailPositionState.matrix
            is ThumbnailPositionState.MissingThumbnail -> Matrix.IDENTITY_MATRIX
        }

    private companion object {
        const val TAG = "TaskThumbnailViewModel"
    }
}
