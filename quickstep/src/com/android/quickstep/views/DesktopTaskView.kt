/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.quickstep.views

import android.animation.Animator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewStub
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.updateLayoutParams
import com.android.launcher3.Flags.enableDesktopExplodedView
import com.android.launcher3.Flags.enableOverviewIconMenu
import com.android.launcher3.Flags.enableRefactorTaskThumbnail
import com.android.launcher3.R
import com.android.launcher3.anim.AnimatedFloat
import com.android.launcher3.testing.TestLogging
import com.android.launcher3.testing.shared.TestProtocol
import com.android.launcher3.util.RunnableList
import com.android.launcher3.util.SplitConfigurationOptions
import com.android.launcher3.util.TransformingTouchDelegate
import com.android.launcher3.util.ViewPool
import com.android.launcher3.util.rects.lerpRect
import com.android.launcher3.util.rects.set
import com.android.quickstep.BaseContainerInterface
import com.android.quickstep.DesktopFullscreenDrawParams
import com.android.quickstep.FullscreenDrawParams
import com.android.quickstep.RemoteTargetGluer.RemoteTargetHandle
import com.android.quickstep.TaskOverlayFactory
import com.android.quickstep.ViewUtils
import com.android.quickstep.recents.di.RecentsDependencies
import com.android.quickstep.recents.di.get
import com.android.quickstep.recents.domain.model.DesktopTaskBoundsData
import com.android.quickstep.recents.ui.viewmodel.DesktopTaskViewModel
import com.android.quickstep.recents.ui.viewmodel.TaskData
import com.android.quickstep.task.thumbnail.TaskThumbnailView
import com.android.quickstep.util.RecentsOrientedState
import com.android.systemui.shared.recents.model.Task

