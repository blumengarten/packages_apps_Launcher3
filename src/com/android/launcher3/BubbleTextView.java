/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.launcher3;

import static android.graphics.fonts.FontStyle.FONT_WEIGHT_BOLD;
import static android.graphics.fonts.FontStyle.FONT_WEIGHT_NORMAL;
import static android.text.Layout.Alignment.ALIGN_NORMAL;

import static com.android.app.animation.Interpolators.EMPHASIZED;
import static com.android.launcher3.BubbleTextView.RunningAppState.RUNNING;
import static com.android.launcher3.BubbleTextView.RunningAppState.NOT_RUNNING;
import static com.android.launcher3.BubbleTextView.RunningAppState.MINIMIZED;
import static com.android.launcher3.Flags.enableContrastTiles;
import static com.android.launcher3.Flags.enableCursorHoverStates;
import static com.android.launcher3.InvariantDeviceProfile.KEY_ALLAPPS_THEMED_ICONS;
import static com.android.launcher3.allapps.AlphabeticalAppsList.PRIVATE_SPACE_PACKAGE;
import static com.android.launcher3.InvariantDeviceProfile.KEY_SHOW_DESKTOP_LABELS;
import static com.android.launcher3.InvariantDeviceProfile.KEY_SHOW_DRAWER_LABELS;
import static com.android.launcher3.graphics.PreloadIconDrawable.newPendingIcon;
import static com.android.launcher3.icons.BitmapInfo.FLAG_NO_BADGE;
import static com.android.launcher3.icons.BitmapInfo.FLAG_SKIP_USER_BADGE;
import static com.android.launcher3.icons.BitmapInfo.FLAG_THEMED;
import static com.android.launcher3.icons.GraphicsUtils.setColorAlphaBound;
import static com.android.launcher3.icons.IconNormalizer.ICON_VISIBLE_AREA_FACTOR;
import static com.android.launcher3.model.data.ItemInfoWithIcon.FLAG_INCREMENTAL_DOWNLOAD_ACTIVE;
import static com.android.launcher3.model.data.ItemInfoWithIcon.FLAG_INSTALL_SESSION_ACTIVE;
import static com.android.launcher3.model.data.ItemInfoWithIcon.FLAG_SHOW_DOWNLOAD_PROGRESS_MASK;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.icu.text.MessageFormat;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextUtils.TruncateAt;
import android.text.style.ImageSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Property;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewDebug;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import com.android.launcher3.accessibility.BaseAccessibilityDelegate;
import com.android.launcher3.dot.DotInfo;
import com.android.launcher3.dragndrop.DragOptions.PreDragCondition;
import com.android.launcher3.dragndrop.DraggableView;
import com.android.launcher3.folder.FolderIcon;
import com.android.launcher3.graphics.PreloadIconDrawable;
import com.android.launcher3.icons.DotRenderer;
import com.android.launcher3.icons.FastBitmapDrawable;
import com.android.launcher3.icons.IconCache.ItemInfoUpdateReceiver;
import com.android.launcher3.icons.PlaceHolderIconDrawable;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.ItemInfoWithIcon;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.popup.PopupContainerWithArrow;
import com.android.launcher3.search.StringMatcherUtility;
import com.android.launcher3.util.CancellableTask;
import com.android.launcher3.util.IntArray;
import com.android.launcher3.util.MultiTranslateDelegate;
import com.android.launcher3.util.SafeCloseable;
import com.android.launcher3.util.ShortcutUtil;
import com.android.launcher3.util.Themes;
import com.android.launcher3.views.ActivityContext;
import com.android.launcher3.views.FloatingIconViewCompanion;

import java.text.NumberFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Objects;

/**
 * TextView that draws a bubble behind the text. We cannot use a LineBackgroundSpan
 * because we want to make the bubble taller than the text and TextView's clip is
 * too aggressive.
 */
