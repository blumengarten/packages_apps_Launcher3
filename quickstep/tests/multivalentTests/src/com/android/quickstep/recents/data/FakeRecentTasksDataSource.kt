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

package com.android.quickstep.recents.data

import com.android.quickstep.util.GroupTask
import java.util.function.Consumer

class FakeRecentTasksDataSource : RecentTasksDataSource {
    private var taskList: List<GroupTask> = listOf()

    override fun getTasks(callback: Consumer<List<GroupTask>>?): Int {
        // Makes a copy of the GroupTask to create a new GroupTask instance and to simulate
        // RecentsModel::getTasks behavior.
        callback?.accept(taskList.map { it.copy() })
        return 0
    }

    fun seedTasks(tasks: List<GroupTask>) {
        taskList = tasks
    }
}
