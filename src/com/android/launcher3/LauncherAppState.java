/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.launcher3;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.launcher3.dagger.LauncherComponentProvider;
import com.android.launcher3.graphics.ThemeManager;
import com.android.launcher3.icons.IconCache;
import com.android.launcher3.icons.IconProvider;
import com.android.launcher3.icons.LauncherIconProvider;
import com.android.launcher3.model.ModelInitializer;
import com.android.launcher3.model.WidgetsFilterDataProvider;
import com.android.launcher3.pm.InstallSessionHelper;
import com.android.launcher3.pm.UserCache;
import com.android.launcher3.util.MainThreadInitializedObject;
import com.android.launcher3.util.PackageManagerHelper;
import com.android.launcher3.util.Preconditions;
import com.android.launcher3.util.RunnableList;
import com.android.launcher3.util.SafeCloseable;
import com.android.launcher3.util.SettingsCache;
import com.android.launcher3.util.TraceHelper;
import com.android.launcher3.widget.custom.CustomWidgetManager;

public class LauncherAppState implements SafeCloseable {

    public static final String TAG = "LauncherAppState";

    // We do not need any synchronization for this variable as its only written on UI thread.
    public static final MainThreadInitializedObject<LauncherAppState> INSTANCE =
            new MainThreadInitializedObject<>(LauncherAppState::new);

    private final Context mContext;
    private final LauncherModel mModel;
    private final LauncherIconProvider mIconProvider;
    private final IconCache mIconCache;
    private final InvariantDeviceProfile mInvariantDeviceProfile;
    private boolean mIsSafeModeEnabled;

    private final RunnableList mOnTerminateCallback = new RunnableList();

    public static LauncherAppState getInstance(Context context) {
        return INSTANCE.get(context);
    }

    public Context getContext() {
        return mContext;
    }

    @SuppressWarnings("NewApi")
    public LauncherAppState(Context context) {
        this(context, LauncherFiles.APP_ICONS_DB);
        Log.v(Launcher.TAG, "LauncherAppState initiated");
        Preconditions.assertUIThread();

        mIsSafeModeEnabled = TraceHelper.allowIpcs("isSafeMode",
                () -> context.getPackageManager().isSafeMode());

        ModelInitializer initializer = new ModelInitializer(
                context,
                LauncherComponentProvider.get(context).getIconPool(),
                mIconCache,
                mInvariantDeviceProfile,
                ThemeManager.INSTANCE.get(context),
                UserCache.INSTANCE.get(context),
                SettingsCache.INSTANCE.get(context),
                mIconProvider,
                CustomWidgetManager.INSTANCE.get(context),
                InstallSessionHelper.INSTANCE.get(context),
                closeable -> mOnTerminateCallback.add(closeable::close)
        );
        initializer.initialize(mModel);
    }

    public LauncherAppState(Context context, @Nullable String iconCacheFileName) {
        mContext = context;

        mInvariantDeviceProfile = InvariantDeviceProfile.INSTANCE.get(context);
        mIconProvider = new LauncherIconProvider(context);
        mIconCache = new IconCache(mContext, mInvariantDeviceProfile,
                iconCacheFileName, mIconProvider);
        mModel = new LauncherModel(context, this, mIconCache,
                WidgetsFilterDataProvider.Companion.newInstance(context), new AppFilter(mContext),
                PackageManagerHelper.INSTANCE.get(context), iconCacheFileName != null);
        mOnTerminateCallback.add(mIconCache::close);
        mOnTerminateCallback.add(mModel::destroy);
    }

    /**
     * Call from Application.onTerminate(), which is not guaranteed to ever be called.
     */
    @Override
    public void close() {
        mOnTerminateCallback.executeAllAndDestroy();
    }

    public IconProvider getIconProvider() {
        return mIconProvider;
    }

    public IconCache getIconCache() {
        return mIconCache;
    }

    public LauncherModel getModel() {
        return mModel;
    }

    public InvariantDeviceProfile getInvariantDeviceProfile() {
        return mInvariantDeviceProfile;
    }

    public boolean isSafeModeEnabled() {
        return mIsSafeModeEnabled;
    }

    /**
     * Shorthand for {@link #getInvariantDeviceProfile()}
     */
    public static InvariantDeviceProfile getIDP(Context context) {
        return InvariantDeviceProfile.INSTANCE.get(context);
    }
}
