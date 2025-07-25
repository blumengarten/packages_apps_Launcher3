/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import com.android.app.animation.Interpolators
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.Utilities.mapToRange
import com.android.launcher3.icons.GraphicsUtils.setColorAlphaBound
import com.android.launcher3.popup.RoundedArrowDrawable
import kotlin.math.max
import kotlin.math.min

/** Drawable for the background of the bubble bar. */
class BubbleBarBackground(context: Context, private var backgroundHeight: Float) : Drawable() {

    private val fillPaint: Paint = Paint()
    private val strokePaint: Paint = Paint()
    private val arrowWidth: Float
    private val arrowHeight: Float
    private val arrowTipRadius: Float
    private val arrowVisibleHeight: Float

    private val strokeAlpha: Int
    private val strokeColor: Int
    private val strokeColorDropTarget: Int
    private val shadowAlpha: Int
    private val shadowBlur: Float
    private val keyShadowDistance: Float
    private var arrowHeightFraction = 1f
    private var isShowingDropTarget: Boolean = false

    var arrowPositionX: Float = 0f
        private set

    private var showingArrow: Boolean = false

    var width: Float = 0f

    /**
     * Set whether the drawable is anchored to the left or right edge of the container.
     *
     * When `anchorLeft` is set to `true`, drawable left edge aligns up with the container left
     * edge. Drawable can be drawn outside container bounds on the right edge. When it is set to
     * `false` (the default), drawable right edge aligns up with the container right edge. Drawable
     * can be drawn outside container bounds on the left edge.
     */
    var anchorLeft: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    /**
     * Scale of the background in the x direction. Pivot is at the left edge if [anchorLeft] is
     * `true` and at the right edge if it is `false`
     */
    var scaleX: Float = 1f
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    /** Scale of the background in the y direction. Pivot is at the bottom edge. */
    var scaleY: Float = 1f
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    init {
        val res = context.resources
        // configure fill paint
        fillPaint.color = context.getColor(R.color.taskbar_background)
        fillPaint.flags = Paint.ANTI_ALIAS_FLAG
        fillPaint.style = Paint.Style.FILL
        // configure stroke paint
        strokeColor = context.getColor(R.color.taskbar_stroke)
        strokeColorDropTarget = context.getColor(com.android.internal.R.color.system_primary_fixed)
        strokePaint.color = strokeColor
        strokePaint.flags = Paint.ANTI_ALIAS_FLAG
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = res.getDimension(R.dimen.transient_taskbar_stroke_width)
        // apply theme alpha attributes
        if (Utilities.isDarkTheme(context)) {
            strokeAlpha = DARK_THEME_STROKE_ALPHA
            shadowAlpha = DARK_THEME_SHADOW_ALPHA
        } else {
            strokeAlpha = LIGHT_THEME_STROKE_ALPHA
            shadowAlpha = LIGHT_THEME_SHADOW_ALPHA
        }
        strokePaint.alpha = strokeAlpha
        shadowBlur = res.getDimension(R.dimen.transient_taskbar_shadow_blur)
        keyShadowDistance = res.getDimension(R.dimen.transient_taskbar_key_shadow_distance)
        arrowWidth = res.getDimension(R.dimen.bubblebar_pointer_width)
        arrowHeight = res.getDimension(R.dimen.bubblebar_pointer_height)
        arrowVisibleHeight = res.getDimension(R.dimen.bubblebar_pointer_visible_size)
        arrowTipRadius = res.getDimension(R.dimen.bubblebar_pointer_radius)
    }

    fun showArrow(show: Boolean) {
        showingArrow = show
    }

    fun setArrowPosition(x: Float) {
        arrowPositionX = x
    }

