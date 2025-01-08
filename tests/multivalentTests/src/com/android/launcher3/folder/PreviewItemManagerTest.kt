/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.launcher3.folder

import android.R
import android.graphics.Bitmap
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.launcher3.LauncherAppState
import com.android.launcher3.graphics.PreloadIconDrawable
import com.android.launcher3.graphics.ThemeManager
import com.android.launcher3.icons.BitmapInfo
import com.android.launcher3.icons.FastBitmapDrawable
import com.android.launcher3.icons.IconCache
import com.android.launcher3.icons.IconCache.ItemInfoUpdateReceiver
import com.android.launcher3.icons.PlaceHolderIconDrawable
import com.android.launcher3.icons.UserBadgeDrawable
import com.android.launcher3.icons.mono.MonoThemedBitmap
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfoWithIcon.FLAG_ARCHIVED
import com.android.launcher3.model.data.ItemInfoWithIcon.FLAG_INSTALL_SESSION_ACTIVE
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.util.ActivityContextWrapper
import com.android.launcher3.util.Executors
import com.android.launcher3.util.FlagOp
import com.android.launcher3.util.LauncherLayoutBuilder
import com.android.launcher3.util.LauncherModelHelper
import com.android.launcher3.util.LauncherModelHelper.SandboxModelContext
import com.android.launcher3.util.TestUtil
import com.android.launcher3.util.UserIconInfo
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Tests for [PreviewItemManager] */
@SmallTest
@RunWith(AndroidJUnit4::class)
class PreviewItemManagerTest {

    private lateinit var previewItemManager: PreviewItemManager
    private lateinit var context: SandboxModelContext
    private lateinit var folderItems: ArrayList<WorkspaceItemInfo>
    private lateinit var modelHelper: LauncherModelHelper
    private lateinit var folderIcon: FolderIcon
    private lateinit var iconCache: IconCache

    private var defaultThemedIcons = false

    private val themeManager: ThemeManager
        get() = ThemeManager.INSTANCE.get(context)

    @Before
    fun setup() {
        modelHelper = LauncherModelHelper()
        context = modelHelper.sandboxContext
        folderIcon = FolderIcon(ActivityContextWrapper(context))

        val app = spy(LauncherAppState.getInstance(context))
        iconCache = spy(app.iconCache)
        doReturn(iconCache).whenever(app).iconCache
        context.putObject(LauncherAppState.INSTANCE, app)
        doReturn(null).whenever(iconCache).updateIconInBackground(any(), any())

        previewItemManager = PreviewItemManager(folderIcon)
        modelHelper
            .setupDefaultLayoutProvider(
                LauncherLayoutBuilder()
                    .atWorkspace(0, 0, 1)
                    .putFolder(R.string.copy)
                    .addApp(LauncherModelHelper.TEST_PACKAGE, LauncherModelHelper.TEST_ACTIVITY)
                    .addApp(LauncherModelHelper.TEST_PACKAGE, LauncherModelHelper.TEST_ACTIVITY2)
                    .addApp(LauncherModelHelper.TEST_PACKAGE, LauncherModelHelper.TEST_ACTIVITY3)
                    .addApp(LauncherModelHelper.TEST_PACKAGE, LauncherModelHelper.TEST_ACTIVITY4)
                    .build()
            )
            .loadModelSync()

        // Use getAppContents() to "cast" contents to WorkspaceItemInfo so we can set bitmaps
        folderItems = modelHelper.bgDataModel.collections.valueAt(0).getAppContents()
        folderIcon.mInfo = modelHelper.bgDataModel.collections.valueAt(0) as FolderInfo
        folderIcon.mInfo.getContents().addAll(folderItems)

        // Set first icon to be themed.
        folderItems[0].bitmap.themedBitmap =
            MonoThemedBitmap(
                folderItems[0].bitmap.icon,
                Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888),
            )

        // Set second icon to be non-themed.
        folderItems[1].bitmap.themedBitmap = null

