/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.content.Context.RECEIVER_NOT_EXPORTED;
import static android.content.Context.RECEIVER_EXPORTED;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;

import static com.android.launcher3.BaseActivity.EVENT_DESTROYED;
import static com.android.launcher3.Flags.enableGrowthNudge;
import static com.android.launcher3.Flags.enableUnfoldStateAnimation;
import static com.android.launcher3.config.FeatureFlags.ENABLE_TASKBAR_NAVBAR_UNIFICATION;
import static com.android.launcher3.config.FeatureFlags.enableTaskbarNoRecreate;
import static com.android.launcher3.util.DisplayController.CHANGE_DENSITY;
import static com.android.launcher3.util.DisplayController.CHANGE_DESKTOP_MODE;
import static com.android.launcher3.util.DisplayController.CHANGE_NAVIGATION_MODE;
import static com.android.launcher3.util.DisplayController.CHANGE_TASKBAR_PINNING;
import static com.android.launcher3.util.DisplayController.TASKBAR_NOT_DESTROYED_TAG;
import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;
import static com.android.launcher3.util.FlagDebugUtils.formatFlagChange;
import static com.android.launcher3.taskbar.growth.GrowthConstants.BROADCAST_SHOW_NUDGE;
import static com.android.quickstep.util.SystemActionConstants.ACTION_SHOW_TASKBAR;
import static com.android.quickstep.util.SystemActionConstants.SYSTEM_ACTION_ID_TASKBAR;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Trace;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.Display;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.window.DesktopExperienceFlags;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.anim.AnimatorPlaybackController;
import com.android.launcher3.statemanager.StatefulActivity;
import com.android.launcher3.taskbar.TaskbarNavButtonController.TaskbarNavButtonCallbacks;
import com.android.launcher3.taskbar.unfold.NonDestroyableScopedUnfoldTransitionProgressProvider;
import com.android.launcher3.uioverrides.QuickstepLauncher;
import com.android.launcher3.util.DisplayController;
import com.android.launcher3.util.SettingsCache;
import com.android.launcher3.util.SimpleBroadcastReceiver;
import com.android.quickstep.AllAppsActionManager;
import com.android.quickstep.RecentsActivity;
import com.android.quickstep.SystemUiProxy;
import com.android.quickstep.fallback.window.RecentsDisplayModel;
import com.android.quickstep.fallback.window.RecentsWindowManager;
import com.android.quickstep.util.ContextualSearchInvoker;
import com.android.quickstep.views.RecentsViewContainer;
import com.android.systemui.shared.statusbar.phone.BarTransitions;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.shared.system.QuickStepContract.SystemUiStateFlags;
import com.android.systemui.unfold.UnfoldTransitionProgressProvider;
import com.android.systemui.unfold.util.ScopedUnfoldTransitionProgressProvider;

import java.io.PrintWriter;
import java.util.StringJoiner;

/**
 * Class to manage taskbar lifecycle
 */
public class TaskbarManager {
    private static final String TAG = "TaskbarManager";
    private static final boolean DEBUG = false;
    // TODO(b/382378283) remove all logs with this tag
    public static final String NULL_TASKBAR_ROOT_LAYOUT_TAG = "b/382378283";
    public static final String ILLEGAL_ARGUMENT_WM_ADD_VIEW = "b/391653300";

    // TODO: b/397738606  - Remove all logs with this tag after the growth framework is integrated.
    public static final String GROWTH_FRAMEWORK_TAG = "Growth Framework";

    /**
     * All the configurations which do not initiate taskbar recreation.
     * This includes all the configurations defined in Launcher's manifest entry and
     * ActivityController#filterConfigChanges
     */
    private static final int SKIP_RECREATE_CONFIG_CHANGES = ActivityInfo.CONFIG_WINDOW_CONFIGURATION
            | ActivityInfo.CONFIG_KEYBOARD
            | ActivityInfo.CONFIG_KEYBOARD_HIDDEN
            | ActivityInfo.CONFIG_MCC
            | ActivityInfo.CONFIG_MNC
            | ActivityInfo.CONFIG_NAVIGATION
            | ActivityInfo.CONFIG_ORIENTATION
            | ActivityInfo.CONFIG_SCREEN_SIZE
            | ActivityInfo.CONFIG_SCREEN_LAYOUT
            | ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE;

    private static final Uri USER_SETUP_COMPLETE_URI = Settings.Secure.getUriFor(
            Settings.Secure.USER_SETUP_COMPLETE);

    private static final Uri NAV_BAR_KIDS_MODE = Settings.Secure.getUriFor(
            Settings.Secure.NAV_BAR_KIDS_MODE);

    private final Context mBaseContext;
    private TaskbarNavButtonCallbacks mNavCallbacks;
    // TODO: Remove this during the connected displays lifecycle refactor.
    private final Context mPrimaryWindowContext;
    private WindowManager mPrimaryWindowManager;
    private TaskbarNavButtonController mPrimaryNavButtonController;
    private ComponentCallbacks mPrimaryComponentCallbacks;

    private final SimpleBroadcastReceiver mShutdownReceiver;

    // The source for this provider is set when Launcher is available
    // We use 'non-destroyable' version here so the original provider won't be destroyed
    // as it is tied to the activity lifecycle, not the taskbar lifecycle.
    // It's destruction/creation will be managed by the activity.
    private final ScopedUnfoldTransitionProgressProvider mUnfoldProgressProvider =
            new NonDestroyableScopedUnfoldTransitionProgressProvider();
    /** DisplayId - {@link TaskbarActivityContext} map for Connected Display. */
    private final SparseArray<TaskbarActivityContext> mTaskbars = new SparseArray<>();
    /** DisplayId - {@link Context} map for Connected Display. */
    private final SparseArray<Context> mWindowContexts = new SparseArray<>();
    /** DisplayId - {@link FrameLayout} map for Connected Display. */
    private final SparseArray<FrameLayout> mRootLayouts = new SparseArray<>();
    /** DisplayId - {@link Boolean} map indicating if RootLayout was added to window. */
    private final SparseBooleanArray mAddedRootLayouts = new SparseBooleanArray();
    /** DisplayId - {@link TaskbarNavButtonController} map for Connected Display. */
    private final SparseArray<TaskbarNavButtonController> mNavButtonControllers =
            new SparseArray<>();
    /** DisplayId - {@link ComponentCallbacks} map for Connected Display. */
    private final SparseArray<ComponentCallbacks> mComponentCallbacks = new SparseArray<>();
    private StatefulActivity mActivity;
    private RecentsViewContainer mRecentsViewContainer;

    /**
     * Cache a copy here so we can initialize state whenever taskbar is recreated, since
     * this class does not get re-initialized w/ new taskbars.
     */
    private final TaskbarSharedState mSharedState = new TaskbarSharedState();

    /**
     * We use WindowManager's ComponentCallbacks() for internal UI changes (similar to an Activity)
     * which comes via a different channel
     */
    private final RecreationListener mRecreationListener = new RecreationListener();

