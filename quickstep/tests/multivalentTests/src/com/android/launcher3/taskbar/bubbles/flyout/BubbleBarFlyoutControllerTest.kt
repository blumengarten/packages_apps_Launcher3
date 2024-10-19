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

package com.android.launcher3.taskbar.bubbles.flyout

import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.animation.AnimatorTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit tests for [BubbleBarFlyoutController] */
@SmallTest
@RunWith(AndroidJUnit4::class)
class BubbleBarFlyoutControllerTest {

    @get:Rule val animatorTestRule = AnimatorTestRule()

    private lateinit var flyoutController: BubbleBarFlyoutController
    private lateinit var flyoutContainer: FrameLayout
    private lateinit var topBoundaryListener: FakeTopBoundaryListener
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val flyoutMessage = BubbleBarFlyoutMessage(icon = null, "sender name", "message")
    private var onLeft = true
    private var flyoutTy = 50f

    @Before
    fun setUp() {
        flyoutContainer = FrameLayout(context)
        val positioner =
            object : BubbleBarFlyoutPositioner {
                override val isOnLeft
                    get() = onLeft

                override val targetTy
                    get() = flyoutTy

                override val distanceToCollapsedPosition = PointF(100f, 200f)
                override val collapsedSize = 30f
                override val collapsedColor = Color.BLUE
                override val collapsedElevation = 1f
                override val distanceToRevealTriangle = 50f
            }
        topBoundaryListener = FakeTopBoundaryListener()
        val flyoutScheduler = FlyoutScheduler { block -> block.invoke() }
        flyoutController =
            BubbleBarFlyoutController(
                flyoutContainer,
                positioner,
                topBoundaryListener,
                flyoutScheduler,
            )
    }

    @Test
    fun flyoutPosition_left() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            flyoutController.setUpFlyout(flyoutMessage)
            assertThat(flyoutContainer.childCount).isEqualTo(1)
            val flyout = flyoutContainer.getChildAt(0)
            val lp = flyout.layoutParams as FrameLayout.LayoutParams
            assertThat(lp.gravity).isEqualTo(Gravity.BOTTOM or Gravity.LEFT)
            assertThat(flyout.translationY).isEqualTo(50f)
        }
    }

    @Test
    fun flyoutPosition_right() {
        onLeft = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            flyoutController.setUpFlyout(flyoutMessage)
            assertThat(flyoutContainer.childCount).isEqualTo(1)
            val flyout = flyoutContainer.getChildAt(0)
            val lp = flyout.layoutParams as FrameLayout.LayoutParams
            assertThat(lp.gravity).isEqualTo(Gravity.BOTTOM or Gravity.RIGHT)
            assertThat(flyout.translationY).isEqualTo(50f)
        }
    }

    @Test
    fun flyoutMessage() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            flyoutController.setUpFlyout(flyoutMessage)
            assertThat(flyoutContainer.childCount).isEqualTo(1)
            val flyout = flyoutContainer.getChildAt(0)
            val sender = flyout.findViewById<TextView>(R.id.bubble_flyout_title)
            assertThat(sender.text).isEqualTo("sender name")
            val message = flyout.findViewById<TextView>(R.id.bubble_flyout_text)
            assertThat(message.text).isEqualTo("message")
        }
    }

    @Test
    fun hideFlyout_removedFromContainer() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            flyoutController.setUpFlyout(flyoutMessage)
            assertThat(flyoutContainer.childCount).isEqualTo(1)
            flyoutController.hideFlyout {}
            animatorTestRule.advanceTimeBy(300)
        }
        assertThat(flyoutContainer.childCount).isEqualTo(0)
    }

    @Test
    fun showFlyout_extendsTopBoundary() {
        // set negative translation for the flyout so that it will request to extend the top
        // boundary
        flyoutTy = -50f
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            flyoutController.setUpFlyout(flyoutMessage)
            assertThat(flyoutContainer.childCount).isEqualTo(1)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            animatorTestRule.advanceTimeBy(300)
        }
        assertThat(topBoundaryListener.topBoundaryExtendedSpace).isEqualTo(50)
    }

    @Test
    fun showFlyout_withinBoundary() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            flyoutController.setUpFlyout(flyoutMessage)
            assertThat(flyoutContainer.childCount).isEqualTo(1)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            animatorTestRule.advanceTimeBy(300)
        }
        assertThat(topBoundaryListener.topBoundaryExtendedSpace).isEqualTo(0)
    }

    @Test
    fun hideFlyout_resetsTopBoundary() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            flyoutController.setUpFlyout(flyoutMessage)
            assertThat(flyoutContainer.childCount).isEqualTo(1)
            flyoutController.hideFlyout {}
            animatorTestRule.advanceTimeBy(300)
        }
        assertThat(topBoundaryListener.topBoundaryReset).isTrue()
    }

    class FakeTopBoundaryListener : BubbleBarFlyoutController.TopBoundaryListener {

        var topBoundaryExtendedSpace = 0
        var topBoundaryReset = false

        override fun extendTopBoundary(space: Int) {
            topBoundaryExtendedSpace = space
        }

        override fun resetTopBoundary() {
            topBoundaryReset = true
        }
    }
}
