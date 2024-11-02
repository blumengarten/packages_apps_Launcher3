/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.launcher3.allapps;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;

import com.android.app.animation.Interpolators;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.Insettable;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.anim.AnimatedPropertySetter;
import com.android.launcher3.anim.KeyboardInsetAnimationCallback;
import com.android.launcher3.model.StringCache;
import com.android.launcher3.views.ActivityContext;
/**
 * Work profile toggle switch shown at the bottom of AllApps work tab
 */
public class WorkModeSwitch extends LinearLayout implements Insettable,
        KeyboardInsetAnimationCallback.KeyboardInsetListener {

    private static final int TEXT_EXPAND_OPACITY_DURATION = 300;
    private static final int TEXT_COLLAPSE_OPACITY_DURATION = 50;
    private static final int EXPAND_COLLAPSE_DURATION = 300;
    private static final int TEXT_ALPHA_EXPAND_DELAY = 80;
    private static final int TEXT_ALPHA_COLLAPSE_DELAY = 0;
    private static final int FLAG_FADE_ONGOING = 1 << 1;
    private static final int FLAG_TRANSLATION_ONGOING = 1 << 2;
    private static final int FLAG_IS_EXPAND = 1 << 3;
    private static final int SCROLL_THRESHOLD_DP = 10;

    private final Rect mInsets = new Rect();
    private final Rect mImeInsets = new Rect();
    private int mFlags;
    private final ActivityContext mActivityContext;
    private final Context mContext;
    private final int mTextMarginStart;
    private final int mTextMarginEnd;
    private final int mIconMarginStart;

    // Threshold when user scrolls up/down to determine when should button extend/collapse
    private final int mScrollThreshold;
    private TextView mTextView;
    private ImageView mIcon;
    private ValueAnimator mPauseFABAnim;

    public WorkModeSwitch(@NonNull Context context) {
        this(context, null, 0);
    }

    public WorkModeSwitch(@NonNull Context context, @NonNull AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WorkModeSwitch(@NonNull Context context, @NonNull AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mContext = context;
        mScrollThreshold = Utilities.dpToPx(SCROLL_THRESHOLD_DP);
        mActivityContext = ActivityContext.lookupContext(getContext());
        mTextMarginStart = mContext.getResources().getDimensionPixelSize(
                R.dimen.work_fab_text_start_margin);
        mTextMarginEnd = mContext.getResources().getDimensionPixelSize(
                R.dimen.work_fab_text_end_margin);
        mIconMarginStart = mContext.getResources().getDimensionPixelSize(
                R.dimen.work_fab_icon_start_margin_expanded);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mTextView = findViewById(R.id.pause_text);
        mIcon = findViewById(R.id.work_icon);
        setSelected(true);
        KeyboardInsetAnimationCallback keyboardInsetAnimationCallback =
                new KeyboardInsetAnimationCallback(this);
        setWindowInsetsAnimationCallback(keyboardInsetAnimationCallback);
        // Expand is the default state upon initialization.
        addFlag(FLAG_IS_EXPAND);
        setInsets(mActivityContext.getDeviceProfile().getInsets());
        updateStringFromCache();
    }

    @Override
    public void setInsets(Rect insets) {
        mInsets.set(insets);
        updateTranslationY();
        MarginLayoutParams lp = (MarginLayoutParams) getLayoutParams();
        if (lp != null) {
            int bottomMargin = getResources().getDimensionPixelSize(R.dimen.work_fab_margin_bottom);
            DeviceProfile dp = ActivityContext.lookupContext(getContext()).getDeviceProfile();
            if (mActivityContext.getAppsView().isSearchBarFloating()) {
                bottomMargin += dp.hotseatQsbHeight;
            }

            if (!dp.isGestureMode && dp.isTaskbarPresent) {
                bottomMargin += dp.taskbarHeight;
            }

            lp.bottomMargin = bottomMargin;
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        boolean isRtl = Utilities.isRtl(getResources());
        int shift = mActivityContext.getDeviceProfile().getAllAppsIconStartMargin(mContext);
        setTranslationX(isRtl ? shift : -shift);
    }

    @Override
    public boolean isEnabled() {
        return super.isEnabled() && getVisibility() == VISIBLE;
    }

    public void animateVisibility(boolean visible) {
        clearAnimation();
        if (visible) {
            addFlag(FLAG_FADE_ONGOING);
            setVisibility(VISIBLE);
            extend();
            animate().alpha(1).withEndAction(() -> removeFlag(FLAG_FADE_ONGOING)).start();
        } else if (getVisibility() != GONE) {
            addFlag(FLAG_FADE_ONGOING);
            animate().alpha(0).withEndAction(() -> {
                removeFlag(FLAG_FADE_ONGOING);
                setVisibility(GONE);
            }).start();
        }
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        WindowInsetsCompat windowInsetsCompat =
                WindowInsetsCompat.toWindowInsetsCompat(insets, this);
        if (windowInsetsCompat.isVisible(WindowInsetsCompat.Type.ime())) {
            setInsets(mImeInsets, windowInsetsCompat.getInsets(WindowInsetsCompat.Type.ime()));
        } else {
            mImeInsets.setEmpty();
        }
        updateTranslationY();
        return super.onApplyWindowInsets(insets);
    }

    void updateTranslationY() {
        setTranslationY(-mImeInsets.bottom);
    }

    @Override
    public void setTranslationY(float translationY) {
        // Always translate at least enough for nav bar insets.
        super.setTranslationY(Math.min(translationY, -mInsets.bottom));
    }


    private void animatePillTransition(boolean isExpanding) {
        if (!shouldAnimate(isExpanding)) {
            return;
        }
        AnimatorSet animatorSet = new AnimatedPropertySetter().buildAnim();
        mTextView.measure(0,0);
        int currentWidth = mTextView.getWidth();
        int fullWidth = mTextView.getMeasuredWidth();
        float from = isExpanding ? 0 : currentWidth;
        float to = isExpanding ? fullWidth : 0;
        mPauseFABAnim = ObjectAnimator.ofFloat(from, to);
        mPauseFABAnim.setDuration(EXPAND_COLLAPSE_DURATION);
        mPauseFABAnim.setInterpolator(Interpolators.STANDARD);
        mPauseFABAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                float translation = (float) valueAnimator.getAnimatedValue();
                float translationFraction = translation / fullWidth;
                ViewGroup.MarginLayoutParams textViewLayoutParams =
                        (ViewGroup.MarginLayoutParams) mTextView.getLayoutParams();
                textViewLayoutParams.width = (int) translation;
                textViewLayoutParams.setMarginStart((int) (mTextMarginStart * translationFraction));
                textViewLayoutParams.setMarginEnd((int) (mTextMarginEnd * translationFraction));
                mTextView.setLayoutParams(textViewLayoutParams);
                ViewGroup.MarginLayoutParams iconLayoutParams =
                        (ViewGroup.MarginLayoutParams) mIcon.getLayoutParams();
                iconLayoutParams.setMarginStart((int) (mIconMarginStart * translationFraction));
                mIcon.setLayoutParams(iconLayoutParams);
            }
        });
        mPauseFABAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animator) {
                if (isExpanding) {
                    addFlag(FLAG_IS_EXPAND);
                } else {
                    mTextView.setVisibility(GONE);
                    removeFlag(FLAG_IS_EXPAND);
                }
                mTextView.setHorizontallyScrolling(false);
                mTextView.setEllipsize(TextUtils.TruncateAt.END);
            }

            @Override
            public void onAnimationStart(Animator animator) {
                mTextView.setHorizontallyScrolling(true);
                mTextView.setVisibility(VISIBLE);
                mTextView.setEllipsize(null);
            }
        });
        animatorSet.playTogether(mPauseFABAnim, updatePauseTextAlpha(isExpanding));
        animatorSet.start();
    }


    private ValueAnimator updatePauseTextAlpha(boolean expand) {
        float from = expand ? 0 : 1;
        float to = expand ? 1 : 0;
        ValueAnimator alphaAnim = ObjectAnimator.ofFloat(from, to);
        alphaAnim.setDuration(expand ? TEXT_EXPAND_OPACITY_DURATION
                : TEXT_COLLAPSE_OPACITY_DURATION);
        alphaAnim.setStartDelay(expand ? TEXT_ALPHA_EXPAND_DELAY : TEXT_ALPHA_COLLAPSE_DELAY);
        alphaAnim.setInterpolator(Interpolators.LINEAR);
        alphaAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                mTextView.setAlpha((float) valueAnimator.getAnimatedValue());
            }
        });
        return alphaAnim;
    }

    private void setInsets(Rect rect, Insets insets) {
        rect.set(insets.left, insets.top, insets.right, insets.bottom);
    }

    public Rect getImeInsets() {
        return mImeInsets;
    }

    @Override
    public void onTranslationStart() {
        addFlag(FLAG_TRANSLATION_ONGOING);
    }

    @Override
    public void onTranslationEnd() {
        removeFlag(FLAG_TRANSLATION_ONGOING);
    }

    private void addFlag(int flag) {
        mFlags |= flag;
    }

    private void removeFlag(int flag) {
        mFlags &= ~flag;
    }

    private boolean containsFlag(int flag) {
        return (mFlags & flag) == flag;
    }

    public void extend() {
        animatePillTransition(true);
    }

    public void shrink(){
         animatePillTransition(false);
    }

    /**
     * Determines if the button should animate based on current state. It should animate the button
     * only if it is not in the same state it is animating to.
     */
    private boolean shouldAnimate(boolean expanding) {
        return expanding != containsFlag(FLAG_IS_EXPAND)
                && (mPauseFABAnim == null || !mPauseFABAnim.isRunning());
    }

    public int getScrollThreshold() {
        return mScrollThreshold;
    }

    public void updateStringFromCache(){
        StringCache cache = mActivityContext.getStringCache();
        if (cache != null) {
            mTextView.setText(cache.workProfilePauseButton);
        }
    }
}
