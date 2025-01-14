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

import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.launcher3.Flags
import com.android.launcher3.util.TestDispatcherProvider
import com.android.quickstep.recents.data.FakeRecentsDeviceProfileRepository
import com.android.quickstep.recents.data.FakeTasksRepository
import com.android.quickstep.recents.data.RecentsDeviceProfile
import com.android.quickstep.recents.usecase.GetThumbnailPositionUseCase
import com.android.quickstep.recents.usecase.ThumbnailPositionState.MatrixScaling
import com.android.quickstep.recents.usecase.ThumbnailPositionState.MissingThumbnail
import com.android.quickstep.recents.viewmodel.RecentsViewData
import com.android.quickstep.task.thumbnail.TaskThumbnailUiState.BackgroundOnly
import com.android.quickstep.task.thumbnail.TaskThumbnailUiState.LiveTile
import com.android.quickstep.task.thumbnail.TaskThumbnailUiState.Snapshot
import com.android.quickstep.task.thumbnail.TaskThumbnailUiState.SnapshotSplash
import com.android.quickstep.task.thumbnail.TaskThumbnailUiState.ThumbnailHeader
import com.android.quickstep.task.thumbnail.TaskThumbnailUiState.Uninitialized
import com.android.quickstep.task.viewmodel.TaskContainerData
import com.android.quickstep.task.viewmodel.TaskThumbnailViewModelImpl
import com.android.systemui.shared.recents.model.Task
import com.android.systemui.shared.recents.model.ThumbnailData
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
    private val tasksRepository = FakeTasksRepository()
    private val deviceProfileRepository = FakeRecentsDeviceProfileRepository()
    private val mGetThumbnailPositionUseCase = mock<GetThumbnailPositionUseCase>()
    private val splashAlphaUseCase: SplashAlphaUseCase = mock()

    private val systemUnderTest by lazy {
        TaskThumbnailViewModelImpl(
            recentsViewData,
            taskContainerData,
            dispatcherProvider,
            tasksRepository,
            deviceProfileRepository,
            mGetThumbnailPositionUseCase,
            splashAlphaUseCase,
        )
    }

    private val fullscreenTaskIdRange: IntRange = 0..5
    private val freeformTaskIdRange: IntRange = 6..10

    private val fullscreenTasks = fullscreenTaskIdRange.map(::createTaskWithId)
    private val freeformTasks = freeformTaskIdRange.map(::createFreeformTaskWithId)
    private val tasks = fullscreenTasks + freeformTasks

    @Test
    fun initialStateIsUninitialized() =
        testScope.runTest { assertThat(systemUnderTest.uiState.first()).isEqualTo(Uninitialized) }

    @Test
    fun bindRunningTask_thenStateIs_LiveTile() =
        testScope.runTest {
            val taskId = 1
            tasksRepository.seedTasks(tasks)
            tasksRepository.setVisibleTasks(setOf(taskId))
            recentsViewData.runningTaskIds.value = setOf(taskId)
            systemUnderTest.bind(taskId)

            assertThat(systemUnderTest.uiState.first()).isEqualTo(LiveTile.WithoutHeader)
        }

    @Test
    fun bindRunningTaskShouldShowScreenshot_thenStateIs_SnapshotSplash() =
        testScope.runTest {
            val taskId = 1
            val expectedThumbnailData = createThumbnailData()
            tasksRepository.seedThumbnailData(mapOf(taskId to expectedThumbnailData))
            val expectedIconData = mock<Drawable>()
            tasksRepository.seedIconData(taskId, "Task $taskId", "", expectedIconData)
            tasksRepository.seedTasks(tasks)
            tasksRepository.setVisibleTasks(setOf(taskId))
            recentsViewData.runningTaskIds.value = setOf(taskId)
            recentsViewData.runningTaskShowScreenshot.value = true
            systemUnderTest.bind(taskId)

            assertThat(systemUnderTest.uiState.first())
                .isEqualTo(
                    SnapshotSplash(
                        Snapshot.WithoutHeader(
                            backgroundColor = Color.rgb(1, 1, 1),
                            bitmap = expectedThumbnailData.thumbnail!!,
                            thumbnailRotation = Surface.ROTATION_0,
                        ),
                        expectedIconData,
                    )
                )
        }

    @Test
    fun bindRunningTaskThenStoppedTaskWithoutThumbnail_thenStateChangesToBackgroundOnly() =
        testScope.runTest {
            val runningTaskId = 1
            val stoppedTaskId = 2
            tasksRepository.seedTasks(tasks)
            tasksRepository.setVisibleTasks(setOf(runningTaskId, stoppedTaskId))
            recentsViewData.runningTaskIds.value = setOf(runningTaskId)
            systemUnderTest.bind(runningTaskId)
            assertThat(systemUnderTest.uiState.first()).isEqualTo(LiveTile.WithoutHeader)

            systemUnderTest.bind(stoppedTaskId)
            assertThat(systemUnderTest.uiState.first())
                .isEqualTo(BackgroundOnly(backgroundColor = Color.rgb(2, 2, 2)))
        }

    @Test
    fun bindStoppedTaskWithoutThumbnail_thenStateIs_BackgroundOnly_withAlphaRemoved() =
        testScope.runTest {
            val stoppedTaskId = 2
            tasksRepository.seedTasks(tasks)
            tasksRepository.setVisibleTasks(setOf(stoppedTaskId))

            systemUnderTest.bind(stoppedTaskId)
            assertThat(systemUnderTest.uiState.first())
                .isEqualTo(BackgroundOnly(backgroundColor = Color.rgb(2, 2, 2)))
        }

    @Test
    fun bindLockedTaskWithThumbnail_thenStateIs_BackgroundOnly() =
        testScope.runTest {
            val taskId = 2
            tasksRepository.seedThumbnailData(mapOf(taskId to createThumbnailData()))
            tasks[taskId].isLocked = true
            tasksRepository.seedTasks(tasks)
            tasksRepository.setVisibleTasks(setOf(taskId))

            systemUnderTest.bind(taskId)
            assertThat(systemUnderTest.uiState.first())
                .isEqualTo(BackgroundOnly(backgroundColor = Color.rgb(2, 2, 2)))
        }

    @Test
    fun bindStoppedTaskWithThumbnail_thenStateIs_SnapshotSplash_withAlphaRemoved() =
        testScope.runTest {
            val taskId = 2
            val expectedThumbnailData = createThumbnailData(rotation = Surface.ROTATION_270)
            tasksRepository.seedThumbnailData(mapOf(taskId to expectedThumbnailData))
            val expectedIconData = mock<Drawable>()
            tasksRepository.seedIconData(taskId, "Task $taskId", "", expectedIconData)
            tasksRepository.seedTasks(tasks)
            tasksRepository.setVisibleTasks(setOf(taskId))

            systemUnderTest.bind(taskId)
            assertThat(systemUnderTest.uiState.first())
                .isEqualTo(
                    SnapshotSplash(
                        Snapshot.WithoutHeader(
                            backgroundColor = Color.rgb(2, 2, 2),
                            bitmap = expectedThumbnailData.thumbnail!!,
                            thumbnailRotation = Surface.ROTATION_270,
                        ),
                        expectedIconData,
                    )
                )
        }

    @Test
    fun bindNonVisibleStoppedTask_whenMadeVisible_thenStateIsSnapshotSplash() =
        testScope.runTest {
            val taskId = 2
            val expectedThumbnailData = createThumbnailData()
            tasksRepository.seedThumbnailData(mapOf(taskId to expectedThumbnailData))
            val expectedIconData = mock<Drawable>()
            tasksRepository.seedIconData(taskId, "Task $taskId", "", expectedIconData)
            tasksRepository.seedTasks(tasks)

            systemUnderTest.bind(taskId)
            assertThat(systemUnderTest.uiState.first()).isEqualTo(Uninitialized)

            tasksRepository.setVisibleTasks(setOf(taskId))
            assertThat(systemUnderTest.uiState.first())
                .isEqualTo(
                    SnapshotSplash(
                        Snapshot.WithoutHeader(
                            backgroundColor = Color.rgb(2, 2, 2),
                            bitmap = expectedThumbnailData.thumbnail!!,
                            thumbnailRotation = Surface.ROTATION_0,
                        ),
                        expectedIconData,
                    )
                )
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_EXPLODED_VIEW)
    fun bindRunningTask_inDesktop_thenStateIs_LiveTile_withHeader() =
        testScope.runTest {
            deviceProfileRepository.setRecentsDeviceProfile(
                RecentsDeviceProfile(isLargeScreen = true, canEnterDesktopMode = true)
            )

            val taskId = freeformTaskIdRange.first
            val expectedIconData = mock<Drawable>()
            tasksRepository.seedIconData(taskId, "Task $taskId", "Task $taskId", expectedIconData)
            tasksRepository.seedTasks(freeformTasks)
            tasksRepository.setVisibleTasks(setOf(taskId))
            recentsViewData.runningTaskIds.value = setOf(taskId)
            systemUnderTest.bind(taskId)

            assertThat(systemUnderTest.uiState.first())
                .isEqualTo(LiveTile.WithHeader(ThumbnailHeader(expectedIconData, "Task $taskId")))
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_EXPLODED_VIEW)
    fun bindStoppedTaskWithThumbnail_inDesktop_thenStateIs_SnapshotSplash_withHeader() =
        testScope.runTest {
            deviceProfileRepository.setRecentsDeviceProfile(
                RecentsDeviceProfile(isLargeScreen = true, canEnterDesktopMode = true)
            )

            val taskId = freeformTaskIdRange.first
            val expectedThumbnailData = createThumbnailData(rotation = Surface.ROTATION_0)
            tasksRepository.seedThumbnailData(mapOf(taskId to expectedThumbnailData))
            val expectedIconData = mock<Drawable>()
            tasksRepository.seedIconData(taskId, "Task $taskId", "Task $taskId", expectedIconData)
            tasksRepository.seedTasks(freeformTasks)
            tasksRepository.setVisibleTasks(setOf(taskId))

            systemUnderTest.bind(taskId)
            assertThat(systemUnderTest.uiState.first())
                .isEqualTo(
                    SnapshotSplash(
                        Snapshot.WithHeader(
                            backgroundColor = Color.rgb(taskId, taskId, taskId),
                            bitmap = expectedThumbnailData.thumbnail!!,
                            thumbnailRotation = Surface.ROTATION_0,
                            header = ThumbnailHeader(expectedIconData, "Task $taskId"),
                        ),
                        expectedIconData,
                    )
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

    private fun createTaskWithId(taskId: Int) =
        Task(
                Task.TaskKey(
                    taskId,
                    WINDOWING_MODE_FULLSCREEN,
                    Intent(),
                    ComponentName("", ""),
                    0,
                    2000,
                )
            )
            .apply {
                colorBackground = Color.argb(taskId, taskId, taskId, taskId)
                titleDescription = "Task $taskId"
                icon = mock<Drawable>()
            }

    private fun createFreeformTaskWithId(taskId: Int) =
        Task(
                Task.TaskKey(
                    taskId,
                    WINDOWING_MODE_FREEFORM,
                    Intent(),
                    ComponentName("", ""),
                    0,
                    2000,
                )
            )
            .apply {
                colorBackground = Color.argb(taskId, taskId, taskId, taskId)
                titleDescription = "Task $taskId"
                icon = mock<Drawable>()
            }

    private fun createThumbnailData(rotation: Int = Surface.ROTATION_0): ThumbnailData {
        val bitmap = mock<Bitmap>()
        whenever(bitmap.width).thenReturn(THUMBNAIL_WIDTH)
        whenever(bitmap.height).thenReturn(THUMBNAIL_HEIGHT)

        return ThumbnailData(thumbnail = bitmap, rotation = rotation)
    }

    companion object {
        const val THUMBNAIL_WIDTH = 100
        const val THUMBNAIL_HEIGHT = 200
        const val CANVAS_WIDTH = 300
        const val CANVAS_HEIGHT = 600
        val MATRIX =
            Matrix().apply {
                setValues(floatArrayOf(2.3f, 4.5f, 2.6f, 7.4f, 3.4f, 2.3f, 2.5f, 6.0f, 3.4f))
            }
    }
}
