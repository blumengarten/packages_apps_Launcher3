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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Rect
import android.os.VibrationEffect
import android.view.MotionEvent
import android.view.animation.Interpolator
import com.android.app.animation.Interpolators
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.LauncherAnimUtils.SUCCESS_TRANSITION_PROGRESS
import com.android.launcher3.LauncherAnimUtils.blockedFlingDurationFactor
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.anim.AnimatorPlaybackController
import com.android.launcher3.anim.PendingAnimation
import com.android.launcher3.touch.BaseSwipeDetector
import com.android.launcher3.touch.SingleAxisSwipeDetector
import com.android.launcher3.util.DisplayController
import com.android.launcher3.util.FlingBlockCheck
import com.android.launcher3.util.TouchController
import com.android.launcher3.util.VibratorWrapper
import com.android.quickstep.util.VibrationConstants
import com.android.quickstep.views.RecentsView
import com.android.quickstep.views.RecentsViewContainer
import com.android.quickstep.views.TaskView
import kotlin.math.abs

/** Touch controller for handling task view card swipes */
class TaskViewTouchController<CONTAINER>(
    private val container: CONTAINER,
    private val taskViewRecentsTouchContext: TaskViewRecentsTouchContext,
) : AnimatorListenerAdapter(), TouchController, SingleAxisSwipeDetector.Listener where
CONTAINER : Context,
CONTAINER : RecentsViewContainer {
    private val recentsView: RecentsView<*, *> = container.getOverviewPanel()
    private val detector: SingleAxisSwipeDetector =
        SingleAxisSwipeDetector(
            container as Context,
            this,
            recentsView.pagedOrientationHandler.upDownSwipeDirection,
        )
    private val tempRect = Rect()
    private val isRtl = Utilities.isRtl(container.resources)
    private val flingBlockCheck = FlingBlockCheck()

    private var currentAnimation: AnimatorPlaybackController? = null
    private var currentAnimationIsGoingUp = false
    private var allowGoingUp = false
    private var allowGoingDown = false
    private var noIntercept = false
    private var displacementShift = 0f
    private var progressMultiplier = 0f
    private var endDisplacement = 0f
    private var draggingEnabled = true
    private var overrideVelocity: Float? = null
    private var taskBeingDragged: TaskView? = null
    private var isDismissHapticRunning = false

    private fun canInterceptTouch(ev: MotionEvent): Boolean {
        val currentAnimation = currentAnimation
        return when {
            (ev.edgeFlags and Utilities.EDGE_NAV_BAR) != 0 -> {
                // Don't intercept swipes on the nav bar, as user might be trying to go home
                // during a task dismiss animation.
                currentAnimation?.animationPlayer?.end()
                false
            }
            currentAnimation != null -> {
                currentAnimation.forceFinishIfCloseToEnd()
                true
            }
            AbstractFloatingView.getTopOpenViewWithType(
                container,
                AbstractFloatingView.TYPE_TOUCH_CONTROLLER_NO_INTERCEPT,
            ) != null -> false
            else -> taskViewRecentsTouchContext.isRecentsInteractive
        }
    }

    override fun onAnimationCancel(animation: Animator) {
        if (animation === currentAnimation?.target) {
            clearState()
        }
    }

    override fun onControllerInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (
            (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) &&
                currentAnimation == null
        ) {
            clearState()
        }
        if (ev.action == MotionEvent.ACTION_DOWN) {
            // Disable swiping up and down if the task overlay is modal.
            if (taskViewRecentsTouchContext.isRecentsModal) {
                noIntercept = true
                return false
            }
            noIntercept = !canInterceptTouch(ev)
            if (noIntercept) {
                return false
            }
            // Now figure out which direction scroll events the controller will start
            // calling the callbacks.
            var directionsToDetectScroll = 0
            var ignoreSlopWhenSettling = false
            if (currentAnimation != null) {
                directionsToDetectScroll = SingleAxisSwipeDetector.DIRECTION_BOTH
                ignoreSlopWhenSettling = true
            } else {
                taskBeingDragged = null
                recentsView.taskViews.forEach { taskView ->
                    if (
                        recentsView.isTaskViewVisible(taskView) &&
                            container.dragLayer.isEventOverView(taskView, ev)
                    ) {
                        taskBeingDragged = taskView
                        val upDirection = recentsView.pagedOrientationHandler.getUpDirection(isRtl)

                        // The task can be dragged up to dismiss it
                        allowGoingUp = true

                        // The task can be dragged down to open it if:
                        // - It's the current page
                        // - We support gestures to enter overview
                        // - It's the focused task if in grid view
                        // - The task is snapped
                        allowGoingDown =
                            taskView === recentsView.currentPageTaskView &&
                                DisplayController.getNavigationMode(container).hasGestures &&
                                (!recentsView.showAsGrid() || taskView.isLargeTile) &&
                                recentsView.isTaskInExpectedScrollPosition(taskView)

                        directionsToDetectScroll =
                            if (allowGoingDown) SingleAxisSwipeDetector.DIRECTION_BOTH
                            else upDirection
                        return@forEach
                    }
                }
                if (taskBeingDragged == null) {
                    noIntercept = true
                    return false
                }
            }
            detector.setDetectableScrollConditions(directionsToDetectScroll, ignoreSlopWhenSettling)
        }
        if (noIntercept) {
            return false
        }
        onControllerTouchEvent(ev)
        return detector.isDraggingOrSettling
    }

    override fun onControllerTouchEvent(ev: MotionEvent): Boolean = detector.onTouchEvent(ev)

    private fun reInitAnimationController(goingUp: Boolean) {
        if (currentAnimation != null && currentAnimationIsGoingUp == goingUp) {
            // No need to init
            return
        }
        if ((goingUp && !allowGoingUp) || (!goingUp && !allowGoingDown)) {
            // Trying to re-init in an unsupported direction.
            return
        }
        val taskBeingDragged = taskBeingDragged ?: return
        currentAnimation?.setPlayFraction(0f)
        currentAnimation?.target?.removeListener(this)
        currentAnimation?.dispatchOnCancel()

        val orientationHandler = recentsView.pagedOrientationHandler
        currentAnimationIsGoingUp = goingUp
        val dl = container.dragLayer
        val secondaryLayerDimension = orientationHandler.getSecondaryDimension(dl)
        val maxDuration = 2L * secondaryLayerDimension
        val verticalFactor = orientationHandler.getTaskDragDisplacementFactor(isRtl)
        val secondaryTaskDimension = orientationHandler.getSecondaryDimension(taskBeingDragged)
        // The interpolator controlling the most prominent visual movement. We use this to determine
        // whether we passed SUCCESS_TRANSITION_PROGRESS.
        val currentInterpolator: Interpolator
        val pa: PendingAnimation
        if (goingUp) {
            currentInterpolator = Interpolators.LINEAR
            pa = PendingAnimation(maxDuration)
            recentsView.createTaskDismissAnimation(
                pa,
                taskBeingDragged,
                true, /* animateTaskView */
                true, /* removeTask */
                maxDuration,
                false, /* dismissingForSplitSelection*/
            )

            endDisplacement = -secondaryTaskDimension.toFloat()
        } else {
            currentInterpolator = Interpolators.ZOOM_IN
            pa =
                recentsView.createTaskLaunchAnimation(
                    taskBeingDragged,
                    maxDuration,
                    currentInterpolator,
                )

            // Since the thumbnail is what is filling the screen, based the end displacement on it.
            taskBeingDragged.getThumbnailBounds(tempRect, /* relativeToDragLayer= */ true)
            endDisplacement = (secondaryLayerDimension - tempRect.bottom).toFloat()
        }
        endDisplacement *= verticalFactor.toFloat()
        currentAnimation =
            pa.createPlaybackController().apply {
                // Setting this interpolator doesn't affect the visual motion, but is used to
                // determine whether we successfully reached the target state in onDragEnd().
                target.interpolator = currentInterpolator
                taskViewRecentsTouchContext.onUserControlledAnimationCreated(this)
                target.addListener(this@TaskViewTouchController)
                dispatchOnStart()
            }
        progressMultiplier = 1 / endDisplacement
    }

    override fun onDragStart(start: Boolean, startDisplacement: Float) {
        if (!draggingEnabled) return
        val currentAnimation = currentAnimation

        val orientationHandler = recentsView.pagedOrientationHandler
        if (currentAnimation == null) {
            reInitAnimationController(orientationHandler.isGoingUp(startDisplacement, isRtl))
            displacementShift = 0f
        } else {
            displacementShift = currentAnimation.progressFraction / progressMultiplier
            currentAnimation.pause()
        }
        flingBlockCheck.unblockFling()
        overrideVelocity = null
    }

    override fun onDrag(displacement: Float): Boolean {
        if (!draggingEnabled) return true
        val taskBeingDragged = taskBeingDragged ?: return true
        val currentAnimation = currentAnimation ?: return true

        val orientationHandler = recentsView.pagedOrientationHandler
        val totalDisplacement = displacement + displacementShift
        val isGoingUp =
            if (totalDisplacement == 0f) currentAnimationIsGoingUp
            else orientationHandler.isGoingUp(totalDisplacement, isRtl)
        if (isGoingUp != currentAnimationIsGoingUp) {
            reInitAnimationController(isGoingUp)
            flingBlockCheck.blockFling()
        } else {
            flingBlockCheck.onEvent()
        }

        if (isGoingUp) {
            if (currentAnimation.progressFraction < ANIMATION_PROGRESS_FRACTION_MIDPOINT) {
                // Halve the value when dismissing, as we are animating the drag across the full
                // length for only the first half of the progress
                currentAnimation.setPlayFraction(
                    Utilities.boundToRange(totalDisplacement * progressMultiplier / 2, 0f, 1f)
                )
            } else {
                // Set mOverrideVelocity to control task dismiss velocity in onDragEnd
                var velocityDimenId = R.dimen.default_task_dismiss_drag_velocity
                if (recentsView.showAsGrid()) {
                    velocityDimenId =
                        if (taskBeingDragged.isLargeTile) {
                            R.dimen.default_task_dismiss_drag_velocity_grid_focus_task
                        } else {
                            R.dimen.default_task_dismiss_drag_velocity_grid
                        }
                }
                overrideVelocity = -taskBeingDragged.resources.getDimension(velocityDimenId)

                // Once halfway through task dismissal interpolation, switch from reversible
                // dragging-task animation to playing the remaining task translation animations,
                // while this is in progress disable dragging.
                draggingEnabled = false
            }
        } else {
            currentAnimation.setPlayFraction(
                Utilities.boundToRange(totalDisplacement * progressMultiplier, 0f, 1f)
            )
        }

        return true
    }

    override fun onDragEnd(velocity: Float) {
        val taskBeingDragged = taskBeingDragged ?: return
        val currentAnimation = currentAnimation ?: return

        // Limit velocity, as very large scalar values make animations play too quickly
        val maxTaskDismissDragVelocity =
            taskBeingDragged.resources.getDimension(R.dimen.max_task_dismiss_drag_velocity)
        val endVelocity =
            Utilities.boundToRange(
                overrideVelocity ?: velocity,
                -maxTaskDismissDragVelocity,
                maxTaskDismissDragVelocity,
            )
        overrideVelocity = null

        var fling = draggingEnabled && detector.isFling(endVelocity)
        val goingToEnd: Boolean
        val blockedFling = fling && flingBlockCheck.isBlocked
        if (blockedFling) {
            fling = false
        }
        val orientationHandler = recentsView.pagedOrientationHandler
        val goingUp = orientationHandler.isGoingUp(endVelocity, isRtl)
        val progress = currentAnimation.progressFraction
        val interpolatedProgress = currentAnimation.interpolatedProgress
        goingToEnd =
            if (fling) {
                goingUp == currentAnimationIsGoingUp
            } else {
                interpolatedProgress > SUCCESS_TRANSITION_PROGRESS
            }
        var animationDuration =
            BaseSwipeDetector.calculateDuration(
                endVelocity,
                if (goingToEnd) (1 - progress) else progress,
            )
        if (blockedFling && !goingToEnd) {
            animationDuration *= blockedFlingDurationFactor(endVelocity).toLong()
        }
        // Due to very high or low velocity dismissals, animation durations can be inconsistently
        // long or short. Bound the duration for animation of task translations for a more
        // standardized feel.
        animationDuration =
            Utilities.boundToRange(
                animationDuration,
                MIN_TASK_DISMISS_ANIMATION_DURATION,
                MAX_TASK_DISMISS_ANIMATION_DURATION,
            )

        currentAnimation.setEndAction { this.clearState() }
        currentAnimation.startWithVelocity(
            container,
            goingToEnd,
            abs(endVelocity.toDouble()).toFloat(),
            endDisplacement,
            animationDuration,
        )
        if (goingUp && goingToEnd && !isDismissHapticRunning) {
            VibratorWrapper.INSTANCE.get(container)
                .vibrate(
                    TASK_DISMISS_VIBRATION_PRIMITIVE,
                    TASK_DISMISS_VIBRATION_PRIMITIVE_SCALE,
                    TASK_DISMISS_VIBRATION_FALLBACK,
                )
            isDismissHapticRunning = true
        }

        draggingEnabled = true
    }

    private fun clearState() {
        detector.finishedScrolling()
        detector.setDetectableScrollConditions(0, false)
        draggingEnabled = true
        taskBeingDragged = null
        currentAnimation = null
        isDismissHapticRunning = false
    }

    companion object {
        private const val ANIMATION_PROGRESS_FRACTION_MIDPOINT = 0.5f
        private const val MIN_TASK_DISMISS_ANIMATION_DURATION: Long = 300
        private const val MAX_TASK_DISMISS_ANIMATION_DURATION: Long = 600

        private const val TASK_DISMISS_VIBRATION_PRIMITIVE: Int =
            VibrationEffect.Composition.PRIMITIVE_TICK
        private const val TASK_DISMISS_VIBRATION_PRIMITIVE_SCALE: Float = 1f
        private val TASK_DISMISS_VIBRATION_FALLBACK: VibrationEffect =
            VibrationConstants.EFFECT_TEXTURE_TICK
    }
}
