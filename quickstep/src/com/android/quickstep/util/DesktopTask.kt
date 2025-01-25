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
package com.android.quickstep.util

import com.android.quickstep.views.TaskViewType
import com.android.systemui.shared.recents.model.Task

/**
 * A [Task] container that can contain N number of tasks that are part of the desktop in recent
 * tasks list.
 */
class DesktopTask(override val tasks: List<Task>) :
    GroupTask(tasks[0], null, null, TaskViewType.DESKTOP) {

    override fun containsTask(taskId: Int) = tasks.any { it.key.id == taskId }

    override fun hasMultipleTasks() = tasks.size > 1

    override fun supportsMultipleTasks() = true

    override fun copy() = DesktopTask(tasks)

    override fun toString() = "type=$taskViewType tasks=$tasks"

    override fun equals(o: Any?): Boolean {
        if (this === o) return true
        if (o !is DesktopTask) return false
        return super.equals(o)
    }
}
