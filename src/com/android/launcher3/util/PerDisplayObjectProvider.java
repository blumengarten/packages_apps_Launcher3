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
package com.android.launcher3.util;

/**
 * Interface for providers of objects for which there is one per display. The lifecycle of the
 * object is for the time the display is connected or is that of the app, whichever is shorter.
 */
public interface PerDisplayObjectProvider {
    /**
     * Get the DisplayController the given display id.
     *
     * @param displayId The display id
     * @return Returns the display controller if the display id is valid and otherwise throws an
     * IllegalArgumentException.
     */
    DisplayController getDisplayController(int displayId);
}
