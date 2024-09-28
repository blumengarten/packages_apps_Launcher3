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

package com.android.launcher3.taskbar.bubbles

import android.animation.ValueAnimator
import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.core.animation.doOnEnd
import androidx.dynamicanimation.animation.SpringForce
import com.android.launcher3.R
import com.android.launcher3.anim.AnimatedFloat
import com.android.launcher3.anim.SpringAnimationBuilder
import com.android.launcher3.taskbar.TaskbarActivityContext
import com.android.launcher3.taskbar.TaskbarThresholdUtils
import com.android.launcher3.taskbar.bubbles.BubbleBarSwipeController.StartState.COLLAPSED
import com.android.launcher3.taskbar.bubbles.BubbleBarSwipeController.StartState.EXPANDED
import com.android.launcher3.taskbar.bubbles.BubbleBarSwipeController.StartState.STASHED
import com.android.launcher3.taskbar.bubbles.BubbleBarSwipeController.StartState.UNKNOWN
import com.android.launcher3.taskbar.bubbles.stashing.BubbleStashController
import com.android.launcher3.touch.OverScroll

/** Handle swipe events on the bubble bar and handle */
class BubbleBarSwipeController {

    private val context: Context

    private var bubbleStashedHandleViewController: BubbleStashedHandleViewController? = null
    private lateinit var bubbleBarViewController: BubbleBarViewController
    private lateinit var bubbleStashController: BubbleStashController

    private var springAnimation: ValueAnimator? = null
    private val animatedSwipeTranslation = AnimatedFloat(this::onSwipeUpdate)

    private val unstashThreshold: Int
    private val expandThreshold: Int
    private val maxOverscroll: Int
    private val stashThreshold: Int

    private var swipeState: SwipeState = SwipeState()

    constructor(tac: TaskbarActivityContext) : this(tac, DefaultDimensionProvider(tac))

    @VisibleForTesting
    constructor(context: Context, dimensionProvider: DimensionProvider) {
        this.context = context
        unstashThreshold = dimensionProvider.unstashThreshold
        expandThreshold = dimensionProvider.expandThreshold
        maxOverscroll = dimensionProvider.maxOverscroll
        stashThreshold = dimensionProvider.stashThreshold
    }

    fun init(bubbleControllers: BubbleControllers) {
        bubbleStashedHandleViewController =
            bubbleControllers.bubbleStashedHandleViewController.orElse(null)
        bubbleBarViewController = bubbleControllers.bubbleBarViewController
        bubbleStashController = bubbleControllers.bubbleStashController
    }

    /** Start tracking a new swipe gesture */
    fun start() {
        if (springAnimation != null) reset()
        val startState =
            when {
                bubbleStashController.isStashed -> STASHED
                bubbleBarViewController.isExpanded -> EXPANDED
                bubbleStashController.isBubbleBarVisible() -> COLLAPSED
                else -> UNKNOWN
            }
        swipeState = SwipeState(startState = startState)
    }

    /** Update swipe distance to [dy] */
    fun swipeTo(dy: Float) {
        if (!canHandleSwipe(dy)) {
            return
        }
        animatedSwipeTranslation.updateValue(dy)

        val prevState = swipeState
        // We can pass unstash threshold once per gesture, keep it true if it happened once
        val passedUnstashThreshold = isUnstash(dy) || prevState.passedUnstashThreshold
        // Expand happens at the end of the gesture, always keep the current value
        val passedExpandThreshold = isExpand(dy)
        // Stash happens at the end of the gesture, always keep the current value
        val passedStashThreshold = isStash(dy)

        if (
            passedUnstashThreshold != prevState.passedUnstashThreshold ||
                passedExpandThreshold != prevState.passedExpandThreshold ||
                passedStashThreshold != prevState.passedStashThreshold
        ) {
            swipeState =
                swipeState.copy(
                    passedUnstashThreshold = passedUnstashThreshold,
                    passedExpandThreshold = passedExpandThreshold,
                    passedStashThreshold = passedStashThreshold,
                )
        }

        if (
            swipeState.startState == STASHED &&
                swipeState.passedUnstashThreshold &&
                !prevState.passedUnstashThreshold
        ) {
            bubbleStashController.showBubbleBar(expandBubbles = false)
        }
    }