        // Set third icon to be themed with badge.
        folderItems[2].bitmap.themedBitmap =
            MonoThemedBitmap(
                folderItems[2].bitmap.icon,
                Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888),
            )
        folderItems[2].bitmap =
            folderItems[2].bitmap.withFlags(profileFlagOp(UserIconInfo.TYPE_WORK))

        // Set fourth icon to be non-themed with badge.
        folderItems[3].bitmap =
            folderItems[3].bitmap.withFlags(profileFlagOp(UserIconInfo.TYPE_WORK))
        folderItems[3].bitmap.themedBitmap = null

        defaultThemedIcons = themeManager.isMonoThemeEnabled
    }

    @After
    @Throws(Exception::class)
    fun tearDown() {
        themeManager.isMonoThemeEnabled = defaultThemedIcons
        modelHelper.destroy()
    }

    @Test
    fun checkThemedIconWithThemingOn_iconShouldBeThemed() {
        themeManager.isMonoThemeEnabled = true
        val drawingParams = PreviewItemDrawingParams(0f, 0f, 0f)

        previewItemManager.setDrawable(drawingParams, folderItems[0])

        assert((drawingParams.drawable as FastBitmapDrawable).isThemed)
    }

    @Test
    fun checkThemedIconWithThemingOff_iconShouldNotBeThemed() {
        themeManager.isMonoThemeEnabled = false
        val drawingParams = PreviewItemDrawingParams(0f, 0f, 0f)

        previewItemManager.setDrawable(drawingParams, folderItems[0])

        assert(!(drawingParams.drawable as FastBitmapDrawable).isThemed)
    }

    @Test
    fun checkUnthemedIconWithThemingOn_iconShouldNotBeThemed() {
        themeManager.isMonoThemeEnabled = true
        val drawingParams = PreviewItemDrawingParams(0f, 0f, 0f)

        previewItemManager.setDrawable(drawingParams, folderItems[1])

        assert(!(drawingParams.drawable as FastBitmapDrawable).isThemed)
    }

    @Test
    fun checkUnthemedIconWithThemingOff_iconShouldNotBeThemed() {
        themeManager.isMonoThemeEnabled = false
        val drawingParams = PreviewItemDrawingParams(0f, 0f, 0f)

        previewItemManager.setDrawable(drawingParams, folderItems[1])

        assert(!(drawingParams.drawable as FastBitmapDrawable).isThemed)
    }

    @Test
    fun checkThemedIconWithBadgeWithThemingOn_iconAndBadgeShouldBeThemed() {
        themeManager.isMonoThemeEnabled = true
        val drawingParams = PreviewItemDrawingParams(0f, 0f, 0f)

        previewItemManager.setDrawable(drawingParams, folderItems[2])

        assert((drawingParams.drawable as FastBitmapDrawable).isThemed)
        assert(
            ((drawingParams.drawable as FastBitmapDrawable).badge as UserBadgeDrawable).mIsThemed
        )
    }

    @Test
    fun checkUnthemedIconWithBadgeWithThemingOn_badgeShouldBeThemed() {
        themeManager.isMonoThemeEnabled = true
        val drawingParams = PreviewItemDrawingParams(0f, 0f, 0f)

        previewItemManager.setDrawable(drawingParams, folderItems[3])

        assert(!(drawingParams.drawable as FastBitmapDrawable).isThemed)
        assert(
            ((drawingParams.drawable as FastBitmapDrawable).badge as UserBadgeDrawable).mIsThemed
        )
    }

    @Test
    fun checkUnthemedIconWithBadgeWithThemingOff_iconAndBadgeShouldNotBeThemed() {
        themeManager.isMonoThemeEnabled = false
        val drawingParams = PreviewItemDrawingParams(0f, 0f, 0f)

        previewItemManager.setDrawable(drawingParams, folderItems[3])

        assert(!(drawingParams.drawable as FastBitmapDrawable).isThemed)
        assert(
            !((drawingParams.drawable as FastBitmapDrawable).badge as UserBadgeDrawable).mIsThemed
        )
    }

    @Test
    fun `Inactive archived app previews are not drawn as preload icon`() {
        // Given
        val drawingParams = PreviewItemDrawingParams(0f, 0f, 0f)
        val archivedApp =
            WorkspaceItemInfo().apply {
                runtimeStatusFlags = runtimeStatusFlags or FLAG_ARCHIVED
                runtimeStatusFlags = runtimeStatusFlags and FLAG_INSTALL_SESSION_ACTIVE.inv()
            }
        // When
        previewItemManager.setDrawable(drawingParams, archivedApp)
        // Then
        assertThat(drawingParams.drawable).isNotInstanceOf(PreloadIconDrawable::class.java)
    }

    @Test
    fun `Actively installing archived app previews are drawn as preload icon`() {
        // Given
        val drawingParams = PreviewItemDrawingParams(0f, 0f, 0f)
        val archivedApp =
            WorkspaceItemInfo().apply {
                runtimeStatusFlags = runtimeStatusFlags or FLAG_ARCHIVED
                runtimeStatusFlags = runtimeStatusFlags or FLAG_INSTALL_SESSION_ACTIVE
            }
        // When
        TestUtil.runOnExecutorSync(Executors.MAIN_EXECUTOR) {
            // Run on main thread because preload drawable triggers animator
            previewItemManager.setDrawable(drawingParams, archivedApp)
        }
        // Then
        assertThat(drawingParams.drawable).isInstanceOf(PreloadIconDrawable::class.java)
    }

    @Test
    fun `Preview item loads and apply high res icon`() {
        val drawingParams = PreviewItemDrawingParams(0f, 0f, 0f)
        val originalBitmap = folderItems[3].bitmap
        folderItems[3].bitmap = BitmapInfo.LOW_RES_INFO

        previewItemManager.setDrawable(drawingParams, folderItems[3])
        assertThat(drawingParams.drawable).isInstanceOf(PlaceHolderIconDrawable::class.java)

        val callbackCaptor = argumentCaptor<ItemInfoUpdateReceiver>()
        verify(iconCache).updateIconInBackground(callbackCaptor.capture(), eq(folderItems[3]))

        // Restore high-res icon
        folderItems[3].bitmap = originalBitmap

        // Calling with a different item info will ignore the update
        callbackCaptor.firstValue.reapplyItemInfo(folderItems[2])
        assertThat(drawingParams.drawable).isInstanceOf(PlaceHolderIconDrawable::class.java)

        // Calling with correct value will update the drawable to high-res
        callbackCaptor.firstValue.reapplyItemInfo(folderItems[3])
        assertThat(drawingParams.drawable).isNotInstanceOf(PlaceHolderIconDrawable::class.java)
        assertThat(drawingParams.drawable).isInstanceOf(FastBitmapDrawable::class.java)
    }

    private fun profileFlagOp(type: Int) =
        UserIconInfo(Process.myUserHandle(), type).applyBitmapInfoFlags(FlagOp.NO_OP)
}