    private class RecreationListener implements DisplayController.DisplayInfoChangeListener {
        @Override
        public void onDisplayInfoChanged(Context context, DisplayController.Info info, int flags) {
            if ((flags & CHANGE_DENSITY) != 0) {
                debugTaskbarManager("onDisplayInfoChanged - Display density changed",
                        context.getDisplayId());
            }
            if ((flags & CHANGE_NAVIGATION_MODE) != 0) {
                debugTaskbarManager("onDisplayInfoChanged - Navigation mode changed",
                        context.getDisplayId());
            }
            if ((flags & CHANGE_DESKTOP_MODE) != 0) {
                debugTaskbarManager("onDisplayInfoChanged - Desktop mode changed",
                        context.getDisplayId());
            }
            if ((flags & CHANGE_TASKBAR_PINNING) != 0) {
                debugTaskbarManager("onDisplayInfoChanged - Taskbar pinning changed",
                        context.getDisplayId());
            }

            if ((flags & (CHANGE_DENSITY | CHANGE_NAVIGATION_MODE | CHANGE_DESKTOP_MODE
                    | CHANGE_TASKBAR_PINNING)) != 0) {
                debugTaskbarManager("onDisplayInfoChanged - Recreating Taskbar!",
                        context.getDisplayId());
                recreateTaskbar();
            }
        }
    }
    private final SettingsCache.OnChangeListener mOnSettingsChangeListener = c -> {
        debugTaskbarManager("Settings changed! Recreating Taskbar!");
        recreateTaskbar();
    };

    private boolean mUserUnlocked = false;

    private final SimpleBroadcastReceiver mTaskbarBroadcastReceiver;

    private final SimpleBroadcastReceiver mGrowthBroadcastReceiver;

    private final AllAppsActionManager mAllAppsActionManager;
    private final RecentsDisplayModel mRecentsDisplayModel;

    private final Runnable mActivityOnDestroyCallback = new Runnable() {
        @Override
        public void run() {
            int displayId = getDefaultDisplayId();
            debugTaskbarManager("mActivityOnDestroyCallback running!", displayId);
            if (mActivity != null) {
                displayId = mActivity.getDisplayId();
                mActivity.removeOnDeviceProfileChangeListener(
                        mDebugActivityDeviceProfileChanged);
                Log.d(TASKBAR_NOT_DESTROYED_TAG,
                        "unregistering activity lifecycle callbacks from "
                                + "onActivityDestroyed.");
                mActivity.removeEventCallback(EVENT_DESTROYED, this);
            }
            if (mActivity == mRecentsViewContainer) {
                mRecentsViewContainer = null;
            }
            mActivity = null;
            TaskbarActivityContext taskbar = getTaskbarForDisplay(displayId);
            if (taskbar != null) {
                taskbar.setUIController(TaskbarUIController.DEFAULT);
            }
            mUnfoldProgressProvider.setSourceProvider(null);
        }
    };

    UnfoldTransitionProgressProvider.TransitionProgressListener mUnfoldTransitionProgressListener =
            new UnfoldTransitionProgressProvider.TransitionProgressListener() {
                @Override
                public void onTransitionStarted() {
                    debugTaskbarManager("fold/unfold transition started getting called.");
                }

                @Override
                public void onTransitionProgress(float progress) {
                    debugTaskbarManager(
                            "fold/unfold transition progress getting called. | progress="
                                    + progress);
                }

                @Override
                public void onTransitionFinishing() {
                    debugTaskbarManager(
                            "fold/unfold transition finishing getting called.");

                }

                @Override
                public void onTransitionFinished() {
                    debugTaskbarManager(
                            "fold/unfold transition finished getting called.");
                }
            };

