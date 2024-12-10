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

package com.android.quickstep.fallback.window

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.util.Log
import android.util.SparseArray
import android.view.Display
import androidx.core.util.valueIterator


/**
 * Factory for creating [RecentsWindowManager] instances based on context per display.
 */
class RecentsWindowFactory(private val context: Context) {

    companion object {
        private const val TAG = "RecentsWindowFactory"
        private const val DEBUG = false
    }

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val managerArray = SparseArray<RecentsWindowManager>()

    private val displayListener: DisplayManager.DisplayListener =
        (object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                if (DEBUG) Log.d(TAG, "onDisplayAdded: displayId=$displayId")
                create(displayId)
            }

            override fun onDisplayRemoved(displayId: Int) {
                if (DEBUG) Log.d(TAG, "onDisplayRemoved: displayId=$displayId")
                delete(displayId)
            }

            override fun onDisplayChanged(displayId: Int) {
                if (DEBUG) Log.d(TAG, "onDisplayChanged: displayId=$displayId")
            }
        })

    init {
        create(Display.DEFAULT_DISPLAY) // create manager for first display early.
        displayManager.registerDisplayListener(displayListener, Handler.getMain())
    }

    fun destroy() {
        managerArray.valueIterator().forEach { manager ->
            manager.destroy()
        }
        managerArray.clear()
        displayManager.unregisterDisplayListener(displayListener)
    }

    fun get(displayId: Int): RecentsWindowManager? {
        if (DEBUG) Log.d(TAG, "get: displayId=$displayId")
        return managerArray[displayId]
    }

    fun delete(displayId: Int) {
        if (DEBUG) Log.d(TAG, "delete: displayId=$displayId")
        get(displayId)?.destroy()
        managerArray.remove(displayId)
    }

    fun create(displayId: Int): RecentsWindowManager {
        if (DEBUG) Log.d(TAG, "create: displayId=$displayId")
        get(displayId)?.let {
            return it
        }
        val display = displayManager.getDisplay(displayId)
        val displayContext = context.createDisplayContext(display)
        val recentsWindowManager = RecentsWindowManager(displayId, displayContext)
        managerArray[displayId] = recentsWindowManager
        return recentsWindowManager
    }
}