    /** Finish tracking swipe gesture. Animate views back to resting state */
    fun finish() {
        when {
            swipeState.passedExpandThreshold &&
                swipeState.startState in setOf(STASHED, COLLAPSED) -> {
                bubbleStashController.showBubbleBar(expandBubbles = true)
            }
            swipeState.passedStashThreshold && swipeState.startState == COLLAPSED -> {
                bubbleStashController.stashBubbleBar()
            }
        }
        if (animatedSwipeTranslation.value == 0f) {
            reset()
        } else {
            springToRest()
        }
    }

    /** Returns `true` if we are tracking a swipe gesture */
    fun isSwipeGesture(): Boolean {
        return swipeState.passedUnstashThreshold ||
            swipeState.passedExpandThreshold ||
            swipeState.passedStashThreshold
    }

    private fun canHandleSwipe(dy: Float): Boolean {
        return when (swipeState.startState) {
            STASHED -> dy < 0 // stashed bar only handles swipe up
            COLLAPSED -> true // collapsed bar can be swiped in either direction
            UNKNOWN,
            EXPANDED -> false // expanded bar can't be swiped
        }
    }

    private fun isUnstash(dy: Float): Boolean {
        return dy < -unstashThreshold
    }

    private fun isExpand(dy: Float): Boolean {
        return dy < -expandThreshold
    }

    private fun isStash(dy: Float): Boolean {
        return dy > stashThreshold
    }

    private fun reset() {
        springAnimation?.let {
            if (it.isRunning) {
                it.removeAllListeners()
                it.cancel()
                animatedSwipeTranslation.updateValue(0f)
            }
        }
        springAnimation = null
        swipeState = SwipeState()
    }

    private fun onSwipeUpdate(value: Float) {
        val dampedSwipe = -OverScroll.dampedScroll(-value, maxOverscroll).toFloat()
        bubbleStashedHandleViewController?.setTranslationYForSwipe(dampedSwipe)
        bubbleBarViewController.setTranslationYForSwipe(dampedSwipe)
    }

    private fun springToRest() {
        springAnimation =
            SpringAnimationBuilder(context)
                .setStartValue(animatedSwipeTranslation.value)
                .setEndValue(0f)
                .setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY)
                .setStiffness(SpringForce.STIFFNESS_LOW)
                .build(animatedSwipeTranslation, AnimatedFloat.VALUE)
                .also { it.doOnEnd { reset() } }
        springAnimation?.start()
    }

    internal data class SwipeState(
        val startState: StartState = UNKNOWN,
        val passedUnstashThreshold: Boolean = false,
        val passedExpandThreshold: Boolean = false,
        val passedStashThreshold: Boolean = false,
    )

    internal enum class StartState {
        UNKNOWN,
        STASHED,
        COLLAPSED,
        EXPANDED,
    }

    /** Allows overriding the dimension provider for testing */
    @VisibleForTesting
    interface DimensionProvider {
        val unstashThreshold: Int
        val expandThreshold: Int
        val maxOverscroll: Int
        val stashThreshold: Int
    }

    private class DefaultDimensionProvider(taskbarActivityContext: TaskbarActivityContext) :
        DimensionProvider {
        override val unstashThreshold: Int
        override val expandThreshold: Int
        override val maxOverscroll: Int
        override val stashThreshold: Int

        init {
            val resources = taskbarActivityContext.resources
            unstashThreshold =
                TaskbarThresholdUtils.getFromNavThreshold(
                    resources,
                    taskbarActivityContext.deviceProfile,
                )
            // TODO(325673340): review threshold with ux
            expandThreshold =
                TaskbarThresholdUtils.getAppWindowThreshold(
                    resources,
                    taskbarActivityContext.deviceProfile,
                )
            maxOverscroll = taskbarActivityContext.deviceProfile.heightPx - unstashThreshold
            stashThreshold = resources.getDimensionPixelSize(R.dimen.taskbar_to_nav_threshold)
        }
    }
}
