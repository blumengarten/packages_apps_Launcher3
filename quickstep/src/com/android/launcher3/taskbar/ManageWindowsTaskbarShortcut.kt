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

package com.android.launcher3.taskbar

import android.content.Context
import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.View
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.popup.SystemShortcut
import com.android.launcher3.taskbar.TaskbarAutohideSuspendController.FLAG_AUTOHIDE_SUSPEND_MULTI_INSTANCE_MENU_OPEN
import com.android.launcher3.taskbar.overlay.TaskbarOverlayContext
import com.android.launcher3.util.Themes
import com.android.launcher3.util.TouchController
import com.android.launcher3.views.ActivityContext
import com.android.quickstep.RecentsModel
import com.android.quickstep.SystemUiProxy
import com.android.quickstep.util.GroupTask
import com.android.systemui.shared.recents.model.ThumbnailData
import com.android.wm.shell.shared.desktopmode.ManageWindowsViewContainer
import java.util.Collections
import java.util.function.Predicate

/**
 * A single menu item shortcut to execute displaying open instances of an app. Default interaction
 * for [onClick] is to open the menu in a floating window. Touching one of the displayed tasks
 * launches it.
 */
class ManageWindowsTaskbarShortcut<T>(
    private val target: T,
    private val itemInfo: ItemInfo?,
    private val originalView: View?,
    private val controllers: TaskbarControllers,
) :
    SystemShortcut<T>(
        R.drawable.desktop_mode_ic_taskbar_menu_manage_windows,
        R.string.manage_windows_option_taskbar,
        target,
        itemInfo,
        originalView,
    ) where T : Context?, T : ActivityContext? {
    private lateinit var taskbarShortcutAllWindowsView: TaskbarShortcutManageWindowsView
    private val recentsModel = RecentsModel.INSTANCE[controllers.taskbarActivityContext]

    override fun onClick(v: View?) {
        val filter =
            Predicate<GroupTask> { task: GroupTask? ->
                task != null && task.task1.key.packageName == itemInfo?.getTargetPackage()
            }
        recentsModel.getTasks(
            { tasks: List<GroupTask> ->
                // Since fetching thumbnails is asynchronous, use this set to gate until the tasks
                // are ready to display
                val pendingTaskIds =
                    Collections.synchronizedSet(tasks.map { it.task1.key.id }.toMutableSet())
                createAndShowTaskShortcutView(tasks, pendingTaskIds)
            },
            filter,
        )
    }

    /**
     * Processes a list of tasks to generate thumbnails and create a taskbar shortcut view.
     *
     * Iterates through the tasks, retrieves thumbnails, and adds them to a list. When all
     * thumbnails are processed, it creates a [TaskbarShortcutManageWindowsView] with the collected
     * thumbnails and positions it appropriately.
     */
    private fun createAndShowTaskShortcutView(
        tasks: List<GroupTask?>,
        pendingTaskIds: MutableSet<Int>,
    ) {
        val taskList = arrayListOf<Pair<Int, Bitmap?>>()
        tasks.forEach { groupTask ->
            groupTask?.task1?.let { task ->
                recentsModel.thumbnailCache.getThumbnailInBackground(task) {
                    thumbnailData: ThumbnailData ->
                    pendingTaskIds.remove(task.key.id)
                    // Add the current pair of task id and ThumbnailData to the list of all tasks
                    if (thumbnailData.thumbnail != null) {
                        taskList.add(task.key.id to thumbnailData.thumbnail)
                    }

                    // If the set is empty, all thumbnails have been fetched
                    if (pendingTaskIds.isEmpty() && taskList.isNotEmpty()) {
                        createAndPositionTaskbarShortcut(taskList)
                    }
                }
            }
        }
    }

    /**
     * Creates and positions the [TaskbarShortcutManageWindowsView] with the provided thumbnails.
     */
    private fun createAndPositionTaskbarShortcut(taskList: ArrayList<Pair<Int, Bitmap?>>) {
        val onIconClickListener =
            ({ taskId: Int? ->
                taskbarShortcutAllWindowsView.removeFromContainer()
                if (taskId != null) {
                    SystemUiProxy.INSTANCE.get(target).showDesktopApp(taskId, null)
                }
            })

        val onOutsideClickListener = { taskbarShortcutAllWindowsView.removeFromContainer() }

        taskbarShortcutAllWindowsView =
            TaskbarShortcutManageWindowsView(
                originalView!!,
                controllers.taskbarOverlayController.requestWindow(),
                taskList,
                onIconClickListener,
                onOutsideClickListener,
                controllers,
            )
    }

    /**
     * A view container for displaying the window of open instances of an app
     *
     * Handles showing the window snapshots, adding the carousel to the overlay, and closing it.
     * Also acts as a touch controller to intercept touch events outside the carousel to close it.
     */
    class TaskbarShortcutManageWindowsView(
        private val originalView: View,
        private val taskbarOverlayContext: TaskbarOverlayContext,
        snapshotList: ArrayList<Pair<Int, Bitmap?>>,
        onIconClickListener: (Int) -> Unit,
        onOutsideClickListener: () -> Unit,
        private val controllers: TaskbarControllers,
    ) :
        ManageWindowsViewContainer(
            originalView.context,
            Themes.getAttrColor(originalView.context, R.attr.materialColorSurfaceBright),
        ),
        TouchController {
        private val taskbarActivityContext = controllers.taskbarActivityContext

        init {
            createAndShowMenuView(snapshotList, onIconClickListener, onOutsideClickListener)
            taskbarOverlayContext.dragLayer.addTouchController(this)
        }

        /** Adds the carousel menu to the taskbar overlay drag layer */
        override fun addToContainer(menuView: ManageWindowsView) {
            taskbarOverlayContext.dragLayer.post { positionCarouselMenu() }

            controllers.taskbarAutohideSuspendController.updateFlag(
                FLAG_AUTOHIDE_SUSPEND_MULTI_INSTANCE_MENU_OPEN,
                true,
            )
            AbstractFloatingView.closeAllOpenViewsExcept(
                taskbarActivityContext,
                AbstractFloatingView.TYPE_TASKBAR_OVERLAY_PROXY,
            )
            menuView.rootView.minimumHeight = menuView.menuHeight
            menuView.rootView.minimumWidth = menuView.menuWidth

            taskbarOverlayContext.dragLayer?.addView(menuView.rootView)
            menuView.rootView.requestFocus()
        }

        /**
         * Positions the carousel menu relative to the taskbar and the calling app's icon.
         *
         * Calculates the Y position to place the carousel above the taskbar, and the X position to
         * align with the calling app while ensuring it doesn't go beyond the screen edge.
         */
        private fun positionCarouselMenu() {
            val margin =
                context.resources.getDimension(
                    R.dimen.taskbar_multi_instance_menu_min_padding_from_screen_edge
                )

            // Calculate the Y position to place the carousel above the taskbar
            val availableHeight = taskbarOverlayContext.dragLayer.height
            menuView.rootView.y =
                availableHeight -
                    menuView.menuHeight -
                    controllers.taskbarStashController.touchableHeight -
                    margin

            // Calculate the X position to align with the calling app,
            // but avoid clashing with the screen edge
            val availableWidth = taskbarOverlayContext.dragLayer.width
            if (Utilities.isRtl(context.resources)) {
                menuView.rootView.translationX = -(availableWidth - menuView.menuWidth) / 2f
            } else {
                val maxX = availableWidth - menuView.menuWidth - margin
                menuView.rootView.translationX = minOf(originalView.x, maxX)
            }
        }

        /** Closes the carousel menu and removes it from the taskbar overlay drag layer */
        override fun removeFromContainer() {
            controllers.taskbarAutohideSuspendController.updateFlag(
                FLAG_AUTOHIDE_SUSPEND_MULTI_INSTANCE_MENU_OPEN,
                false,
            )
            controllers.taskbarStashController.updateAndAnimateTransientTaskbar(true)
            taskbarOverlayContext.dragLayer?.removeView(menuView.rootView)
            taskbarOverlayContext.dragLayer.removeTouchController(this)
        }

        /** TouchController implementations for closing the carousel when touched outside */
        override fun onControllerTouchEvent(ev: MotionEvent?): Boolean {
            return false
        }

        override fun onControllerInterceptTouchEvent(ev: MotionEvent?): Boolean {
            ev?.let {
                if (
                    ev.action == MotionEvent.ACTION_DOWN &&
                        !taskbarOverlayContext.dragLayer.isEventOverView(menuView.rootView, ev)
                ) {
                    removeFromContainer()
                }
            }
            return false
        }
    }
}
