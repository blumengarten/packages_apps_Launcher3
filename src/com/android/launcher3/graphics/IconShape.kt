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
import android.animation.FloatArrayEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.util.Xml
import android.view.View
import android.view.ViewOutlineProvider
import com.android.launcher3.R
import com.android.launcher3.anim.RoundedRectRevealOutlineProvider
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppComponent
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.graphics.ThemeManager.ThemeChangeListener
import com.android.launcher3.icons.GraphicsUtils
import com.android.launcher3.icons.IconNormalizer
import com.android.launcher3.util.DaggerSingletonObject
import com.android.launcher3.util.DaggerSingletonTracker
import com.android.launcher3.views.ClipPathView
import java.io.IOException
import javax.inject.Inject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/** Abstract representation of the shape of an icon shape */
@LauncherAppSingleton
class IconShape
@Inject
constructor(
    @ApplicationContext context: Context,
    themeManager: ThemeManager,
    lifeCycle: DaggerSingletonTracker,
) {
    var shape: ShapeDelegate = Circle()
        private set

    var normalizationScale: Float = IconNormalizer.ICON_VISIBLE_AREA_FACTOR
        private set

    init {
        pickBestShape(context)

        val changeListener = ThemeChangeListener { pickBestShape(context) }
        themeManager.addChangeListener(changeListener)
        lifeCycle.addCloseable { themeManager.removeChangeListener(changeListener) }
    }

    /** Initializes the shape which is closest to the [AdaptiveIconDrawable] */
    fun pickBestShape(context: Context) {
        // Pick any large size
        val size = 200
        val full = Region(0, 0, size, size)
        val shapePath = Path()
        val shapeR = Region()
        val iconR = Region()
        val drawable = AdaptiveIconDrawable(ColorDrawable(Color.BLACK), ColorDrawable(Color.BLACK))
        drawable.setBounds(0, 0, size, size)
        iconR.setPath(drawable.iconMask, full)

        // Find the shape with minimum area of divergent region.
        var minArea = Int.MAX_VALUE
        var closestShape: ShapeDelegate? = null
        for (shape in getAllShapes(context)) {
            shapePath.reset()
            shape.addToPath(shapePath, 0f, 0f, size / 2f)
            shapeR.setPath(shapePath, full)
            shapeR.op(iconR, Region.Op.XOR)

            val area = GraphicsUtils.getArea(shapeR)
            if (area < minArea) {
                minArea = area
                closestShape = shape
            }
        }

        if (closestShape != null) {
            shape = closestShape
        }

        // Initialize shape properties
        normalizationScale = IconNormalizer.normalizeAdaptiveIcon(drawable, size, null)
    }

    interface ShapeDelegate {
        fun enableShapeDetection(): Boolean {
            return false
        }

        fun drawShape(canvas: Canvas, offsetX: Float, offsetY: Float, radius: Float, paint: Paint)

        fun addToPath(path: Path, offsetX: Float, offsetY: Float, radius: Float)

        fun <T> createRevealAnimator(
            target: T,
            startRect: Rect,
            endRect: Rect,
            endRadius: Float,
            isReversed: Boolean,
        ): ValueAnimator where T : View?, T : ClipPathView?
    }

    /** Abstract shape where the reveal animation is a derivative of a round rect animation */
    private abstract class SimpleRectShape : ShapeDelegate {
        override fun <T> createRevealAnimator(
            target: T,
            startRect: Rect,
            endRect: Rect,
            endRadius: Float,
            isReversed: Boolean,
        ): ValueAnimator where T : View?, T : ClipPathView? {
            return object :
                    RoundedRectRevealOutlineProvider(
                        getStartRadius(startRect),
                        endRadius,
                        startRect,
                        endRect,
                    ) {
                    override fun shouldRemoveElevationDuringAnimation(): Boolean {
                        return true
                    }
                }
                .createRevealAnimator(target, isReversed)
        }

        protected abstract fun getStartRadius(startRect: Rect): Float
    }

    /** Abstract shape which draws using [Path] */
    abstract class PathShape : ShapeDelegate {
        private val mTmpPath = Path()

        override fun drawShape(
            canvas: Canvas,
            offsetX: Float,
            offsetY: Float,
            radius: Float,
            paint: Paint,
        ) {
            mTmpPath.reset()
            addToPath(mTmpPath, offsetX, offsetY, radius)
            canvas.drawPath(mTmpPath, paint)
        }

        protected abstract fun newUpdateListener(
            startRect: Rect,
            endRect: Rect,
            endRadius: Float,
            outPath: Path,
        ): ValueAnimator.AnimatorUpdateListener

        override fun <T> createRevealAnimator(
            target: T,
            startRect: Rect,
            endRect: Rect,
            endRadius: Float,
            isReversed: Boolean,
        ): ValueAnimator where T : View?, T : ClipPathView? {
            val path = Path()
            val listener = newUpdateListener(startRect, endRect, endRadius, path)

            val va =
                if (isReversed) ValueAnimator.ofFloat(1f, 0f) else ValueAnimator.ofFloat(0f, 1f)
            va.addListener(
                object : AnimatorListenerAdapter() {
                    private var mOldOutlineProvider: ViewOutlineProvider? = null

                    override fun onAnimationStart(animation: Animator) {
                        target?.apply {
                            mOldOutlineProvider = outlineProvider
                            outlineProvider = null
                            translationZ = -target.elevation
                        }
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        target?.apply {
                            translationZ = 0f
                            setClipPath(null)
                            outlineProvider = mOldOutlineProvider
                        }
                    }
                }
            )

            va.addUpdateListener { anim: ValueAnimator ->
                path.reset()
                listener.onAnimationUpdate(anim)
                target?.setClipPath(path)
            }

            return va
        }
    }

    open class Circle : PathShape() {
        private val mTempRadii = FloatArray(8)

        override fun newUpdateListener(
            startRect: Rect,
            endRect: Rect,
            endRadius: Float,
            outPath: Path,
        ): ValueAnimator.AnimatorUpdateListener {
            val r1 = getStartRadius(startRect)

            val startValues =
                floatArrayOf(
                    startRect.left.toFloat(),
                    startRect.top.toFloat(),
                    startRect.right.toFloat(),
                    startRect.bottom.toFloat(),
                    r1,
                    r1,
                )
            val endValues =
                floatArrayOf(
                    endRect.left.toFloat(),
                    endRect.top.toFloat(),
                    endRect.right.toFloat(),
                    endRect.bottom.toFloat(),
                    endRadius,
                    endRadius,
                )

            val evaluator = FloatArrayEvaluator(FloatArray(6))

            return ValueAnimator.AnimatorUpdateListener { anim: ValueAnimator ->
                val progress = anim.animatedValue as Float
                val values = evaluator.evaluate(progress, startValues, endValues)
                outPath.addRoundRect(
                    values[0],
                    values[1],
                    values[2],
                    values[3],
                    getRadiiArray(values[4], values[5]),
                    Path.Direction.CW,
                )
            }
        }

        private fun getRadiiArray(r1: Float, r2: Float): FloatArray {
            mTempRadii[7] = r1
            mTempRadii[6] = mTempRadii[7]
            mTempRadii[3] = mTempRadii[6]
            mTempRadii[2] = mTempRadii[3]
            mTempRadii[1] = mTempRadii[2]
            mTempRadii[0] = mTempRadii[1]
            mTempRadii[5] = r2
            mTempRadii[4] = mTempRadii[5]
            return mTempRadii
        }

        override fun addToPath(path: Path, offsetX: Float, offsetY: Float, radius: Float) {
            path.addCircle(radius + offsetX, radius + offsetY, radius, Path.Direction.CW)
        }

        private fun getStartRadius(startRect: Rect): Float {
            return startRect.width() / 2f
        }

        override fun enableShapeDetection(): Boolean {
            return true
        }
    }

    private class RoundedSquare(
        /** Ratio of corner radius to half size. */
        private val mRadiusRatio: Float
    ) : SimpleRectShape() {
        override fun drawShape(
            canvas: Canvas,
            offsetX: Float,
            offsetY: Float,
            radius: Float,
            paint: Paint,
        ) {
            val cx = radius + offsetX
            val cy = radius + offsetY
            val cr = radius * mRadiusRatio
            canvas.drawRoundRect(cx - radius, cy - radius, cx + radius, cy + radius, cr, cr, paint)
        }

        override fun addToPath(path: Path, offsetX: Float, offsetY: Float, radius: Float) {
            val cx = radius + offsetX
            val cy = radius + offsetY
            val cr = radius * mRadiusRatio
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

        override fun getStartRadius(startRect: Rect): Float {
            return (startRect.width() / 2f) * mRadiusRatio
        }
    }

    private class TearDrop(
        /**
         * Radio of short radius to large radius, based on the shape options defined in the config.
         */
        private val mRadiusRatio: Float
    ) : PathShape() {
        private val mTempRadii = FloatArray(8)

        override fun addToPath(path: Path, offsetX: Float, offsetY: Float, radius: Float) {
            val r2 = radius * mRadiusRatio
            val cx = radius + offsetX
            val cy = radius + offsetY

            path.addRoundRect(
                cx - radius,
                cy - radius,
                cx + radius,
                cy + radius,
                getRadiiArray(radius, r2),
                Path.Direction.CW,
            )
        }

        fun getRadiiArray(r1: Float, r2: Float): FloatArray {
            mTempRadii[7] = r1
            mTempRadii[6] = mTempRadii[7]
            mTempRadii[3] = mTempRadii[6]
            mTempRadii[2] = mTempRadii[3]
            mTempRadii[1] = mTempRadii[2]
            mTempRadii[0] = mTempRadii[1]
            mTempRadii[5] = r2
            mTempRadii[4] = mTempRadii[5]
            return mTempRadii
        }

        override fun newUpdateListener(
            startRect: Rect,
            endRect: Rect,
            endRadius: Float,
            outPath: Path,
        ): ValueAnimator.AnimatorUpdateListener {
            val r1 = startRect.width() / 2f
            val r2 = r1 * mRadiusRatio

            val startValues =
                floatArrayOf(
                    startRect.left.toFloat(),
                    startRect.top.toFloat(),
                    startRect.right.toFloat(),
                    startRect.bottom.toFloat(),
                    r1,
                    r2,
                )
            val endValues =
                floatArrayOf(
                    endRect.left.toFloat(),
                    endRect.top.toFloat(),
                    endRect.right.toFloat(),
                    endRect.bottom.toFloat(),
                    endRadius,
                    endRadius,
                )

            val evaluator = FloatArrayEvaluator(FloatArray(6))

            return ValueAnimator.AnimatorUpdateListener { anim: ValueAnimator ->
                val progress = anim.animatedValue as Float
                val values = evaluator.evaluate(progress, startValues, endValues)
                outPath.addRoundRect(
                    values[0],
                    values[1],
                    values[2],
                    values[3],
                    getRadiiArray(values[4], values[5]),
                    Path.Direction.CW,
                )
            }
        }
    }

    private class Squircle(
        /** Radio of radius to circle radius, based on the shape options defined in the config. */
        private val mRadiusRatio: Float
    ) : PathShape() {
        override fun addToPath(path: Path, offsetX: Float, offsetY: Float, radius: Float) {
            val cx = radius + offsetX
            val cy = radius + offsetY
            val control = radius - radius * mRadiusRatio

            path.moveTo(cx, cy - radius)
            addLeftCurve(cx, cy, radius, control, path)
            addRightCurve(cx, cy, radius, control, path)
            addLeftCurve(cx, cy, -radius, -control, path)
            addRightCurve(cx, cy, -radius, -control, path)
            path.close()
        }

        fun addLeftCurve(cx: Float, cy: Float, r: Float, control: Float, path: Path) {
            path.cubicTo(cx - control, cy - r, cx - r, cy - control, cx - r, cy)
        }

        fun addRightCurve(cx: Float, cy: Float, r: Float, control: Float, path: Path) {
            path.cubicTo(cx - r, cy + control, cx - control, cy + r, cx, cy + r)
        }

        override fun newUpdateListener(
            startRect: Rect,
            endRect: Rect,
            endRadius: Float,
            outPath: Path,
        ): ValueAnimator.AnimatorUpdateListener {
            val startCX = startRect.exactCenterX()
            val startCY = startRect.exactCenterY()
            val startR = startRect.width() / 2f
            val startControl = startR - startR * mRadiusRatio
            val startHShift = 0f
            val startVShift = 0f

            val endCX = endRect.exactCenterX()
            val endCY = endRect.exactCenterY()
            // Approximate corner circle using bezier curves
            // http://spencermortensen.com/articles/bezier-circle/
            val endControl = endRadius * 0.551915024494f
            val endHShift = endRect.width() / 2f - endRadius
            val endVShift = endRect.height() / 2f - endRadius

            return ValueAnimator.AnimatorUpdateListener { anim: ValueAnimator ->
                val progress = anim.animatedValue as Float
                val cx = (1 - progress) * startCX + progress * endCX
                val cy = (1 - progress) * startCY + progress * endCY
                val r = (1 - progress) * startR + progress * endRadius
                val control = (1 - progress) * startControl + progress * endControl
                val hShift = (1 - progress) * startHShift + progress * endHShift
                val vShift = (1 - progress) * startVShift + progress * endVShift

                outPath.moveTo(cx, cy - vShift - r)
                outPath.rLineTo(-hShift, 0f)

                addLeftCurve(cx - hShift, cy - vShift, r, control, outPath)
                outPath.rLineTo(0f, vShift + vShift)

                addRightCurve(cx - hShift, cy + vShift, r, control, outPath)
                outPath.rLineTo(hShift + hShift, 0f)

                addLeftCurve(cx + hShift, cy + vShift, -r, -control, outPath)
                outPath.rLineTo(0f, -vShift - vShift)

                addRightCurve(cx + hShift, cy - vShift, -r, -control, outPath)
                outPath.close()
            }
        }
    }

    companion object {
        @JvmField var INSTANCE = DaggerSingletonObject(LauncherAppComponent::getIconShape)

        private fun getShapeDefinition(type: String, radius: Float): ShapeDelegate {
            return when (type) {
                "Circle" -> Circle()
                "RoundedSquare" -> RoundedSquare(radius)
                "TearDrop" -> TearDrop(radius)
                "Squircle" -> Squircle(radius)
                else -> throw IllegalArgumentException("Invalid shape type: $type")
            }
        }

        private fun getAllShapes(context: Context): List<ShapeDelegate> {
            val result = ArrayList<ShapeDelegate>()
            try {
                context.resources.getXml(R.xml.folder_shapes).use { parser ->
                    // Find the root tag
                    var type: Int = parser.next()
                    while (
                        type != XmlPullParser.END_TAG &&
                            type != XmlPullParser.END_DOCUMENT &&
                            "shapes" != parser.name
                    ) {
                        type = parser.next()
                    }
                    val depth = parser.depth
                    val radiusAttr = intArrayOf(R.attr.folderIconRadius)
                    type = parser.next()
                    while (
                        (type != XmlPullParser.END_TAG || parser.depth > depth) &&
                            type != XmlPullParser.END_DOCUMENT
                    ) {
                        if (type == XmlPullParser.START_TAG) {
                            val attrs = Xml.asAttributeSet(parser)
                            val arr = context.obtainStyledAttributes(attrs, radiusAttr)
                            val shape = getShapeDefinition(parser.name, arr.getFloat(0, 1f))
                            arr.recycle()
                            result.add(shape)
                        }
                        type = parser.next()
                    }
                }
            } catch (e: IOException) {
                throw RuntimeException(e)
            } catch (e: XmlPullParserException) {
                throw RuntimeException(e)
            }
            return result
        }
    }
}
