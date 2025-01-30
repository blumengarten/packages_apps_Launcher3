/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.launcher3.graphics

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Matrix.ScaleToFit.FILL
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.View
import android.view.ViewOutlineProvider
import androidx.annotation.VisibleForTesting
import androidx.core.graphics.PathParser
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.SvgPathParser
import androidx.graphics.shapes.toPath
import androidx.graphics.shapes.transformed
import com.android.launcher3.anim.RoundedRectRevealOutlineProvider
import com.android.launcher3.dagger.LauncherAppComponent
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.graphics.ThemeManager.ThemeChangeListener
import com.android.launcher3.icons.GraphicsUtils
import com.android.launcher3.icons.IconNormalizer.normalizeAdaptiveIcon
import com.android.launcher3.util.DaggerSingletonObject
import com.android.launcher3.util.DaggerSingletonTracker
import com.android.launcher3.views.ClipPathView
import javax.inject.Inject

/** Abstract representation of the shape of an icon shape */
@LauncherAppSingleton
class IconShape
@Inject
constructor(private val themeManager: ThemeManager, lifeCycle: DaggerSingletonTracker) {

    val normalizationScale =
        normalizeAdaptiveIcon(
            AdaptiveIconDrawable(null, ColorDrawable(Color.BLACK)),
            AREA_CALC_SIZE,
        )

    var shape: ShapeDelegate = pickBestShape(themeManager.iconState.iconMask)
        private set

    var folderShape: ShapeDelegate =
        themeManager.iconState.run {
            if (folderShapeMask == iconMask || folderShapeMask.isEmpty()) shape
            else pickBestShape(folderShapeMask)
        }
        private set

    init {
        val changeListener = ThemeChangeListener {
            shape = pickBestShape(themeManager.iconState.iconMask)
            folderShape =
                themeManager.iconState.run {
                    if (folderShapeMask == iconMask || folderShapeMask.isEmpty()) shape
                    else pickBestShape(folderShapeMask)
                }
        }
        themeManager.addChangeListener(changeListener)
        lifeCycle.addCloseable { themeManager.removeChangeListener(changeListener) }
    }

    interface ShapeDelegate {
        fun getPath(pathSize: Float = DEFAULT_PATH_SIZE) =
            Path().apply { addToPath(this, 0f, 0f, pathSize / 2) }

        fun getPath(bounds: Rect) =
            Path().apply {
                addToPath(
                    this,
                    bounds.left.toFloat(),
                    bounds.top.toFloat(),
                    // Radius is half of the average size of the icon
                    (bounds.width() + bounds.height()) / 4f,
                )
            }

        fun drawShape(canvas: Canvas, offsetX: Float, offsetY: Float, radius: Float, paint: Paint)

        fun addToPath(path: Path, offsetX: Float, offsetY: Float, radius: Float)

        fun <T> createRevealAnimator(
            target: T,
            startRect: Rect,
            endRect: Rect,
            endRadius: Float,
            isReversed: Boolean,
        ): ValueAnimator where T : View, T : ClipPathView
    }

    class Circle : RoundedSquare(1f) {

        override fun drawShape(
            canvas: Canvas,
            offsetX: Float,
            offsetY: Float,
            radius: Float,
            paint: Paint,
        ) = canvas.drawCircle(radius + offsetX, radius + offsetY, radius, paint)

        override fun addToPath(path: Path, offsetX: Float, offsetY: Float, radius: Float) =
            path.addCircle(radius + offsetX, radius + offsetY, radius, Path.Direction.CW)
    }

    /** Rounded square with [radiusRatio] as a ratio of its half edge size */
    @VisibleForTesting
    open class RoundedSquare(val radiusRatio: Float) : ShapeDelegate {

        override fun drawShape(
            canvas: Canvas,
            offsetX: Float,
            offsetY: Float,
            radius: Float,
            paint: Paint,
        ) {
            val cx = radius + offsetX
            val cy = radius + offsetY
            val cr = radius * radiusRatio
            canvas.drawRoundRect(cx - radius, cy - radius, cx + radius, cy + radius, cr, cr, paint)
        }

        override fun addToPath(path: Path, offsetX: Float, offsetY: Float, radius: Float) {
            val cx = radius + offsetX
            val cy = radius + offsetY
            val cr = radius * radiusRatio
            path.addRoundRect(
                cx - radius,
                cy - radius,
                cx + radius,
                cy + radius,
                cr,
                cr,
                Path.Direction.CW,
            )
        }

        override fun <T> createRevealAnimator(
            target: T,
            startRect: Rect,
            endRect: Rect,
            endRadius: Float,
            isReversed: Boolean,
        ): ValueAnimator where T : View, T : ClipPathView {
            return object :
                    RoundedRectRevealOutlineProvider(
                        (startRect.width() / 2f) * radiusRatio,
                        endRadius,
                        startRect,
                        endRect,
                    ) {
                    override fun shouldRemoveElevationDuringAnimation() = true
                }
                .createRevealAnimator(target, isReversed)
        }
    }

    /** Generic shape delegate with pathString in bounds [0, 0, 100, 100] */
    class GenericPathShape(pathString: String) : ShapeDelegate {
        private val poly =
            RoundedPolygon(
                features = SvgPathParser.parseFeatures(pathString),
                centerX = 50f,
                centerY = 50f,
            )
        // This ensures that a valid morph is possible from the provided path
        private val basePath =
            Path().apply {
                Morph(poly, createRoundedRect(0f, 0f, 100f, 100f, 25f)).toPath(0f, this)
            }
        private val tmpPath = Path()
        private val tmpMatrix = Matrix()

        override fun drawShape(
            canvas: Canvas,
            offsetX: Float,
            offsetY: Float,
            radius: Float,
            paint: Paint,
        ) {
            tmpPath.reset()
            addToPath(tmpPath, offsetX, offsetY, radius, tmpMatrix)
            canvas.drawPath(tmpPath, paint)
        }

        override fun addToPath(path: Path, offsetX: Float, offsetY: Float, radius: Float) {
            addToPath(path, offsetX, offsetY, radius, Matrix())
        }

        private fun addToPath(
            path: Path,
            offsetX: Float,
            offsetY: Float,
            radius: Float,
            matrix: Matrix,
        ) {
            matrix.setScale(radius / 50, radius / 50)
            matrix.postTranslate(offsetX, offsetY)
            basePath.transform(matrix, path)
        }

        override fun <T> createRevealAnimator(
            target: T,
            startRect: Rect,
            endRect: Rect,
            endRadius: Float,
            isReversed: Boolean,
        ): ValueAnimator where T : View, T : ClipPathView {
            // End poly is defined as a rectangle starting at top/center so that the
            // transformation has minimum motion
            val morph =
                Morph(
                    start =
                        poly.transformed(
                            Matrix().apply {
                                setRectToRect(RectF(0f, 0f, 100f, 100f), RectF(startRect), FILL)
                            }
                        ),
                    end =
                        createRoundedRect(
                            left = endRect.left.toFloat(),
                            top = endRect.top.toFloat(),
                            right = endRect.right.toFloat(),
                            bottom = endRect.bottom.toFloat(),
                            cornerR = endRadius,
                        ),
                )

            val va =
                if (isReversed) ValueAnimator.ofFloat(1f, 0f) else ValueAnimator.ofFloat(0f, 1f)
            va.addListener(
                object : AnimatorListenerAdapter() {
                    private var oldOutlineProvider: ViewOutlineProvider? = null

                    override fun onAnimationStart(animation: Animator) {
                        target.apply {
                            oldOutlineProvider = outlineProvider
                            outlineProvider = null
                            translationZ = -target.elevation
                        }
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        target.apply {
                            translationZ = 0f
                            setClipPath(null)
                            outlineProvider = oldOutlineProvider
                        }
                    }
                }
            )

            val path = Path()
            va.addUpdateListener { anim: ValueAnimator ->
                path.reset()
                morph.toPath(anim.animatedValue as Float, path)
                target.setClipPath(path)
            }
            return va
        }
    }

    companion object {
        @JvmField var INSTANCE = DaggerSingletonObject(LauncherAppComponent::getIconShape)

        const val TAG = "IconShape"
        const val DEFAULT_PATH_SIZE = 100f
        const val AREA_CALC_SIZE = 1000
        // .1% error margin
        const val AREA_DIFF_THRESHOLD = AREA_CALC_SIZE * AREA_CALC_SIZE / 1000

        /** Returns a function to calculate area diff from [base] */
        @VisibleForTesting
        fun areaDiffCalculator(base: Path): (ShapeDelegate) -> Int {
            val fullRegion = Region(0, 0, AREA_CALC_SIZE, AREA_CALC_SIZE)
            val iconRegion = Region().apply { setPath(base, fullRegion) }

            val shapePath = Path()
            val shapeRegion = Region()
            return fun(shape: ShapeDelegate): Int {
                shapePath.reset()
                shape.addToPath(shapePath, 0f, 0f, AREA_CALC_SIZE / 2f)
                shapeRegion.setPath(shapePath, fullRegion)
                shapeRegion.op(iconRegion, Region.Op.XOR)
                return GraphicsUtils.getArea(shapeRegion)
            }
        }

        @VisibleForTesting
        fun pickBestShape(shapeStr: String): ShapeDelegate {
            val baseShape =
                if (shapeStr.isNotEmpty()) {
                    PathParser.createPathFromPathData(shapeStr).apply {
                        transform(
                            Matrix().apply {
                                setScale(AREA_CALC_SIZE / 100f, AREA_CALC_SIZE / 100f)
                            }
                        )
                    }
                } else {
                    AdaptiveIconDrawable(null, ColorDrawable(Color.BLACK)).let {
                        it.setBounds(0, 0, AREA_CALC_SIZE, AREA_CALC_SIZE)
                        it.iconMask
                    }
                }
            return pickBestShape(baseShape, shapeStr)
        }

        @VisibleForTesting
        fun pickBestShape(baseShape: Path, shapeStr: String): ShapeDelegate {
            val calcAreaDiff = areaDiffCalculator(baseShape)

            // Find the shape with minimum area of divergent region.
            var closestShape: ShapeDelegate = Circle()
            var minAreaDiff = calcAreaDiff(closestShape)

            // Try some common rounded rect edges
            for (f in 0..20) {
                val rectShape = RoundedSquare(f.toFloat() / 20)
                val rectArea = calcAreaDiff(rectShape)
                if (rectArea < minAreaDiff) {
                    minAreaDiff = rectArea
                    closestShape = rectShape
                }
            }

            // Use the generic shape only if we have more than .1% error
            if (shapeStr.isNotEmpty() && minAreaDiff > AREA_DIFF_THRESHOLD) {
                try {
                    val generic = GenericPathShape(shapeStr)
                    closestShape = generic
                } catch (e: Exception) {
                    Log.e(TAG, "Error converting mask to generic shape", e)
                }
            }
            return closestShape
        }

        /**
         * Creates a rounded rect with the start point at the center of the top edge. This ensures a
         * better animation since our shape paths also start at top-center of the bounding box.
         */
        fun createRoundedRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            cornerR: Float,
        ) =
            RoundedPolygon(
                vertices =
                    floatArrayOf(
                        // x1, y1
                        (left + right) / 2,
                        top,
                        // x2, y2
                        right,
                        top,
                        // x3, y3
                        right,
                        bottom,
                        // x4, y4
                        left,
                        bottom,
                        // x5, y5
                        left,
                        top,
                    ),
                centerX = (left + right) / 2,
                centerY = (top + bottom) / 2,
                rounding = CornerRounding(cornerR),
            )
    }
}
