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

package com.android.launcher3.taskbar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.android.launcher3.R;
import com.android.launcher3.Reorderable;
import com.android.launcher3.Utilities;
import com.android.launcher3.util.MultiTranslateDelegate;
import com.android.launcher3.util.Themes;
import com.android.systemui.shared.recents.model.Task;

import java.util.ArrayList;
import java.util.List;

/**
 * View used as overflow icon within task bar, when the list of recent/running apps overflows the
 * available display bounds - if display is not wide enough to show all running apps in the taskbar,
 * this icon is added to the taskbar as an entry point to open UI that surfaces all running apps.
 * The icon contains icon representations of up to 4 more recent tasks in overflow, stacked on top
 * each other in counter clockwise manner (icons of tasks partially overlapping with each other).
 */
public class TaskbarOverflowView extends FrameLayout implements Reorderable {
    private static final int MAX_ITEMS_IN_PREVIEW = 4;

    private boolean mIsRtlLayout;
    private final List<Task> mItems = new ArrayList<Task>();
    private int mIconSize;
    private int mPadding;
    private Paint mItemBackgroundPaint;
    private final MultiTranslateDelegate mTranslateDelegate = new MultiTranslateDelegate(this);
    private float mScaleForReorderBounce = 1f;
    private int mItemBackgroundColor;
    private int mLeaveBehindColor;
    private float mItemPreviewStrokeWidth;

    // Active means the overflow icon has been pressed, which replaces the app icons with the
    // leave-behind circle and shows the KQS UI.
    private boolean mIsActive = false;

    public TaskbarOverflowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TaskbarOverflowView(Context context) {
        super(context);
        init();
    }

    /**
     * Inflates the taskbar overflow button view.
     * @param resId The resource to inflate the view from.
     * @param group The parent view.
     * @param iconSize The size of the overflow button icon.
     * @param padding The internal padding of the overflow view.
     * @return A taskbar overflow button.
     */
    public static TaskbarOverflowView inflateIcon(int resId, ViewGroup group, int iconSize,
            int padding) {
        LayoutInflater inflater = LayoutInflater.from(group.getContext());
        TaskbarOverflowView icon = (TaskbarOverflowView) inflater.inflate(resId, group, false);

        icon.mIconSize = iconSize;
        icon.mPadding = padding;
        return icon;
    }

    private void init() {
        mIsRtlLayout = Utilities.isRtl(getResources());
        mItemBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mItemBackgroundColor = getContext().getColor(R.color.taskbar_background);
        mLeaveBehindColor = Themes.getAttrColor(getContext(), android.R.attr.textColorTertiary);
        mItemPreviewStrokeWidth = getResources().getDimension(
                R.dimen.taskbar_overflow_button_preview_stroke);

        setWillNotDraw(false);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        if (mIsActive) {
            drawLeaveBehindCircle(canvas);
        } else {
            drawAppIcons(canvas);
        }
    }

    private void drawAppIcons(@NonNull Canvas canvas) {
        mItemBackgroundPaint.setColor(mItemBackgroundColor);
        float radius = mIconSize / 2f - mPadding;

        int itemsToShow = Math.min(mItems.size(), MAX_ITEMS_IN_PREVIEW);
        for (int i = itemsToShow - 1; i >= 0; --i) {
            Drawable icon = mItems.get(mItems.size() - i - 1).icon;
            if (icon == null) {
                continue;
            }

            // Set the item icon size so two items fit within the overflow icon with stroke width
            // included, and overlap of 4 stroke width sizes between base item preview items.
            // 2 * strokeWidth + 2 * itemIconSize - 4 * strokeWidth = iconSize = 2 * radius.
            float itemIconSize = radius + mItemPreviewStrokeWidth;
            // Offset item icon from center so item icon stroke edge matches the parent icon edge.
            float itemCenterOffset = radius - itemIconSize / 2 - mItemPreviewStrokeWidth;

            float itemCenterX = getItemXOffset(itemCenterOffset, mIsRtlLayout, i, itemsToShow);
            float itemCenterY = getItemYOffset(itemCenterOffset, i, itemsToShow);

            Drawable iconCopy = icon.getConstantState().newDrawable().mutate();
            iconCopy.setBounds(0, 0, (int) itemIconSize, (int) itemIconSize);

            canvas.save();
            float itemIconRadius = itemIconSize / 2;
            canvas.translate(
                    mPadding + itemCenterX + radius - itemIconRadius,
                    mPadding + itemCenterY + radius - itemIconRadius);
            canvas.drawCircle(itemIconRadius, itemIconRadius,
                    itemIconRadius + mItemPreviewStrokeWidth, mItemBackgroundPaint);
            iconCopy.draw(canvas);
            canvas.restore();
        }
    }

