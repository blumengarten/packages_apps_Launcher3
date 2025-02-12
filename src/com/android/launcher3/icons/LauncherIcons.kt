/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.launcher3.icons

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.UserHandle
import com.android.launcher3.Flags
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.graphics.IconShape
import com.android.launcher3.graphics.ThemeManager
import com.android.launcher3.pm.UserCache
import com.android.launcher3.util.MainThreadInitializedObject
import com.android.launcher3.util.SafeCloseable
import com.android.launcher3.util.UserIconInfo
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Wrapper class to provide access to [BaseIconFactory] and also to provide pool of this class that
 * are threadsafe.
 */
class LauncherIcons
protected constructor(
    context: Context,
    fillResIconDpi: Int,
    iconBitmapSize: Int,
    private val pool: ConcurrentLinkedQueue<LauncherIcons>,
) : BaseIconFactory(context, fillResIconDpi, iconBitmapSize), AutoCloseable {

    init {
        mThemeController = ThemeManager.INSTANCE[context].themeController
    }

    /** Recycles a LauncherIcons that may be in-use. */
    fun recycle() {
        clear()
        pool.add(this)
    }

    override fun getUserInfo(user: UserHandle): UserIconInfo {
        return UserCache.INSTANCE[mContext].getUserInfo(user)
    }

    public override fun getShapePath(drawable: AdaptiveIconDrawable, iconBounds: Rect): Path {
        if (!Flags.enableLauncherIconShapes()) return drawable.iconMask
        return IconShape.INSTANCE[mContext].shape.getPath(iconBounds)
    }

    override fun drawAdaptiveIcon(
        canvas: Canvas,
        drawable: AdaptiveIconDrawable,
        overridePath: Path,
    ) {
        if (!Flags.enableLauncherIconShapes()) {
            super.drawAdaptiveIcon(canvas, drawable, overridePath)
            return
        }
        val shapeKey = LauncherPrefs.get(mContext).get(ThemeManager.PREF_ICON_SHAPE)
        val iconScale =
            when (shapeKey) {
                "seven_sided_cookie" -> SEVEN_SIDED_COOKIE_SCALE
                "four_sided_cookie" -> FOUR_SIDED_COOKIE_SCALE
                "sunny" -> VERY_SUNNY_SCALE
                else -> DEFAULT_ICON_SCALE
            }
        canvas.clipPath(overridePath)
        canvas.drawColor(Color.BLACK)
        canvas.save()
        canvas.scale(iconScale, iconScale, canvas.width / 2f, canvas.height / 2f)
        if (drawable.background != null) {
            drawable.background.draw(canvas)
        }
        if (drawable.foreground != null) {
            drawable.foreground.draw(canvas)
        }
        canvas.restore()
    }

    override fun close() {
        recycle()
    }

    private class Pool(private val context: Context) : SafeCloseable {
        private var pool = ConcurrentLinkedQueue<LauncherIcons>()

        fun obtain(): LauncherIcons {
            val pool = pool
            return pool.poll()
                ?: InvariantDeviceProfile.INSTANCE[context].let {
                    LauncherIcons(context, it.fillResIconDpi, it.iconBitmapSize, pool)
                }
        }

        override fun close() {
            pool = ConcurrentLinkedQueue()
        }
    }

    companion object {
        private const val SEVEN_SIDED_COOKIE_SCALE = 72f / 80f
        private const val FOUR_SIDED_COOKIE_SCALE = 72f / 83.4f
        private const val VERY_SUNNY_SCALE = 72f / 92f
        private const val DEFAULT_ICON_SCALE = 1f

        private val POOL = MainThreadInitializedObject { Pool(it) }

        /**
         * Return a new Message instance from the global pool. Allows us to avoid allocating new
         * objects in many cases.
         */
        @JvmStatic
        fun obtain(context: Context): LauncherIcons {
            return POOL[context].obtain()
        }

        @JvmStatic
        fun clearPool(context: Context) {
            POOL[context].close()
        }
    }
}
