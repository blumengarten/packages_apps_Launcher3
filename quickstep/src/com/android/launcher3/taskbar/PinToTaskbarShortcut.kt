/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.launcher3.taskbar

import android.content.Context
import android.view.View
import com.android.launcher3.R
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.popup.SystemShortcut
import com.android.launcher3.views.ActivityContext

/**
 * A single menu item shortcut to allow users to pin an item to the taskbar and unpin an item from
 * the taskbar.
 */
class PinToTaskbarShortcut<T>(target: T, itemInfo: ItemInfo?, originalView: View, isPin: Boolean) :
    SystemShortcut<T>(
        if (isPin) R.drawable.ic_pin else R.drawable.ic_unpin,
        if (isPin) R.string.pin_to_taskbar else R.string.unpin_from_taskbar,
        target,
        itemInfo,
        originalView,
    ) where T : Context?, T : ActivityContext? {

    override fun onClick(v: View?) {
        // TODO(b/375648361): Pin/Unpin the item here.
    }
}
