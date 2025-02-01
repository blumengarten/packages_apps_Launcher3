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
class ShapesProviderTest {

    @get:Rule val setFlagsRule: SetFlagsRule = SetFlagsRule()

    @Test
    @EnableFlags(FLAG_ENABLE_LAUNCHER_ICON_SHAPES, FLAG_NEW_CUSTOMIZATION_PICKER_UI)
    fun `verify valid path arch`() {
        ShapesProvider.iconShapes["arch"]?.apply {
            GenericPathShape(pathString)
            PathParser.createPathFromPathData(pathString)
        }
    }

    @Test
    @EnableFlags(FLAG_ENABLE_LAUNCHER_ICON_SHAPES, FLAG_NEW_CUSTOMIZATION_PICKER_UI)
    fun `verify valid path 4_sided_cookie`() {
        ShapesProvider.iconShapes["4_sided_cookie"]?.apply {
            GenericPathShape(pathString)
            PathParser.createPathFromPathData(pathString)
        }
    }

    @Test
    @EnableFlags(FLAG_ENABLE_LAUNCHER_ICON_SHAPES, FLAG_NEW_CUSTOMIZATION_PICKER_UI)
    fun `verify valid path seven_sided_cookie`() {
        ShapesProvider.iconShapes["seven_sided_cookie"]?.apply {
            GenericPathShape(pathString)
            PathParser.createPathFromPathData(pathString)
        }
    }

    @Test
    @EnableFlags(FLAG_ENABLE_LAUNCHER_ICON_SHAPES, FLAG_NEW_CUSTOMIZATION_PICKER_UI)
    fun `verify valid path sunny`() {
        ShapesProvider.iconShapes["sunny"]?.apply {
            GenericPathShape(pathString)
            PathParser.createPathFromPathData(pathString)
        }
    }

    @Test
    @EnableFlags(FLAG_ENABLE_LAUNCHER_ICON_SHAPES, FLAG_NEW_CUSTOMIZATION_PICKER_UI)
    fun `verify valid path circle`() {
        ShapesProvider.iconShapes["circle"]?.apply {
            GenericPathShape(pathString)
            PathParser.createPathFromPathData(pathString)
        }
    }

    @Test
    @EnableFlags(FLAG_ENABLE_LAUNCHER_ICON_SHAPES, FLAG_NEW_CUSTOMIZATION_PICKER_UI)
    fun `verify valid path square`() {
        ShapesProvider.iconShapes["square"]?.apply {
            GenericPathShape(pathString)
            PathParser.createPathFromPathData(pathString)
        }
    }

    @Test
    @EnableFlags(FLAG_ENABLE_LAUNCHER_ICON_SHAPES, FLAG_NEW_CUSTOMIZATION_PICKER_UI)
    fun `verify valid folder path clover`() {
        ShapesProvider.folderShapes["clover"]?.let { pathString ->
            GenericPathShape(pathString)
            PathParser.createPathFromPathData(pathString)
        }
    }

    @Test
    @EnableFlags(FLAG_ENABLE_LAUNCHER_ICON_SHAPES, FLAG_NEW_CUSTOMIZATION_PICKER_UI)
    fun `verify valid folder path complexClover`() {
        ShapesProvider.folderShapes["complexClover"]?.let { pathString ->
            GenericPathShape(pathString)
            PathParser.createPathFromPathData(pathString)
        }
    }

    @Test
    @EnableFlags(FLAG_ENABLE_LAUNCHER_ICON_SHAPES, FLAG_NEW_CUSTOMIZATION_PICKER_UI)
    fun `verify valid folder path arch`() {
        ShapesProvider.folderShapes["arch"]?.let { pathString ->
            GenericPathShape(pathString)
            PathParser.createPathFromPathData(pathString)
        }
    }

    @Test
    @EnableFlags(FLAG_ENABLE_LAUNCHER_ICON_SHAPES, FLAG_NEW_CUSTOMIZATION_PICKER_UI)
    fun `verify valid folder path square`() {
        ShapesProvider.folderShapes["square"]?.let { pathString ->
            GenericPathShape(pathString)
            PathParser.createPathFromPathData(pathString)
        }
    }
}
