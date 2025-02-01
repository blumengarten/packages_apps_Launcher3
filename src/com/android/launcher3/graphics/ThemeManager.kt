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

package com.android.launcher3.graphics

import android.content.Context
import android.content.res.Resources
import com.android.launcher3.EncryptionType
import com.android.launcher3.LauncherPrefChangeListener
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.LauncherPrefs.Companion.backedUpItem
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppComponent
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.icons.IconThemeController
import com.android.launcher3.icons.mono.MonoIconThemeController
import com.android.launcher3.shapes.ShapesProvider
import com.android.launcher3.util.DaggerSingletonObject
import com.android.launcher3.util.DaggerSingletonTracker
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.SimpleBroadcastReceiver
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject

/** Centralized class for managing Launcher icon theming */
@LauncherAppSingleton
open class ThemeManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val prefs: LauncherPrefs,
    lifecycle: DaggerSingletonTracker,
) {

    /** Representation of the current icon state */
    var iconState = parseIconState()
        private set

    var isMonoThemeEnabled
        set(value) = prefs.put(THEMED_ICONS, value)
        get() = prefs.get(THEMED_ICONS)

    var themeController: IconThemeController? =
        if (isMonoThemeEnabled) MonoIconThemeController() else null
        private set

    private val listeners = CopyOnWriteArrayList<ThemeChangeListener>()

    init {
        val receiver = SimpleBroadcastReceiver(MAIN_EXECUTOR) { verifyIconState() }
        receiver.registerPkgActions(context, "android", ACTION_OVERLAY_CHANGED)

        val prefListener = LauncherPrefChangeListener { key ->
            when (key) {
                KEY_THEMED_ICONS,
                KEY_ICON_SHAPE -> verifyIconState()
            }
        }
        prefs.addListener(prefListener, THEMED_ICONS, PREF_ICON_SHAPE)

        lifecycle.addCloseable {
            receiver.unregisterReceiverSafely(context)
            prefs.removeListener(prefListener)
        }
    }

    private fun verifyIconState() {
        val newState = parseIconState()
        if (newState == iconState) return

        iconState = newState
        themeController = if (isMonoThemeEnabled) MonoIconThemeController() else null

        listeners.forEach { it.onThemeChanged() }
    }

    fun addChangeListener(listener: ThemeChangeListener) = listeners.add(listener)

    fun removeChangeListener(listener: ThemeChangeListener) = listeners.remove(listener)

    private fun parseIconState(): IconState {
        val shapeModel =
            prefs.get(PREF_ICON_SHAPE).let { shapeOverride ->
                ShapesProvider.iconShapes.values.firstOrNull { it.key == shapeOverride }
            }
        val iconMask =
            when {
                shapeModel != null -> shapeModel.pathString
                CONFIG_ICON_MASK_RES_ID == Resources.ID_NULL -> ""
                else -> context.resources.getString(CONFIG_ICON_MASK_RES_ID)
            }
        return IconState(
            iconMask = iconMask,
            folderShapeMask = shapeModel?.folderPathString ?: iconMask,
            isMonoTheme = isMonoThemeEnabled,
        )
    }

    data class IconState(
        val iconMask: String,
        val folderShapeMask: String,
        val isMonoTheme: Boolean,
        val themeCode: String = if (isMonoTheme) "with-theme" else "no-theme",
    ) {
        fun toUniqueId() = "${iconMask.hashCode()},$themeCode"
    }

    /** Interface for receiving theme change events */
    fun interface ThemeChangeListener {
        fun onThemeChanged()
    }

    companion object {

        @JvmField val INSTANCE = DaggerSingletonObject(LauncherAppComponent::getThemeManager)
        const val KEY_ICON_SHAPE = "icon_shape_model"

        const val KEY_THEMED_ICONS = "themed_icons"
        @JvmField val THEMED_ICONS = backedUpItem(KEY_THEMED_ICONS, false, EncryptionType.ENCRYPTED)
        @JvmField val PREF_ICON_SHAPE = backedUpItem(KEY_ICON_SHAPE, "", EncryptionType.ENCRYPTED)

        private const val ACTION_OVERLAY_CHANGED = "android.intent.action.OVERLAY_CHANGED"
        private val CONFIG_ICON_MASK_RES_ID: Int =
            Resources.getSystem().getIdentifier("config_icon_mask", "string", "android")
    }
}