    private void drawLeaveBehindCircle(@NonNull Canvas canvas) {
        mItemBackgroundPaint.setColor(mLeaveBehindColor);

        final var xyCenter = mIconSize / 2f;
        canvas.drawCircle(xyCenter, xyCenter, mIconSize / 4f, mItemBackgroundPaint);
    }

    /**
     * Clears the list of tasks tracked by the view.
     */
    public void clearItems() {
        mItems.clear();
        invalidate();
    }

    /**
     * Update the view to represent a new list of recent tasks.
     * @param items Items to be shown in the view.
     */
    public void setItems(List<Task> items) {
        mItems.clear();
        mItems.addAll(items);
        invalidate();
    }

    /**
     * Called when a task is updated. If the task is contained within the view, it's cached value
     * gets updated. If the task is shown within the icon, invalidates the view, so the task icon
     * gets updated.
     * @param task The updated task.
     */
    public void updateTaskIsShown(Task task) {
        for (int i = 0; i < mItems.size(); ++i) {
            if (mItems.get(i).key.id == task.key.id) {
                mItems.set(i, task);
                if (i >= mItems.size() - MAX_ITEMS_IN_PREVIEW) {
                    invalidate();
                }
                break;
            }
        }
    }

    /**
     * Returns the view's state (whether it shows a set of app icons or a leave-behind circle).
     */
    public boolean getIsActive() {
        return mIsActive;
    }

    /**
     * Updates the view's state to draw either a set of app icons or a leave-behind circle.
     * @param isActive The next state of the view.
     */
    public void setIsActive(boolean isActive) {
        if (mIsActive != isActive) {
            mIsActive = isActive;
            invalidate();
        }
    }

    @Override
    public MultiTranslateDelegate getTranslateDelegate() {
        return mTranslateDelegate;
    }

    @Override
    public float getReorderBounceScale() {
        return mScaleForReorderBounce;
    }

    @Override
    public void setReorderBounceScale(float scale) {
        mScaleForReorderBounce = scale;
        super.setScaleX(scale);
        super.setScaleY(scale);
    }

    private float getItemXOffset(float baseOffset, boolean isRtl, int itemIndex, int itemCount) {
        // Item with index 1 is on the left in all cases.
        if (itemIndex == 1) {
            return (isRtl ? 1 : -1) * baseOffset;
        }

        // First item is centered if total number of items shown is 3, on the right otherwise.
        if (itemIndex == 0) {
            if (itemCount == 3) {
                return 0;
            }
            return (isRtl ? -1 : 1) * baseOffset;
        }

        // Last item is on the right when there are more than 2 items (case which is already handled
        // as `itemIndex == 1`).
        if (itemIndex == itemCount - 1) {
            return (isRtl ? -1 : 1) * baseOffset;
        }

        return (isRtl ? 1 : -1) * baseOffset;
    }

    private float getItemYOffset(float baseOffset, int itemIndex, int itemCount) {
        // If icon contains two items, they are both centered vertically.
        if (itemCount == 2) {
            return 0;
        }
        // First half of items is on top, later half is on bottom.
        return (itemIndex + 1 <= itemCount / 2 ? -1 : 1) * baseOffset;
    }
}
