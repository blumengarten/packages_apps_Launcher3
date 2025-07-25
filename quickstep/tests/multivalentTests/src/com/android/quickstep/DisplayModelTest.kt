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

package com.android.quickstep

import android.content.Context
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import java.io.PrintWriter
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class DisplayModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    class TestableResource : DisplayModel.DisplayResource() {
        var isCleanupCalled = false

        override fun cleanup() {
            isCleanupCalled = true
        }

        override fun dump(prefix: String, writer: PrintWriter) {
            // No-Op
        }
    }

    private val testableDisplayModel =
        object : DisplayModel<TestableResource>(context) {
            override fun createDisplayResource(display: Display): TestableResource {
                return TestableResource()
            }
        }

    @Test
    fun testCreate() {
        testableDisplayModel.storeDisplayResource(Display.DEFAULT_DISPLAY)
        val resource = testableDisplayModel.getDisplayResource(Display.DEFAULT_DISPLAY)
        assertNotNull(resource)
    }

    @Test
    fun testCleanAndDelete() {
        testableDisplayModel.storeDisplayResource(Display.DEFAULT_DISPLAY)
        val resource = testableDisplayModel.getDisplayResource(Display.DEFAULT_DISPLAY)!!
        assertNotNull(resource)
        testableDisplayModel.deleteDisplayResource(Display.DEFAULT_DISPLAY)
        assert(resource.isCleanupCalled)
        assertNull(testableDisplayModel.getDisplayResource(Display.DEFAULT_DISPLAY))
    }
}
