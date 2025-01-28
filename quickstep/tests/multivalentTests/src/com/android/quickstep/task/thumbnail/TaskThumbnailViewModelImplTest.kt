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

package com.android.quickstep.task.thumbnail

import android.graphics.Matrix
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.launcher3.util.TestDispatcherProvider
import com.android.quickstep.recents.usecase.GetThumbnailPositionUseCase
import com.android.quickstep.recents.usecase.ThumbnailPositionState.MatrixScaling
import com.android.quickstep.recents.usecase.ThumbnailPositionState.MissingThumbnail
import com.android.quickstep.recents.viewmodel.RecentsViewData
import com.android.quickstep.task.viewmodel.TaskContainerData
import com.android.quickstep.task.viewmodel.TaskThumbnailViewModelImpl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Test for [TaskThumbnailView] */
@RunWith(AndroidJUnit4::class)
class TaskThumbnailViewModelImplTest {
    @get:Rule val setFlagsRule = SetFlagsRule()

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private val recentsViewData = RecentsViewData()
    private val taskContainerData = TaskContainerData()
    private val dispatcherProvider = TestDispatcherProvider(dispatcher)
    private val mGetThumbnailPositionUseCase = mock<GetThumbnailPositionUseCase>()
    private val splashAlphaUseCase: SplashAlphaUseCase = mock()

    private val systemUnderTest by lazy {
        TaskThumbnailViewModelImpl(
            recentsViewData,
            taskContainerData,
            dispatcherProvider,
            mGetThumbnailPositionUseCase,
            splashAlphaUseCase,
        )
    }

    @Test
    fun getSnapshotMatrix_MissingThumbnail() =
        testScope.runTest {
            val taskId = 2
            val isRtl = true

            whenever(mGetThumbnailPositionUseCase.run(taskId, CANVAS_WIDTH, CANVAS_HEIGHT, isRtl))
                .thenReturn(MissingThumbnail)

            systemUnderTest.bind(taskId)
            assertThat(
                    systemUnderTest.getThumbnailPositionState(CANVAS_WIDTH, CANVAS_HEIGHT, isRtl)
                )
                .isEqualTo(Matrix.IDENTITY_MATRIX)
        }

    @Test
    fun getSnapshotMatrix_MatrixScaling() =
        testScope.runTest {
            val taskId = 2
            val isRtl = true

            whenever(mGetThumbnailPositionUseCase.run(taskId, CANVAS_WIDTH, CANVAS_HEIGHT, isRtl))
                .thenReturn(MatrixScaling(MATRIX, isRotated = false))

            systemUnderTest.bind(taskId)
            assertThat(
                    systemUnderTest.getThumbnailPositionState(CANVAS_WIDTH, CANVAS_HEIGHT, isRtl)
                )
                .isEqualTo(MATRIX)
        }

    @Test
    fun getForegroundScrimDimProgress_returnsForegroundMaxScrim() =
        testScope.runTest {
            recentsViewData.tintAmount.value = 0.32f
            taskContainerData.taskMenuOpenProgress.value = 0f
            assertThat(systemUnderTest.dimProgress.first()).isEqualTo(0.32f)
        }

    @Test
    fun getTaskMenuScrimDimProgress_returnsTaskMenuScrim() =
        testScope.runTest {
            recentsViewData.tintAmount.value = 0f
            taskContainerData.taskMenuOpenProgress.value = 1f
            assertThat(systemUnderTest.dimProgress.first()).isEqualTo(0.4f)
        }

    @Test
    fun getForegroundScrimDimProgress_returnsNoScrim() =
        testScope.runTest {
            recentsViewData.tintAmount.value = 0f
            taskContainerData.taskMenuOpenProgress.value = 0f
            assertThat(systemUnderTest.dimProgress.first()).isEqualTo(0f)
        }

    private companion object {
        const val CANVAS_WIDTH = 300
        const val CANVAS_HEIGHT = 600
        val MATRIX =
            Matrix().apply {
                setValues(floatArrayOf(2.3f, 4.5f, 2.6f, 7.4f, 3.4f, 2.3f, 2.5f, 6.0f, 3.4f))
            }
    }
}
