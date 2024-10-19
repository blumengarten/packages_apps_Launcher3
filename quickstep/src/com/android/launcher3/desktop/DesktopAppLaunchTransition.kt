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

package com.android.launcher3.desktop

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.os.IBinder
import android.view.SurfaceControl.Transaction
import android.view.WindowManager.TRANSIT_OPEN
import android.view.WindowManager.TRANSIT_TO_BACK
import android.view.WindowManager.TRANSIT_TO_FRONT
import android.window.IRemoteTransitionFinishedCallback
import android.window.RemoteTransitionStub
import android.window.TransitionInfo
import android.window.TransitionInfo.Change
import androidx.core.animation.addListener
import com.android.app.animation.Interpolators
import com.android.quickstep.RemoteRunnable
import java.util.concurrent.Executor

/**
 * [android.window.RemoteTransition] for Desktop app launches.
 *
 * This transition supports minimize-changes, i.e. in a launch-transition, if a window is moved back
 * ([android.view.WindowManager.TRANSIT_TO_BACK]) this transition will apply a minimize animation to
 * that window.
 */
class DesktopAppLaunchTransition(private val context: Context, private val mainExecutor: Executor) :
    RemoteTransitionStub() {

    override fun startAnimation(
        token: IBinder,
        info: TransitionInfo,
        t: Transaction,
        transitionFinishedCallback: IRemoteTransitionFinishedCallback,
    ) {
        val safeTransitionFinishedCallback = RemoteRunnable {
            transitionFinishedCallback.onTransitionFinished(/* wct= */ null, /* sct= */ null)
        }
        mainExecutor.execute {
            runAnimators(info, safeTransitionFinishedCallback)
            t.apply()
        }
    }

    private fun runAnimators(info: TransitionInfo, finishedCallback: RemoteRunnable) {
        val animators = mutableListOf<Animator>()
        val animatorFinishedCallback: (Animator) -> Unit = { animator ->
            animators -= animator
            if (animators.isEmpty()) finishedCallback.run()
        }
        animators += createAnimators(info, animatorFinishedCallback)
        animators.forEach { it.start() }
    }

    private fun createAnimators(
        info: TransitionInfo,
        finishCallback: (Animator) -> Unit,
    ): List<Animator> {
        val transaction = Transaction()
        val launchAnimator =
            createLaunchAnimator(getLaunchChange(info), transaction, finishCallback)
        val minimizeChange = getMinimizeChange(info) ?: return listOf(launchAnimator)
        val minimizeAnimator = createMinimizeAnimator(minimizeChange, transaction, finishCallback)
        return listOf(launchAnimator, minimizeAnimator)
    }

    private fun getLaunchChange(info: TransitionInfo): Change =
        requireNotNull(info.changes.firstOrNull { change -> change.mode in LAUNCH_CHANGE_MODES }) {
            "expected an app launch Change"
        }

    private fun getMinimizeChange(info: TransitionInfo): Change? =
        info.changes.firstOrNull { change -> change.mode == TRANSIT_TO_BACK }

    private fun createLaunchAnimator(
        change: Change,
        transaction: Transaction,
        onAnimFinish: (Animator) -> Unit,
    ): Animator {
        val boundsAnimator =
            WindowAnimator.createBoundsAnimator(
                context,
                launchBoundsAnimationDef,
                change,
                transaction,
            )
        val alphaAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = LAUNCH_ANIM_ALPHA_DURATION_MS
                interpolator = Interpolators.LINEAR
                addUpdateListener { animation ->
                    transaction.setAlpha(change.leash, animation.animatedValue as Float).apply()
                }
            }
        return AnimatorSet().apply {
            playTogether(boundsAnimator, alphaAnimator)
            addListener(onEnd = { animation -> onAnimFinish(animation) })
        }
    }

    private fun createMinimizeAnimator(
        change: Change,
        transaction: Transaction,
        onAnimFinish: (Animator) -> Unit,
    ): Animator {
        val boundsAnimator =
            WindowAnimator.createBoundsAnimator(
                context,
                minimizeBoundsAnimationDef,
                change,
                transaction,
            )
        val alphaAnimator =
            ValueAnimator.ofFloat(1f, 0f).apply {
                duration = MINIMIZE_ANIM_ALPHA_DURATION_MS
                interpolator = Interpolators.LINEAR
                addUpdateListener { animation ->
                    transaction.setAlpha(change.leash, animation.animatedValue as Float).apply()
                }
            }
        return AnimatorSet().apply {
            playTogether(boundsAnimator, alphaAnimator)
            addListener(onEnd = { animation -> onAnimFinish(animation) })
        }
    }

    companion object {
        private val LAUNCH_CHANGE_MODES = intArrayOf(TRANSIT_OPEN, TRANSIT_TO_FRONT)

        private const val LAUNCH_ANIM_ALPHA_DURATION_MS = 100L
        private const val MINIMIZE_ANIM_ALPHA_DURATION_MS = 100L

        private val launchBoundsAnimationDef =
            WindowAnimator.BoundsAnimationParams(
                durationMs = 300,
                startOffsetYDp = 12f,
                startScale = 0.97f,
                interpolator = Interpolators.STANDARD_DECELERATE,
            )

        private val minimizeBoundsAnimationDef =
            WindowAnimator.BoundsAnimationParams(
                durationMs = 200,
                endOffsetYDp = 12f,
                endScale = 0.97f,
                interpolator = Interpolators.STANDARD_ACCELERATE,
            )
    }
}
