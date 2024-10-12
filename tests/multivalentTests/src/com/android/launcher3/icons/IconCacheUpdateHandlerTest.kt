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

package com.android.launcher3.icons

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.database.MatrixCursor
import android.os.Process.myUserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.launcher3.icons.cache.BaseIconCache
import com.android.launcher3.icons.cache.BaseIconCache.IconDB
import com.android.launcher3.icons.cache.CachedObject
import com.android.launcher3.icons.cache.CachedObjectCachingLogic
import com.android.launcher3.icons.cache.IconCacheUpdateHandler
import com.android.launcher3.util.RoboApiWrapper
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.FutureTask
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class IconCacheUpdateHandlerTest {

    @Mock private lateinit var iconProvider: IconProvider
    @Mock private lateinit var baseIconCache: BaseIconCache

    private var cursor: MatrixCursor? = null
    private var cachingLogic = CachedObjectCachingLogic

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        doReturn(iconProvider).whenever(baseIconCache).iconProvider
    }

    @After
    fun tearDown() {
        cursor?.close()
    }

    @Test
    fun `IconCacheUpdateHandler returns null if the component name is malformed`() {
        val updateHandlerUnderTest = IconCacheUpdateHandler(baseIconCache)
        val cn = ComponentName.unflattenFromString("com.android.fake/.FakeActivity")!!

        val result =
            updateHandlerUnderTest.updateOrDeleteIcon(
                createCursor(1, cn.flattenToString() + "#", "freshId-old"),
                hashMapOf(cn to TestCachedObject(cn, "freshId")),
                setOf(),
                myUserHandle(),
                cachingLogic,
            )
        assertThat(result).isNull()
    }

    @Test
    fun `IconCacheUpdateHandler returns null if the freshId match`() {
        val updateHandlerUnderTest = IconCacheUpdateHandler(baseIconCache)
        val cn = ComponentName.unflattenFromString("com.android.fake/.FakeActivity")!!

        val result =
            updateHandlerUnderTest.updateOrDeleteIcon(
                createCursor(1, cn.flattenToString(), "freshId"),
                hashMapOf(cn to TestCachedObject(cn, "freshId")),
                setOf(),
                myUserHandle(),
                cachingLogic,
            )
        assertThat(result).isNull()
    }

    @Test
    fun `IconCacheUpdateHandler returns non-null if the freshId do not match`() {
        val updateHandlerUnderTest = IconCacheUpdateHandler(baseIconCache)
        val cn = ComponentName.unflattenFromString("com.android.fake/.FakeActivity")!!
        val testObj = TestCachedObject(cn, "freshId")

        val result =
            updateHandlerUnderTest.updateOrDeleteIcon(
                createCursor(1, cn.flattenToString(), "freshId-old"),
                hashMapOf(cn to testObj),
                setOf(),
                myUserHandle(),
                cachingLogic,
            )
        assertThat(result).isEqualTo(testObj)
    }

    private fun createCursor(row: Long, component: String, appState: String) =
        MatrixCursor(
                arrayOf(IconDB.COLUMN_ROWID, IconDB.COLUMN_COMPONENT, IconDB.COLUMN_FRESHNESS_ID)
            )
            .apply { addRow(arrayOf(row, component, appState)) }
            .apply {
                cursor = this
                moveToNext()
            }
}

/** Utility method to wait for the icon update handler to finish */
fun IconCache.waitForUpdateHandlerToFinish() {
    var cacheUpdateInProgress = true
    while (cacheUpdateInProgress) {
        val cacheCheck = FutureTask {
            // Check for pending message on the worker thread itself as some task may be
            // running currently
            workerHandler.hasMessages(0, iconUpdateToken)
        }
        workerHandler.postDelayed(cacheCheck, 10)
        RoboApiWrapper.waitForLooperSync(workerHandler.looper)
        cacheUpdateInProgress = cacheCheck.get()
    }
}

class TestCachedObject(val cn: ComponentName, val freshnessId: String) : CachedObject {

    override fun getComponent() = cn

    override fun getUser() = myUserHandle()

    override fun getLabel(): CharSequence? = null

    override fun getApplicationInfo(): ApplicationInfo? = null

    override fun getFreshnessIdentifier(iconProvider: IconProvider): String? = freshnessId
}
