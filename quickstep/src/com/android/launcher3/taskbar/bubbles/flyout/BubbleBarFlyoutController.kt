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

package com.android.launcher3.taskbar.bubbles.flyout

import android.graphics.Rect
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.animation.ValueAnimator
import com.android.launcher3.R
import com.android.systemui.util.addListener

/** Creates and manages the visibility of the [BubbleBarFlyoutView]. */
class BubbleBarFlyoutController
@JvmOverloads
constructor(
    private val container: FrameLayout,
    private val positioner: BubbleBarFlyoutPositioner,
    private val callbacks: FlyoutCallbacks,
    private val flyoutScheduler: FlyoutScheduler = HandlerScheduler(container),
) {

    private companion object {
        const val ANIMATION_DURATION_MS = 250L
    }

    private var flyout: BubbleBarFlyoutView? = null
    private val horizontalMargin =
        container.context.resources.getDimensionPixelSize(R.dimen.transient_taskbar_bottom_margin)

    private enum class AnimationType {
        COLLAPSE,
        FADE,
    }

    /** The bounds of the flyout. */
    val flyoutBounds: Rect?
        get() {
            val flyout = this.flyout ?: return null
            val rect = Rect(flyout.bounds)
            rect.offset(0, flyout.translationY.toInt())
            return rect
        }

    fun setUpAndShowFlyout(message: BubbleBarFlyoutMessage, onEnd: () -> Unit) {
        flyout?.let(container::removeView)
        val flyout = BubbleBarFlyoutView(container.context, positioner, flyoutScheduler)

        flyout.translationY = positioner.targetTy

        val lp =
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or if (positioner.isOnLeft) Gravity.LEFT else Gravity.RIGHT,
            )
        lp.marginStart = horizontalMargin
        lp.marginEnd = horizontalMargin
        container.addView(flyout, lp)

        val animator = ValueAnimator.ofFloat(0f, 1f).setDuration(ANIMATION_DURATION_MS)
        animator.addUpdateListener { _ ->
            flyout.updateExpansionProgress(animator.animatedValue as Float)
        }
        animator.addListener(
            onStart = {
                val flyoutTop = flyout.top + flyout.translationY
                // If the top position of the flyout is negative, then it's bleeding over the
                // top boundary of its parent view
                if (flyoutTop < 0) callbacks.extendTopBoundary(space = -flyoutTop.toInt())
            },
            onEnd = {
                onEnd()
                flyout.setOnClickListener { callbacks.flyoutClicked() }
            },
        )
        flyout.showFromCollapsed(message) { animator.start() }
        this.flyout = flyout
    }

    fun cancelFlyout(endAction: () -> Unit) {
        hideFlyout(AnimationType.FADE, endAction)
    }

    fun collapseFlyout(endAction: () -> Unit) {
        hideFlyout(AnimationType.COLLAPSE, endAction)
    }

    private fun hideFlyout(animationType: AnimationType, endAction: () -> Unit) {
        // TODO: b/277815200 - stop the current animation if it's running
        val flyout = this.flyout ?: return
        val animator = ValueAnimator.ofFloat(1f, 0f).setDuration(ANIMATION_DURATION_MS)
        when (animationType) {
            AnimationType.FADE ->
                animator.addUpdateListener { _ -> flyout.alpha = animator.animatedValue as Float }
            AnimationType.COLLAPSE ->
                animator.addUpdateListener { _ ->
                    flyout.updateExpansionProgress(animator.animatedValue as Float)
                }
        }
        animator.addListener(
            onStart = { flyout.setOnClickListener(null) },
            onEnd = {
                container.removeView(flyout)
                this@BubbleBarFlyoutController.flyout = null
                callbacks.resetTopBoundary()
                endAction()
            },
        )
        animator.start()
    }
}