    @SuppressLint("WrongConstant")
    public TaskbarManager(
            Context context,
            AllAppsActionManager allAppsActionManager,
            TaskbarNavButtonCallbacks navCallbacks,
            RecentsDisplayModel recentsDisplayModel) {
        mBaseContext = context;
        mAllAppsActionManager = allAppsActionManager;
        mNavCallbacks = navCallbacks;
        mRecentsDisplayModel = recentsDisplayModel;

        // Set up primary display.
        int primaryDisplayId = getDefaultDisplayId();
        debugTaskbarManager("TaskbarManager constructor", primaryDisplayId);
        mPrimaryWindowContext = createWindowContext(primaryDisplayId);
        mPrimaryWindowManager = mPrimaryWindowContext.getSystemService(WindowManager.class);
        createTaskbarRootLayout(primaryDisplayId);
        createNavButtonController(primaryDisplayId);
        createAndRegisterComponentCallbacks(primaryDisplayId);

        SettingsCache.INSTANCE.get(mPrimaryWindowContext)
                .register(USER_SETUP_COMPLETE_URI, mOnSettingsChangeListener);
        SettingsCache.INSTANCE.get(mPrimaryWindowContext)
                .register(NAV_BAR_KIDS_MODE, mOnSettingsChangeListener);
        mShutdownReceiver =
                new SimpleBroadcastReceiver(
                        mPrimaryWindowContext, UI_HELPER_EXECUTOR, i -> destroyAllTaskbars());
        mTaskbarBroadcastReceiver =
                new SimpleBroadcastReceiver(mPrimaryWindowContext,
                        UI_HELPER_EXECUTOR, this::showTaskbarFromBroadcast);

        mShutdownReceiver.register(Intent.ACTION_SHUTDOWN);
        if (enableGrowthNudge()) {
            // TODO: b/397739323 - Add permission to limit access to Growth Framework.
            mGrowthBroadcastReceiver =
                    new SimpleBroadcastReceiver(
                            mPrimaryWindowContext, UI_HELPER_EXECUTOR, this::showGrowthNudge);
            mGrowthBroadcastReceiver.register(RECEIVER_EXPORTED,
                    BROADCAST_SHOW_NUDGE);
        } else {
            mGrowthBroadcastReceiver = null;
        }
        UI_HELPER_EXECUTOR.execute(() -> {
            mSharedState.taskbarSystemActionPendingIntent = PendingIntent.getBroadcast(
                    mPrimaryWindowContext,
                    SYSTEM_ACTION_ID_TASKBAR,
                    new Intent(ACTION_SHOW_TASKBAR).setPackage(
                            mPrimaryWindowContext.getPackageName()),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            mTaskbarBroadcastReceiver.register(RECEIVER_NOT_EXPORTED, ACTION_SHOW_TASKBAR);
        });

        debugTaskbarManager("TaskbarManager created");
        recreateTaskbar();
    }

    private void destroyAllTaskbars() {
        for (int i = 0; i < mTaskbars.size(); i++) {
            int displayId = mTaskbars.keyAt(i);
            destroyTaskbarForDisplay(displayId);
            removeTaskbarRootViewFromWindow(displayId);
        }
    }

    private void destroyTaskbarForDisplay(int displayId) {
        Log.d(ILLEGAL_ARGUMENT_WM_ADD_VIEW, "destroyTaskbarForDisplay: " + displayId);
        TaskbarActivityContext taskbar = getTaskbarForDisplay(displayId);
        debugTaskbarManager("destroyTaskbarForDisplay: " + taskbar, displayId);
        if (taskbar != null) {
            taskbar.onDestroy();
            // remove all defaults that we store
            removeTaskbarFromMap(displayId);
        }
        // TODO (b/381113004): make this display-specific via getWindowContext()
        DeviceProfile dp = mUserUnlocked ? LauncherAppState.getIDP(
                mPrimaryWindowContext).getDeviceProfile(mPrimaryWindowContext) : null;
        if (dp == null || !isTaskbarEnabled(dp)) {
            removeTaskbarRootViewFromWindow(displayId);
        }
    }

    /**
     * Show Taskbar upon receiving broadcast
     */
    private void showTaskbarFromBroadcast(Intent intent) {
        // TODO: make this code displayId specific
        TaskbarActivityContext taskbar = getTaskbarForDisplay(getDefaultDisplayId());
        if (ACTION_SHOW_TASKBAR.equals(intent.getAction())) {
            taskbar.showTaskbarFromBroadcast();
        }
    }

    private void showGrowthNudge(Intent intent) {
        if (!enableGrowthNudge()) {
            return;
        }
        if (BROADCAST_SHOW_NUDGE.equals(intent.getAction())) {
            // TODO: b/397738606 - extract the details and create a nudge payload.
            Log.d(GROWTH_FRAMEWORK_TAG, "Intent received");
        }
    }

    /**
     * Toggles All Apps for Taskbar or Launcher depending on the current state.
     */
    public void toggleAllApps() {
        TaskbarActivityContext taskbar = getTaskbarForDisplay(getDefaultDisplayId());
        if (taskbar == null || taskbar.canToggleHomeAllApps()) {
            // Home All Apps should be toggled from this class, because the controllers are not
            // initialized when Taskbar is disabled (i.e. TaskbarActivityContext is null).
            if (mActivity instanceof Launcher l) l.toggleAllAppsSearch();
        } else {
            taskbar.toggleAllAppsSearch();
        }
    }

    /**
     * Displays a frame of the first Launcher reveal animation.
     *
     * This should be used to run a first Launcher reveal animation whose progress matches a swipe
     * progress.
     */
    public AnimatorPlaybackController createLauncherStartFromSuwAnim(int duration) {
        TaskbarActivityContext taskbar = getTaskbarForDisplay(getDefaultDisplayId());
        return taskbar == null ? null : taskbar.createLauncherStartFromSuwAnim(duration);
    }

    /**
     * Called when the user is unlocked
     */
    public void onUserUnlocked() {
        mUserUnlocked = true;
        DisplayController.INSTANCE.get(mPrimaryWindowContext).addChangeListener(
                mRecreationListener);
        recreateTaskbar();
        for (int i = 0; i < mTaskbars.size(); i++) {
            int displayId = mTaskbars.keyAt(i);
            addTaskbarRootViewToWindow(displayId);
        }
    }

    /**
     * Sets a {@link StatefulActivity} to act as taskbar callback
     */
    public void setActivity(@NonNull StatefulActivity activity) {
        if (mActivity == activity) {
            return;
        }
        removeActivityCallbacksAndListeners();
        mActivity = activity;
        debugTaskbarManager("Set mActivity=" + mActivity);
        mActivity.addOnDeviceProfileChangeListener(mDebugActivityDeviceProfileChanged);
        Log.d(TASKBAR_NOT_DESTROYED_TAG,
                "registering activity lifecycle callbacks from setActivity().");
        mActivity.addEventCallback(EVENT_DESTROYED, mActivityOnDestroyCallback);
        UnfoldTransitionProgressProvider unfoldTransitionProgressProvider =
                getUnfoldTransitionProgressProviderForActivity(activity);
        if (unfoldTransitionProgressProvider != null) {
            unfoldTransitionProgressProvider.addCallback(mUnfoldTransitionProgressListener);
        }
        mUnfoldProgressProvider.setSourceProvider(unfoldTransitionProgressProvider);

        if (activity instanceof RecentsViewContainer recentsViewContainer) {
            setRecentsViewContainer(recentsViewContainer);
        }
    }

    /**
     * Sets the current RecentsViewContainer, from which we create a TaskbarUIController.
     */
    public void setRecentsViewContainer(@NonNull RecentsViewContainer recentsViewContainer) {
        if (mRecentsViewContainer == recentsViewContainer) {
            return;
        }
        if (mRecentsViewContainer == mActivity) {
            // When switching to RecentsWindowManager (not an Activity), the old mActivity is not
            // destroyed, nor is there a new Activity to replace it. Thus if we don't clear it here,
            // it will not get re-set properly if we return to the Activity (e.g. NexusLauncher).
            mActivityOnDestroyCallback.run();
        }
        mRecentsViewContainer = recentsViewContainer;
        TaskbarActivityContext taskbar = getCurrentActivityContext();
        if (taskbar != null) {
            taskbar.setUIController(
                    createTaskbarUIControllerForRecentsViewContainer(mRecentsViewContainer));
        }
    }

    /**
     * Returns an {@link UnfoldTransitionProgressProvider} to use while the given StatefulActivity
     * is active.
     */
    private UnfoldTransitionProgressProvider getUnfoldTransitionProgressProviderForActivity(
            StatefulActivity activity) {
        if (!enableUnfoldStateAnimation()) {
            if (activity instanceof QuickstepLauncher ql) {
                return ql.getUnfoldTransitionProgressProvider();
            }
        } else {
            return SystemUiProxy.INSTANCE.get(mBaseContext).getUnfoldTransitionProvider();
        }
        return null;
    }

    /** Creates a {@link TaskbarUIController} to use with non default displays. */
    private TaskbarUIController createTaskbarUIControllerForNonDefaultDisplay(int displayId) {
        if (RecentsDisplayModel.enableOverviewInWindow()) {
            RecentsViewContainer rvc = mRecentsDisplayModel.getRecentsWindowManager(displayId);
            if (rvc != null) {
                return createTaskbarUIControllerForRecentsViewContainer(rvc);
            }
        }

        return new TaskbarUIController();
    }

    /**
     * Creates a {@link TaskbarUIController} to use while the given StatefulActivity is active.
     */
    private TaskbarUIController createTaskbarUIControllerForRecentsViewContainer(
            RecentsViewContainer container) {
        if (container instanceof QuickstepLauncher quickstepLauncher) {
            return new LauncherTaskbarUIController(quickstepLauncher);
        }
        // If a 3P Launcher is default, always use FallbackTaskbarUIController regardless of
        // whether the recents container is RecentsActivity or RecentsWindowManager.
        if (container instanceof RecentsActivity recentsActivity) {
            return new FallbackTaskbarUIController<>(recentsActivity);
        }
        if (container instanceof RecentsWindowManager recentsWindowManager) {
            return new FallbackTaskbarUIController<>(recentsWindowManager);
        }
        return TaskbarUIController.DEFAULT;
    }

    /**
     * This method is called multiple times (ex. initial init, then when user unlocks) in which case
     * we fully want to destroy existing taskbars and create all desired new ones.
     * In other case (folding/unfolding) we don't need to remove and add window.
     */
    @VisibleForTesting
    public synchronized void recreateTaskbar() {
        // Handles initial creation case.
        if (mTaskbars.size() == 0) {
            recreateTaskbarForDisplay(getDefaultDisplayId());
            return;
        }

        for (int i = 0; i < mTaskbars.size(); i++) {
            int displayId = mTaskbars.keyAt(i);
            recreateTaskbarForDisplay(displayId);
        }
    }

    /**
     * This method is called multiple times (ex. initial init, then when user unlocks) in which case
     * we fully want to destroy an existing taskbar for a specified display and create a new one.
     * In other case (folding/unfolding) we don't need to remove and add window.
     */
    private void recreateTaskbarForDisplay(int displayId) {
        Trace.beginSection("recreateTaskbarForDisplay");
        try {
            Log.d(ILLEGAL_ARGUMENT_WM_ADD_VIEW, "recreateTaskbarForDisplay: " + displayId);
            // TODO (b/381113004): make this display-specific via getWindowContext()
            DeviceProfile dp = mUserUnlocked ? LauncherAppState.getIDP(
                    mPrimaryWindowContext).getDeviceProfile(mPrimaryWindowContext) : null;

            // All Apps action is unrelated to navbar unification, so we only need to check DP.
            final boolean isLargeScreenTaskbar = dp != null && dp.isTaskbarPresent;
            mAllAppsActionManager.setTaskbarPresent(isLargeScreenTaskbar);

            destroyTaskbarForDisplay(displayId);

            boolean displayExists = getDisplay(displayId) != null;
            boolean isTaskbarEnabled = dp != null && isTaskbarEnabled(dp);
            debugTaskbarManager("recreateTaskbarForDisplay: isTaskbarEnabled=" + isTaskbarEnabled
                + " [dp != null (i.e. mUserUnlocked)]=" + (dp != null)
                + " FLAG_HIDE_NAVBAR_WINDOW=" + ENABLE_TASKBAR_NAVBAR_UNIFICATION
                    + " dp.isTaskbarPresent=" + (dp == null ? "null" : dp.isTaskbarPresent)
                    + " displayExists=" + displayExists);
            if (!isTaskbarEnabled || !isLargeScreenTaskbar || !displayExists) {
                SystemUiProxy.INSTANCE.get(mBaseContext)
                    .notifyTaskbarStatus(/* visible */ false, /* stashed */ false);
                if (!isTaskbarEnabled || !displayExists) {
                    return;
                }
            }

            TaskbarActivityContext taskbar = getTaskbarForDisplay(displayId);
            if (enableTaskbarNoRecreate() || taskbar == null) {
                taskbar = createTaskbarActivityContext(dp, displayId);
                if (taskbar == null) {
                    debugTaskbarManager(
                            "recreateTaskbarForDisplay: new taskbar instance is null!", displayId);
                    return;
                }
            } else {
                taskbar.updateDeviceProfile(dp);
            }
            mSharedState.startTaskbarVariantIsTransient =
                    DisplayController.isTransientTaskbar(taskbar);
            mSharedState.allAppsVisible = mSharedState.allAppsVisible && isLargeScreenTaskbar;
            taskbar.init(mSharedState);

            // Non default displays should not use LauncherTaskbarUIController as they shouldn't
            // have access to the Launcher activity.
            if (!isDefaultDisplay(displayId)
                    && DesktopExperienceFlags.ENABLE_TASKBAR_CONNECTED_DISPLAYS.isTrue()) {
                taskbar.setUIController(createTaskbarUIControllerForNonDefaultDisplay(displayId));
            } else if (mRecentsViewContainer != null) {
                taskbar.setUIController(
                        createTaskbarUIControllerForRecentsViewContainer(mRecentsViewContainer));
            }

            if (enableTaskbarNoRecreate()) {
                addTaskbarRootViewToWindow(displayId);
                FrameLayout taskbarRootLayout = getTaskbarRootLayoutForDisplay(displayId);
                if (taskbarRootLayout != null) {
                    taskbarRootLayout.removeAllViews();
                    taskbarRootLayout.addView(taskbar.getDragLayer());
                    taskbar.notifyUpdateLayoutParams();
                } else {
                    Log.e(NULL_TASKBAR_ROOT_LAYOUT_TAG,
                            "taskbarRootLayout is null for displayId=" + displayId);
                }
            }
        } finally {
            Trace.endSection();
        }
    }

    public void onSystemUiFlagsChanged(@SystemUiStateFlags long systemUiStateFlags) {
        if (DEBUG) {
            Log.d(TAG, "SysUI flags changed: " + formatFlagChange(systemUiStateFlags,
                    mSharedState.sysuiStateFlags, QuickStepContract::getSystemUiStateString));
        }
        mSharedState.sysuiStateFlags = systemUiStateFlags;
        TaskbarActivityContext taskbar = getTaskbarForDisplay(getDefaultDisplayId());
        if (taskbar != null) {
            taskbar.updateSysuiStateFlags(systemUiStateFlags, false /* fromInit */);
        }
    }

    public void onLongPressHomeEnabled(boolean assistantLongPressEnabled) {
        if (mPrimaryNavButtonController != null) {
            mPrimaryNavButtonController.setAssistantLongPressEnabled(assistantLongPressEnabled);
        }
    }

    /**
     * Sets the flag indicating setup UI is visible
     */
    public void setSetupUIVisible(boolean isVisible) {
        mSharedState.setupUIVisible = isVisible;
        mAllAppsActionManager.setSetupUiVisible(isVisible);
        TaskbarActivityContext taskbar = getTaskbarForDisplay(getDefaultDisplayId());
        if (taskbar != null) {
            taskbar.setSetupUIVisible(isVisible);
        }
    }

    /**
     * Sets wallpaper visibility for specific display.
     */
    public void setWallpaperVisible(int displayId, boolean isVisible) {
        mSharedState.wallpaperVisible = isVisible;
        TaskbarActivityContext taskbar = getTaskbarForDisplay(displayId);
        if (taskbar != null) {
            taskbar.setWallpaperVisible(isVisible);
        }
    }

    public void checkNavBarModes(int displayId) {
        TaskbarActivityContext taskbar = getTaskbarForDisplay(displayId);
        if (taskbar != null) {
            taskbar.checkNavBarModes();
        }
    }

    public void finishBarAnimations(int displayId) {
        TaskbarActivityContext taskbar = getTaskbarForDisplay(displayId);
        if (taskbar != null) {
            taskbar.finishBarAnimations();
        }
    }

    public void touchAutoDim(int displayId, boolean reset) {
        TaskbarActivityContext taskbar = getTaskbarForDisplay(displayId);
        if (taskbar != null) {
            taskbar.touchAutoDim(reset);
        }
    }

    public void transitionTo(int displayId, @BarTransitions.TransitionMode int barMode,
                             boolean animate) {
        TaskbarActivityContext taskbar = getTaskbarForDisplay(displayId);
        if (taskbar != null) {
            taskbar.transitionTo(barMode, animate);
        }
    }

    public void appTransitionPending(boolean pending) {
        TaskbarActivityContext taskbar = getTaskbarForDisplay(getDefaultDisplayId());
        if (taskbar != null) {
            taskbar.appTransitionPending(pending);
        }
    }

    private boolean isTaskbarEnabled(DeviceProfile deviceProfile) {
        return ENABLE_TASKBAR_NAVBAR_UNIFICATION || deviceProfile.isTaskbarPresent;
    }

    public void onRotationProposal(int rotation, boolean isValid) {
        TaskbarActivityContext taskbar = getTaskbarForDisplay(getDefaultDisplayId());
        if (taskbar != null) {
            taskbar.onRotationProposal(rotation, isValid);
        }
    }

    public void disableNavBarElements(int displayId, int state1, int state2, boolean animate) {
        mSharedState.disableNavBarDisplayId = displayId;
        mSharedState.disableNavBarState1 = state1;
        mSharedState.disableNavBarState2 = state2;
        TaskbarActivityContext taskbar = getTaskbarForDisplay(displayId);
        if (taskbar != null) {
            taskbar.disableNavBarElements(displayId, state1, state2, animate);
        }
    }

    public void onSystemBarAttributesChanged(int displayId, int behavior) {
        mSharedState.systemBarAttrsDisplayId = displayId;
        mSharedState.systemBarAttrsBehavior = behavior;
        TaskbarActivityContext taskbar = getTaskbarForDisplay(displayId);
        if (taskbar != null) {
            taskbar.onSystemBarAttributesChanged(displayId, behavior);
        }
    }

    public void onTransitionModeUpdated(int barMode, boolean checkBarModes) {
        mSharedState.barMode = barMode;
        TaskbarActivityContext taskbar = getTaskbarForDisplay(getDefaultDisplayId());
        if (taskbar != null) {
            taskbar.onTransitionModeUpdated(barMode, checkBarModes);
        }
    }

    public void onNavButtonsDarkIntensityChanged(float darkIntensity) {
        mSharedState.navButtonsDarkIntensity = darkIntensity;
        TaskbarActivityContext taskbar = getTaskbarForDisplay(getDefaultDisplayId());
        if (taskbar != null) {
            taskbar.onNavButtonsDarkIntensityChanged(darkIntensity);
        }
    }

    public void onNavigationBarLumaSamplingEnabled(int displayId, boolean enable) {
        mSharedState.mLumaSamplingDisplayId = displayId;
        mSharedState.mIsLumaSamplingEnabled = enable;
        TaskbarActivityContext taskbar = getTaskbarForDisplay(displayId);
        if (taskbar != null) {
            taskbar.onNavigationBarLumaSamplingEnabled(displayId, enable);
        }
    }

    /**
     * Signal from SysUI indicating that a non-mirroring display was just connected to the
     * primary device or a previously mirroring display is switched to extended mode.
     */
    public void onDisplayAddSystemDecorations(int displayId) {
        Display display = getDisplay(displayId);
        if (!DesktopExperienceFlags.ENABLE_TASKBAR_CONNECTED_DISPLAYS.isTrue() || isDefaultDisplay(
                displayId) || display == null) {
            debugTaskbarManager("onDisplayAddSystemDecorations: not adding display");
            return;
        }

        // TODO (b/391965805): remove once onDisplayAddSystemDecorations is working.
        WindowManager wm = getWindowManager(displayId);
        if (wm == null || !wm.shouldShowSystemDecors(displayId)) {
            return;
        }

        Context newWindowContext = createWindowContext(displayId);
        if (newWindowContext != null) {
            addWindowContextToMap(displayId, newWindowContext);
            createTaskbarRootLayout(displayId);
            createNavButtonController(displayId);
            createAndRegisterComponentCallbacks(displayId);
            recreateTaskbarForDisplay(displayId);
        }
    }

    /**
     * Signal from SysUI indicating that a previously connected non-mirroring display was just
     * removed from the primary device.
     */
    public void onDisplayRemoved(int displayId) {
        if (!DesktopExperienceFlags.ENABLE_TASKBAR_CONNECTED_DISPLAYS.isTrue() || isDefaultDisplay(
                displayId)) {
            return;
        }

        Context windowContext = getWindowContext(displayId);
        if (windowContext != null) {
            removeNavButtonController(displayId);
            removeAndUnregisterComponentCallbacks(displayId);
            destroyTaskbarForDisplay(displayId);
            removeWindowContextFromMap(displayId);
        }
    }

    /**
     * Signal from SysUI indicating that system decorations should be removed from the display.
     */
    public void onDisplayRemoveSystemDecorations(int displayId) {
        // The display mirroring starts. The handling logic is the same as when removing a
        // display.
        onDisplayRemoved(displayId);
    }

    private void removeActivityCallbacksAndListeners() {
        if (mActivity != null) {
            mActivity.removeOnDeviceProfileChangeListener(mDebugActivityDeviceProfileChanged);
            Log.d(TASKBAR_NOT_DESTROYED_TAG,
                    "unregistering activity lifecycle callbacks from "
                            + "removeActivityCallbackAndListeners().");
            mActivity.removeEventCallback(EVENT_DESTROYED, mActivityOnDestroyCallback);
            UnfoldTransitionProgressProvider unfoldTransitionProgressProvider =
                    getUnfoldTransitionProgressProviderForActivity(mActivity);
            if (unfoldTransitionProgressProvider != null) {
                unfoldTransitionProgressProvider.removeCallback(mUnfoldTransitionProgressListener);
            }
        }
    }

    /**
     * Called when the manager is no longer needed
     */
    public void destroy() {
        mRecentsViewContainer = null;
        debugTaskbarManager("TaskbarManager#destroy()");
        removeActivityCallbacksAndListeners();
        mTaskbarBroadcastReceiver.unregisterReceiverSafely();
        if (mGrowthBroadcastReceiver != null) {
            mGrowthBroadcastReceiver.unregisterReceiverSafely();
        }

        if (mUserUnlocked) {
            DisplayController.INSTANCE.get(mPrimaryWindowContext).removeChangeListener(
                    mRecreationListener);
        }
        SettingsCache.INSTANCE.get(mPrimaryWindowContext)
                .unregister(USER_SETUP_COMPLETE_URI, mOnSettingsChangeListener);
        SettingsCache.INSTANCE.get(mPrimaryWindowContext)
                .unregister(NAV_BAR_KIDS_MODE, mOnSettingsChangeListener);
        Log.d(TASKBAR_NOT_DESTROYED_TAG, "unregistering component callbacks from destroy().");
        removeAndUnregisterComponentCallbacks(getDefaultDisplayId());
        mShutdownReceiver.unregisterReceiverSafely();
        destroyAllTaskbars();
    }

    public @Nullable TaskbarActivityContext getCurrentActivityContext() {
        return getTaskbarForDisplay(getDefaultDisplayId());
    }

    public void dumpLogs(String prefix, PrintWriter pw) {
        pw.println(prefix + "TaskbarManager:");
        // iterate through taskbars and do the dump for each
        for (int i = 0; i < mTaskbars.size(); i++) {
            int displayId = mTaskbars.keyAt(i);
            TaskbarActivityContext taskbar = mTaskbars.get(i);
            pw.println(prefix + "\tTaskbar at display " + displayId + ":");
            if (taskbar == null) {
                pw.println(prefix + "\t\tTaskbarActivityContext: null");
            } else {
                taskbar.dumpLogs(prefix + "\t\t", pw);
            }
        }
    }

    private void addTaskbarRootViewToWindow(int displayId) {
        TaskbarActivityContext taskbar = getTaskbarForDisplay(displayId);
        if (!enableTaskbarNoRecreate() || taskbar == null) {
            debugTaskbarManager("addTaskbarRootViewToWindow - taskbar null", displayId);
            return;
        }

        if (getDisplay(displayId) == null) {
            debugTaskbarManager("addTaskbarRootViewToWindow - display null", displayId);
            return;
        }

        if (!isTaskbarRootLayoutAddedForDisplay(displayId)) {
            FrameLayout rootLayout = getTaskbarRootLayoutForDisplay(displayId);
            WindowManager windowManager = getWindowManager(displayId);
            if (rootLayout != null && windowManager != null) {
                windowManager.addView(rootLayout, taskbar.getWindowLayoutParams());
                mAddedRootLayouts.put(displayId, true);
            } else {
                Log.d(ILLEGAL_ARGUMENT_WM_ADD_VIEW,
                        "addTaskbarRootViewToWindow - root layout null | displayId=" + displayId);
            }
        } else {
            Log.d(NULL_TASKBAR_ROOT_LAYOUT_TAG,
                    "addTaskbarRootViewToWindow - root layout already added | displayId="
                            + displayId);
        }
    }

    private void removeTaskbarRootViewFromWindow(int displayId) {
        Log.d(ILLEGAL_ARGUMENT_WM_ADD_VIEW, "removeTaskbarRootViewFromWindow: " + displayId);
        FrameLayout rootLayout = getTaskbarRootLayoutForDisplay(displayId);
        if (!enableTaskbarNoRecreate() || rootLayout == null) {
            return;
        }

        WindowManager windowManager = getWindowManager(displayId);
        if (isTaskbarRootLayoutAddedForDisplay(displayId) && windowManager != null) {
            windowManager.removeViewImmediate(rootLayout);
            mAddedRootLayouts.put(displayId, false);
            removeTaskbarRootLayoutFromMap(displayId);
        } else {
            debugTaskbarManager("removeTaskbarRootViewFromWindow - WindowManager is null",
                    displayId);
        }
    }

    /**
     * Returns the {@link TaskbarUIController} associated with the given display ID.
     * TODO(b/395061396): Remove this method when overview in widow is enabled.
     *
     * @param displayId The ID of the display to retrieve the taskbar for.
     * @return The {@link TaskbarUIController} for the specified display, or
     *         {@code null} if no taskbar is associated with that display.
     */
    @Nullable
    public TaskbarUIController getUIControllerForDisplay(int displayId) {
        if (!mTaskbars.contains(displayId)) {
            return null;
        }

        return getTaskbarForDisplay(displayId).getControllers().uiController;
    }

    /**
     * Retrieves whether RootLayout was added to window for specific display, or false if no
     * such mapping has been made.
     *
     * @param displayId The ID of the display for which to retrieve the taskbar root layout.
     * @return if RootLayout was added to window {@link Boolean} for a display or {@code false}.
     */
    private boolean isTaskbarRootLayoutAddedForDisplay(int displayId) {
        return mAddedRootLayouts.get(displayId);
    }

    /**
     * Returns the {@link TaskbarActivityContext} associated with the given display ID.
     *
     * @param displayId The ID of the display to retrieve the taskbar for.
     * @return The {@link TaskbarActivityContext} for the specified display, or
     *         {@code null} if no taskbar is associated with that display.
     */
    private TaskbarActivityContext getTaskbarForDisplay(int displayId) {
        return mTaskbars.get(displayId);
    }


    /**
     * Creates a {@link TaskbarActivityContext} for the given display and adds it to the map.
     * @param dp The {@link DeviceProfile} for the display.
     * @param displayId The ID of the display.
     */
    private @Nullable TaskbarActivityContext createTaskbarActivityContext(DeviceProfile dp,
            int displayId) {
        Display display = getDisplay(displayId);
        if (display == null) {
            debugTaskbarManager("createTaskbarActivityContext: display null", displayId);
            return null;
        }

        Context navigationBarPanelContext = null;
        if (ENABLE_TASKBAR_NAVBAR_UNIFICATION) {
            navigationBarPanelContext = mBaseContext.createWindowContext(display,
                    TYPE_NAVIGATION_BAR_PANEL, null);
        }

        boolean isPrimaryDisplay = isDefaultDisplay(displayId)
                || !DesktopExperienceFlags.ENABLE_TASKBAR_CONNECTED_DISPLAYS.isTrue();

        TaskbarActivityContext newTaskbar = new TaskbarActivityContext(getWindowContext(displayId),
                navigationBarPanelContext, dp, getNavButtonController(displayId),
                mUnfoldProgressProvider, isPrimaryDisplay,
                SystemUiProxy.INSTANCE.get(mBaseContext));

        addTaskbarToMap(displayId, newTaskbar);
        return newTaskbar;
    }

    /**
     * Create {@link ComponentCallbacks} for the given display and register it to the relevant
     * WindowContext. For external displays, populate maps.
     * @param displayId The ID of the display.
     */
    private void createAndRegisterComponentCallbacks(int displayId) {
        ComponentCallbacks callbacks = new ComponentCallbacks() {
            private Configuration mOldConfig =
                    getWindowContext(displayId).getResources().getConfiguration();

            @Override
            public void onConfigurationChanged(Configuration newConfig) {
                Trace.instantForTrack(Trace.TRACE_TAG_APP, "TaskbarManager",
                        "onConfigurationChanged: " + newConfig);
                debugTaskbarManager(
                        "TaskbarManager#mComponentCallbacks.onConfigurationChanged: " + newConfig);
                // TODO (b/381113004): make this display-specific via getWindowContext()
                DeviceProfile dp = mUserUnlocked ? LauncherAppState.getIDP(
                        mPrimaryWindowContext).getDeviceProfile(mPrimaryWindowContext) : null;
                int configDiff = mOldConfig.diff(newConfig) & ~SKIP_RECREATE_CONFIG_CHANGES;

                if ((configDiff & ActivityInfo.CONFIG_UI_MODE) != 0) {
                    Log.d(ILLEGAL_ARGUMENT_WM_ADD_VIEW, "onConfigurationChanged: theme changed");
                    // Only recreate for theme changes, not other UI mode changes such as docking.
                    int oldUiNightMode = (mOldConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK);
                    int newUiNightMode = (newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK);
                    if (oldUiNightMode == newUiNightMode) {
                        configDiff &= ~ActivityInfo.CONFIG_UI_MODE;
                    }
                }

                debugTaskbarManager("ComponentCallbacks#onConfigurationChanged() "
                        + "configDiff=" + Configuration.configurationDiffToString(configDiff));
                if (configDiff != 0 || getCurrentActivityContext() == null) {
                    recreateTaskbar();
                } else {
                    // Config change might be handled without re-creating the taskbar
                    if (dp != null && !isTaskbarEnabled(dp)) {
                        destroyTaskbarForDisplay(getDefaultDisplayId());
                    } else {
                        if (dp != null && isTaskbarEnabled(dp)) {
                            if (ENABLE_TASKBAR_NAVBAR_UNIFICATION) {
                                // Re-initialize for screen size change? Should this be done
                                // by looking at screen-size change flag in configDiff in the
                                // block above?
                                recreateTaskbar();
                            } else {
                                getCurrentActivityContext().updateDeviceProfile(dp);
                            }
                        }
                        getCurrentActivityContext().onConfigurationChanged(configDiff);
                    }
                }
                mOldConfig = new Configuration(newConfig);
                // reset taskbar was pinned value, so we don't automatically unstash taskbar upon
                // user unfolding the device.
                mSharedState.setTaskbarWasPinned(false);
            }

            @Override
            public void onLowMemory() { }
        };
        if (isDefaultDisplay(displayId)
                || !DesktopExperienceFlags.ENABLE_TASKBAR_CONNECTED_DISPLAYS.isTrue()) {
            mPrimaryComponentCallbacks = callbacks;
            mPrimaryWindowContext.registerComponentCallbacks(callbacks);
        } else {
            mComponentCallbacks.put(displayId, callbacks);
            getWindowContext(displayId).registerComponentCallbacks(callbacks);
        }
    }

    /**
     * Unregister {@link ComponentCallbacks} for the given display from its WindowContext. For
     * external displays, remove from the map.
     * @param displayId The ID of the display.
     */
    private void removeAndUnregisterComponentCallbacks(int displayId) {
        if (isDefaultDisplay(displayId)
                || !DesktopExperienceFlags.ENABLE_TASKBAR_CONNECTED_DISPLAYS.isTrue()) {
            mPrimaryWindowContext.unregisterComponentCallbacks(mPrimaryComponentCallbacks);
        } else {
            ComponentCallbacks callbacks = mComponentCallbacks.get(displayId);
            getWindowContext(displayId).unregisterComponentCallbacks(callbacks);
            mComponentCallbacks.delete(displayId);
        }
    }

    /**
     * Creates a {@link TaskbarNavButtonController} for the given display and adds it to the map
     * if it doesn't already exist.
     * @param displayId The ID of the display
     */
    private void createNavButtonController(int displayId) {
        if (isDefaultDisplay(displayId)
                || !DesktopExperienceFlags.ENABLE_TASKBAR_CONNECTED_DISPLAYS.isTrue()) {
            mPrimaryNavButtonController = new TaskbarNavButtonController(
                    mPrimaryWindowContext,
                    mNavCallbacks,
                    SystemUiProxy.INSTANCE.get(mBaseContext),
                    new Handler(),
                    new ContextualSearchInvoker(mBaseContext));
        } else {
            TaskbarNavButtonController navButtonController = new TaskbarNavButtonController(
                    getWindowContext(displayId),
                    mNavCallbacks,
                    SystemUiProxy.INSTANCE.get(mBaseContext),
                    new Handler(),
                    new ContextualSearchInvoker(mBaseContext));
            mNavButtonControllers.put(displayId, navButtonController);
        }
    }

    private TaskbarNavButtonController getNavButtonController(int displayId) {
        return (isDefaultDisplay(displayId)
                || !DesktopExperienceFlags.ENABLE_TASKBAR_CONNECTED_DISPLAYS.isTrue())
                ? mPrimaryNavButtonController : mNavButtonControllers.get(displayId);
    }

    private void removeNavButtonController(int displayId) {
        if (isDefaultDisplay(displayId)
                || !DesktopExperienceFlags.ENABLE_TASKBAR_CONNECTED_DISPLAYS.isTrue()) {
            mPrimaryNavButtonController = null;
        } else {
            mNavButtonControllers.delete(displayId);
        }
    }

    /**
     * Adds the {@link TaskbarActivityContext} associated with the given display ID to taskbar
     * map if there is not already a taskbar mapped to that displayId.
     *
     * @param displayId The ID of the display to retrieve the taskbar for.
     * @param newTaskbar The new {@link TaskbarActivityContext} to add to the map.
     */
    private void addTaskbarToMap(int displayId, TaskbarActivityContext newTaskbar) {
        if (!mTaskbars.contains(displayId)) {
            mTaskbars.put(displayId, newTaskbar);
        }
    }

    /**
     * Removes the taskbar associated with the given display ID from the taskbar map.
     *
     * @param displayId The ID of the display for which to remove the taskbar.
     */
    private void removeTaskbarFromMap(int displayId) {
        mTaskbars.delete(displayId);
    }

    /**
     * Creates {@link FrameLayout} for the taskbar on the specified display and adds it to map.
     * @param displayId The ID of the display for which to create the taskbar root layout.
     */
    private void createTaskbarRootLayout(int displayId) {
        Log.d(ILLEGAL_ARGUMENT_WM_ADD_VIEW, "createTaskbarRootLayout: " + displayId);
        if (!enableTaskbarNoRecreate()) {
            return;
        }

        FrameLayout newTaskbarRootLayout = new FrameLayout(getWindowContext(displayId)) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                // The motion events can be outside the view bounds of task bar, and hence
                // manually dispatching them to the drag layer here.
                TaskbarActivityContext taskbar = getTaskbarForDisplay(displayId);
                if (taskbar != null && taskbar.getDragLayer().isAttachedToWindow()) {
                    return taskbar.getDragLayer().dispatchTouchEvent(ev);
                }
                return super.dispatchTouchEvent(ev);
            }
        };
        addTaskbarRootLayoutToMap(displayId, newTaskbarRootLayout);
        Log.d(NULL_TASKBAR_ROOT_LAYOUT_TAG, "created new root layout - displayId=" + displayId);
    }

    private boolean isDefaultDisplay(int displayId) {
        return displayId == getDefaultDisplayId();
    }

    /**
     * Retrieves the root layout of the taskbar for the specified display.
     *
     * @param displayId The ID of the display for which to retrieve the taskbar root layout.
     * @return The taskbar root layout {@link FrameLayout} for a given display or {@code null}.
     */
    private FrameLayout getTaskbarRootLayoutForDisplay(int displayId) {
        Log.d(ILLEGAL_ARGUMENT_WM_ADD_VIEW, "getTaskbarRootLayoutForDisplay: " + displayId);
        FrameLayout frameLayout = mRootLayouts.get(displayId);
        if (frameLayout != null) {
            return frameLayout;
        } else {
            Log.d(NULL_TASKBAR_ROOT_LAYOUT_TAG,
                    "getTaskbarRootLayoutForDisplay == null | displayId=" + displayId);
            return null;
        }
    }

    /**
     * Adds the taskbar root layout {@link FrameLayout} to taskbar map, mapped to display ID.
     *
     * @param displayId The ID of the display to associate with the taskbar root layout.
     * @param rootLayout The taskbar root layout {@link FrameLayout} to add to the map.
     */
    private void addTaskbarRootLayoutToMap(int displayId, FrameLayout rootLayout) {
        if (!mRootLayouts.contains(displayId) && rootLayout != null) {
            mRootLayouts.put(displayId, rootLayout);
        }

        Log.d(NULL_TASKBAR_ROOT_LAYOUT_TAG, "mRootLayouts.size()=" + mRootLayouts.size());
    }

    /**
     * Removes taskbar root layout {@link FrameLayout} for given display ID from the taskbar map.
     *
     * @param displayId The ID of the display for which to remove the taskbar root layout.
     */
    private void removeTaskbarRootLayoutFromMap(int displayId) {
        if (mRootLayouts.contains(displayId)) {
            mAddedRootLayouts.delete(displayId);
            mRootLayouts.delete(displayId);
        }

        Log.d(NULL_TASKBAR_ROOT_LAYOUT_TAG, "mRootLayouts.size()=" + mRootLayouts.size());
    }

    /**
     * Creates {@link Context} for the taskbar on the specified display.
     * @param displayId The ID of the display for which to create the window context.
     */
    private @Nullable Context createWindowContext(int displayId) {
        debugTaskbarManager("createWindowContext: " + displayId);
        Display display = getDisplay(displayId);
        if (display == null) {
            debugTaskbarManager("createWindowContext: display null", displayId);
            return null;
        }

        int windowType = TYPE_NAVIGATION_BAR_PANEL;
        if (ENABLE_TASKBAR_NAVBAR_UNIFICATION && isDefaultDisplay(displayId)) {
            windowType = TYPE_NAVIGATION_BAR;
        }
        debugTaskbarManager(
                "createWindowContext: windowType=" + ((windowType == TYPE_NAVIGATION_BAR)
                        ? "TYPE_NAVIGATION_BAR" : "TYPE_NAVIGATION_BAR_PANEL"), displayId);

        return mBaseContext.createWindowContext(display, windowType, null);
    }

    private @Nullable Display getDisplay(int displayId) {
        DisplayManager displayManager = mBaseContext.getSystemService(DisplayManager.class);
        if (displayManager == null) {
            debugTaskbarManager("cannot get DisplayManager", displayId);
            return null;
        }

        Display display = displayManager.getDisplay(displayId);
        if (display == null) {
            debugTaskbarManager("Cannot get display!", displayId);
            return null;
        }

        return displayManager.getDisplay(displayId);
    }

    /**
     * Retrieves the window context of the taskbar for the specified display.
     *
     * @param displayId The ID of the display for which to retrieve the window context.
     * @return The Window Context {@link Context} for a given display or {@code null}.
     */
    private Context getWindowContext(int displayId) {
        return (isDefaultDisplay(displayId)
                || !DesktopExperienceFlags.ENABLE_TASKBAR_CONNECTED_DISPLAYS.isTrue())
                ? mPrimaryWindowContext : mWindowContexts.get(displayId);
    }

    @VisibleForTesting
    public Context getPrimaryWindowContext() {
        return mPrimaryWindowContext;
    }

    /**
     * Retrieves the window manager {@link WindowManager} of the taskbar for the specified display.
     *
     * @param displayId The ID of the display for which to retrieve the window manager.
     * @return The window manager {@link WindowManager} for a given display or {@code null}.
     */
    private @Nullable WindowManager getWindowManager(int displayId) {
        if (isDefaultDisplay(displayId)
                || !DesktopExperienceFlags.ENABLE_TASKBAR_CONNECTED_DISPLAYS.isTrue()) {
            debugTaskbarManager("cannot get mPrimaryWindowManager", displayId);
            return mPrimaryWindowManager;
        }

        Context externalDisplayContext = getWindowContext(displayId);
        if (externalDisplayContext == null) {
            debugTaskbarManager("cannot get externalDisplayContext", displayId);
            return null;
        }

        return externalDisplayContext.getSystemService(WindowManager.class);
    }

    /**
     * Adds the window context {@link Context} to taskbar map, mapped to display ID.
     *
     * @param displayId The ID of the display to associate with the taskbar root layout.
     * @param windowContext The window context {@link Context} to add to the map.
     */
    private void addWindowContextToMap(int displayId, @NonNull Context windowContext) {
        if (!mWindowContexts.contains(displayId)) {
            mWindowContexts.put(displayId, windowContext);
        }
    }

    /**
     * Removes the window context {@link Context} for given display ID from the taskbar map.
     *
     * @param displayId The ID of the display for which to remove the taskbar root layout.
     */
    private void removeWindowContextFromMap(int displayId) {
        if (mWindowContexts.contains(displayId)) {
            mWindowContexts.delete(displayId);
        }
    }

    private int getDefaultDisplayId() {
        return mBaseContext.getDisplayId();
    }

    /** Temp logs for b/254119092. */
    public void debugTaskbarManager(String debugReason) {
        debugTaskbarManager(debugReason, getDefaultDisplayId());
    }

    /** Temp logs for b/254119092. */
    public void debugTaskbarManager(String debugReason, int displayId) {
        StringJoiner log = new StringJoiner("\n");
        log.add(debugReason + " displayId=" + displayId + " isDefaultDisplay=" + isDefaultDisplay(
                displayId));

        boolean activityTaskbarPresent = mActivity != null
                && mActivity.getDeviceProfile().isTaskbarPresent;
        // TODO (b/381113004): make this display-specific via getWindowContext()
        Context windowContext = mPrimaryWindowContext;
        if (windowContext == null) {
            log.add("window context for displayId" + displayId);
            return;
        }

        boolean contextTaskbarPresent = mUserUnlocked && LauncherAppState.getIDP(windowContext)
                .getDeviceProfile(windowContext).isTaskbarPresent;
        if (activityTaskbarPresent == contextTaskbarPresent) {
            log.add("mActivity and mWindowContext agree taskbarIsPresent=" + contextTaskbarPresent);
            Log.d(TASKBAR_NOT_DESTROYED_TAG, log.toString());
            return;
        }

        log.add("mActivity & mWindowContext device profiles have different values, add more logs.");

        log.add("\tmActivity logs:");
        log.add("\t\tmActivity=" + mActivity);
        if (mActivity != null) {
            log.add("\t\tmActivity.getResources().getConfiguration()="
                    + mActivity.getResources().getConfiguration());
            log.add("\t\tmActivity.getDeviceProfile().isTaskbarPresent="
                    + activityTaskbarPresent);
        }
        log.add("\tWindowContext logs:");
        log.add("\t\tWindowContext=" + windowContext);
        log.add("\t\tWindowContext.getResources().getConfiguration()="
                + windowContext.getResources().getConfiguration());
        if (mUserUnlocked) {
            log.add("\t\tLauncherAppState.getIDP().getDeviceProfile(mPrimaryWindowContext)"
                    + ".isTaskbarPresent=" + contextTaskbarPresent);
        } else {
            log.add("\t\tCouldn't get DeviceProfile because !mUserUnlocked");
        }

        Log.d(TASKBAR_NOT_DESTROYED_TAG, log.toString());
    }

    private final DeviceProfile.OnDeviceProfileChangeListener mDebugActivityDeviceProfileChanged =
            dp -> debugTaskbarManager("mActivity onDeviceProfileChanged");

}
