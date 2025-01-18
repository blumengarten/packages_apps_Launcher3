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
package com.android.quickstep;

import static android.view.Display.DEFAULT_DISPLAY;

import static androidx.test.InstrumentationRegistry.getTargetContext;

import static com.android.quickstep.TaskbarModeSwitchRule.Mode.PERSISTENT;
import static com.android.wm.shell.shared.desktopmode.DesktopModeStatus.ENTER_DESKTOP_BY_DEFAULT_ON_FREEFORM_DISPLAY_SYS_PROP;

import android.app.WindowConfiguration;
import android.os.RemoteException;
import android.util.Log;
import android.view.WindowManagerGlobal;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.launcher3.ui.PortraitLandscapeRunner.PortraitLandscape;
import com.android.launcher3.util.rule.SetPropRule;
import com.android.quickstep.NavigationModeSwitchRule.NavigationModeSwitch;
import com.android.quickstep.TaskbarModeSwitchRule.TaskbarModeSwitch;
import com.android.window.flags.Flags;
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class TaplTestsLockedTaskbar extends AbstractTaplTestsTaskbar {
    private static final String TAG = "TaplTestsLockedTaskbar";

    @Rule
    public SetPropRule mSetPropRule =
            new SetPropRule(ENTER_DESKTOP_BY_DEFAULT_ON_FREEFORM_DISPLAY_SYS_PROP, "true");

    @Override
    public void setUp() throws Exception {
        Assume.assumeTrue(mLauncher.isTablet());
        Assume.assumeTrue(Flags.enterDesktopByDefaultOnFreeformDisplays());
        Assume.assumeTrue(DesktopModeStatus.canEnterDesktopMode(getTargetContext()));
        super.setUp();

        // Default-to-desktop feature requires the display to be freeform mode.
        setDisplayWindowingMode(WindowConfiguration.WINDOWING_MODE_FREEFORM);
    }

    @Override
    public void tearDown() throws Exception {
        // Reset the display windowing mode to the device default.
        setDisplayWindowingMode(WindowConfiguration.WINDOWING_MODE_UNDEFINED);

        mLauncher.recreateTaskbar();

        super.tearDown();
    }

    @Test
    @PortraitLandscape
    @NavigationModeSwitch
    @TaskbarModeSwitch(mode = PERSISTENT)
    public void testTaskbarVisibility() {
        // The taskbar should be visible on home.
        mDevice.pressHome();
        waitForResumed("Launcher internal state is still Background");
        mLauncher.getLaunchedAppState().assertTaskbarVisible();

        // The taskbar should be visible when a freeform task is active.
        startAppFast(CALCULATOR_APP_PACKAGE);
        mLauncher.getLaunchedAppState().assertTaskbarVisible();
    }

    private void setDisplayWindowingMode(int windowingMode) {
        try {
            WindowManagerGlobal.getWindowManagerService().setWindowingMode(
                    DEFAULT_DISPLAY, windowingMode);
        } catch (RemoteException e) {
            Log.e(TAG, "error setting windowing mode", e);
            throw new RuntimeException(e);
        }
    }
}
