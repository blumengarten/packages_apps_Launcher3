/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.launcher3.util

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/** Extension of [AbstractExecutorService] which executed on a provided looper. */
class LooperExecutor(looper: Looper, private val defaultPriority: Int) : AbstractExecutorService() {
    val handler: Handler = Handler(looper)

    @JvmOverloads
    constructor(
        name: String,
        defaultPriority: Int = Process.THREAD_PRIORITY_DEFAULT,
    ) : this(createAndStartNewLooper(name, defaultPriority), defaultPriority)

    /** Returns the thread for this executor */
    val thread: Thread
        get() = handler.looper.thread

    /** Returns the looper for this executor */
    val looper: Looper
        get() = handler.looper

    override fun execute(runnable: Runnable) {
        if (handler.looper == Looper.myLooper()) {
            runnable.run()
        } else {
            handler.post(runnable)
        }
    }

    /** Same as execute, but never runs the action inline. */
    fun post(runnable: Runnable) {
        handler.post(runnable)
    }

    @Deprecated("Not supported and throws an exception when used")
    override fun shutdown() {
        throw UnsupportedOperationException()
    }

    @Deprecated("Not supported and throws an exception when used.")
    override fun shutdownNow(): List<Runnable> {
        throw UnsupportedOperationException()
    }

    override fun isShutdown() = false

    override fun isTerminated() = false

    @Deprecated("Not supported and throws an exception when used.")
    override fun awaitTermination(l: Long, timeUnit: TimeUnit): Boolean {
        throw UnsupportedOperationException()
    }

    /**
     * Set the priority of a thread, based on Linux priorities.
     *
     * @param priority Linux priority level, from -20 for highest scheduling priority to 19 for
     *   lowest scheduling priority.
     * @see Process.setThreadPriority
     */
    fun setThreadPriority(priority: Int) {
        Process.setThreadPriority((thread as HandlerThread).threadId, priority)
    }

    companion object {
        /** Utility method to get a started handler thread statically with the provided priority */
        @JvmOverloads
        @JvmStatic
        fun createAndStartNewLooper(
            name: String,
            priority: Int = Process.THREAD_PRIORITY_DEFAULT,
        ): Looper = HandlerThread(name, priority).apply { start() }.looper
    }
}