public class BubbleTextView extends TextView implements ItemInfoUpdateReceiver,
        FloatingIconViewCompanion, DraggableView, Reorderable {

    public static final String TAG = "BubbleTextView";

    public static final int DISPLAY_WORKSPACE = 0;
    public static final int DISPLAY_ALL_APPS = 1;
    public static final int DISPLAY_FOLDER = 2;
    public static final int DISPLAY_TASKBAR = 5;
    public static final int DISPLAY_SEARCH_RESULT = 6;
    public static final int DISPLAY_SEARCH_RESULT_SMALL = 7;
    public static final int DISPLAY_PREDICTION_ROW = 8;
    public static final int DISPLAY_SEARCH_RESULT_APP_ROW = 9;

    private static final float MIN_LETTER_SPACING = -0.05f;
    private static final int MAX_SEARCH_LOOP_COUNT = 20;
    private static final Character NEW_LINE = '\n';
    private static final String EMPTY = "";
    private static final StringMatcherUtility.StringMatcher MATCHER =
            StringMatcherUtility.StringMatcher.getInstance();
    private static final int BOLD_TEXT_ADJUSTMENT = FONT_WEIGHT_BOLD - FONT_WEIGHT_NORMAL;

    public static final int LINE_INDICATOR_ANIM_DURATION = 150;
    private static final float MINIMIZED_APP_INDICATOR_SCALE = 0.5f;

    private static final int[] STATE_PRESSED = new int[]{android.R.attr.state_pressed};

    private float mScaleForReorderBounce = 1f;

    private IntArray mBreakPointsIntArray;
    private CharSequence mLastOriginalText;
    private CharSequence mLastModifiedText;

    private static final Property<BubbleTextView, Float> DOT_SCALE_PROPERTY
            = new Property<BubbleTextView, Float>(Float.TYPE, "dotScale") {
        @Override
        public Float get(BubbleTextView bubbleTextView) {
            return bubbleTextView.mDotParams.scale;
        }

        @Override
        public void set(BubbleTextView bubbleTextView, Float value) {
            bubbleTextView.mDotParams.scale = value;
            bubbleTextView.invalidate();
        }
    };

    public static final Property<BubbleTextView, Float> TEXT_ALPHA_PROPERTY
            = new Property<BubbleTextView, Float>(Float.class, "textAlpha") {
        @Override
        public Float get(BubbleTextView bubbleTextView) {
            return bubbleTextView.mTextAlpha;
        }

        @Override
        public void set(BubbleTextView bubbleTextView, Float alpha) {
            bubbleTextView.setTextAlpha(alpha);
        }
    };

    private static final Property<BubbleTextView, Integer> LINE_INDICATOR_COLOR_PROPERTY =
            new Property<>(Integer.class, "lineIndicatorColor") {

                @Override
                public Integer get(BubbleTextView bubbleTextView) {
                    return bubbleTextView.mLineIndicatorColor;
                }

                @Override
                public void set(BubbleTextView bubbleTextView, Integer color) {
                    bubbleTextView.mLineIndicatorColor = color;
                    bubbleTextView.invalidate();
                }
            };

    private static final Property<BubbleTextView, Float> LINE_INDICATOR_SCALE_PROPERTY =
            new Property<>(Float.TYPE, "lineIndicatorScale") {

                @Override
                public Float get(BubbleTextView bubbleTextView) {
                    return bubbleTextView.mLineIndicatorScale;
                }

                @Override
                public void set(BubbleTextView bubbleTextView, Float scale) {
                    bubbleTextView.mLineIndicatorScale = scale;
                    bubbleTextView.invalidate();
                }
            };

    private final MultiTranslateDelegate mTranslateDelegate = new MultiTranslateDelegate(this);
    protected final ActivityContext mActivity;
    private FastBitmapDrawable mIcon;
    private DeviceProfile mDeviceProfile;
    private boolean mCenterVertically;

    protected int mDisplay;

    private final CheckLongPressHelper mLongPressHelper;

    private boolean mLayoutHorizontal;
    private final boolean mIsRtl;
    private final int mIconSize;

    @ViewDebug.ExportedProperty(category = "launcher")
    private boolean mHideBadge = false;
    @ViewDebug.ExportedProperty(category = "launcher")
    private boolean mSkipUserBadge = false;
    @ViewDebug.ExportedProperty(category = "launcher")
    protected boolean mIsIconVisible = true;
    @ViewDebug.ExportedProperty(category = "launcher")
    private int mTextColor;
    @ViewDebug.ExportedProperty(category = "launcher")
    private ColorStateList mTextColorStateList;
    @ViewDebug.ExportedProperty(category = "launcher")
    private float mTextAlpha = 1;

    @ViewDebug.ExportedProperty(category = "launcher")
    private DotInfo mDotInfo;
    private DotRenderer mDotRenderer;
    @ViewDebug.ExportedProperty(category = "launcher", deepExport = true)
    protected DotRenderer.DrawParams mDotParams;
    private Animator mDotScaleAnim;
    private boolean mForceHideDot;

    // These fields, related to showing running apps, are only used for Taskbar.
    private final int mRunningAppIndicatorWidth;
    private final int mRunningAppIndicatorHeight;
    private final int mRunningAppIndicatorTopMargin;
    private final Paint mRunningAppIndicatorPaint;
    private final Rect mRunningAppIconBounds = new Rect();
    private RunningAppState mRunningAppState;
    private final int mRunningAppIndicatorColor;
    private final int mMinimizedAppIndicatorColor;
    @ViewDebug.ExportedProperty(category = "launcher")
    private int mLineIndicatorColor;
    @ViewDebug.ExportedProperty(category = "launcher")
    private float mLineIndicatorScale;
    private int mLineIndicatorAnimStartDelay;
    private Animator mLineIndicatorAnim;

    private final String mMinimizedStateDescription;
    private final String mRunningStateDescription;

    /**
     * Various options for the running state of an app.
     */
    public enum RunningAppState {
        NOT_RUNNING,
        RUNNING,
        MINIMIZED,
    }

    @ViewDebug.ExportedProperty(category = "launcher")
    private boolean mStayPressed;
    @ViewDebug.ExportedProperty(category = "launcher")
    private boolean mIgnorePressedStateChange;
    @ViewDebug.ExportedProperty(category = "launcher")
    private boolean mDisableRelayout = false;

    private boolean mShouldShowLabel;
    private boolean mThemeAllAppsIcons;

    private CancellableTask mIconLoadRequest;

    private boolean mHighResUpdateInProgress = false;

    public BubbleTextView(Context context) {
        this(context, null, 0);
    }

    public BubbleTextView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BubbleTextView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mActivity = ActivityContext.lookupContext(context);
        FastBitmapDrawable.setFlagHoverEnabled(enableCursorHoverStates());
        mMinimizedStateDescription = getContext().getString(
                R.string.app_minimized_state_description);
        mRunningStateDescription = getContext().getString(R.string.app_running_state_description);

        TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.BubbleTextView, defStyle, 0);
        mLayoutHorizontal = a.getBoolean(R.styleable.BubbleTextView_layoutHorizontal, false);
        mIsRtl = (getResources().getConfiguration().getLayoutDirection()
                == View.LAYOUT_DIRECTION_RTL);
        mDeviceProfile = mActivity.getDeviceProfile();
        mCenterVertically = a.getBoolean(R.styleable.BubbleTextView_centerVertically, false);

        SharedPreferences prefs = LauncherPrefs.getPrefs(context.getApplicationContext());

        mDisplay = a.getInteger(R.styleable.BubbleTextView_iconDisplay, DISPLAY_WORKSPACE);
        final int defaultIconSize;
        if (mDisplay == DISPLAY_WORKSPACE) {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, mDeviceProfile.iconTextSizePx);
            setCompoundDrawablePadding(mDeviceProfile.iconDrawablePaddingPx);
            defaultIconSize = mDeviceProfile.iconSizePx;
            setCenterVertically(mDeviceProfile.iconCenterVertically);
            mShouldShowLabel = prefs.getBoolean(KEY_SHOW_DESKTOP_LABELS, true);
        } else if (mDisplay == DISPLAY_ALL_APPS || mDisplay == DISPLAY_PREDICTION_ROW
                || mDisplay == DISPLAY_SEARCH_RESULT_APP_ROW) {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, mDeviceProfile.allAppsIconTextSizePx);
            setCompoundDrawablePadding(mDeviceProfile.allAppsIconDrawablePaddingPx);
            defaultIconSize = mDeviceProfile.allAppsIconSizePx;
            mShouldShowLabel = prefs.getBoolean(KEY_SHOW_DRAWER_LABELS, true);
            mThemeAllAppsIcons = prefs.getBoolean(KEY_ALLAPPS_THEMED_ICONS, false);
        } else if (mDisplay == DISPLAY_FOLDER) {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, mDeviceProfile.folderChildTextSizePx);
            setCompoundDrawablePadding(mDeviceProfile.folderChildDrawablePaddingPx);
            defaultIconSize = mDeviceProfile.folderChildIconSizePx;
            mShouldShowLabel = prefs.getBoolean(KEY_SHOW_DESKTOP_LABELS, true);
        } else if (mDisplay == DISPLAY_SEARCH_RESULT) {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, mDeviceProfile.allAppsIconTextSizePx);
            defaultIconSize = getResources().getDimensionPixelSize(R.dimen.search_row_icon_size);
            mShouldShowLabel = prefs.getBoolean(KEY_SHOW_DESKTOP_LABELS, true);
        } else if (mDisplay == DISPLAY_SEARCH_RESULT_SMALL) {
            defaultIconSize = getResources().getDimensionPixelSize(
                    R.dimen.search_row_small_icon_size);
            mShouldShowLabel = prefs.getBoolean(KEY_SHOW_DESKTOP_LABELS, true);
        } else if (mDisplay == DISPLAY_TASKBAR) {
            defaultIconSize = mDeviceProfile.taskbarIconSize;
        } else {
            // widget_selection or shortcut_popup
            defaultIconSize = mDeviceProfile.iconSizePx;
            mShouldShowLabel = prefs.getBoolean(KEY_SHOW_DESKTOP_LABELS, true);
        }


        mIconSize = a.getDimensionPixelSize(R.styleable.BubbleTextView_iconSizeOverride,
                defaultIconSize);
        a.recycle();

        mRunningAppIndicatorWidth =
                getResources().getDimensionPixelSize(R.dimen.taskbar_running_app_indicator_width);
        mRunningAppIndicatorHeight =
                getResources().getDimensionPixelSize(R.dimen.taskbar_running_app_indicator_height);
        mRunningAppIndicatorTopMargin =
                getResources().getDimensionPixelSize(
                        R.dimen.taskbar_running_app_indicator_top_margin);

        mRunningAppIndicatorPaint = new Paint();
        mRunningAppIndicatorColor = getResources().getColor(
                R.color.taskbar_running_app_indicator_color, context.getTheme());
        mMinimizedAppIndicatorColor = getResources().getColor(
                R.color.taskbar_minimized_app_indicator_color, context.getTheme());

        mLongPressHelper = new CheckLongPressHelper(this);

        mDotParams = new DotRenderer.DrawParams();

        setEllipsize(TruncateAt.END);
        setAccessibilityDelegate(mActivity.getAccessibilityDelegate());
        setTextAlpha(1f);
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        // Disable marques when not focused to that, so that updating text does not cause relayout.
        setEllipsize(focused ? TruncateAt.MARQUEE : TruncateAt.END);
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
    }

    public void setHideBadge(boolean hideBadge) {
        mHideBadge = hideBadge;
    }

    public void setSkipUserBadge(boolean skipUserBadge) {
        mSkipUserBadge = skipUserBadge;
    }

    /**
     * Resets the view so it can be recycled.
     */
    public void reset() {
        mDotInfo = null;
        mDotParams.dotColor = Color.TRANSPARENT;
        mDotParams.appColor = Color.TRANSPARENT;
        cancelDotScaleAnim();
        mDotParams.scale = 0f;
        mForceHideDot = false;
        setBackground(null);

        mLineIndicatorColor = Color.TRANSPARENT;
        mLineIndicatorScale = 0;
        mLineIndicatorAnimStartDelay = 0;
        cancelLineIndicatorAnim();

        setTag(null);
        if (mIconLoadRequest != null) {
            mIconLoadRequest.cancel();
            mIconLoadRequest = null;
        }
        // Reset any shifty arrangements in case animation is disrupted.
        setPivotY(0);
        setAlpha(1);
        setScaleY(1);
        setTranslationY(0);
        setMaxLines(1);
        setVisibility(VISIBLE);
    }

    private void cancelDotScaleAnim() {
        if (mDotScaleAnim != null) {
            mDotScaleAnim.cancel();
        }
    }

    public void animateDotScale(float... dotScales) {
        cancelDotScaleAnim();
        mDotScaleAnim = ObjectAnimator.ofFloat(this, DOT_SCALE_PROPERTY, dotScales);
        mDotScaleAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mDotScaleAnim = null;
            }
        });
        mDotScaleAnim.start();
    }

    @Override
    public void setAccessibilityDelegate(AccessibilityDelegate delegate) {
        if (delegate instanceof BaseAccessibilityDelegate) {
            super.setAccessibilityDelegate(delegate);
        } else {
            // NO-OP
            // Workaround for b/129745295 where RecyclerView is setting our Accessibility
            // delegate incorrectly. There are no cases when we shouldn't be using the
            // LauncherAccessibilityDelegate for BubbleTextView.
        }
    }

    @UiThread
    public void applyFromWorkspaceItem(WorkspaceItemInfo info) {
        applyIconAndLabel(info);
        setItemInfo(info);

        applyDotState(info, false /* animate */);
        setDownloadStateContentDescription(info, info.getProgressLevel());
    }

    @UiThread
    public void applyFromApplicationInfo(AppInfo info) {
        applyIconAndLabel(info);
        setItemInfo(info);

        // Verify high res immediately
        verifyHighRes();

        applyDotState(info, false /* animate */);
        setDownloadStateContentDescription(info, info.getProgressLevel());
    }

    /**
     * Apply label and tag using a generic {@link ItemInfoWithIcon}
     */
    @UiThread
    public void applyFromItemInfoWithIcon(ItemInfoWithIcon info) {
        applyIconAndLabel(info);
        // We don't need to check the info since it's not a WorkspaceItemInfo
        setItemInfo(info);

        // Verify high res immediately
        verifyHighRes();

        setDownloadStateContentDescription(info, info.getProgressLevel());
    }

    /**
     * Directly set the icon and label.
     */
    @UiThread
    public void applyIconAndLabel(Drawable icon, CharSequence label) {
        applyCompoundDrawables(icon);
        setText(label);
        setContentDescription(label);
    }

    /** Updates whether the app this view represents is currently running. */
    @UiThread
    public void updateRunningState(RunningAppState runningAppState, boolean animate) {
        if (runningAppState.equals(mRunningAppState)) {
            return;
        }
        mRunningAppState = runningAppState;
        cancelLineIndicatorAnim();

        int color = switch (mRunningAppState) {
            case NOT_RUNNING -> Color.TRANSPARENT;
            case RUNNING -> mRunningAppIndicatorColor;
            case MINIMIZED -> mMinimizedAppIndicatorColor;
        };
        float scale = switch (mRunningAppState) {
            case NOT_RUNNING -> 0;
            case RUNNING -> 1;
            case MINIMIZED -> MINIMIZED_APP_INDICATOR_SCALE;
        };

        if (!animate) {
            mLineIndicatorColor = color;
            mLineIndicatorScale = scale;
            invalidate();
            return;
        }

        AnimatorSet lineIndicatorAnim  = new AnimatorSet();
        mLineIndicatorAnim = lineIndicatorAnim;
        Animator colorAnimator = ObjectAnimator.ofArgb(this, LINE_INDICATOR_COLOR_PROPERTY, color);
        Animator scaleAnimator = ObjectAnimator.ofFloat(this, LINE_INDICATOR_SCALE_PROPERTY, scale);
        lineIndicatorAnim.playTogether(colorAnimator, scaleAnimator);

        lineIndicatorAnim.setInterpolator(EMPHASIZED);
        lineIndicatorAnim.setStartDelay(mLineIndicatorAnimStartDelay);
        lineIndicatorAnim.setDuration(LINE_INDICATOR_ANIM_DURATION).start();
    }

    public void setLineIndicatorAnimStartDelay(int lineIndicatorAnimStartDelay) {
        mLineIndicatorAnimStartDelay = lineIndicatorAnimStartDelay;
    }

    private void cancelLineIndicatorAnim() {
        if (mLineIndicatorAnim != null) {
            mLineIndicatorAnim.cancel();
        }
    }

    /**
     * Returns state description of this icon.
     */
    public String getIconStateDescription() {
        if (mRunningAppState == MINIMIZED) {
            return mMinimizedStateDescription;
        } else if (mRunningAppState == RUNNING) {
            return mRunningStateDescription;
        } else {
            return "";
        }
    }

    protected void setItemInfo(ItemInfoWithIcon itemInfo) {
        setTag(itemInfo);
    }

    @VisibleForTesting
    @UiThread
    public void applyIconAndLabel(ItemInfoWithIcon info) {
        FastBitmapDrawable oldIcon = mIcon;
        if (!canReuseIcon(info)) {
            setNonPendingIcon(info);
        }
        applyLabel(info);
        maybeApplyProgressLevel(info, oldIcon);
    }

    /**
     * Check if we can reuse icon so that any animation is preserved
     */
    private boolean canReuseIcon(ItemInfoWithIcon info) {
        return mIcon instanceof PreloadIconDrawable p
                && p.hasNotCompleted() && p.isSameInfo(info.bitmap);
    }

    /**
     * Apply progress level to the icon if necessary
     */
    private void maybeApplyProgressLevel(ItemInfoWithIcon info, FastBitmapDrawable oldIcon) {
        if (!shouldApplyProgressLevel(info, oldIcon)) {
            return;
        }
        PreloadIconDrawable pendingIcon = applyProgressLevel(info);
        boolean isNoLongerPending = info instanceof WorkspaceItemInfo wii
                ? !wii.hasPromiseIconUi() : !info.isArchived();
        if (isNoLongerPending && info.getProgressLevel() == 100 && pendingIcon != null) {
            pendingIcon.maybePerformFinishedAnimation(
                    (oldIcon instanceof PreloadIconDrawable p) ? p : pendingIcon,
                    () -> setNonPendingIcon(
                            (getTag() instanceof ItemInfoWithIcon iiwi) ? iiwi : info));
        }
    }

    /**
     * Check if progress level should be applied to the icon
     */
    private boolean shouldApplyProgressLevel(ItemInfoWithIcon info, FastBitmapDrawable oldIcon) {
        return (info.runtimeStatusFlags & FLAG_SHOW_DOWNLOAD_PROGRESS_MASK) != 0
                || (info instanceof WorkspaceItemInfo wii && wii.hasPromiseIconUi())
                || (oldIcon instanceof PreloadIconDrawable p && p.hasNotCompleted());
    }

    private void setNonPendingIcon(ItemInfoWithIcon info) {
        // Set nonPendingIcon acts as a restart which should refresh the flag state when applicable.
        int flags = Objects.equals(info.getTargetPackage(), PRIVATE_SPACE_PACKAGE)
                ? info.bitmap.creationFlags : shouldUseTheme() ? FLAG_THEMED : 0;
        // Remove badge on icons smaller than 48dp.
        if (mHideBadge || mDisplay == DISPLAY_SEARCH_RESULT_SMALL) {
            flags |= FLAG_NO_BADGE;
        }
        if (mSkipUserBadge) {
            flags |= FLAG_SKIP_USER_BADGE;
        }
        FastBitmapDrawable iconDrawable = info.newIcon(getContext(), flags);
        mDotParams.appColor = iconDrawable.getIconColor();
        mDotParams.dotColor = Themes.getAttrColor(getContext(), R.attr.notificationDotColor);
        setIcon(iconDrawable);
    }

    protected boolean shouldUseTheme() {
        if (mDisplay == DISPLAY_ALL_APPS) {
            return mThemeAllAppsIcons;
        }
        return mDisplay == DISPLAY_WORKSPACE || mDisplay == DISPLAY_FOLDER
                || mDisplay == DISPLAY_TASKBAR;
    }

    /**
     * Only if actual text can be displayed in two line, the {@code true} value will be effective.
     */
    protected boolean shouldUseTwoLine() {
        return mDeviceProfile.inv.enableTwoLinesInAllApps
                && (mDisplay == DISPLAY_ALL_APPS || mDisplay == DISPLAY_PREDICTION_ROW);
    }

    @UiThread
    public void applyLabel(ItemInfo info) {
        if (mShouldShowLabel) {
        CharSequence label = info.title;
        if (label != null) {
            mLastOriginalText = label;
            mLastModifiedText = mLastOriginalText;
            mBreakPointsIntArray = StringMatcherUtility.getListOfBreakpoints(label, MATCHER);
            if (Flags.useNewIconForArchivedApps()
                    && info instanceof ItemInfoWithIcon infoWithIcon
                    && infoWithIcon.isInactiveArchive()) {
                setTextWithArchivingIcon(label);
            } else {
                setText(label);
                }
            }
        }
        if (info.contentDescription != null) {
            setContentDescription(info.isDisabled()
                    ? getContext().getString(R.string.disabled_app_label, info.contentDescription)
                    : info.contentDescription);
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (getTag() instanceof ItemInfoWithIcon infoWithIcon && infoWithIcon.isInactiveArchive()) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfoCompat.ACTION_CLICK,
                    getContext().getString(R.string.app_unarchiving_action)));
        }
    }

    /** This is used for testing to forcefully set the display. */
    @VisibleForTesting
    public void setDisplay(int display) {
        mDisplay = display;
    }

    /**
     * Overrides the default long press timeout.
     */
    public void setLongPressTimeoutFactor(float longPressTimeoutFactor) {
        mLongPressHelper.setLongPressTimeoutFactor(longPressTimeoutFactor);
    }

    @Override
    public void refreshDrawableState() {
        if (!mIgnorePressedStateChange) {
            super.refreshDrawableState();
        }
    }

    @Override
    protected int[] onCreateDrawableState(int extraSpace) {
        final int[] drawableState = super.onCreateDrawableState(extraSpace + 1);
        if (mStayPressed) {
            mergeDrawableStates(drawableState, STATE_PRESSED);
        }
        return drawableState;
    }

    /** Returns the icon for this view. */
    public FastBitmapDrawable getIcon() {
        return mIcon;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // ignore events if they happen in padding area
        if (event.getAction() == MotionEvent.ACTION_DOWN
                && shouldIgnoreTouchDown(event.getX(), event.getY())) {
            return false;
        }
        if (isLongClickable()) {
            super.onTouchEvent(event);
            mLongPressHelper.onTouchEvent(event);
            // Keep receiving the rest of the events
            return true;
        } else {
            return super.onTouchEvent(event);
        }
    }

    /**
     * Returns true if the touch down at the provided position be ignored
     */
    protected boolean shouldIgnoreTouchDown(float x, float y) {
        if (mDisplay == DISPLAY_TASKBAR) {
            // Allow touching within padding on taskbar, given icon sizes are smaller.
            return false;
        }
        return y < getPaddingTop()
                || x < getPaddingLeft()
                || y > getHeight() - getPaddingBottom()
                || x > getWidth() - getPaddingRight();
    }

    void setStayPressed(boolean stayPressed) {
        mStayPressed = stayPressed;
        refreshDrawableState();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (mIcon != null) {
            mIcon.setVisible(isVisible, false);
        }
    }

    public void clearPressedBackground() {
        setPressed(false);
        setStayPressed(false);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Unlike touch events, keypress event propagate pressed state change immediately,
        // without waiting for onClickHandler to execute. Disable pressed state changes here
        // to avoid flickering.
        mIgnorePressedStateChange = true;
        boolean result = super.onKeyUp(keyCode, event);
        mIgnorePressedStateChange = false;
        refreshDrawableState();
        return result;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        checkForEllipsis();
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        checkForEllipsis();
    }

    private void checkForEllipsis() {
        float width = getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight();
        if (width <= 0) {
            return;
        }
        setLetterSpacing(0);

        String text = getText().toString();
        TextPaint paint = getPaint();
        if (paint.measureText(text) < width) {
            return;
        }

        float spacing = findBestSpacingValue(paint, text, width, MIN_LETTER_SPACING);
        // Reset the paint value so that the call to TextView does appropriate diff.
        paint.setLetterSpacing(0);
        setLetterSpacing(spacing);
    }

    /**
     * Find the appropriate text spacing to display the provided text
     *
     * @param paint          the paint used by the text view
     * @param text           the text to display
     * @param allowedWidthPx available space to render the text
     * @param minSpacingEm   minimum spacing allowed between characters
     * @return the final textSpacing value
     * @see #setLetterSpacing(float)
     */
    private float findBestSpacingValue(TextPaint paint, String text, float allowedWidthPx,
            float minSpacingEm) {
        paint.setLetterSpacing(minSpacingEm);
        if (paint.measureText(text) > allowedWidthPx) {
            // If there is no result at high limit, we can do anything more
            return minSpacingEm;
        }

        float lowLimit = 0;
        float highLimit = minSpacingEm;

        for (int i = 0; i < MAX_SEARCH_LOOP_COUNT; i++) {
            float value = (lowLimit + highLimit) / 2;
            paint.setLetterSpacing(value);
            if (paint.measureText(text) < allowedWidthPx) {
                highLimit = value;
            } else {
                lowLimit = value;
            }
        }

        // At the end error on the higher side
        return highLimit;
    }

    @SuppressWarnings("wrongcall")
    protected void drawWithoutDot(Canvas canvas) {
        super.onDraw(canvas);
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawDotIfNecessary(canvas);
        drawRunningAppIndicatorIfNecessary(canvas);
    }

    /**
     * Draws the notification dot in the top right corner of the icon bounds.
     *
     * @param canvas The canvas to draw to.
     */
    protected void drawDotIfNecessary(Canvas canvas) {
        if (!mForceHideDot && (hasDot() || mDotParams.scale > 0)) {
            getIconBounds(mDotParams.iconBounds);
            Utilities.scaleRectAboutCenter(mDotParams.iconBounds, ICON_VISIBLE_AREA_FACTOR);
            final int scrollX = getScrollX();
            final int scrollY = getScrollY();
            canvas.translate(scrollX, scrollY);
            mDotRenderer.draw(canvas, mDotParams);
            canvas.translate(-scrollX, -scrollY);
        }
    }

    /** Draws a background behind the App Title label when required. **/
    public void drawAppContrastTile(Canvas canvas) {
        RectF appTitleBounds;
        Paint.FontMetrics fm = getPaint().getFontMetrics();
        Rect tmpRect = new Rect();
        getDrawingRect(tmpRect);
        CharSequence text = getText();

        int mAppTitleHorizontalPadding = getResources().getDimensionPixelSize(
                R.dimen.app_title_pill_horizontal_padding);
        int mRoundRectPadding = getResources().getDimensionPixelSize(
                R.dimen.app_title_pill_round_rect_padding);

        float titleLength = (getPaint().measureText(text, 0, text.length())
                + (mAppTitleHorizontalPadding + mRoundRectPadding) * 2);
        titleLength = Math.min(titleLength, tmpRect.width());
        appTitleBounds = new RectF((tmpRect.width() - titleLength) / 2.f - getCompoundPaddingLeft(),
                0, (tmpRect.width() + titleLength) / 2.f + getCompoundPaddingRight(),
                (int) Math.ceil(fm.bottom - fm.top));
        appTitleBounds.inset((mAppTitleHorizontalPadding) * 2, 0);


        if (mIcon != null) {
            Rect iconBounds = new Rect();
            getIconBounds(iconBounds);
            int textStart = iconBounds.bottom + getCompoundDrawablePadding();
            appTitleBounds.offset(0, textStart);
        }

        canvas.drawRoundRect(appTitleBounds, appTitleBounds.height() / 2,
                appTitleBounds.height() / 2,
                PillColorProvider.getInstance(getContext()).getAppTitlePillPaint());
    }

    /** Draws a line under the app icon if this is representing a running app in Desktop Mode. */
    protected void drawRunningAppIndicatorIfNecessary(Canvas canvas) {
        if (mDisplay != DISPLAY_TASKBAR
                || mLineIndicatorScale == 0
                || mLineIndicatorColor == Color.TRANSPARENT) {
            return;
        }
        getIconBounds(mRunningAppIconBounds);
        Utilities.scaleRectAboutCenter(mRunningAppIconBounds, ICON_VISIBLE_AREA_FACTOR);

        final int indicatorTop = mRunningAppIconBounds.bottom + mRunningAppIndicatorTopMargin;
        final float indicatorWidth = mRunningAppIndicatorWidth * mLineIndicatorScale;
        final float cornerRadius = mRunningAppIndicatorHeight / 2f;
        mRunningAppIndicatorPaint.setColor(mLineIndicatorColor);

        canvas.drawRoundRect(
                mRunningAppIconBounds.centerX() - indicatorWidth / 2f,
                indicatorTop,
                mRunningAppIconBounds.centerX() + indicatorWidth / 2f,
                indicatorTop + mRunningAppIndicatorHeight,
                cornerRadius,
                cornerRadius,
                mRunningAppIndicatorPaint);
    }

    @Override
    public void setForceHideDot(boolean forceHideDot) {
        if (mForceHideDot == forceHideDot) {
            return;
        }
        mForceHideDot = forceHideDot;

        if (forceHideDot) {
            invalidate();
        } else if (hasDot()) {
            animateDotScale(0, 1);
        }
    }

    @VisibleForTesting
    public boolean getForceHideDot() {
        return mForceHideDot;
    }

    public boolean hasDot() {
        return mDotInfo != null;
    }

    /**
     * Get the icon bounds on the view depending on the layout type.
     */
    public void getIconBounds(Rect outBounds) {
        getIconBounds(mIconSize, outBounds);
    }

    /**
     * Get the icon bounds on the view depending on the layout type.
     */
    public void getIconBounds(int iconSize, Rect outBounds) {
        outBounds.set(0, 0, iconSize, iconSize);
        if (mLayoutHorizontal) {
            int top = (getHeight() - iconSize) / 2;
            if (mIsRtl) {
                outBounds.offsetTo(getWidth() - iconSize - getPaddingRight(), top);
            } else {
                outBounds.offsetTo(getPaddingLeft(), top);
            }
        } else {
            outBounds.offset((getWidth() - iconSize) / 2, getPaddingTop());
        }
    }

    /**
     * Sets whether the layout is horizontal.
     */
    public void setLayoutHorizontal(boolean layoutHorizontal) {
        if (mLayoutHorizontal == layoutHorizontal) {
            return;
        }

        mLayoutHorizontal = layoutHorizontal;
        applyCompoundDrawables(getIconOrTransparentColor());
    }

    /**
     * Sets whether to vertically center the content.
     */
    public void setCenterVertically(boolean centerVertically) {
        mCenterVertically = centerVertically;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (mCenterVertically) {
            Paint.FontMetrics fm = getPaint().getFontMetrics();
            int cellHeightPx = mIconSize + getCompoundDrawablePadding() +
                    (int) Math.ceil(fm.bottom - fm.top);
            setPadding(getPaddingLeft(), (height - cellHeightPx) / 2, getPaddingRight(),
                    getPaddingBottom());
        }
        if (shouldDrawAppContrastTile()) {
            int mAppTitleHorizontalPadding = getResources().getDimensionPixelSize(
                    R.dimen.app_title_pill_horizontal_padding);
            int mRoundRectPadding = getResources().getDimensionPixelSize(
                    R.dimen.app_title_pill_round_rect_padding);

            setPadding(mAppTitleHorizontalPadding + mRoundRectPadding, getPaddingTop(),
                    mAppTitleHorizontalPadding + mRoundRectPadding,
                    getPaddingBottom());
        }
        // Only apply two line for all_apps and device search only if necessary.
        if (shouldUseTwoLine() && (mLastOriginalText != null)) {
            int allowedVerticalSpace = height - getPaddingTop() - getPaddingBottom()
                    - mDeviceProfile.allAppsIconSizePx
                    - mDeviceProfile.allAppsIconDrawablePaddingPx;
            CharSequence modifiedString = modifyTitleToSupportMultiLine(
                    MeasureSpec.getSize(widthMeasureSpec) - getCompoundPaddingLeft()
                            - getCompoundPaddingRight(),
                    allowedVerticalSpace,
                    mLastOriginalText,
                    getPaint(),
                    mBreakPointsIntArray,
                    getLineSpacingMultiplier(),
                    getLineSpacingExtra());
            if (!TextUtils.equals(modifiedString, mLastModifiedText)) {
                mLastModifiedText = modifiedString;
                if (Flags.useNewIconForArchivedApps()
                        && getTag() instanceof ItemInfoWithIcon infoWithIcon
                        && infoWithIcon.isInactiveArchive()) {
                    setTextWithArchivingIcon(modifiedString);
                } else {
                    setText(modifiedString);
                }
                // if text contains NEW_LINE, set max lines to 2
                if (TextUtils.indexOf(modifiedString, NEW_LINE) != -1) {
                    setSingleLine(false);
                    setMaxLines(2);
                } else {
                    setSingleLine(true);
                    setMaxLines(1);
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    public void setTextColor(int color) {
        mTextColor = color;
        mTextColorStateList = null;
        super.setTextColor(getModifiedColor());
    }

    /**
     * Sets text with a start icon for App Archiving.
     * Uses a bolded drawable if text is bolded.
     * @param text
     */
    private void setTextWithArchivingIcon(CharSequence text) {
        var drawableId = R.drawable.cloud_download_24px;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                && getResources().getConfiguration().fontWeightAdjustment >= BOLD_TEXT_ADJUSTMENT) {
            // If System bold text setting is on, then use a bolded icon
            drawableId = R.drawable.cloud_download_semibold_24px;
        }
        setTextWithStartIcon(text, drawableId);
    }

    /**
     * Uses a SpannableString to set text with a Drawable at the start of the TextView
     * @param text text to use for TextView
     * @param drawableId Drawable Resource to use for drawing image at start of text
     */
    @VisibleForTesting
    public void setTextWithStartIcon(CharSequence text, @DrawableRes int drawableId) {
        Drawable drawable = getContext().getDrawable(drawableId);
        if (drawable == null) {
            setText(text);
            Log.w(TAG, "setTextWithStartIcon: start icon Drawable not found from resources"
                    + ", will just set text instead.");
            return;
        }
        drawable.setTint(getCurrentTextColor());
        drawable.setBounds(0, 0, Math.round(getTextSize()), Math.round(getTextSize()));
        ImageSpan imageSpan = new ImageSpan(drawable, ImageSpan.ALIGN_CENTER);
        // First space will be replaced with Drawable, second space is for space before text.
        SpannableString spannable = new SpannableString("  " + text);
        spannable.setSpan(imageSpan, 0, 1, Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
        setText(spannable);
    }

    @Override
    public void setTextColor(ColorStateList colors) {
        if (shouldDrawAppContrastTile()) {
            mTextColor = PillColorProvider.getInstance(
                    getContext()).getAppTitleTextPaint().getColor();
        } else {
            mTextColor = colors.getDefaultColor();
            mTextColorStateList = colors;
        }

        if (Float.compare(mTextAlpha, 1) == 0) {
            super.setTextColor(colors);
        } else {
            super.setTextColor(getModifiedColor());
        }
    }

    public boolean shouldShowLabel() {
        return mShouldShowLabel;
    }

    public boolean shouldTextBeVisible() {
        // Text should be visible everywhere but the hotseat.
        Object tag = getParent() instanceof FolderIcon ? ((View) getParent()).getTag() : getTag();
        ItemInfo info = tag instanceof ItemInfo ? (ItemInfo) tag : null;
        return info == null || (info.container != LauncherSettings.Favorites.CONTAINER_HOTSEAT
                && info.container != LauncherSettings.Favorites.CONTAINER_HOTSEAT_PREDICTION);
    }

    /**
     * Whether or not an App title contrast tile should be drawn for this element.
     **/
    public boolean shouldDrawAppContrastTile() {
        return mDisplay == DISPLAY_WORKSPACE && shouldTextBeVisible()
                && PillColorProvider.getInstance(getContext()).isMatchaEnabled()
                && enableContrastTiles();
    }

    public void setTextVisibility(boolean visible) {
        setTextAlpha(visible ? 1 : 0);
    }

    private void setTextAlpha(float alpha) {
        mTextAlpha = alpha;
        if (mTextColorStateList != null) {
            setTextColor(mTextColorStateList);
        } else {
            super.setTextColor(getModifiedColor());
        }
    }

    private int getModifiedColor() {
        if (mTextAlpha == 0) {
            // Special case to prevent text shadows in high contrast mode
            return Color.TRANSPARENT;
        }
        return setColorAlphaBound(mTextColor, Math.round(Color.alpha(mTextColor) * mTextAlpha));
    }

    /**
     * Creates an animator to fade the text in or out.
     *
     * @param fadeIn Whether the text should fade in or fade out.
     */
    public ObjectAnimator createTextAlphaAnimator(boolean fadeIn) {
        float toAlpha = shouldTextBeVisible() && fadeIn ? 1 : 0;
        return ObjectAnimator.ofFloat(this, TEXT_ALPHA_PROPERTY, toAlpha);
    }

    /**
     * Generate a new string that will support two line text depending on the current string.
     * This method calculates the limited width of a text view and creates a string to fit as
     * many words as it can until the limit is reached. Once the limit is reached, we decide to
     * either return the original title or continue on a new line. How to get the new string is by
     * iterating through the list of break points and determining if the strings between the break
     * points can fit within the line it is in. We will show the modified string if there is enough
     * horizontal and vertical space, otherwise this method will just return the original string.
     * Example assuming each character takes up one spot:
     * title = "Battery Stats", breakpoint = [6], stringPtr = 0, limitedWidth = 7
     * We get the current word -> from sublist(0, breakpoint[i]+1) so sublist (0,7) -> Battery,
     * now stringPtr = 7 then from sublist(7) the current string is " Stats" and the runningWidth
     * at this point exceeds limitedWidth and so we put " Stats" onto the next line (after checking
     * if the first char is a SPACE, we trim to append "Stats". So resulting string would be
     * "Battery\nStats"
     */
    public static CharSequence modifyTitleToSupportMultiLine(int limitedWidth, int limitedHeight,
            CharSequence title, TextPaint paint, IntArray breakPoints, float spacingMultiplier,
            float spacingExtra) {
        // current title is less than the width allowed so we can just skip
        if (title == null || paint.measureText(title, 0, title.length()) <= limitedWidth) {
            return title;
        }
        float currentWordWidth, runningWidth = 0;
        CharSequence currentWord;
        StringBuilder newString = new StringBuilder();
        paint.setLetterSpacing(MIN_LETTER_SPACING);
        int stringPtr = 0;
        for (int i = 0; i < breakPoints.size() + 1; i++) {
            if (i < breakPoints.size()) {
                currentWord = title.subSequence(stringPtr, breakPoints.get(i) + 1);
            } else {
                // last word from recent breakpoint until the end of the string
                currentWord = title.subSequence(stringPtr, title.length());
            }
            currentWordWidth = paint.measureText(currentWord, 0, currentWord.length());
            runningWidth += currentWordWidth;
            if (runningWidth <= limitedWidth) {
                newString.append(currentWord);
            } else {
                if (i != 0) {
                    // If putting word onto a new line, make sure there is no space or new line
                    // character in the beginning of the current word and just put in the rest of
                    // the characters.
                    CharSequence lastCharacters = title.subSequence(stringPtr, title.length());
                    int beginningLetterType =
                            Character.getType(Character.codePointAt(lastCharacters, 0));
                    if (beginningLetterType == Character.SPACE_SEPARATOR
                            || beginningLetterType == Character.LINE_SEPARATOR) {
                        lastCharacters = lastCharacters.length() > 1
                                ? lastCharacters.subSequence(1, lastCharacters.length())
                                : EMPTY;
                    }
                    newString.append(NEW_LINE).append(lastCharacters);
                    StaticLayout staticLayout = new StaticLayout(newString, paint, limitedWidth,
                            ALIGN_NORMAL, spacingMultiplier, spacingExtra, false);
                    if (staticLayout.getHeight() < limitedHeight) {
                        return newString.toString();
                    }
                }
                // if the first words exceeds width, just return as the first line will ellipse
                return title;
            }
            if (i >= breakPoints.size()) {
                // no need to look forward into the string if we've already finished processing
                break;
            }
            stringPtr = breakPoints.get(i) + 1;
        }
        return newString.toString();
    }

    @Override
    public void cancelLongPress() {
        super.cancelLongPress();
        mLongPressHelper.cancelLongPress();
    }

    /** Applies the given progress level to the this icon's progress bar. */
    @Nullable
    private PreloadIconDrawable applyProgressLevel(ItemInfoWithIcon info) {
        if (info.isInactiveArchive()) {
            return null;
        }

        int progressLevel = info.getProgressLevel();
        if (progressLevel >= 100) {
            setContentDescription(info.contentDescription != null
                    ? info.contentDescription : "");
        } else if (progressLevel > 0) {
            setDownloadStateContentDescription(info, progressLevel);
        } else {
            setContentDescription(getContext()
                    .getString(R.string.app_waiting_download_title, info.title));
        }
        PreloadIconDrawable pid;
        if (mIcon instanceof PreloadIconDrawable p) {
            pid = p;
            pid.setLevel(progressLevel);
            pid.setIsDisabled(isIconDisabled(info));
        } else {
            pid = makePreloadIcon(info);
            setIcon(pid);
        }
        return pid;
    }

    /**
     * Creates a PreloadIconDrawable with the appropriate progress level without mutating this
     * object.
     */
    @Nullable
    public PreloadIconDrawable makePreloadIcon() {
        return getTag() instanceof ItemInfoWithIcon info ? makePreloadIcon(info) : null;
    }

    @NonNull
    private PreloadIconDrawable makePreloadIcon(ItemInfoWithIcon info) {
        int progressLevel = info.getProgressLevel();
        final PreloadIconDrawable preloadDrawable = newPendingIcon(getContext(), info);

        preloadDrawable.setLevel(progressLevel);
        preloadDrawable.setIsDisabled(isIconDisabled(info));
        return preloadDrawable;
    }

    /**
     * Returns true to grey the icon if the icon is either suspended or if the icon is pending
     * download
     */
    public boolean isIconDisabled(ItemInfoWithIcon info) {
        return info.isDisabled() || info.isPendingDownload();
    }


    public void applyDotState(ItemInfo itemInfo, boolean animate) {
        if (mIcon != null) {
            boolean wasDotted = mDotInfo != null;
            mDotInfo = mActivity.getDotInfoForItem(itemInfo);
            boolean isDotted = mDotInfo != null;
            float newDotScale = isDotted ? 1f : 0;
            if (mDisplay == DISPLAY_ALL_APPS) {
                mDotRenderer = mActivity.getDeviceProfile().mDotRendererAllApps;
            } else {
                mDotRenderer = mActivity.getDeviceProfile().mDotRendererWorkSpace;
            }
            if (wasDotted || isDotted) {
                // Animate when a dot is first added or when it is removed.
                if (animate && (wasDotted ^ isDotted) && isShown()) {
                    animateDotScale(newDotScale);
                } else {
                    cancelDotScaleAnim();
                    mDotParams.scale = newDotScale;
                    invalidate();
                }
            }
            if (!TextUtils.isEmpty(itemInfo.contentDescription)) {
                if (itemInfo.isDisabled()) {
                    setContentDescription(getContext().getString(R.string.disabled_app_label,
                            itemInfo.contentDescription));
                } else if (itemInfo instanceof WorkspaceItemInfo wai && wai.isArchived()) {
                    setContentDescription(
                            getContext().getString(R.string.app_archived_title, itemInfo.title));
                } else if (hasDot()) {
                    int count = mDotInfo.getNotificationCount();
                    setContentDescription(
                            getAppLabelPluralString(itemInfo.contentDescription.toString(), count));
                } else {
                    setContentDescription(itemInfo.contentDescription);
                }
            }
        }
    }

    private void setDownloadStateContentDescription(ItemInfoWithIcon info, int progressLevel) {
        if ((info.runtimeStatusFlags & ItemInfoWithIcon.FLAG_ARCHIVED) != 0
                && progressLevel == 0) {
            if (mIcon instanceof PreloadIconDrawable) {
                // Tell user that download is pending and not to tap to download again.
                setContentDescription(getContext().getString(
                        R.string.app_waiting_download_title, info.title));
            } else {
                setContentDescription(getContext().getString(
                        R.string.app_archived_title, info.title));
            }
        } else if ((info.runtimeStatusFlags & FLAG_SHOW_DOWNLOAD_PROGRESS_MASK)
                != 0) {
            String percentageString = NumberFormat.getPercentInstance()
                    .format(progressLevel * 0.01);
            if ((info.runtimeStatusFlags & FLAG_INSTALL_SESSION_ACTIVE) != 0) {
                setContentDescription(getContext()
                        .getString(
                                R.string.app_installing_title, info.title, percentageString));
            } else if ((info.runtimeStatusFlags
                    & FLAG_INCREMENTAL_DOWNLOAD_ACTIVE) != 0) {
                setContentDescription(getContext()
                        .getString(
                                R.string.app_downloading_title, info.title, percentageString));
            }
        }
    }

    /**
     * Sets the icon for this view based on the layout direction.
     */
    protected void setIcon(FastBitmapDrawable icon) {
        if (mIsIconVisible) {
            applyCompoundDrawables(icon);
        }
        mIcon = icon;
        if (mIcon != null) {
            mIcon.setVisible(getWindowVisibility() == VISIBLE && isShown(), false);
            mIcon.setHoverScaleEnabledForDisplay(mDisplay != DISPLAY_TASKBAR);
        }
    }

    @Override
    public void setIconVisible(boolean visible) {
        mIsIconVisible = visible;
        if (!mIsIconVisible) {
            resetIconScale();
        }
        Drawable icon = getIconOrTransparentColor();
        applyCompoundDrawables(icon);
    }

    private Drawable getIconOrTransparentColor() {
        return mIsIconVisible ? mIcon : new ColorDrawable(Color.TRANSPARENT);
    }

    /** Sets the icon visual state to disabled or not. */
    public void setIconDisabled(boolean isDisabled) {
        if (mIcon != null) {
            mIcon.setIsDisabled(isDisabled);
        }
    }

    protected void applyCompoundDrawables(Drawable icon) {
        if (icon == null) {
            // Icon can be null when we use the BubbleTextView for text only.
            return;
        }

        // If we had already set an icon before, disable relayout as the icon size is the
        // same as before.
        mDisableRelayout = mIcon != null;

        icon.setBounds(0, 0, mIconSize, mIconSize);

        updateIcon(icon);

        // If the current icon is a placeholder color, animate its update.
        if (mIcon != null
                && mIcon instanceof PlaceHolderIconDrawable
                && mHighResUpdateInProgress) {
            ((PlaceHolderIconDrawable) mIcon).animateIconUpdate(icon);
        }

        mDisableRelayout = false;
    }

    @Override
    public void requestLayout() {
        if (!mDisableRelayout) {
            super.requestLayout();
        }
    }

    /**
     * Applies the item info if it is same as what the view is pointing to currently.
     */
    @Override
    public void reapplyItemInfo(ItemInfoWithIcon info) {
        if (getTag() == info) {
            mIconLoadRequest = null;
            mDisableRelayout = true;
            mHighResUpdateInProgress = true;

            // Optimization: Starting in N, pre-uploads the bitmap to RenderThread.
            info.bitmap.icon.prepareToDraw();

            if (info instanceof AppInfo) {
                applyFromApplicationInfo((AppInfo) info);
            } else if (info instanceof WorkspaceItemInfo) {
                applyFromWorkspaceItem((WorkspaceItemInfo) info);
            } else if (info != null) {
                applyFromItemInfoWithIcon(info);
            }

            mDisableRelayout = false;
            mHighResUpdateInProgress = false;
        }
    }

    /**
     * Verifies that the current icon is high-res otherwise posts a request to load the icon.
     */
    public void verifyHighRes() {
        if (getTag() instanceof ItemInfoWithIcon info && !mHighResUpdateInProgress
                && info.getMatchingLookupFlag().useLowRes()) {
            if (mIconLoadRequest != null) {
                mIconLoadRequest.cancel();
            }
            mIconLoadRequest = LauncherAppState.getInstance(getContext()).getIconCache()
                    .updateIconInBackground(BubbleTextView.this, info);
        }
    }

    public int getIconSize() {
        return mIconSize;
    }

    public boolean isDisplaySearchResult() {
        return mDisplay == DISPLAY_SEARCH_RESULT
                || mDisplay == DISPLAY_SEARCH_RESULT_SMALL
                || mDisplay == DISPLAY_SEARCH_RESULT_APP_ROW;
    }

    public int getIconDisplay() {
        return mDisplay;
    }

    @Override
    public MultiTranslateDelegate getTranslateDelegate() {
        return mTranslateDelegate;
    }

    @Override
    public void setReorderBounceScale(float scale) {
        mScaleForReorderBounce = scale;
        super.setScaleX(scale);
        super.setScaleY(scale);
    }

    @Override
    public float getReorderBounceScale() {
        return mScaleForReorderBounce;
    }

    @Override
    public int getViewType() {
        return DRAGGABLE_ICON;
    }

    @Override
    public void getWorkspaceVisualDragBounds(Rect bounds) {
        getIconBounds(mIconSize, bounds);
    }

    public void getSourceVisualDragBounds(Rect bounds) {
        getIconBounds(mIconSize, bounds);
    }

    @Override
    public SafeCloseable prepareDrawDragView() {
        resetIconScale();
        setForceHideDot(true);
        return () -> {
        };
    }

    private void resetIconScale() {
        if (mIcon != null) {
            mIcon.resetScale();
        }
    }

    private void updateIcon(Drawable newIcon) {
        if (mLayoutHorizontal) {
            setCompoundDrawablesRelative(newIcon, null, null, null);
        } else {
            setCompoundDrawables(null, newIcon, null, null);
        }
    }

    private String getAppLabelPluralString(String appName, int notificationCount) {
        MessageFormat icuCountFormat = new MessageFormat(
                getResources().getString(R.string.dotted_app_label),
                Locale.getDefault());
        HashMap<String, Object> args = new HashMap();
        args.put("app_name", appName);
        args.put("count", notificationCount);
        return icuCountFormat.format(args);
    }

    /**
     * Starts a long press action and returns the corresponding pre-drag condition
     */
    public PreDragCondition startLongPressAction() {
        PopupContainerWithArrow popup = PopupContainerWithArrow.showForIcon(this);
        return popup != null ? popup.createPreDragCondition(true) : null;
    }

    /**
     * Returns true if the view can show long-press popup
     */
    public boolean canShowLongPressPopup() {
        return getTag() instanceof ItemInfo && ShortcutUtil.supportsShortcuts((ItemInfo) getTag());
    }
}
