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
import android.hardware.display.DisplayManager
import android.util.Log
import android.util.SparseArray
import android.view.Display
import androidx.core.util.valueIterator
import com.android.launcher3.util.Executors
import com.android.quickstep.DisplayModel.DisplayResource
import java.io.PrintWriter

/** data model for managing resources with lifecycles that match that of the connected display */
abstract class DisplayModel<RESOURCE_TYPE : DisplayResource>(val context: Context) {

    companion object {
        private const val TAG = "DisplayModel"
        private const val DEBUG = false
    }

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    protected val displayResourceArray = SparseArray<RESOURCE_TYPE>()

    private val displayListener: DisplayManager.DisplayListener =
        (object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                if (DEBUG) Log.d(TAG, "onDisplayAdded: displayId=$displayId")
                storeDisplayResource(displayId)
            }

            override fun onDisplayRemoved(displayId: Int) {
                if (DEBUG) Log.d(TAG, "onDisplayRemoved: displayId=$displayId")
                deleteDisplayResource(displayId)
            }

            override fun onDisplayChanged(displayId: Int) {
                if (DEBUG) Log.d(TAG, "onDisplayChanged: displayId=$displayId")
            }
        })

    protected abstract fun createDisplayResource(display: Display): RESOURCE_TYPE

    protected fun registerDisplayListener() {
        displayManager.registerDisplayListener(displayListener, Executors.MAIN_EXECUTOR.handler)
        // In the scenario where displays were added before this display listener was
        // registered, we should store the DisplayResources for those displays directly.
        displayManager.displays
            .filter { getDisplayResource(it.displayId) == null }
            .forEach { storeDisplayResource(it.displayId) }
    }

    fun destroy() {
        displayResourceArray.valueIterator().forEach { displayResource ->
            displayResource.cleanup()
        }
        displayResourceArray.clear()
        displayManager.unregisterDisplayListener(displayListener)
    }

    fun getDisplayResource(displayId: Int): RESOURCE_TYPE? {
        if (DEBUG) Log.d(TAG, "get: displayId=$displayId")
        return displayResourceArray[displayId]
    }

    fun deleteDisplayResource(displayId: Int) {
        if (DEBUG) Log.d(TAG, "delete: displayId=$displayId")
        getDisplayResource(displayId)?.cleanup()
        displayResourceArray.remove(displayId)
    }

    fun storeDisplayResource(displayId: Int) {
        if (DEBUG) Log.d(TAG, "store: displayId=$displayId")
        getDisplayResource(displayId)?.let {
            return
        }
        val display = displayManager.getDisplay(displayId)
        if (display == null) {
            if (DEBUG)
                Log.w(
                    TAG,
                    "storeDisplayResource: could not create display for displayId=$displayId",
                    Exception(),
                )
            return
        }
        displayResourceArray[displayId] = createDisplayResource(display)
    }

    fun dump(prefix: String, writer: PrintWriter) {
        writer.println("${prefix}${this::class.simpleName}: display resources=[")

        displayResourceArray.valueIterator().forEach { displayResource ->
            displayResource.dump("${prefix}\t", writer)
        }
        writer.println("${prefix}]")
    }

    abstract class DisplayResource {
        abstract fun cleanup()

        abstract fun dump(prefix: String, writer: PrintWriter)
    }
}
