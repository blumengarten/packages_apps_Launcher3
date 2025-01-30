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

package com.android.launcher3.shapes

import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import androidx.core.graphics.PathParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.launcher3.Flags.FLAG_ENABLE_LAUNCHER_ICON_SHAPES
import com.android.launcher3.graphics.IconShape.GenericPathShape
import com.android.systemui.shared.Flags.FLAG_NEW_CUSTOMIZATION_PICKER_UI
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class IconShapesProviderTest {

    @get:Rule val setFlagsRule: SetFlagsRule = SetFlagsRule()

    @Test
    @EnableFlags(FLAG_ENABLE_LAUNCHER_ICON_SHAPES, FLAG_NEW_CUSTOMIZATION_PICKER_UI)
    fun `verify valid path arch`() {
        IconShapesProvider.shapes["arch"]?.apply {
            GenericPathShape(pathString)
            PathParser.createPathFromPathData(pathString)
        }
    }

    @Test
    @EnableFlags(FLAG_ENABLE_LAUNCHER_ICON_SHAPES, FLAG_NEW_CUSTOMIZATION_PICKER_UI)
    fun `verify valid path 4_sided_cookie`() {
        IconShapesProvider.shapes["4_sided_cookie"]?.apply {
            GenericPathShape(pathString)
            PathParser.createPathFromPathData(pathString)
        }
    }

    @Test
    @EnableFlags(FLAG_ENABLE_LAUNCHER_ICON_SHAPES, FLAG_NEW_CUSTOMIZATION_PICKER_UI)
    fun `verify valid path seven_sided_cookie`() {
        IconShapesProvider.shapes["seven_sided_cookie"]?.apply {
            GenericPathShape(pathString)
            PathParser.createPathFromPathData(pathString)
        }
    }

    @Test
    @EnableFlags(FLAG_ENABLE_LAUNCHER_ICON_SHAPES, FLAG_NEW_CUSTOMIZATION_PICKER_UI)
    fun `verify valid path sunny`() {
        IconShapesProvider.shapes["sunny"]?.apply {
            GenericPathShape(pathString)
            PathParser.createPathFromPathData(pathString)
        }
    }

    @Test
    @EnableFlags(FLAG_ENABLE_LAUNCHER_ICON_SHAPES, FLAG_NEW_CUSTOMIZATION_PICKER_UI)
    fun `verify valid path circle`() {
        IconShapesProvider.shapes["circle"]?.apply {
            GenericPathShape(pathString)
            PathParser.createPathFromPathData(pathString)
        }
    }

    @Test
    @EnableFlags(FLAG_ENABLE_LAUNCHER_ICON_SHAPES, FLAG_NEW_CUSTOMIZATION_PICKER_UI)
    fun `verify valid path square`() {
        IconShapesProvider.shapes["square"]?.apply {
            GenericPathShape(pathString)
            PathParser.createPathFromPathData(pathString)
        }
    }
}
