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

import android.animation.RectEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.util.TypedValue
import android.view.SurfaceControl
import android.view.animation.Interpolator
import android.window.TransitionInfo

/** Creates animations that can be applied to windows/surfaces. */
object WindowAnimator {

    /** Parameters defining a window bounds animation. */
    data class BoundsAnimationParams(
        val durationMs: Long,
        val startOffsetYDp: Float = 0f,
        val endOffsetYDp: Float = 0f,
        val startScale: Float = 1f,
        val endScale: Float = 1f,
        val interpolator: Interpolator,
    )

    /**
     * Creates an animator to reposition and scale the bounds of the leash of the given change.
     *
     * @param boundsAnimDef the parameters for the animation itself (duration, scale, position)
     * @param change the change to which the animation should be applied
     * @param transaction the transaction to apply the animation to
     */
    fun createBoundsAnimator(
        context: Context,
        boundsAnimDef: BoundsAnimationParams,
        change: TransitionInfo.Change,
        transaction: SurfaceControl.Transaction,
    ): ValueAnimator {
        val startBounds =
            createBounds(
                context,
                change.startAbsBounds,
                boundsAnimDef.startScale,
                boundsAnimDef.startOffsetYDp,
            )
        val leash = change.leash
        val endBounds =
            createBounds(
                context,
                change.startAbsBounds,
                boundsAnimDef.endScale,
                boundsAnimDef.endOffsetYDp,
            )
        return ValueAnimator.ofObject(RectEvaluator(), startBounds, endBounds).apply {
            duration = boundsAnimDef.durationMs
            interpolator = boundsAnimDef.interpolator
            addUpdateListener { animation ->
                val animBounds = animation.animatedValue as Rect
                val animScale = 1 - (1 - boundsAnimDef.endScale) * animation.animatedFraction
                transaction
                    .setPosition(leash, animBounds.left.toFloat(), animBounds.top.toFloat())
                    .setScale(leash, animScale, animScale)
                    .apply()
            }
        }
    }

    private fun createBounds(context: Context, origBounds: Rect, scale: Float, offsetYDp: Float) =
        Rect(origBounds).apply {
            check(scale in 0.0..1.0)
            // Scale the  bounds down with an anchor in the center
            inset(
                (origBounds.width().toFloat() * (1 - scale) / 2).toInt(),
                (origBounds.height().toFloat() * (1 - scale) / 2).toInt(),
            )
            val offsetYPx =
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        offsetYDp,
                        context.resources.displayMetrics,
                    )
                    .toInt()
            offset(/* dx= */ 0, offsetYPx)
        }
}