    /** Draws the background with the given paint and height, on the provided canvas. */
    override fun draw(canvas: Canvas) {
        canvas.save()

        // Draw shadows.
        val newShadowAlpha =
            mapToRange(fillPaint.alpha, 0, 255, 0, shadowAlpha, Interpolators.LINEAR)
        fillPaint.setShadowLayer(
            shadowBlur,
            0f,
            keyShadowDistance,
            setColorAlphaBound(Color.BLACK, newShadowAlpha),
        )
        // Create background path
        val backgroundPath = Path()
        val scaledBackgroundHeight = backgroundHeight * scaleY
        val scaledWidth = width * scaleX
        val topOffset = scaledBackgroundHeight - bounds.height().toFloat()
        val radius = backgroundHeight / 2f

        val left = bounds.left + (if (anchorLeft) 0f else bounds.width().toFloat() - scaledWidth)
        val right = bounds.left + (if (anchorLeft) scaledWidth else bounds.width().toFloat())
        // Calculate top with scaled heights for background and arrow to align with stash handle
        val top = bounds.bottom - scaledBackgroundHeight + getScaledArrowVisibleHeight()
        val bottom = bounds.bottom.toFloat()

        backgroundPath.addRoundRect(left, top, right, bottom, radius, radius, Path.Direction.CW)
        addArrowPathIfNeeded(backgroundPath, topOffset)

        // Draw background.
        canvas.drawPath(backgroundPath, fillPaint)
        canvas.drawPath(backgroundPath, strokePaint)
        canvas.restore()
    }

    private fun addArrowPathIfNeeded(sourcePath: Path, topOffset: Float) {
        if (!showingArrow || arrowHeightFraction <= 0) return
        val arrowPath = Path()
        val scaledHeight = getScaledArrowHeight()
        RoundedArrowDrawable.addDownPointingRoundedTriangleToPath(
            arrowWidth,
            scaledHeight,
            arrowTipRadius,
            arrowPath,
        )
        // flip it horizontally
        val pathTransform = Matrix()
        pathTransform.setRotate(180f, arrowWidth * 0.5f, scaledHeight * 0.5f)
        arrowPath.transform(pathTransform)
        // shift to arrow position
        val arrowStart = bounds.left + arrowPositionX - (arrowWidth / 2f)
        val arrowTop = (1 - arrowHeightFraction) * getScaledArrowVisibleHeight() - topOffset
        arrowPath.offset(arrowStart, arrowTop)
        // union with rectangle
        sourcePath.op(arrowPath, Path.Op.UNION)
    }

    override fun getOpacity(): Int {
        return when (fillPaint.alpha) {
            255 -> PixelFormat.OPAQUE
            0 -> PixelFormat.TRANSPARENT
            else -> PixelFormat.TRANSLUCENT
        }
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = mapToRange(alpha, 0, 255, 0, strokeAlpha, Interpolators.LINEAR)
        invalidateSelf()
    }

    override fun getAlpha(): Int {
        return fillPaint.alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
    }

    fun setBackgroundHeight(newHeight: Float) {
        backgroundHeight = newHeight
        invalidateSelf()
    }

    /**
     * Set fraction of the arrow height that should be displayed. Allowed values range are [0..1].
     * If value passed is out of range it will be converted to the closest value in tha allowed
     * range.
     */
    fun setArrowHeightFraction(arrowHeightFraction: Float) {
        var newHeightFraction = arrowHeightFraction
        if (newHeightFraction !in 0f..1f) {
            newHeightFraction = min(max(newHeightFraction, 0f), 1f)
        }
        this.arrowHeightFraction = newHeightFraction
        invalidateSelf()
    }

    private fun getScaledArrowHeight(): Float {
        return arrowHeight * scaleY
    }

    private fun getScaledArrowVisibleHeight(): Float {
        return max(0f, getScaledArrowHeight() - (arrowHeight - arrowVisibleHeight))
    }

    /** Set whether the background should show the drop target */
    fun showDropTarget(isDropTarget: Boolean) {
        if (isShowingDropTarget == isDropTarget) {
            return
        }
        isShowingDropTarget = isDropTarget
        val strokeColor = if (isDropTarget) strokeColorDropTarget else strokeColor
        val alpha = if (isDropTarget) DRAG_STROKE_ALPHA else strokeAlpha
        strokePaint.color = strokeColor
        strokePaint.alpha = alpha
        invalidateSelf()
    }

    fun isShowingDropTarget() = isShowingDropTarget

    companion object {
        private const val DARK_THEME_STROKE_ALPHA = 51
        private const val LIGHT_THEME_STROKE_ALPHA = 41
        private const val DRAG_STROKE_ALPHA = 255
        private const val DARK_THEME_SHADOW_ALPHA = 51
        private const val LIGHT_THEME_SHADOW_ALPHA = 25
    }
}
