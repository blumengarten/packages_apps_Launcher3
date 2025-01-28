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

package com.android.quickstep.views

import android.content.Context
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.util.AttributeSet
import android.widget.ImageButton
import com.android.launcher3.LauncherAnimUtils.VIEW_TRANSLATE_X
import com.android.launcher3.R
import com.android.launcher3.util.MultiPropertyFactory

/**
 * Button for supporting multiple desktop sessions. The button will be next to the first TaskView
 * inside overview, while clicking this button will create a new desktop session.
 */
class AddDesktopButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ImageButton(context, attrs) {

    private enum class TranslationX {
        GRID,
        OFFSET,
    }

    private val multiTranslationX =
        MultiPropertyFactory(this, VIEW_TRANSLATE_X, TranslationX.entries.size) { a: Float, b: Float
            ->
            a + b
        }

    var gridTranslationX
        get() = multiTranslationX[TranslationX.GRID.ordinal].value
        set(value) {
            multiTranslationX[TranslationX.GRID.ordinal].value = value
        }

    var offsetTranslationX
        get() = multiTranslationX[TranslationX.OFFSET.ordinal].value
        set(value) {
            multiTranslationX[TranslationX.OFFSET.ordinal].value = value
        }

    override fun onFinishInflate() {
        super.onFinishInflate()

        background =
            ShapeDrawable().apply {
                shape =
                    RoundRectShape(
                        FloatArray(8) { R.dimen.add_desktop_button_size.toFloat() },
                        null,
                        null,
                    )
                setTint(
                    resources.getColor(android.R.color.system_surface_bright_light, context.theme)
                )
            }
    }
}
