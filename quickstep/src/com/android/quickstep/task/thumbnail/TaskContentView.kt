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

package com.android.quickstep.task.thumbnail

import android.content.Context
import android.graphics.Outline
import android.graphics.Path
import android.graphics.Rect
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import androidx.core.view.isInvisible
import com.android.launcher3.Flags.enableRefactorTaskThumbnail
import com.android.launcher3.R
import com.android.launcher3.util.ViewPool
import com.android.quickstep.views.TaskHeaderView
import com.android.quickstep.views.TaskThumbnailViewDeprecated

/**
 * TaskContentView is a wrapper around the TaskHeaderView and TaskThumbnailView. It is a sibling to
 * DWB, AiAi (TaskOverlay).
 */
class TaskContentView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs), ViewPool.Reusable {

    private var taskHeaderView: TaskHeaderView? = null
    private var taskThumbnailView: TaskThumbnailView? = null
    private var taskThumbnailViewDeprecated: TaskThumbnailViewDeprecated? = null
    private var onSizeChanged: ((width: Int, height: Int) -> Unit)? = null
    private val outlinePath = Path()

    /**
     * Sets the outline bounds of the view. Default to use view's bound as outline when set to null.
     */
    var outlineBounds: Rect? = null
        set(value) {
            field = value
            invalidateOutline()
        }

    private val bounds = Rect()

    var cornerRadius: Float = 0f
        set(value) {
            field = value
            invalidateOutline()
        }

    override fun onFinishInflate() {
        super.onFinishInflate()
        createTaskThumbnailView()
    }

    override fun setScaleX(scaleX: Float) {
        super.setScaleX(scaleX)
        taskThumbnailView?.parentScaleXUpdated(scaleX)
    }

    override fun setScaleY(scaleY: Float) {
        super.setScaleY(scaleY)
        taskThumbnailView?.parentScaleYUpdated(scaleY)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        clipToOutline = true
        outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val outlineRect = outlineBounds ?: bounds
                    outlinePath.apply {
                        rewind()
                        addRoundRect(
                            outlineRect.left.toFloat(),
                            outlineRect.top.toFloat(),
                            outlineRect.right.toFloat(),
                            outlineRect.bottom.toFloat(),
                            cornerRadius / scaleX,
                            cornerRadius / scaleY,
                            Path.Direction.CW,
                        )
                    }
                    outline.setPath(outlinePath)
                }
            }
    }

    override fun onRecycle() {
        taskHeaderView?.isInvisible = true
        onSizeChanged = null
        outlineBounds = null
        taskThumbnailView?.onRecycle()
        taskThumbnailViewDeprecated?.onRecycle()
    }

    fun doOnSizeChange(action: (width: Int, height: Int) -> Unit) {
        onSizeChanged = action
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        onSizeChanged?.invoke(width, height)
        bounds.set(0, 0, w, h)
        invalidateOutline()
    }

    private fun createHeaderView(taskHeaderState: TaskHeaderUiState) {
        if (taskHeaderView == null && taskHeaderState is TaskHeaderUiState.ShowHeader) {
            taskHeaderView =
                LayoutInflater.from(context).inflate(R.layout.task_header_view, this, false)
                    as TaskHeaderView
            addView(taskHeaderView, 0)
        }
    }

    private fun createTaskThumbnailView() {
        if (taskThumbnailView == null) {
            if (enableRefactorTaskThumbnail()) {
                taskThumbnailView =
                    LayoutInflater.from(context).inflate(R.layout.task_thumbnail, this, false)
                        as TaskThumbnailView
                addView(taskThumbnailView)
            } else {
                taskThumbnailViewDeprecated =
                    LayoutInflater.from(context)
                        .inflate(R.layout.task_thumbnail_deprecated, this, false)
                        as TaskThumbnailViewDeprecated
                addView(taskThumbnailViewDeprecated)
            }
        }
    }

    fun setState(
        taskHeaderState: TaskHeaderUiState,
        taskThumbnailUiState: TaskThumbnailUiState,
        taskId: Int?,
    ) {
        createHeaderView(taskHeaderState)
        taskHeaderView?.setState(taskHeaderState)
        taskThumbnailView?.setState(taskThumbnailUiState, taskId)
    }
}
