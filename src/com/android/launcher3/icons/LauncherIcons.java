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

package com.android.launcher3.icons;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.os.UserHandle;

import androidx.annotation.NonNull;
import androidx.core.graphics.PathParser;

import com.android.launcher3.Flags;
import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.graphics.IconShape;
import com.android.launcher3.graphics.ThemeManager;
import com.android.launcher3.pm.UserCache;
import com.android.launcher3.shapes.IconShapeModel;
import com.android.launcher3.util.MainThreadInitializedObject;
import com.android.launcher3.util.SafeCloseable;
import com.android.launcher3.util.UserIconInfo;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Wrapper class to provide access to {@link BaseIconFactory} and also to provide pool of this class
 * that are threadsafe.
 */
public class LauncherIcons extends BaseIconFactory implements AutoCloseable {

    private static final MainThreadInitializedObject<Pool> POOL =
            new MainThreadInitializedObject<>(Pool::new);

    /**
     * Return a new Message instance from the global pool. Allows us to
     * avoid allocating new objects in many cases.
     */
    public static LauncherIcons obtain(Context context) {
        return POOL.get(context).obtain();
    }

    public static void clearPool(Context context) {
        POOL.get(context).close();
    }

    private final ConcurrentLinkedQueue<LauncherIcons> mPool;

    protected LauncherIcons(Context context, int fillResIconDpi, int iconBitmapSize,
            ConcurrentLinkedQueue<LauncherIcons> pool) {
        super(context, fillResIconDpi, iconBitmapSize);
        mThemeController = ThemeManager.INSTANCE.get(context).getThemeController();
        mPool = pool;
    }

    /**
     * Recycles a LauncherIcons that may be in-use.
     */
    public void recycle() {
        clear();
        mPool.add(this);
    }

    @NonNull
    @Override
    protected UserIconInfo getUserInfo(@NonNull UserHandle user) {
        return UserCache.INSTANCE.get(mContext).getUserInfo(user);
    }

    @NonNull
    @Override
    public Path getShapePath(AdaptiveIconDrawable drawable, Rect iconBounds) {
        if (!Flags.enableLauncherIconShapes()) return drawable.getIconMask();

        IconShapeModel shapeOverride = IconShape.INSTANCE.get(mContext).getShapeOverride();
        if (shapeOverride != null) {
            Path maskPath = PathParser.createPathFromPathData(shapeOverride.getPathString());
            Matrix matrix = new Matrix();
            // Assuming Path is in [0, 0, 100, 100] coordinate space.
            matrix.setRectToRect(
                    new RectF(0, 0, 100, 100),
                    new RectF(iconBounds),
                    Matrix.ScaleToFit.CENTER // Todo: CENTER or FILL?
            );
            maskPath.transform(matrix);
            return maskPath;
        } else {
            return drawable.getIconMask();
        }
    }

    @Override
    public void close() {
        recycle();
    }

    private static class Pool implements SafeCloseable {

        private final Context mContext;

        @NonNull
        private ConcurrentLinkedQueue<LauncherIcons> mPool = new ConcurrentLinkedQueue<>();

        private Pool(Context context) {
            mContext = context;
        }

        public LauncherIcons obtain() {
            ConcurrentLinkedQueue<LauncherIcons> pool = mPool;
            LauncherIcons m = pool.poll();

            if (m == null) {
                InvariantDeviceProfile idp = InvariantDeviceProfile.INSTANCE.get(mContext);
                return new LauncherIcons(mContext, idp.fillResIconDpi, idp.iconBitmapSize, pool);
            } else {
                return m;
            }
        }

        @Override
        public void close() {
            mPool = new ConcurrentLinkedQueue<>();
        }
    }
}
