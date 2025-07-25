/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.launcher3.util;

import static android.provider.Settings.System.ACCELEROMETER_ROTATION;

import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.annotation.UiThread;

import com.android.launcher3.dagger.ApplicationContext;
import com.android.launcher3.dagger.LauncherAppSingleton;
import com.android.launcher3.dagger.LauncherBaseAppComponent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import javax.inject.Inject;

/**
 * ContentObserver over Settings keys that also has a caching layer.
 * Consumers can register for callbacks via {@link #register(Uri, OnChangeListener)} and
 * {@link #unregister(Uri, OnChangeListener)} methods.
 *
 * This can be used as a normal cache without any listeners as well via the
 * {@link #getValue(Uri, int)} and {@link #onChange)} to update (and subsequently call
 * get)
 *
 * The cache will be invalidated/updated through the normal
 * {@link ContentObserver#onChange(boolean)} calls
 *
 * Cache will also be updated if a key queried is missing (even if it has no listeners registered).
 */
@LauncherAppSingleton
public class SettingsCache extends ContentObserver {

    /** Hidden field Settings.Secure.NOTIFICATION_BADGING */
    public static final Uri NOTIFICATION_BADGING_URI =
            Settings.Secure.getUriFor("notification_badging");
    /** Hidden field Settings.Secure.ONE_HANDED_MODE_ENABLED */
    public static final String ONE_HANDED_ENABLED = "one_handed_mode_enabled";
    /** Hidden field Settings.Secure.SWIPE_BOTTOM_TO_NOTIFICATION_ENABLED */
    public static final String ONE_HANDED_SWIPE_BOTTOM_TO_NOTIFICATION_ENABLED =
            "swipe_bottom_to_notification_enabled";
    /** Hidden field Settings.Secure.HIDE_PRIVATESPACE_ENTRY_POINT */
    public static final Uri PRIVATE_SPACE_HIDE_WHEN_LOCKED_URI =
            Settings.Secure.getUriFor("hide_privatespace_entry_point");
    public static final Uri ROTATION_SETTING_URI =
            Settings.System.getUriFor(ACCELEROMETER_ROTATION);
    /** Hidden field {@link Settings.System#TOUCHPAD_NATURAL_SCROLLING}. */
    public static final Uri TOUCHPAD_NATURAL_SCROLLING = Settings.System.getUriFor(
            "touchpad_natural_scrolling");

    private static final String SYSTEM_URI_PREFIX = Settings.System.CONTENT_URI.toString();
    private static final String GLOBAL_URI_PREFIX = Settings.Global.CONTENT_URI.toString();

    private final Function<Uri, CopyOnWriteArrayList<OnChangeListener>> mListenerMapper = uri -> {
        registerUriAsync(uri);
        return new CopyOnWriteArrayList<>();
    };

    /**
     * Caches the last seen value for registered keys.
     */
    private final Map<Uri, Boolean> mKeyCache = new ConcurrentHashMap<>();
    private final Map<Uri, CopyOnWriteArrayList<OnChangeListener>> mListenerMap =
            new ConcurrentHashMap<>();
    protected final ContentResolver mResolver;

    /**
     * Singleton instance
     */
    public static final DaggerSingletonObject<SettingsCache> INSTANCE =
            new DaggerSingletonObject<>(LauncherBaseAppComponent::getSettingsCache);

    @Inject
    SettingsCache(@ApplicationContext Context context, DaggerSingletonTracker tracker) {
        super(new Handler(Looper.getMainLooper()));
        mResolver = context.getContentResolver();
        tracker.addCloseable(() ->
                UI_HELPER_EXECUTOR.execute(() -> mResolver.unregisterContentObserver(this)));
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        // We use default of 1, but if we're getting an onChange call, can assume a non-default
        // value will exist
        boolean newVal = updateValue(uri, 1 /* Effectively Unused */);
        List<OnChangeListener> listeners = mListenerMap.get(uri);
        if (listeners == null) {
            return;
        }

        for (OnChangeListener listener : listeners) {
            listener.onSettingsChanged(newVal);
        }
    }

    /**
     * Returns the value for this classes key from the cache. If not in cache, will call
     * {@link #updateValue(Uri, int)} to fetch.
     */
    public boolean getValue(Uri keySetting) {
        return getValue(keySetting, 1);
    }

    /**
     * Returns the value for this classes key from the cache. If not in cache, will call
     * {@link #updateValue(Uri, int)} to fetch.
     */
    public boolean getValue(Uri keySetting, int defaultValue) {
        if (mKeyCache.containsKey(keySetting)) {
            return mKeyCache.get(keySetting);
        } else {
            return updateValue(keySetting, defaultValue);
        }
    }

    private void registerUriAsync(Uri uri) {
        UI_HELPER_EXECUTOR.execute(() -> mResolver.registerContentObserver(uri, false, this));
    }

    /**
     * Does not de-dupe if you add same listeners for the same key multiple times.
     * Unregister once complete using {@link #unregister(Uri, OnChangeListener)}
     */
    @UiThread
    public void register(Uri uri, OnChangeListener changeListener) {
        mListenerMap.computeIfAbsent(uri, mListenerMapper).add(changeListener);
    }

    private boolean updateValue(Uri keyUri, int defaultValue) {
        String key = keyUri.getLastPathSegment();
        boolean newVal;
        if (keyUri.toString().startsWith(SYSTEM_URI_PREFIX)) {
            newVal = Settings.System.getInt(mResolver, key, defaultValue) == 1;
        } else if (keyUri.toString().startsWith(GLOBAL_URI_PREFIX)) {
            newVal = Settings.Global.getInt(mResolver, key, defaultValue) == 1;
        } else { // SETTING_SECURE
            newVal = Settings.Secure.getInt(mResolver, key, defaultValue) == 1;
        }

        mKeyCache.put(keyUri, newVal);
        return newVal;
    }

    /**
     * Call to stop receiving updates on the given {@param listener}.
     * This Uri/Listener pair must correspond to the same pair called with for
     * {@link #register(Uri, OnChangeListener)}
     */
    public void unregister(Uri uri, OnChangeListener listener) {
        List<OnChangeListener> listenersToRemoveFrom = mListenerMap.get(uri);
        if (listenersToRemoveFrom != null) {
            listenersToRemoveFrom.remove(listener);
        }
    }

    public interface OnChangeListener {
        void onSettingsChanged(boolean isEnabled);
    }
}