/** TaskView that contains all tasks that are part of the desktop. */
class DesktopTaskView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    TaskView(
        context,
        attrs,
        type = TaskViewType.DESKTOP,
        thumbnailFullscreenParams = DesktopFullscreenDrawParams(context),
    ) {
    private val contentViewFullscreenParams = FullscreenDrawParams(context)

    private val taskThumbnailViewDeprecatedPool =
        if (!enableRefactorTaskThumbnail()) {
            ViewPool<TaskThumbnailViewDeprecated>(
                context,
                this,
                R.layout.task_thumbnail_deprecated,
                VIEW_POOL_MAX_SIZE,
                VIEW_POOL_INITIAL_SIZE,
            )
        } else null

    private val taskThumbnailViewPool =
        if (enableRefactorTaskThumbnail()) {
            ViewPool<TaskThumbnailView>(
                context,
                this,
                R.layout.task_thumbnail,
                VIEW_POOL_MAX_SIZE,
                VIEW_POOL_INITIAL_SIZE,
            )
        } else null

    private val tempPointF = PointF()
    private val lastComputedTaskSize = Rect()
    private lateinit var iconView: TaskViewIcon
    private lateinit var contentView: DesktopTaskContentView
    private lateinit var backgroundView: View

    private var viewModel: DesktopTaskViewModel? = null

    /**
     * Holds the default (user placed) positions of task windows. This can be moved into the
     * viewModel once RefactorTaskThumbnail has been launched.
     */
    private var defaultTaskPositions: List<DesktopTaskBoundsData> = emptyList()

    /**
     * When enableDesktopExplodedView is enabled, this controls the gradual transition from the
     * default positions to the organized non-overlapping positions.
     */
    var explodeProgress = 0.0f
        set(value) {
            field = value
            positionTaskWindows()
        }

    var remoteTargetHandles: Array<RemoteTargetHandle>? = null
        set(value) {
            field = value
            positionTaskWindows()
        }

    private fun getRemoteTargetHandle(taskId: Int): RemoteTargetHandle? =
        remoteTargetHandles?.firstOrNull {
            it.transformParams.targetSet.firstAppTargetTaskId == taskId
        }

    override fun onFinishInflate() {
        super.onFinishInflate()
        iconView =
            (findViewById<View>(R.id.icon) as TaskViewIcon).apply {
                setIcon(
                    this,
                    ResourcesCompat.getDrawable(
                        context.resources,
                        R.drawable.ic_desktop_with_bg,
                        context.theme,
                    ),
                )
                setText(resources.getText(R.string.recent_task_desktop))
            }
        contentView =
            findViewById<DesktopTaskContentView>(R.id.desktop_content).apply {
                updateLayoutParams<LayoutParams> {
                    topMargin = container.deviceProfile.overviewTaskThumbnailTopMarginPx
                }
                cornerRadius = contentViewFullscreenParams.currentCornerRadius
                backgroundView = findViewById(R.id.background)
                backgroundView.setBackgroundColor(
                    resources.getColor(android.R.color.system_neutral2_300, context.theme)
                )
            }
    }

    override fun inflateViewStubs() {
        findViewById<ViewStub>(R.id.icon)
            ?.apply {
                layoutResource =
                    if (enableOverviewIconMenu()) R.layout.icon_app_chip_view
                    else R.layout.icon_view
            }
            ?.inflate()
    }

    fun startWindowExplodeAnimation(): Animator =
        AnimatedFloat { progress -> explodeProgress = progress }.animateToValue(0.0f, 1.0f)

    private fun positionTaskWindows() {
        if (taskContainers.isEmpty()) {
            return
        }

        val thumbnailTopMarginPx = container.deviceProfile.overviewTaskThumbnailTopMarginPx

        val containerWidth = layoutParams.width
        val containerHeight = layoutParams.height - thumbnailTopMarginPx

        BaseContainerInterface.getTaskDimension(mContext, container.deviceProfile, tempPointF)

        val windowWidth = tempPointF.x.toInt()
        val windowHeight = tempPointF.y.toInt()
        val scaleWidth = containerWidth / windowWidth.toFloat()
        val scaleHeight = containerHeight / windowHeight.toFloat()

        taskContainers.forEach {
            val taskId = it.task.key.id
            val defaultPosition = defaultTaskPositions.firstOrNull { it.taskId == taskId } ?: return
            val position =
                if (enableDesktopExplodedView()) {
                    viewModel!!
                        .organizedDesktopTaskPositions
                        .firstOrNull { it.taskId == taskId }
                        ?.let { organizedPosition ->
                            TEMP_RECT.apply {
                                lerpRect(
                                    defaultPosition.bounds,
                                    organizedPosition.bounds,
                                    explodeProgress,
                                )
                            }
                        } ?: defaultPosition.bounds
                } else {
                    defaultPosition.bounds
                }

            if (enableDesktopExplodedView()) {
                getRemoteTargetHandle(taskId)?.let { remoteTargetHandle ->
                    val fromRect =
                        TEMP_RECTF1.apply {
                            set(defaultPosition.bounds)
                            scale(scaleWidth)
                            offset(
                                lastComputedTaskSize.left.toFloat(),
                                lastComputedTaskSize.top.toFloat(),
                            )
                        }
                    val toRect =
                        TEMP_RECTF2.apply {
                            set(position)
                            scale(scaleWidth)
                            offset(
                                lastComputedTaskSize.left.toFloat(),
                                lastComputedTaskSize.top.toFloat(),
                            )
                        }
                    val transform = Matrix()
                    transform.setRectToRect(fromRect, toRect, Matrix.ScaleToFit.FILL)
                    remoteTargetHandle.taskViewSimulator.setTaskRectTransform(transform)
                    remoteTargetHandle.taskViewSimulator.apply(remoteTargetHandle.transformParams)
                }
            }

            val taskLeft = position.left * scaleWidth
            val taskTop = position.top * scaleHeight
            val taskWidth = position.width() * scaleWidth
            val taskHeight = position.height() * scaleHeight
            // TODO(b/394660950): Revisit the choice to update the layout when explodeProgress == 1.
            // To run the explode animation in reverse, it may be simpler to use translation/scale
            // for all cases where the progress is non-zero.
            if (explodeProgress == 0.0f || explodeProgress == 1.0f) {
                // Reset scaling and translation that may have been applied during animation.
                it.snapshotView.apply {
                    scaleX = 1.0f
                    scaleY = 1.0f
                    translationX = 0.0f
                    translationY = 0.0f
                }

                // Position the task to the same position as it would be on the desktop
                it.snapshotView.updateLayoutParams<LayoutParams> {
                    gravity = Gravity.LEFT or Gravity.TOP
                    width = taskWidth.toInt()
                    height = taskHeight.toInt()
                    leftMargin = taskLeft.toInt()
                    topMargin = taskTop.toInt()
                }
            } else {
                // During the animation, apply translation and scale such that the view is
                // transformed to where we want, without triggering layout.
                it.snapshotView.apply {
                    pivotX = 0.0f
                    pivotY = 0.0f
                    translationX = taskLeft - left
                    translationY = taskTop - top
                    scaleX = taskWidth / width.toFloat()
                    scaleY = taskHeight / height.toFloat()
                }
            }
        }
    }

    /** Updates this desktop task to the gives task list defined in `tasks` */
    fun bind(
        tasks: List<Task>,
        orientedState: RecentsOrientedState,
        taskOverlayFactory: TaskOverlayFactory,
    ) {
        if (DEBUG) {
            val sb = StringBuilder()
            sb.append("bind tasks=").append(tasks.size).append("\n")
            tasks.forEach { sb.append(" key=${it.key}\n") }
            Log.d(TAG, sb.toString())
        }

        cancelPendingLoadTasks()
        val backgroundViewIndex = contentView.indexOfChild(backgroundView)
        taskContainers =
            tasks.map { task ->
                val snapshotView =
                    if (enableRefactorTaskThumbnail()) {
                        taskThumbnailViewPool!!.view
                    } else {
                        taskThumbnailViewDeprecatedPool!!.view
                    }
                contentView.addView(snapshotView, backgroundViewIndex + 1)

                TaskContainer(
                    this,
                    task,
                    snapshotView,
                    iconView,
                    TransformingTouchDelegate(iconView.asView()),
                    SplitConfigurationOptions.STAGE_POSITION_UNDEFINED,
                    digitalWellBeingToast = null,
                    showWindowsView = null,
                    taskOverlayFactory,
                )
            }
        onBind(orientedState)
    }

    override fun onBind(orientedState: RecentsOrientedState) {
        super.onBind(orientedState)

        if (enableRefactorTaskThumbnail()) {
            viewModel =
                DesktopTaskViewModel(organizeDesktopTasksUseCase = RecentsDependencies.get())
        }
    }

    override fun onRecycle() {
        super.onRecycle()
        explodeProgress = 0.0f
        viewModel = null
        visibility = VISIBLE
        taskContainers.forEach {
            contentView.removeView(it.snapshotView)
            if (enableRefactorTaskThumbnail()) {
                taskThumbnailViewPool!!.recycle(it.thumbnailView)
            } else {
                taskThumbnailViewDeprecatedPool!!.recycle(it.thumbnailViewDeprecated)
            }
        }
    }

    @SuppressLint("RtlHardcoded")
    override fun updateTaskSize(lastComputedTaskSize: Rect, lastComputedGridTaskSize: Rect) {
        super.updateTaskSize(lastComputedTaskSize, lastComputedGridTaskSize)
        this.lastComputedTaskSize.set(lastComputedTaskSize)

        BaseContainerInterface.getTaskDimension(mContext, container.deviceProfile, tempPointF)
        val desktopSize = Size(tempPointF.x.toInt(), tempPointF.y.toInt())
        DEFAULT_BOUNDS.set(0, 0, desktopSize.width / 4, desktopSize.height / 4)

        defaultTaskPositions =
            taskContainers.map {
                DesktopTaskBoundsData(it.task.key.id, it.task.appBounds ?: DEFAULT_BOUNDS)
            }

        if (enableDesktopExplodedView()) {
            viewModel?.organizeDesktopTasks(desktopSize, defaultTaskPositions)
        }
        positionTaskWindows()
    }

    override fun onTaskListVisibilityChanged(visible: Boolean, changes: Int) {
        super.onTaskListVisibilityChanged(visible, changes)
        if (needsUpdate(changes, FLAG_UPDATE_CORNER_RADIUS)) {
            contentViewFullscreenParams.updateCornerRadius(context)
        }
    }

    override fun onIconLoaded(taskContainer: TaskContainer) {
        // Update contentDescription of snapshotView only, individual task icon is unused.
        taskContainer.snapshotView.contentDescription = taskContainer.task.titleDescription
    }

    override fun setIconState(container: TaskContainer, state: TaskData?) {
        container.snapshotView.contentDescription = (state as? TaskData.Data)?.titleDescription
    }

    // Ignoring [onIconUnloaded] as all tasks shares the same Desktop icon
    override fun onIconUnloaded(taskContainer: TaskContainer) {}

    // thumbnailView is laid out differently and is handled in onMeasure
    override fun updateThumbnailSize() {}

    override fun getThumbnailBounds(bounds: Rect, relativeToDragLayer: Boolean) {
        if (relativeToDragLayer) {
            container.dragLayer.getDescendantRectRelativeToSelf(contentView, bounds)
        } else {
            bounds.set(contentView)
        }
    }

    private fun launchTaskWithDesktopController(animated: Boolean): RunnableList? {
        val recentsView = recentsView ?: return null
        TestLogging.recordEvent(
            TestProtocol.SEQUENCE_MAIN,
            "launchDesktopFromRecents",
            taskIds.contentToString(),
        )
        val endCallback = RunnableList()
        val desktopController = recentsView.desktopRecentsController
        checkNotNull(desktopController) { "recentsController is null" }
        desktopController.launchDesktopFromRecents(this, animated) {
            endCallback.executeAllAndDestroy()
        }
        Log.d(
            TAG,
            "launchTaskWithDesktopController: ${taskIds.contentToString()}, withRemoteTransition: $animated",
        )

        // Callbacks get run from recentsView for case when recents animation already running
        recentsView.addSideTaskLaunchCallback(endCallback)
        return endCallback
    }

    override fun launchAsStaticTile() = launchTaskWithDesktopController(animated = true)

    override fun launchWithoutAnimation(
        isQuickSwitch: Boolean,
        callback: (launched: Boolean) -> Unit,
    ) = launchTaskWithDesktopController(animated = false)?.add { callback(true) } ?: callback(false)

    // Return true when Task cannot be launched as fullscreen (i.e. in split select state) to skip
    // putting DesktopTaskView to split as it's not supported.
    override fun confirmSecondSplitSelectApp(): Boolean =
        recentsView?.canLaunchFullscreenTask() != true

    // TODO(b/330685808) support overlay for Screenshot action
    override fun setOverlayEnabled(overlayEnabled: Boolean) {}

    override fun onFullscreenProgressChanged(fullscreenProgress: Float) {
        backgroundView.alpha = 1 - fullscreenProgress
    }

    override fun updateFullscreenParams() {
        super.updateFullscreenParams()
        updateFullscreenParams(contentViewFullscreenParams)
        contentView.cornerRadius = contentViewFullscreenParams.currentCornerRadius
    }

    override fun addChildrenForAccessibility(outChildren: ArrayList<View>) {
        super.addChildrenForAccessibility(outChildren)
        ViewUtils.addAccessibleChildToList(backgroundView, outChildren)
    }

    companion object {
        private const val TAG = "DesktopTaskView"
        private const val DEBUG = false
        private const val VIEW_POOL_MAX_SIZE = 5

        // As DesktopTaskView is inflated in background, use initialSize=0 to avoid initPool.
        private const val VIEW_POOL_INITIAL_SIZE = 0
        private val DEFAULT_BOUNDS = Rect()
        // Temporaries used for various purposes to avoid allocations.
        private val TEMP_RECT = Rect()
        private val TEMP_RECTF1 = RectF()
        private val TEMP_RECTF2 = RectF()
    }
}
