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

import android.graphics.Typeface
import android.widget.TextView
import com.android.launcher3.Flags

/**
 * Helper util class to set pre-defined typefaces to textviews
 *
 * If the typeface font family is already defined here, you can just reuse it directly. Otherwise,
 * please define it here for future use. You do not need to define the font style. If you need
 * anything other than [Typeface.NORMAL], pass it inline when calling [setTypeface]
 */
class TypefaceUtils {

    companion object {
        const val FONT_FAMILY_BODY_SMALL_BASELINE = "variable-body-small"
        const val FONT_FAMILY_BODY_MEDIUM_BASELINE = "variable-body-medium"
        const val FONT_FAMILY_BODY_LARGE_BASELINE = "variable-body-large"
        const val FONT_FAMILY_LABEL_LARGE_BASELINE = "variable-label-large"
        const val FONT_FAMILY_DISPLAY_SMALL_EMPHASIZED = "variable-display-small-emphasized"
        const val FONT_FAMILY_DISPLAY_MEDIUM_EMPHASIZED = "variable-display-medium-emphasized"
        const val FONT_FAMILY_HEADLINE_SMALL_EMPHASIZED = "variable-headline-small-emphasized"
        const val FONT_FAMILY_HEADLINE_LARGE_EMPHASIZED = "variable-headline-large-emphasized"

        @JvmStatic
        @JvmOverloads
        fun setTypeface(
            textView: TextView?,
            fontFamilyName: String,
            fontStyle: Int = Typeface.NORMAL,
        ) {
            if (!Flags.expressiveThemeInTaskbarAndNavigation()) return
            textView?.typeface = Typeface.create(fontFamilyName, fontStyle)
        }
    }
}
