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

import android.graphics.Color
import android.graphics.drawable.ShapeDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.launcher3.util.TestDispatcherProvider
import com.android.quickstep.recents.domain.model.TaskModel
import com.android.quickstep.recents.domain.usecase.GetTaskUseCase
import com.android.quickstep.recents.viewmodel.RecentsViewData
import com.android.systemui.shared.recents.model.ThumbnailData
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TaskViewModelTest {
    private val unconfinedTestDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(unconfinedTestDispatcher)

    private val recentsViewData = RecentsViewData()
    private val getTaskUseCase = mock<GetTaskUseCase>()
    private val sut =
        TaskViewModel(
            recentsViewData = recentsViewData,
            getTaskUseCase = getTaskUseCase,
            dispatcherProvider = TestDispatcherProvider(unconfinedTestDispatcher),
        )

    @Before
    fun setUp() {
        whenever(getTaskUseCase.invoke(TASK_MODEL_1.id)).thenReturn(flow { emit(TASK_MODEL_1) })
        whenever(getTaskUseCase.invoke(TASK_MODEL_2.id)).thenReturn(flow { emit(TASK_MODEL_2) })
        whenever(getTaskUseCase.invoke(TASK_MODEL_3.id)).thenReturn(flow { emit(TASK_MODEL_3) })
        whenever(getTaskUseCase.invoke(INVALID_TASK_ID)).thenReturn(flow { emit(null) })
        recentsViewData.runningTaskIds.value = emptySet()
    }

    @Test
    fun singleTaskRetrieved_when_validTaskId() =
        testScope.runTest {
            sut.bind(TASK_MODEL_1.id)
            val expectedResult = TaskTileUiState(listOf(TASK_MODEL_1.toUiState()), false)
            assertThat(sut.state.first()).isEqualTo(expectedResult)
        }

    @Test
    fun multipleTasksRetrieved_when_validTaskIds() =
        testScope.runTest {
            sut.bind(TASK_MODEL_1.id, TASK_MODEL_2.id, TASK_MODEL_3.id, INVALID_TASK_ID)
            val expectedResult =
                TaskTileUiState(
                    tasks =
                        listOf(
                            TASK_MODEL_1.toUiState(),
                            TASK_MODEL_2.toUiState(),
                            TASK_MODEL_3.toUiState(),
                            TaskData.NoData(INVALID_TASK_ID),
                        ),
                    isLiveTile = false,
                )
            assertThat(sut.state.first()).isEqualTo(expectedResult)
        }

    @Test
    fun isLiveTile_when_runningTasksMatchTasks() =
        testScope.runTest {
            recentsViewData.runningTaskShowScreenshot.value = false
            recentsViewData.runningTaskIds.value =
                setOf(TASK_MODEL_1.id, TASK_MODEL_2.id, TASK_MODEL_3.id)
            sut.bind(TASK_MODEL_1.id, TASK_MODEL_2.id, TASK_MODEL_3.id)
            val expectedResult =
                TaskTileUiState(
                    tasks =
                        listOf(
                            TASK_MODEL_1.toUiState(),
                            TASK_MODEL_2.toUiState(),
                            TASK_MODEL_3.toUiState(),
                        ),
                    isLiveTile = true,
                )
            assertThat(sut.state.first()).isEqualTo(expectedResult)
        }

    @Test
    fun isNotLiveTile_when_runningTaskShowScreenshotIsTrue() =
        testScope.runTest {
            recentsViewData.runningTaskShowScreenshot.value = true
            recentsViewData.runningTaskIds.value =
                setOf(TASK_MODEL_1.id, TASK_MODEL_2.id, TASK_MODEL_3.id)
            sut.bind(TASK_MODEL_1.id, TASK_MODEL_2.id, TASK_MODEL_3.id)
            val expectedResult =
                TaskTileUiState(
                    tasks =
                        listOf(
                            TASK_MODEL_1.toUiState(),
                            TASK_MODEL_2.toUiState(),
                            TASK_MODEL_3.toUiState(),
                        ),
                    isLiveTile = false,
                )
            assertThat(sut.state.first()).isEqualTo(expectedResult)
        }

    @Test
    fun isNotLiveTile_when_runningTasksMatchPartialTasks_lessRunningTasks() =
        testScope.runTest {
            recentsViewData.runningTaskShowScreenshot.value = false
            recentsViewData.runningTaskIds.value = setOf(TASK_MODEL_1.id, TASK_MODEL_2.id)
            sut.bind(TASK_MODEL_1.id, TASK_MODEL_2.id, TASK_MODEL_3.id)
            val expectedResult =
                TaskTileUiState(
                    tasks =
                        listOf(
                            TASK_MODEL_1.toUiState(),
                            TASK_MODEL_2.toUiState(),
                            TASK_MODEL_3.toUiState(),
                        ),
                    isLiveTile = false,
                )
            assertThat(sut.state.first()).isEqualTo(expectedResult)
        }

    @Test
    fun isNotLiveTile_when_runningTasksMatchPartialTasks_moreRunningTasks() =
        testScope.runTest {
            recentsViewData.runningTaskShowScreenshot.value = false
            recentsViewData.runningTaskIds.value =
                setOf(TASK_MODEL_1.id, TASK_MODEL_2.id, TASK_MODEL_3.id)
            sut.bind(TASK_MODEL_1.id, TASK_MODEL_2.id)
            val expectedResult =
                TaskTileUiState(
                    tasks = listOf(TASK_MODEL_1.toUiState(), TASK_MODEL_2.toUiState()),
                    isLiveTile = false,
                )
            assertThat(sut.state.first()).isEqualTo(expectedResult)
        }

    @Test
    fun noDataAvailable_when_InvalidTaskId() =
        testScope.runTest {
            sut.bind(INVALID_TASK_ID)
            val expectedResult =
                TaskTileUiState(listOf(TaskData.NoData(INVALID_TASK_ID)), isLiveTile = false)
            assertThat(sut.state.first()).isEqualTo(expectedResult)
        }

    private fun TaskModel.toUiState() =
        TaskData.Data(
            taskId = id,
            title = title,
            icon = icon!!,
            thumbnailData = thumbnail,
            backgroundColor = backgroundColor,
            isLocked = isLocked,
        )

    companion object {
        const val INVALID_TASK_ID = -1
        val TASK_MODEL_1 =
            TaskModel(
                1,
                "Title 1",
                "Content Description 1",
                ShapeDrawable(),
                ThumbnailData(),
                Color.BLACK,
                false,
            )
        val TASK_MODEL_2 =
            TaskModel(
                2,
                "Title 2",
                "Content Description 2",
                ShapeDrawable(),
                ThumbnailData(),
                Color.RED,
                true,
            )
        val TASK_MODEL_3 =
            TaskModel(
                3,
                "Title 3",
                "Content Description 3",
                ShapeDrawable(),
                ThumbnailData(),
                Color.BLUE,
                false,
            )
    }
}
