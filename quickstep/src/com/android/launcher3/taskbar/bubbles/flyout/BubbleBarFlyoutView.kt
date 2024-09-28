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

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.android.launcher3.R
import com.android.launcher3.popup.RoundedArrowDrawable

/** The flyout view used to notify the user of a new bubble notification. */
class BubbleBarFlyoutView(context: Context, private val positioner: BubbleBarFlyoutPositioner) :
    ConstraintLayout(context) {

    private companion object {
        // the minimum progress of the expansion animation before the triangle is made visible.
        const val MIN_EXPANSION_PROGRESS_FOR_TRIANGLE = 0.1f
    }

    private val sender: TextView by
        lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.bubble_flyout_name) }

    private val avatar: ImageView by
        lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.bubble_flyout_avatar) }

    private val message: TextView by
        lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.bubble_flyout_text) }

    private val flyoutPadding by
        lazy(LazyThreadSafetyMode.NONE) {
            context.resources.getDimensionPixelSize(R.dimen.bubblebar_flyout_padding)
        }

    private val triangleHeight by
        lazy(LazyThreadSafetyMode.NONE) {
            context.resources.getDimensionPixelSize(R.dimen.bubblebar_flyout_triangle_height)
        }

    private val triangleOverlap by
        lazy(LazyThreadSafetyMode.NONE) {
            context.resources.getDimensionPixelSize(
                R.dimen.bubblebar_flyout_triangle_overlap_amount
            )
        }

    private val triangleWidth by
        lazy(LazyThreadSafetyMode.NONE) {
            context.resources.getDimensionPixelSize(R.dimen.bubblebar_flyout_triangle_width)
        }

    private val triangleRadius by
        lazy(LazyThreadSafetyMode.NONE) {
            context.resources.getDimensionPixelSize(R.dimen.bubblebar_flyout_triangle_radius)
        }

    private val minFlyoutWidth by
        lazy(LazyThreadSafetyMode.NONE) {
            context.resources.getDimensionPixelSize(R.dimen.bubblebar_flyout_min_width)
        }

    private val maxFlyoutWidth by
        lazy(LazyThreadSafetyMode.NONE) {
            context.resources.getDimensionPixelSize(R.dimen.bubblebar_flyout_max_width)
        }

    private val cornerRadius: Float
    private val triangle: Path = Path()
    private var backgroundColor = Color.BLACK
    /** Represents the progress of the expansion animation. 0 when collapsed. 1 when expanded. */
    private var expansionProgress = 0f
    /** Translation x-y values to move the flyout to its collapsed position. */
    private var translationToCollapsedPosition = PointF(0f, 0f)
    /** The size of the flyout when it's collapsed. */
    private var collapsedSize = 0f
    /** The corner radius of the flyout when it's collapsed. */
    private var collapsedCornerRadius = 0f

    /**
     * The paint used to draw the background, whose color changes as the flyout transitions to the
     * tinted notification dot.
     */
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init {
        LayoutInflater.from(context).inflate(R.layout.bubblebar_flyout, this, true)

        val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.dialogCornerRadius))
        cornerRadius = ta.getDimensionPixelSize(0, 0).toFloat()
        ta.recycle()

        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false

        val padding = context.resources.getDimensionPixelSize(R.dimen.bubblebar_flyout_padding)
        // add extra padding to the bottom of the view to include the triangle
        setPadding(padding, padding, padding, padding + triangleHeight - triangleOverlap)
        translationZ =
            context.resources.getDimensionPixelSize(R.dimen.bubblebar_flyout_elevation).toFloat()

        RoundedArrowDrawable.addDownPointingRoundedTriangleToPath(
            triangleWidth.toFloat(),
            triangleHeight.toFloat(),
            triangleRadius.toFloat(),
            triangle,
        )

        applyConfigurationColors(resources.configuration)
    }

    /** Sets the data for the flyout and starts playing the expand animation. */
    fun showFromCollapsed(flyoutMessage: BubbleBarFlyoutMessage, expandAnimation: () -> Unit) {
        setData(flyoutMessage)
        val txToCollapsedPosition =
            if (positioner.isOnLeft) {
                positioner.distanceToCollapsedPosition.x
            } else {
                -positioner.distanceToCollapsedPosition.x
            }
        val tyToCollapsedPosition =
            positioner.distanceToCollapsedPosition.y + triangleHeight - triangleOverlap
        translationToCollapsedPosition = PointF(txToCollapsedPosition, tyToCollapsedPosition)

        collapsedSize = positioner.collapsedSize
        collapsedCornerRadius = collapsedSize / 2

        // post the request to start the expand animation to the looper so the view can measure
        // itself
        post(expandAnimation)
    }

    private fun setData(flyoutMessage: BubbleBarFlyoutMessage) {
        // the avatar is only displayed in group chat messages
        if (flyoutMessage.senderAvatar != null && flyoutMessage.isGroupChat) {
            avatar.visibility = VISIBLE
            avatar.setImageDrawable(flyoutMessage.senderAvatar)
        } else {
            avatar.visibility = GONE
        }

        val minTextViewWidth: Int
        val maxTextViewWidth: Int
        if (avatar.visibility == VISIBLE) {
            minTextViewWidth = minFlyoutWidth - avatar.width - flyoutPadding * 2
            maxTextViewWidth = maxFlyoutWidth - avatar.width - flyoutPadding * 2
        } else {
            // when there's no avatar, the width of the text view is constant, so we're setting the
            // min and max to the same value
            minTextViewWidth = minFlyoutWidth - flyoutPadding * 2
            maxTextViewWidth = minTextViewWidth
        }

        if (flyoutMessage.senderName.isEmpty()) {
            sender.visibility = GONE
        } else {
            sender.minWidth = minTextViewWidth
            sender.maxWidth = maxTextViewWidth
            sender.text = flyoutMessage.senderName
            sender.visibility = VISIBLE
        }

        message.minWidth = minTextViewWidth
        message.maxWidth = maxTextViewWidth
        message.text = flyoutMessage.message
    }

    /** Updates the flyout view with the progress of the animation. */
    fun updateExpansionProgress(fraction: Float) {
        expansionProgress = fraction
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // interpolate the width, height, corner radius and translation based on the progress of the
        // animation

        val currentWidth = collapsedSize + (width - collapsedSize) * expansionProgress
        val rectBottom = height - triangleHeight + triangleOverlap
        val currentHeight = collapsedSize + (rectBottom - collapsedSize) * expansionProgress
        val currentCornerRadius =
            collapsedCornerRadius + (cornerRadius - collapsedCornerRadius) * expansionProgress
        val tx = translationToCollapsedPosition.x * (1 - expansionProgress)
        val ty = translationToCollapsedPosition.y * (1 - expansionProgress)

        canvas.save()
        canvas.translate(tx, ty)
        // draw the background starting from the bottom left if we're positioned left, or the bottom
        // right if we're positioned right.
        canvas.drawRoundRect(
            if (positioner.isOnLeft) 0f else width.toFloat() - currentWidth,
            height.toFloat() - triangleHeight + triangleOverlap - currentHeight,
            if (positioner.isOnLeft) currentWidth else width.toFloat(),
            height.toFloat() - triangleHeight + triangleOverlap,
            currentCornerRadius,
            currentCornerRadius,
            backgroundPaint,
        )
        if (expansionProgress >= MIN_EXPANSION_PROGRESS_FOR_TRIANGLE) {
            drawTriangle(canvas, currentCornerRadius)
        }
        canvas.restore()
        super.onDraw(canvas)
    }

    private fun drawTriangle(canvas: Canvas, currentCornerRadius: Float) {
        canvas.save()
        val triangleX =
            if (positioner.isOnLeft) {
                currentCornerRadius
            } else {
                width - currentCornerRadius - triangleWidth
            }
        // instead of scaling the triangle, increasingly reveal it from the background, starting
        // with half the size. this has the effect of the triangle scaling.
        val triangleY = height - triangleHeight - 0.5f * triangleHeight * (1 - expansionProgress)
        canvas.translate(triangleX, triangleY)
        canvas.drawPath(triangle, backgroundPaint)
        canvas.restore()
    }

    private fun applyConfigurationColors(configuration: Configuration) {
        val nightModeFlags = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isNightModeOn = nightModeFlags == Configuration.UI_MODE_NIGHT_YES
        val defaultBackgroundColor = if (isNightModeOn) Color.BLACK else Color.WHITE
        val defaultTextColor = if (isNightModeOn) Color.WHITE else Color.BLACK
        val ta =
            context.obtainStyledAttributes(
                intArrayOf(
                    com.android.internal.R.attr.materialColorSurfaceContainer,
                    com.android.internal.R.attr.materialColorOnSurface,
                    com.android.internal.R.attr.materialColorOnSurfaceVariant,
                )
            )
        backgroundColor = ta.getColor(0, defaultBackgroundColor)
        sender.setTextColor(ta.getColor(1, defaultTextColor))
        message.setTextColor(ta.getColor(2, defaultTextColor))
        ta.recycle()
        backgroundPaint.color = backgroundColor
    }
}
