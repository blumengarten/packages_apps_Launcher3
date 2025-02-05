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
package com.android.launcher3.uioverrides.touchcontrollers

import android.content.Context
import android.view.MotionEvent
import androidx.dynamicanimation.animation.SpringAnimation
import com.android.app.animation.Interpolators.DECELERATE
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.Utilities.EDGE_NAV_BAR
import com.android.launcher3.Utilities.boundToRange
import com.android.launcher3.Utilities.isRtl
import com.android.launcher3.Utilities.mapToRange
import com.android.launcher3.touch.SingleAxisSwipeDetector
import com.android.launcher3.util.TouchController
import com.android.quickstep.views.RecentsView
import com.android.quickstep.views.RecentsViewContainer
import com.android.quickstep.views.TaskView
import kotlin.math.abs
import kotlin.math.sign

/** Touch controller for handling task view card dismiss swipes */
class TaskViewDismissTouchController<CONTAINER>(
    private val container: CONTAINER,
    private val taskViewRecentsTouchContext: TaskViewRecentsTouchContext,
) : TouchController, SingleAxisSwipeDetector.Listener where
CONTAINER : Context,
CONTAINER : RecentsViewContainer {
    private val recentsView: RecentsView<*, *> = container.getOverviewPanel()
    private val detector: SingleAxisSwipeDetector =
        SingleAxisSwipeDetector(
            container as Context,
            this,
            recentsView.pagedOrientationHandler.upDownSwipeDirection,
        )
    private val isRtl = isRtl(container.resources)

    private var taskBeingDragged: TaskView? = null
    private var springAnimation: SpringAnimation? = null
    private var dismissLength: Int = 0
    private var verticalFactor: Int = 0
    private var initialDisplacement: Float = 0f

    private fun canInterceptTouch(ev: MotionEvent): Boolean =
        when {
            // Don't intercept swipes on the nav bar, as user might be trying to go home during a
            // task dismiss animation.
            (ev.edgeFlags and EDGE_NAV_BAR) != 0 -> {
                false
            }

            // Floating views that a TouchController should not try to intercept touches from.
            AbstractFloatingView.getTopOpenViewWithType(
                container,
                AbstractFloatingView.TYPE_TOUCH_CONTROLLER_NO_INTERCEPT,
            ) != null -> false

            // Disable swiping if the task overlay is modal.
            taskViewRecentsTouchContext.isRecentsModal -> {
                false
            }

            else -> taskViewRecentsTouchContext.isRecentsInteractive
        }

    override fun onControllerInterceptTouchEvent(ev: MotionEvent): Boolean {
        if ((ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL)) {
            clearState()
        }
        if (ev.action == MotionEvent.ACTION_DOWN) {
            if (!onActionDown(ev)) {
                return false
            }
        }

        onControllerTouchEvent(ev)
        return detector.isDraggingState && detector.wasInitialTouchPositive()
    }

    override fun onControllerTouchEvent(ev: MotionEvent?): Boolean = detector.onTouchEvent(ev)

    private fun onActionDown(ev: MotionEvent): Boolean {
        springAnimation?.cancel()
        if (!canInterceptTouch(ev)) {
            return false
        }

        taskBeingDragged =
            recentsView.taskViews
                .firstOrNull {
                    recentsView.isTaskViewVisible(it) && container.dragLayer.isEventOverView(it, ev)
                }
                ?.also {
                    dismissLength = recentsView.pagedOrientationHandler.getSecondaryDimension(it)
                    verticalFactor =
                        recentsView.pagedOrientationHandler.secondaryTranslationDirectionFactor
                }

        detector.setDetectableScrollConditions(
            recentsView.pagedOrientationHandler.getUpDirection(isRtl),
            /* ignoreSlop = */ false,
        )

        return true
    }

    override fun onDragStart(start: Boolean, startDisplacement: Float) {
        val taskBeingDragged = taskBeingDragged ?: return

        initialDisplacement =
            taskBeingDragged.secondaryDismissTranslationProperty.get(taskBeingDragged)

        // Add a tiny bit of translation Z, so that it draws on top of other views. This is relevant
        // (e.g.) when we dismiss a task by sliding it upward: if there is a row of icons above, we
        // want the dragged task to stay above all other views.
        taskBeingDragged.translationZ = 0.1f
    }

    override fun onDrag(displacement: Float): Boolean {
        val taskBeingDragged = taskBeingDragged ?: return false
        val currentDisplacement = displacement + initialDisplacement
        val boundedDisplacement =
            boundToRange(abs(currentDisplacement), 0f, dismissLength.toFloat())
        // When swiping below origin, allow slight undershoot to simulate resisting the movement.
        val totalDisplacement =
            if (isDisplacementPositiveDirection(currentDisplacement))
                boundedDisplacement * sign(currentDisplacement)
            else
                mapToRange(
                    boundedDisplacement,
                    0f,
                    dismissLength.toFloat(),
                    0f,
                    DISMISS_MAX_UNDERSHOOT,
                    DECELERATE,
                )
        taskBeingDragged.secondaryDismissTranslationProperty.setValue(
            taskBeingDragged,
            totalDisplacement,
        )
        if (taskBeingDragged.isRunningTask && recentsView.enableDrawingLiveTile) {
            recentsView.runActionOnRemoteHandles { remoteTargetHandle ->
                remoteTargetHandle.taskViewSimulator.taskSecondaryTranslation.value =
                    totalDisplacement
            }
            recentsView.redrawLiveTile()
        }
        return true
    }

    override fun onDragEnd(velocity: Float) {
        val taskBeingDragged = taskBeingDragged ?: return

        val currentDisplacement =
            taskBeingDragged.secondaryDismissTranslationProperty.get(taskBeingDragged)
        if (currentDisplacement == 0f) {
            clearState()
            return
        }
        val isBeyondDismissThreshold =
            abs(currentDisplacement) > abs(DISMISS_THRESHOLD_FRACTION * dismissLength)
        val isFlingingTowardsDismiss = detector.isFling(velocity) && velocity < 0
        val isFlingingTowardsRestState = detector.isFling(velocity) && velocity > 0
        val isDismissing =
            isFlingingTowardsDismiss || (isBeyondDismissThreshold && !isFlingingTowardsRestState)
        springAnimation =
            recentsView
                .createTaskDismissSettlingSpringAnimation(
                    taskBeingDragged,
                    velocity,
                    isDismissing,
                    detector,
                    dismissLength,
                    this::clearState,
                )
                .apply {
                    animateToFinalPosition(
                        if (isDismissing) (dismissLength * verticalFactor).toFloat() else 0f
                    )
                }
    }

    // Returns if the current task being dragged is towards "positive" (e.g. dismissal).
    private fun isDisplacementPositiveDirection(displacement: Float): Boolean =
        sign(displacement) == sign(verticalFactor.toFloat())

    private fun clearState() {
        detector.finishedScrolling()
        detector.setDetectableScrollConditions(0, false)
        taskBeingDragged?.translationZ = 0f
        taskBeingDragged = null
        springAnimation = null
    }

    companion object {
        private const val DISMISS_THRESHOLD_FRACTION = 0.5f
        private const val DISMISS_MAX_UNDERSHOOT = 25f
    }
}
