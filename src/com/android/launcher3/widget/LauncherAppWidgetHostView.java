/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.launcher3.widget;

import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.AdapterView;
import android.widget.Advanceable;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.launcher3.CheckLongPressHelper;
import com.android.launcher3.Flags;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.util.Themes;
import com.android.launcher3.views.ActivityContext;
import com.android.launcher3.views.BaseDragLayer;
import com.android.launcher3.views.BaseDragLayer.TouchCompleteListener;

/**
 * {@inheritDoc}
 */
public class LauncherAppWidgetHostView extends BaseLauncherAppWidgetHostView
        implements TouchCompleteListener, View.OnLongClickListener {

    private static final String TAG = "LauncherAppWidgetHostView";

    // Related to the auto-advancing of widgets
    private static final long ADVANCE_INTERVAL = 20000;
    private static final long ADVANCE_STAGGER = 250;

    private @Nullable CellChildViewPreLayoutListener mCellChildViewPreLayoutListener;

    // Maintains a list of widget ids which are supposed to be auto advanced.
    private static final SparseBooleanArray sAutoAdvanceWidgetIds = new SparseBooleanArray();
    // Maximum duration for which updates can be deferred.
    private static final long UPDATE_LOCK_TIMEOUT_MILLIS = 1000;

    private static final String TRACE_METHOD_NAME = "appwidget load-widget ";

    private static final Integer NO_LAYOUT_ID = Integer.valueOf(0);

    private final CheckLongPressHelper mLongPressHelper;
    protected final ActivityContext mActivityContext;

    private boolean mIsScrollable;
    private boolean mIsAttachedToWindow;
    private boolean mIsAutoAdvanceRegistered;
    private Runnable mAutoAdvanceRunnable;

    private long mDeferUpdatesUntilMillis = 0;
    private RemoteViews mLastRemoteViews;
    private boolean mReapplyOnResumeUpdates = false;

    private boolean mTrackingWidgetUpdate = false;

    private int mFocusRectOutsets = 0;

    public LauncherAppWidgetHostView(Context context) {
        super(context);
        mActivityContext = ActivityContext.lookupContext(context);
        mLongPressHelper = new CheckLongPressHelper(this, this);
        setAccessibilityDelegate(mActivityContext.getAccessibilityDelegate());
        setBackgroundResource(R.drawable.widget_internal_focus_bg);
        if (Flags.enableFocusOutline()) {
            setDefaultFocusHighlightEnabled(false);
            mFocusRectOutsets = context.getResources().getDimensionPixelSize(
                    R.dimen.focus_rect_widget_outsets);
        }

        if (Themes.getAttrBoolean(context, R.attr.isWorkspaceDarkText)) {
            setOnLightBackground(true);
        }
    }

    @Override
    public void setColorResources(@Nullable SparseIntArray colors) {
        if (colors == null) {
            resetColorResources();
        } else {
            super.setColorResources(colors);
        }
    }

    @Override
    public boolean onLongClick(View view) {
        if (!Utilities.isWorkspaceEditAllowed((Context) mActivityContext)) return true;
        if (mIsScrollable) {
            mActivityContext.getDragLayer().requestDisallowInterceptTouchEvent(false);
        }
        view.performLongClick();
        return true;
    }

    @Override
    public void setAppWidget(int appWidgetId, AppWidgetProviderInfo info) {
        super.setAppWidget(appWidgetId, info);
        if (!mTrackingWidgetUpdate && appWidgetId != -1) {
            mTrackingWidgetUpdate = true;
            Trace.beginAsyncSection(TRACE_METHOD_NAME + info.provider, appWidgetId);
            Log.i(TAG, "App widget created with id: " + appWidgetId);
        }
    }

    @Override
    public void updateAppWidget(RemoteViews remoteViews) {
        if (mTrackingWidgetUpdate && remoteViews != null) {
            Log.i(TAG, "App widget with id: " + getAppWidgetId() + " loaded");
            Trace.endAsyncSection(
                    TRACE_METHOD_NAME + getAppWidgetInfo().provider, getAppWidgetId());
            mTrackingWidgetUpdate = false;
        }
        mLastRemoteViews = remoteViews;
        mReapplyOnResumeUpdates = isDeferringUpdates();
        if (mReapplyOnResumeUpdates) {
            return;
        }

        super.updateAppWidget(remoteViews);

        // The provider info or the views might have changed.
        checkIfAutoAdvance();
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        mReapplyOnResumeUpdates |= isDeferringUpdates();
    }

    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);
        mReapplyOnResumeUpdates |= isDeferringUpdates();
    }

    private boolean checkScrollableRecursively(ViewGroup viewGroup) {
        if (viewGroup instanceof AdapterView) {
            return true;
        } else {
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                View child = viewGroup.getChildAt(i);
                if (child instanceof ViewGroup) {
                    if (checkScrollableRecursively((ViewGroup) child)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isTaggedAsScrollable() {
        // TODO: Introduce new api in AppWidgetHostView to indicate whether the widget is
        // scrollable.
        for (int i = 0; i < this.getChildCount(); i++) {
            View child = this.getChildAt(i);
            final Integer layoutId = (Integer) child.getTag(android.R.id.widget_frame);
            if (layoutId != null) {
                // The layout id is only set to 0 when RemoteViews is created from
                // DrawInstructions.
                return NO_LAYOUT_ID.equals(layoutId);
            }
        }
        return false;
    }

    /**
     * Returns true if the application of {@link RemoteViews} through {@link #updateAppWidget} are
     * currently being deferred.
     * @see #beginDeferringUpdates()
     */
    private boolean isDeferringUpdates() {
        return SystemClock.uptimeMillis() < mDeferUpdatesUntilMillis;
    }

    /**
     * Begin deferring the application of any {@link RemoteViews} updates made through
     * {@link #updateAppWidget} until {@link #endDeferringUpdates()} has been called or the next
     * {@link #updateAppWidget} call after {@link #UPDATE_LOCK_TIMEOUT_MILLIS} have elapsed.
     */
    public void beginDeferringUpdates() {
        mDeferUpdatesUntilMillis = SystemClock.uptimeMillis() + UPDATE_LOCK_TIMEOUT_MILLIS;
    }

    /**
     * Stop deferring the application of {@link RemoteViews} updates made through
     * {@link #updateAppWidget} and apply any deferred updates.
     */
    public void endDeferringUpdates() {
        mDeferUpdatesUntilMillis = 0;
        if (mReapplyOnResumeUpdates) {
            updateAppWidget(mLastRemoteViews);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            BaseDragLayer<?> dragLayer = mActivityContext.getDragLayer();
            if (mIsScrollable) {
                dragLayer.requestDisallowInterceptTouchEvent(true);
            }
            dragLayer.setTouchCompleteListener(this);
        }
        mLongPressHelper.onTouchEvent(ev);
        return mLongPressHelper.hasPerformedLongPress();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        mLongPressHelper.onTouchEvent(ev);
        // We want to keep receiving though events to be able to cancel long press on ACTION_UP
        return true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mIsAttachedToWindow = true;
        checkIfAutoAdvance();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        // We can't directly use isAttachedToWindow() here, as this is called before the internal
        // state is updated. So isAttachedToWindow() will return true until next frame.
        mIsAttachedToWindow = false;
        checkIfAutoAdvance();
    }

    @Override
    public void cancelLongPress() {
        super.cancelLongPress();
        mLongPressHelper.cancelLongPress();
    }

    @Override
    public void getFocusedRect(Rect r) {
        super.getFocusedRect(r);
        // Outset to a larger rect for drawing a padding between focus outline and widget
        r.inset(mFocusRectOutsets, mFocusRectOutsets);
    }

    @Override
    public void onTouchComplete() {
        if (!mLongPressHelper.hasPerformedLongPress()) {
            // If a long press has been performed, we don't want to clear the record of that since
            // we still may be receiving a touch up which we want to intercept
            mLongPressHelper.cancelLongPress();
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        mIsScrollable = isTaggedAsScrollable() || checkScrollableRecursively(this);
    }

    /**
     * Set the pre-layout listener
     * @param listener The listener to be notified when {@code CellLayout} is to layout this view
     */
    public void setCellChildViewPreLayoutListener(
            @NonNull CellChildViewPreLayoutListener listener) {
        mCellChildViewPreLayoutListener = listener;
    }

    /** @return The current cell layout listener */
    @Nullable
    public CellChildViewPreLayoutListener getCellChildViewPreLayoutListener() {
        return mCellChildViewPreLayoutListener;
    }

    /** Clear the listener for the pre-layout in CellLayout */
    public void clearCellChildViewPreLayoutListener() {
        mCellChildViewPreLayoutListener = null;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(getClass().getName());
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        maybeRegisterAutoAdvance();
    }

    private void checkIfAutoAdvance() {
        boolean isAutoAdvance = false;
        Advanceable target = getAdvanceable();
        if (target != null) {
            isAutoAdvance = true;
            target.fyiWillBeAdvancedByHostKThx();
        }

        boolean wasAutoAdvance = sAutoAdvanceWidgetIds.indexOfKey(getAppWidgetId()) >= 0;
        if (isAutoAdvance != wasAutoAdvance) {
            if (isAutoAdvance) {
                sAutoAdvanceWidgetIds.put(getAppWidgetId(), true);
            } else {
                sAutoAdvanceWidgetIds.delete(getAppWidgetId());
            }
            maybeRegisterAutoAdvance();
        }
    }

    private Advanceable getAdvanceable() {
        AppWidgetProviderInfo info = getAppWidgetInfo();
        if (info == null || info.autoAdvanceViewId == NO_ID || !mIsAttachedToWindow) {
            return null;
        }
        View v = findViewById(info.autoAdvanceViewId);
        return (v instanceof Advanceable) ? (Advanceable) v : null;
    }

    private void maybeRegisterAutoAdvance() {
        Handler handler = getHandler();
        boolean shouldRegisterAutoAdvance = getWindowVisibility() == VISIBLE && handler != null
                && (sAutoAdvanceWidgetIds.indexOfKey(getAppWidgetId()) >= 0);
        if (shouldRegisterAutoAdvance != mIsAutoAdvanceRegistered) {
            mIsAutoAdvanceRegistered = shouldRegisterAutoAdvance;
            if (mAutoAdvanceRunnable == null) {
                mAutoAdvanceRunnable = this::runAutoAdvance;
            }

            handler.removeCallbacks(mAutoAdvanceRunnable);
            scheduleNextAdvance();
        }
    }

    private void scheduleNextAdvance() {
        if (!mIsAutoAdvanceRegistered) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        long advanceTime = now + (ADVANCE_INTERVAL - (now % ADVANCE_INTERVAL)) +
                ADVANCE_STAGGER * sAutoAdvanceWidgetIds.indexOfKey(getAppWidgetId());
        Handler handler = getHandler();
        if (handler != null) {
            handler.postAtTime(mAutoAdvanceRunnable, advanceTime);
        }
    }

    private void runAutoAdvance() {
        Advanceable target = getAdvanceable();
        if (target != null) {
            target.advance();
        }
        scheduleNextAdvance();
    }

    @Override
    protected boolean shouldAllowDirectClick() {
        if (getTag() instanceof ItemInfo item) {
            return item.spanX == 1 && item.spanY == 1;
        }
        return false;
    }

    /**
     * Listener interface to be called when {@code CellLayout} is about to layout this child view
     */
    public interface CellChildViewPreLayoutListener {
        /**
         * Notify the bound changes to this view on pre-layout
         * @param v The view which the listener is set for
         * @param left The new left coordinate of this view
         * @param top The new top coordinate of this view
         * @param right The new right coordinate of this view
         * @param bottom The new bottom coordinate of this view
         */
        void notifyBoundChangeOnPreLayout(View v, int left, int top, int right, int bottom);
    }

    @Override
    protected void dispatchRestoreInstanceState(SparseArray<Parcelable> container) {
        try {
            super.dispatchRestoreInstanceState(container);
        } catch (Exception e) {
            Log.i(TAG, "Exception: " + e);
        }
    }
}
