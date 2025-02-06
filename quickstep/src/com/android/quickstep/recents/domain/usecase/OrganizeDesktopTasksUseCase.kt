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

package com.android.quickstep.recents.domain.usecase

import android.graphics.Rect
import android.util.Size
import com.android.quickstep.recents.domain.model.DesktopTaskBoundsData
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * This usecase is responsible for organizing desktop windows in a non-overlapping way. Note: this
 * is currently a placeholder implementation.
 */
class OrganizeDesktopTasksUseCase {
    fun run(
        desktopSize: Size,
        taskBounds: List<DesktopTaskBoundsData>,
    ): List<DesktopTaskBoundsData> {
        return getRects(desktopSize, taskBounds.size).zip(taskBounds) { rect, task ->
            shrinkRect(rect, 0.8f)
            DesktopTaskBoundsData(task.taskId, fitRect(task.bounds, rect))
        }
    }

    private fun shrinkRect(bounds: Rect, fraction: Float) {
        val xMargin = (bounds.width() * ((1.0f - fraction) / 2.0f)).toInt()
        val yMargin = (bounds.height() * ((1.0f - fraction) / 2.0f)).toInt()
        bounds.inset(xMargin, yMargin, xMargin, yMargin)
    }

    /** Generates `tasks` number of non-overlapping rects that fit into `desktopSize`. */
    private fun getRects(desktopSize: Size, tasks: Int): List<Rect> {
        val (xSlots, ySlots) =
            when (tasks) {
                2 -> Pair(2, 1)
                3,
                4 -> Pair(2, 2)
                5,
                6 -> Pair(3, 2)
                else -> {
                    val sides = ceil(sqrt(tasks.toDouble())).toInt()
                    Pair(sides, sides)
                }
            }

        // The width and height of one of the boxes.
        val boxWidth = desktopSize.width / xSlots
        val boxHeight = desktopSize.height / ySlots

        return (0 until tasks).map {
            val x = it % xSlots
            val y = it / xSlots
            Rect(x * boxWidth, y * boxHeight, (x + 1) * boxWidth, (y + 1) * boxHeight)
        }
    }

    /** Centers and fits `rect` into `bounds`, while preserving the former's aspect ratio. */
    private fun fitRect(rect: Rect, bounds: Rect): Rect {
        val boundsAspect = bounds.width().toFloat() / bounds.height()
        val rectAspect = rect.width().toFloat() / rect.height()

        if (rectAspect > boundsAspect) {
            // The width is the limiting dimension.
            val scale = bounds.width().toFloat() / rect.width()
            val width = bounds.width()
            val height = (rect.height() * scale).toInt()
            val top = (bounds.top + bounds.height() / 2.0f - height / 2.0f).toInt()
            return Rect(bounds.left, top, bounds.left + width, top + height)
        } else {
            // The height is the limiting dimension.
            val scale = bounds.height().toFloat() / rect.height()
            val width = (rect.width() * scale).toInt()
            val height = bounds.height()
            val left = (bounds.left + bounds.width() / 2.0f - width / 2.0f).toInt()
            return Rect(left, bounds.top, left + width, bounds.top + height)
        }
    }
}
