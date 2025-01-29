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

package com.android.quickstep.util

import android.view.Display.DEFAULT_DISPLAY
import com.android.systemui.shared.recents.model.Task

/** Whether this displayId belongs to an external display */
val Int.isExternalDisplay
    get() = this != DEFAULT_DISPLAY

/** Returns displayId of this [Task], default to [DEFAULT_DISPLAY] */
val Task?.displayId
    get() = this?.key?.displayId ?: DEFAULT_DISPLAY

/** Returns if this task belongs tto [DEFAULT_DISPLAY] */
val Task?.isExternalDisplay
    get() = displayId.isExternalDisplay
