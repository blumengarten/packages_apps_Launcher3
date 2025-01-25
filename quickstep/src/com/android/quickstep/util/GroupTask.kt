/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.quickstep.util

import androidx.annotation.VisibleForTesting
import com.android.launcher3.util.SplitConfigurationOptions
import com.android.quickstep.views.TaskViewType
import com.android.systemui.shared.recents.model.Task
import java.util.Objects

/**
 * An abstract class for creating [Task] containers that can be [SingleTask]s, [SplitTask]s, or
 * [DesktopTask]s in the recent tasks list.
 */
abstract class GroupTask
@VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
constructor(
    @Deprecated("Prefer using `getTasks()` instead") @JvmField val task1: Task,
    @Deprecated("Prefer using `getTasks()` instead") @JvmField val task2: Task?,
    @JvmField val mSplitBounds: SplitConfigurationOptions.SplitBounds?,
    @JvmField val taskViewType: TaskViewType,
) {
    protected constructor(
        task1: Task,
        task2: Task?,
        splitBounds: SplitConfigurationOptions.SplitBounds?,
    ) : this(
        task1,
        task2,
        splitBounds,
        if (task2 != null) TaskViewType.GROUPED else TaskViewType.SINGLE,
    )

    open fun containsTask(taskId: Int) =
        task1.key.id == taskId || (task2 != null && task2.key.id == taskId)

    /**
     * Returns true if a task in this group has a package name that matches the given `packageName`.
     */
    fun containsPackage(packageName: String) = tasks.any { it.key.packageName == packageName }

    open fun hasMultipleTasks() = task2 != null

    /** Returns whether this task supports multiple tasks or not. */
    open fun supportsMultipleTasks() = taskViewType == TaskViewType.GROUPED

    /** Returns a List of all the Tasks in this GroupTask */
    open val tasks: List<Task>
        get() = listOfNotNull(task1, task2)

    /** Creates a copy of this instance */
    abstract fun copy(): GroupTask

    override fun toString() = "type=$taskViewType task1=$task1 task2=$task2"

    override fun equals(o: Any?): Boolean {
        if (this === o) return true
        if (o !is GroupTask) return false
        return taskViewType == o.taskViewType && tasks == o.tasks
    }

    override fun hashCode() = Objects.hash(tasks, taskViewType)
}

/** A [Task] container that must contain exactly one task in the recent tasks list. */
class SingleTask(task: Task) :
    GroupTask(task, task2 = null, mSplitBounds = null, TaskViewType.SINGLE) {
    override fun copy() = SingleTask(task1)

    override fun toString() = "type=$taskViewType task=$task1"

    override fun equals(o: Any?): Boolean {
        if (this === o) return true
        if (o !is SingleTask) return false
        return super.equals(o)
    }
}

/**
 * A [Task] container that must contain exactly two tasks and split bounds to represent an app-pair
 * in the recent tasks list.
 */
class SplitTask(task1: Task, task2: Task, splitBounds: SplitConfigurationOptions.SplitBounds) :
    GroupTask(task1, task2, splitBounds, TaskViewType.GROUPED) {

    override fun copy() = SplitTask(task1, task2!!, mSplitBounds!!)

    override fun toString() = "type=$taskViewType task1=$task1 task2=$task2"

    override fun equals(o: Any?): Boolean {
        if (this === o) return true
        if (o !is SplitTask) return false
        if (mSplitBounds!! != o.mSplitBounds!!) return false
        return super.equals(o)
    }

    override fun hashCode() = Objects.hash(super.hashCode(), mSplitBounds)
}
