/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.quickstep;

import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_POINTER_DOWN;
import static android.view.MotionEvent.ACTION_POINTER_UP;
import static android.view.MotionEvent.ACTION_UP;

import static com.android.launcher3.Flags.enableCursorHoverStates;
import static com.android.launcher3.Flags.enableHandleDelayedGestureCallbacks;
import static com.android.launcher3.Flags.useActivityOverlay;
import static com.android.launcher3.LauncherPrefs.backedUpItem;
import static com.android.launcher3.MotionEventsUtils.isTrackpadMotionEvent;
import static com.android.launcher3.MotionEventsUtils.isTrackpadMultiFingerSwipe;
import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;
import static com.android.launcher3.util.OnboardingPrefs.HOME_BOUNCE_SEEN;
import static com.android.launcher3.util.window.WindowManagerProxy.MIN_TABLET_WIDTH;
import static com.android.quickstep.GestureState.DEFAULT_STATE;
import static com.android.quickstep.GestureState.TrackpadGestureType.getTrackpadGestureType;
import static com.android.quickstep.InputConsumer.TYPE_CURSOR_HOVER;
import static com.android.quickstep.util.ActiveGestureErrorDetector.GestureEvent.FLAG_USING_OTHER_ACTIVITY_INPUT_CONSUMER;
import static com.android.systemui.shared.system.ActivityManagerWrapper.CLOSE_SYSTEM_WINDOWS_REASON_RECENTS;
import static com.android.systemui.shared.system.QuickStepContract.KEY_EXTRA_SYSUI_PROXY;
import static com.android.systemui.shared.system.QuickStepContract.KEY_EXTRA_UNFOLD_ANIMATION_FORWARDER;
import static com.android.systemui.shared.system.QuickStepContract.KEY_EXTRA_UNLOCK_ANIMATION_CONTROLLER;
import static com.android.wm.shell.Flags.enableBubblesLongPressNavHandle;
import static com.android.wm.shell.shared.ShellSharedConstants.KEY_EXTRA_SHELL_BACK_ANIMATION;
import static com.android.wm.shell.shared.ShellSharedConstants.KEY_EXTRA_SHELL_BUBBLES;
import static com.android.wm.shell.shared.ShellSharedConstants.KEY_EXTRA_SHELL_DESKTOP_MODE;
import static com.android.wm.shell.shared.ShellSharedConstants.KEY_EXTRA_SHELL_DRAG_AND_DROP;
import static com.android.wm.shell.shared.ShellSharedConstants.KEY_EXTRA_SHELL_ONE_HANDED;
import static com.android.wm.shell.shared.ShellSharedConstants.KEY_EXTRA_SHELL_PIP;
import static com.android.wm.shell.shared.ShellSharedConstants.KEY_EXTRA_SHELL_RECENT_TASKS;
import static com.android.wm.shell.shared.ShellSharedConstants.KEY_EXTRA_SHELL_SHELL_TRANSITIONS;
import static com.android.wm.shell.shared.ShellSharedConstants.KEY_EXTRA_SHELL_SPLIT_SCREEN;
import static com.android.wm.shell.shared.ShellSharedConstants.KEY_EXTRA_SHELL_STARTING_WINDOW;

import android.app.PendingIntent;
import android.app.Service;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Trace;
import android.util.ArraySet;
import android.util.Log;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.BinderThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;

import com.android.launcher3.ConstantItem;
import com.android.launcher3.EncryptionType;
import com.android.launcher3.Flags;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.anim.AnimatedFloat;
import com.android.launcher3.provider.RestoreDbTask;
import com.android.launcher3.statehandlers.DesktopVisibilityController;
import com.android.launcher3.statemanager.StatefulActivity;
import com.android.launcher3.taskbar.TaskbarActivityContext;
import com.android.launcher3.taskbar.TaskbarManager;
import com.android.launcher3.taskbar.TaskbarNavButtonController.TaskbarNavButtonCallbacks;
import com.android.launcher3.taskbar.bubbles.BubbleControllers;
import com.android.launcher3.testing.TestLogging;
import com.android.launcher3.testing.shared.ResourceUtils;
import com.android.launcher3.testing.shared.TestProtocol;
import com.android.launcher3.util.DisplayController;
import com.android.launcher3.util.LockedUserState;
import com.android.launcher3.util.NavigationMode;
import com.android.launcher3.util.PluginManagerWrapper;
import com.android.launcher3.util.SafeCloseable;
import com.android.launcher3.util.ScreenOnTracker;
import com.android.launcher3.util.TraceHelper;
import com.android.quickstep.OverviewCommandHelper.CommandType;
import com.android.quickstep.fallback.window.RecentsWindowManager;
import com.android.quickstep.fallback.window.RecentsWindowSwipeHandler;
import com.android.quickstep.inputconsumers.AccessibilityInputConsumer;
import com.android.quickstep.inputconsumers.AssistantInputConsumer;
import com.android.quickstep.inputconsumers.BubbleBarInputConsumer;
import com.android.quickstep.inputconsumers.DeviceLockedInputConsumer;
import com.android.quickstep.inputconsumers.NavHandleLongPressInputConsumer;
import com.android.quickstep.inputconsumers.OneHandedModeInputConsumer;
import com.android.quickstep.inputconsumers.OtherActivityInputConsumer;
import com.android.quickstep.inputconsumers.OverviewInputConsumer;
import com.android.quickstep.inputconsumers.OverviewWithoutFocusInputConsumer;
import com.android.quickstep.inputconsumers.ProgressDelegateInputConsumer;
import com.android.quickstep.inputconsumers.ResetGestureInputConsumer;
import com.android.quickstep.inputconsumers.ScreenPinnedInputConsumer;
import com.android.quickstep.inputconsumers.SysUiOverlayInputConsumer;
import com.android.quickstep.inputconsumers.TaskbarUnstashInputConsumer;
import com.android.quickstep.inputconsumers.TrackpadStatusBarInputConsumer;
import com.android.quickstep.util.ActiveGestureLog;
import com.android.quickstep.util.ActiveGestureLog.CompoundString;
import com.android.quickstep.util.ActiveGestureProtoLogProxy;
import com.android.quickstep.util.AssistStateManager;
import com.android.quickstep.util.AssistUtils;
import com.android.quickstep.views.RecentsViewContainer;
import com.android.systemui.shared.recents.IOverviewProxy;
import com.android.systemui.shared.recents.ISystemUiProxy;
import com.android.systemui.shared.statusbar.phone.BarTransitions;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.InputChannelCompat.InputEventReceiver;
import com.android.systemui.shared.system.InputConsumerController;
import com.android.systemui.shared.system.InputMonitorCompat;
import com.android.systemui.shared.system.QuickStepContract.SystemUiStateFlags;
import com.android.systemui.shared.system.smartspace.ISysuiUnlockAnimationController;
import com.android.systemui.unfold.progress.IUnfoldAnimation;
import com.android.wm.shell.back.IBackAnimation;
import com.android.wm.shell.bubbles.IBubbles;
import com.android.wm.shell.common.pip.IPip;
import com.android.wm.shell.desktopmode.IDesktopMode;
import com.android.wm.shell.draganddrop.IDragAndDrop;
import com.android.wm.shell.onehanded.IOneHanded;
import com.android.wm.shell.recents.IRecentTasks;
import com.android.wm.shell.shared.IShellTransitions;
import com.android.wm.shell.splitscreen.ISplitScreen;
import com.android.wm.shell.startingsurface.IStartingWindow;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Service connected by system-UI for handling touch interaction.
 */
public class TouchInteractionService extends Service {

    private static final String SUBSTRING_PREFIX = "; ";
    private static final String NEWLINE_PREFIX = "\n\t\t\t-> ";

    private static final String TAG = "TouchInteractionService";

    private static final ConstantItem<Boolean> HAS_ENABLED_QUICKSTEP_ONCE = backedUpItem(
            "launcher.has_enabled_quickstep_once", false, EncryptionType.ENCRYPTED);

    private final TISBinder mTISBinder = new TISBinder(this);

    /**
     * Local IOverviewProxy implementation with some methods for local components
     */
    public static class TISBinder extends IOverviewProxy.Stub {

        private final WeakReference<TouchInteractionService> mTis;

        @Nullable private Runnable mOnOverviewTargetChangeListener = null;

        private TISBinder(TouchInteractionService tis) {
            mTis = new WeakReference<>(tis);
        }

        @BinderThread
        public void onInitialize(Bundle bundle) {
            ISystemUiProxy proxy = ISystemUiProxy.Stub.asInterface(
                    bundle.getBinder(KEY_EXTRA_SYSUI_PROXY));
            IPip pip = IPip.Stub.asInterface(bundle.getBinder(KEY_EXTRA_SHELL_PIP));
            IBubbles bubbles = IBubbles.Stub.asInterface(bundle.getBinder(KEY_EXTRA_SHELL_BUBBLES));
            ISplitScreen splitscreen = ISplitScreen.Stub.asInterface(bundle.getBinder(
                    KEY_EXTRA_SHELL_SPLIT_SCREEN));
            IOneHanded onehanded = IOneHanded.Stub.asInterface(
                    bundle.getBinder(KEY_EXTRA_SHELL_ONE_HANDED));
            IShellTransitions shellTransitions = IShellTransitions.Stub.asInterface(
                    bundle.getBinder(KEY_EXTRA_SHELL_SHELL_TRANSITIONS));
            IStartingWindow startingWindow = IStartingWindow.Stub.asInterface(
                    bundle.getBinder(KEY_EXTRA_SHELL_STARTING_WINDOW));
            ISysuiUnlockAnimationController launcherUnlockAnimationController =
                    ISysuiUnlockAnimationController.Stub.asInterface(
                            bundle.getBinder(KEY_EXTRA_UNLOCK_ANIMATION_CONTROLLER));
            IRecentTasks recentTasks = IRecentTasks.Stub.asInterface(
                    bundle.getBinder(KEY_EXTRA_SHELL_RECENT_TASKS));
            IBackAnimation backAnimation = IBackAnimation.Stub.asInterface(
                    bundle.getBinder(KEY_EXTRA_SHELL_BACK_ANIMATION));
            IDesktopMode desktopMode = IDesktopMode.Stub.asInterface(
                    bundle.getBinder(KEY_EXTRA_SHELL_DESKTOP_MODE));
            IUnfoldAnimation unfoldTransition = IUnfoldAnimation.Stub.asInterface(
                    bundle.getBinder(KEY_EXTRA_UNFOLD_ANIMATION_FORWARDER));
            IDragAndDrop dragAndDrop = IDragAndDrop.Stub.asInterface(
                    bundle.getBinder(KEY_EXTRA_SHELL_DRAG_AND_DROP));
            MAIN_EXECUTOR.execute(() -> executeForTouchInteractionService(tis -> {
                SystemUiProxy.INSTANCE.get(tis).setProxy(proxy, pip,
                        bubbles, splitscreen, onehanded, shellTransitions, startingWindow,
                        recentTasks, launcherUnlockAnimationController, backAnimation, desktopMode,
                        unfoldTransition, dragAndDrop);
                tis.initInputMonitor("TISBinder#onInitialize()");
                tis.preloadOverview(true /* fromInit */);
            }));
            sIsInitialized = true;
        }

        @BinderThread
        @Override
        public void onTaskbarToggled() {
            MAIN_EXECUTOR.execute(() -> executeForTouchInteractionService(tis -> {
                TaskbarActivityContext activityContext =
                        tis.mTaskbarManager.getCurrentActivityContext();

                if (activityContext != null) {
                    activityContext.toggleTaskbarStash();
                }
            }));
        }

        @BinderThread
        public void onOverviewToggle() {
            TestLogging.recordEvent(TestProtocol.SEQUENCE_MAIN, "onOverviewToggle");
            executeForTouchInteractionService(tis -> {
                // If currently screen pinning, do not enter overview
                if (tis.mDeviceState.isScreenPinningActive()) {
                    return;
                }
                TaskUtils.closeSystemWindowsAsync(CLOSE_SYSTEM_WINDOWS_REASON_RECENTS);
                tis.mOverviewCommandHelper.addCommand(CommandType.TOGGLE);
            });
        }

        @BinderThread
        @Override
        public void onOverviewShown(boolean triggeredFromAltTab) {
            executeForTouchInteractionService(tis -> {
                if (triggeredFromAltTab) {
                    TaskUtils.closeSystemWindowsAsync(CLOSE_SYSTEM_WINDOWS_REASON_RECENTS);
                    tis.mOverviewCommandHelper.addCommand(CommandType.KEYBOARD_INPUT);
                } else {
                    tis.mOverviewCommandHelper.addCommand(CommandType.SHOW);
                }
            });
        }

        @BinderThread
        @Override
        public void onOverviewHidden(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
            executeForTouchInteractionService(tis -> {
                if (triggeredFromAltTab && !triggeredFromHomeKey) {
                    // onOverviewShownFromAltTab hides the overview and ends at the target app
                    tis.mOverviewCommandHelper.addCommand(CommandType.HIDE);
                }
            });
        }

        @BinderThread
        @Override
        public void onAssistantAvailable(boolean available, boolean longPressHomeEnabled) {
            MAIN_EXECUTOR.execute(() -> executeForTouchInteractionService(tis -> {
                tis.mDeviceState.setAssistantAvailable(available);
                tis.onAssistantVisibilityChanged();
                executeForTaskbarManager(taskbarManager -> taskbarManager
                        .onLongPressHomeEnabled(longPressHomeEnabled));
            }));
        }

        @BinderThread
        @Override
        public void onAssistantVisibilityChanged(float visibility) {
            MAIN_EXECUTOR.execute(() -> executeForTouchInteractionService(tis -> {
                tis.mDeviceState.setAssistantVisibility(visibility);
                tis.onAssistantVisibilityChanged();
            }));
        }

        /**
         * Sent when the assistant has been invoked with the given type (defined in AssistManager)
         * and should be shown. This method is used if SystemUiProxy#setAssistantOverridesRequested
         * was previously called including this invocation type.
         */
        @Override
        public void onAssistantOverrideInvoked(int invocationType) {
            executeForTouchInteractionService(tis -> {
                if (!AssistUtils.newInstance(tis).tryStartAssistOverride(invocationType)) {
                    Log.w(TAG, "Failed to invoke Assist override");
                }
            });
        }

        @BinderThread
        public void onSystemUiStateChanged(@SystemUiStateFlags long stateFlags) {
            MAIN_EXECUTOR.execute(() -> executeForTouchInteractionService(tis -> {
                long lastFlags = tis.mDeviceState.getSystemUiStateFlags();
                tis.mDeviceState.setSystemUiFlags(stateFlags);
                tis.onSystemUiFlagsChanged(lastFlags);
            }));
        }

        @BinderThread
        public void onActiveNavBarRegionChanges(Region region) {
            MAIN_EXECUTOR.execute(() -> executeForTouchInteractionService(
                    tis -> tis.mDeviceState.setDeferredGestureRegion(region)));
        }

        @BinderThread
        @Override
        public void enterStageSplitFromRunningApp(boolean leftOrTop) {
            executeForTouchInteractionService(tis -> {
                RecentsViewContainer container = tis.mOverviewComponentObserver
                        .getContainerInterface().getCreatedContainer();
                if (container != null) {
                    container.enterStageSplitFromRunningApp(leftOrTop);
                }
            });
        }

        @BinderThread
        @Override
        public void updateWallpaperVisibility(int displayId, boolean visible) {
            MAIN_EXECUTOR.execute(() -> executeForTouchInteractionService(tis ->
                    executeForTaskbarManager(
                            taskbarManager -> taskbarManager.setWallpaperVisible(visible))
            ));
        }

        @BinderThread
        @Override
        public void checkNavBarModes() {
            MAIN_EXECUTOR.execute(() -> executeForTouchInteractionService(tis ->
                    executeForTaskbarManager(TaskbarManager::checkNavBarModes)
            ));
        }

        @BinderThread
        @Override
        public void finishBarAnimations() {
            MAIN_EXECUTOR.execute(() -> executeForTouchInteractionService(tis ->
                    executeForTaskbarManager(TaskbarManager::finishBarAnimations)
            ));
        }

        @BinderThread
        @Override
        public void touchAutoDim(boolean reset) {
            MAIN_EXECUTOR.execute(() -> executeForTouchInteractionService(tis ->
                    executeForTaskbarManager(taskbarManager -> taskbarManager.touchAutoDim(reset))
            ));
        }

        @BinderThread
        @Override
        public void transitionTo(@BarTransitions.TransitionMode int barMode,
                boolean animate) {
            MAIN_EXECUTOR.execute(() -> executeForTouchInteractionService(tis ->
                    executeForTaskbarManager(
                            taskbarManager -> taskbarManager.transitionTo(barMode, animate))
            ));
        }

        @BinderThread
        @Override
        public void appTransitionPending(boolean pending) {
            MAIN_EXECUTOR.execute(() -> executeForTouchInteractionService(tis ->
                    executeForTaskbarManager(
                            taskbarManager -> taskbarManager.appTransitionPending(pending))
            ));
        }

        /**
         * Preloads the Overview activity.
         * <p>
         * This method should only be used when the All Set page of the SUW is reached to safely
         * preload the Launcher for the SUW first reveal.
         */
        public void preloadOverviewForSUWAllSet() {
            executeForTouchInteractionService(tis -> tis.preloadOverview(false, true));
        }

        @Override
        public void onRotationProposal(int rotation, boolean isValid) {
            executeForTaskbarManager(taskbarManager ->
                    taskbarManager.onRotationProposal(rotation, isValid));
        }

        @Override
        public void disable(int displayId, int state1, int state2, boolean animate) {
            executeForTaskbarManager(taskbarManager ->
                    taskbarManager.disableNavBarElements(displayId, state1, state2, animate));
        }

        @Override
        public void onSystemBarAttributesChanged(int displayId, int behavior) {
            executeForTaskbarManager(taskbarManager ->
                    taskbarManager.onSystemBarAttributesChanged(displayId, behavior));
        }

        @Override
        public void onTransitionModeUpdated(int barMode, boolean checkBarModes) {
            executeForTaskbarManager(taskbarManager ->
                    taskbarManager.onTransitionModeUpdated(barMode, checkBarModes));
        }

        @Override
        public void onNavButtonsDarkIntensityChanged(float darkIntensity) {
            executeForTaskbarManager(taskbarManager ->
                    taskbarManager.onNavButtonsDarkIntensityChanged(darkIntensity));
        }

        @Override
        public void onNavigationBarLumaSamplingEnabled(int displayId, boolean enable) {
            executeForTaskbarManager(taskbarManager ->
                    taskbarManager.onNavigationBarLumaSamplingEnabled(displayId, enable));
        }

        private void executeForTouchInteractionService(
                @NonNull Consumer<TouchInteractionService> tisConsumer) {
            TouchInteractionService tis = mTis.get();
            if (tis == null) return;
            tisConsumer.accept(tis);
        }

        private void executeForTaskbarManager(
                @NonNull Consumer<TaskbarManager> taskbarManagerConsumer) {
            MAIN_EXECUTOR.execute(() -> executeForTouchInteractionService(tis -> {
                TaskbarManager taskbarManager = tis.mTaskbarManager;
                if (taskbarManager == null) return;
                taskbarManagerConsumer.accept(taskbarManager);
            }));
        }

        /**
         * Returns the {@link TaskbarManager}.
         * <p>
         * Returns {@code null} if TouchInteractionService is not connected
         */
        @Nullable
        public TaskbarManager getTaskbarManager() {
            TouchInteractionService tis = mTis.get();
            if (tis == null) return null;
            return tis.mTaskbarManager;
        }

        /**
         * Returns the {@link DesktopVisibilityController}
         * <p>
         * Returns {@code null} if TouchInteractionService is not connected
         */
        @Nullable
        public DesktopVisibilityController getDesktopVisibilityController() {
            TouchInteractionService tis = mTis.get();
            if (tis == null) return null;
            return tis.mDesktopVisibilityController;
        }

        @VisibleForTesting
        public void injectFakeTrackpadForTesting() {
            TouchInteractionService tis = mTis.get();
            if (tis == null) return;
            tis.mTrackpadsConnected.add(1000);
            tis.initInputMonitor("tapl testing");
        }

        @VisibleForTesting
        public void ejectFakeTrackpadForTesting() {
            TouchInteractionService tis = mTis.get();
            if (tis == null) return;
            tis.mTrackpadsConnected.clear();
            // This method destroys the current input monitor if set up, and only init a new one
            // in 3-button mode if {@code mTrackpadsConnected} is not empty. So in other words,
            // it will destroy the input monitor.
            tis.initInputMonitor("tapl testing");
        }

        /**
         * Sets whether a predictive back-to-home animation is in progress in the device state
         */
        public void setPredictiveBackToHomeInProgress(boolean isInProgress) {
            executeForTouchInteractionService(tis ->
                    tis.mDeviceState.setPredictiveBackToHomeInProgress(isInProgress));
        }

        /**
         * Returns the {@link OverviewCommandHelper}.
         * <p>
         * Returns {@code null} if TouchInteractionService is not connected
         */
        @Nullable
        public OverviewCommandHelper getOverviewCommandHelper() {
            TouchInteractionService tis = mTis.get();
            if (tis == null) return null;
            return tis.mOverviewCommandHelper;
        }

        /**
         * Sets a proxy to bypass swipe up behavior
         */
        public void setSwipeUpProxy(Function<GestureState, AnimatedFloat> proxy) {
            executeForTouchInteractionService(
                    tis -> tis.mSwipeUpProxyProvider = proxy != null ? proxy : (i -> null));
        }

        /**
         * Sets the task id where gestures should be blocked
         */
        public void setGestureBlockedTaskId(int taskId) {
            executeForTouchInteractionService(
                    tis -> tis.mDeviceState.setGestureBlockingTaskId(taskId));
        }

        /** Sets a listener to be run on Overview Target updates. */
        public void setOverviewTargetChangeListener(@Nullable Runnable listener) {
            mOnOverviewTargetChangeListener = listener;
        }

        protected void onOverviewTargetChange() {
            if (mOnOverviewTargetChangeListener != null) {
                mOnOverviewTargetChangeListener.run();
                mOnOverviewTargetChangeListener = null;
            }
        }

        /** Refreshes the current overview target. */
        public void refreshOverviewTarget() {
            executeForTouchInteractionService(tis -> {
                tis.mAllAppsActionManager.onDestroy();
                tis.onOverviewTargetChange(tis.mOverviewComponentObserver.isHomeAndOverviewSame());
            });
        }
    }

    private final InputManager.InputDeviceListener mInputDeviceListener =
            new InputManager.InputDeviceListener() {
                @Override
                public void onInputDeviceAdded(int deviceId) {
                    if (isTrackpadDevice(deviceId)) {
                        boolean wasEmpty = mTrackpadsConnected.isEmpty();
                        mTrackpadsConnected.add(deviceId);
                        if (wasEmpty) {
                            update();
                        }
                    }
                }

                @Override
                public void onInputDeviceChanged(int deviceId) {
                }

                @Override
                public void onInputDeviceRemoved(int deviceId) {
                    mTrackpadsConnected.remove(deviceId);
                    if (mTrackpadsConnected.isEmpty()) {
                        update();
                    }
                }

                private void update() {
                    if (mInputMonitorCompat != null && !mTrackpadsConnected.isEmpty()) {
                        // Don't destroy and reinitialize input monitor due to trackpad
                        // connecting when it's already set up.
                        return;
                    }
                    initInputMonitor("onTrackpadConnected()");
                }

                private boolean isTrackpadDevice(int deviceId) {
                    InputDevice inputDevice = mInputManager.getInputDevice(deviceId);
                    if (inputDevice == null) {
                        return false;
                    }
                    return inputDevice.getSources() == (InputDevice.SOURCE_MOUSE
                            | InputDevice.SOURCE_TOUCHPAD);
                }
            };

    private static boolean sConnected = false;
    private static boolean sIsInitialized = false;
    private RotationTouchHelper mRotationTouchHelper;

    public static boolean isConnected() {
        return sConnected;
    }

    public static boolean isInitialized() {
        return sIsInitialized;
    }

    private final AbsSwipeUpHandler.Factory mLauncherSwipeHandlerFactory =
            this::createLauncherSwipeHandler;
    private final AbsSwipeUpHandler.Factory mFallbackSwipeHandlerFactory =
            this::createFallbackSwipeHandler;
    private final AbsSwipeUpHandler.Factory mRecentsWindowSwipeHandlerFactory =
            this::createRecentsWindowSwipeHandler;

    private final ScreenOnTracker.ScreenOnListener mScreenOnListener = this::onScreenOnChanged;

    private final TaskbarNavButtonCallbacks mNavCallbacks = new TaskbarNavButtonCallbacks() {
        @Override
        public void onNavigateHome() {
            mOverviewCommandHelper.addCommand(CommandType.HOME);
        }

        @Override
        public void onToggleOverview() {
            mOverviewCommandHelper.addCommand(CommandType.TOGGLE);
        }
    };

    private ActivityManagerWrapper mAM;
    private OverviewCommandHelper mOverviewCommandHelper;
    private OverviewComponentObserver mOverviewComponentObserver;
    private InputConsumerController mInputConsumer;
    private RecentsAnimationDeviceState mDeviceState;
    private TaskAnimationManager mTaskAnimationManager;

    private @NonNull InputConsumer mUncheckedConsumer = InputConsumer.NO_OP;
    private @NonNull InputConsumer mConsumer = InputConsumer.NO_OP;
    private Choreographer mMainChoreographer;
    private @Nullable ResetGestureInputConsumer mResetGestureInputConsumer;
    private GestureState mGestureState = DEFAULT_STATE;

    private InputMonitorCompat mInputMonitorCompat;
    private InputEventReceiver mInputEventReceiver;

    private TaskbarManager mTaskbarManager;
    private RecentsWindowManager mRecentsWindowManager;
    private Function<GestureState, AnimatedFloat> mSwipeUpProxyProvider = i -> null;
    private AllAppsActionManager mAllAppsActionManager;
    private InputManager mInputManager;
    private final Set<Integer> mTrackpadsConnected = new ArraySet<>();

    private NavigationMode mGestureStartNavMode = null;

    private DesktopVisibilityController mDesktopVisibilityController;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate: user=" + getUserId()
                + " instance=" + System.identityHashCode(this));
        // Initialize anything here that is needed in direct boot mode.
        // Everything else should be initialized in onUserUnlocked() below.
        mMainChoreographer = Choreographer.getInstance();
        mAM = ActivityManagerWrapper.getInstance();
        mDeviceState = new RecentsAnimationDeviceState(this, true);
        mRotationTouchHelper = mDeviceState.getRotationTouchHelper();
        mAllAppsActionManager = new AllAppsActionManager(
                this, UI_HELPER_EXECUTOR, this::createAllAppsPendingIntent);
        mInputManager = getSystemService(InputManager.class);
        mInputManager.registerInputDeviceListener(mInputDeviceListener,
                UI_HELPER_EXECUTOR.getHandler());
        int [] inputDevices = mInputManager.getInputDeviceIds();
        for (int inputDeviceId : inputDevices) {
            mInputDeviceListener.onInputDeviceAdded(inputDeviceId);
        }
        mDesktopVisibilityController = new DesktopVisibilityController(this);
        mTaskbarManager = new TaskbarManager(
                this, mAllAppsActionManager, mNavCallbacks, mDesktopVisibilityController);
        if(Flags.enableFallbackOverviewInWindow()) {
            mRecentsWindowManager = new RecentsWindowManager(this);
        }
        mInputConsumer = InputConsumerController.getRecentsAnimationInputConsumer();

        // Call runOnUserUnlocked() before any other callbacks to ensure everything is initialized.
        LockedUserState.get(this).runOnUserUnlocked(this::onUserUnlocked);
        LockedUserState.get(this).runOnUserUnlocked(mTaskbarManager::onUserUnlocked);
        mDeviceState.addNavigationModeChangedCallback(this::onNavigationModeChanged);
        sConnected = true;

        ScreenOnTracker.INSTANCE.get(this).addListener(mScreenOnListener);
    }

    private void disposeEventHandlers(String reason) {
        Log.d(TAG, "disposeEventHandlers: Reason: " + reason
                + " instance=" + System.identityHashCode(this));
        if (mInputEventReceiver != null) {
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
        }
        if (mInputMonitorCompat != null) {
            mInputMonitorCompat.dispose();
            mInputMonitorCompat = null;
        }
    }

    private void initInputMonitor(String reason) {
        disposeEventHandlers("Initializing input monitor due to: " + reason);

        if (mDeviceState.isButtonNavMode()
                && !mDeviceState.supportsAssistantGestureInButtonNav()
                && (mTrackpadsConnected.isEmpty())) {
            return;
        }

        mInputMonitorCompat = new InputMonitorCompat("swipe-up", mDeviceState.getDisplayId());
        mInputEventReceiver = mInputMonitorCompat.getInputReceiver(Looper.getMainLooper(),
                mMainChoreographer, this::onInputEvent);

        mRotationTouchHelper.updateGestureTouchRegions();
    }

    /**
     * Called when the navigation mode changes, guaranteed to be after the device state has updated.
     */
    private void onNavigationModeChanged() {
        initInputMonitor("onNavigationModeChanged()");
        resetHomeBounceSeenOnQuickstepEnabledFirstTime();
    }

    @UiThread
    public void onUserUnlocked() {
        Log.d(TAG, "onUserUnlocked: userId=" + getUserId()
                + " instance=" + System.identityHashCode(this));
        mTaskAnimationManager = new TaskAnimationManager(this, mRecentsWindowManager);
        mOverviewComponentObserver = new OverviewComponentObserver(this, mDeviceState);
        mOverviewCommandHelper = new OverviewCommandHelper(this,
                mOverviewComponentObserver, mTaskAnimationManager);
        mResetGestureInputConsumer = new ResetGestureInputConsumer(
                mTaskAnimationManager, mTaskbarManager::getCurrentActivityContext);
        mInputConsumer.registerInputConsumer();
        onSystemUiFlagsChanged(mDeviceState.getSystemUiStateFlags());
        onAssistantVisibilityChanged();

        // Initialize the task tracker
        TopTaskTracker.INSTANCE.get(this);

        // Temporarily disable model preload
        // new ModelPreload().start(this);
        resetHomeBounceSeenOnQuickstepEnabledFirstTime();

        mOverviewComponentObserver.setOverviewChangeListener(this::onOverviewTargetChange);
        onOverviewTargetChange(mOverviewComponentObserver.isHomeAndOverviewSame());
    }

    public OverviewCommandHelper getOverviewCommandHelper() {
        return mOverviewCommandHelper;
    }

    private void resetHomeBounceSeenOnQuickstepEnabledFirstTime() {
        if (!LockedUserState.get(this).isUserUnlocked() || mDeviceState.isButtonNavMode()) {
            // Skip if not yet unlocked (can't read user shared prefs) or if the current navigation
            // mode doesn't have gestures
            return;
        }

        // Reset home bounce seen on quick step enabled for first time
        LauncherPrefs prefs = LauncherPrefs.get(this);
        if (!prefs.get(HAS_ENABLED_QUICKSTEP_ONCE)) {
            prefs.put(
                    HAS_ENABLED_QUICKSTEP_ONCE.to(true),
                    HOME_BOUNCE_SEEN.to(false));
        }
    }

    private void onOverviewTargetChange(boolean isHomeAndOverviewSame) {
        mAllAppsActionManager.setHomeAndOverviewSame(isHomeAndOverviewSame);
        RecentsViewContainer newOverviewContainer =
                mOverviewComponentObserver.getContainerInterface().getCreatedContainer();
        if (newOverviewContainer != null
                && newOverviewContainer instanceof StatefulActivity activity) {
            //TODO(b/368030750) refactor taskbarManager to accept RecentsViewContainer
            mTaskbarManager.setActivity(activity);
        }
        mTISBinder.onOverviewTargetChange();
    }

    private PendingIntent createAllAppsPendingIntent() {
        return new PendingIntent(new IIntentSender.Stub() {
            @Override
            public void send(int code, Intent intent, String resolvedType,
                    IBinder allowlistToken, IIntentReceiver finishedReceiver,
                    String requiredPermission, Bundle options) {
                MAIN_EXECUTOR.execute(() -> mTaskbarManager.toggleAllApps());
            }
        });
    }

    @UiThread
    private void onSystemUiFlagsChanged(@SystemUiStateFlags long lastSysUIFlags) {
        if (LockedUserState.get(this).isUserUnlocked()) {
            long systemUiStateFlags = mDeviceState.getSystemUiStateFlags();
            SystemUiProxy.INSTANCE.get(this).setLastSystemUiStateFlags(systemUiStateFlags);
            mOverviewComponentObserver.onSystemUiStateChanged();
            mTaskbarManager.onSystemUiFlagsChanged(systemUiStateFlags);
            mTaskAnimationManager.onSystemUiFlagsChanged(lastSysUIFlags, systemUiStateFlags);
        }
    }

    @UiThread
    private void onAssistantVisibilityChanged() {
        if (LockedUserState.get(this).isUserUnlocked()) {
            mOverviewComponentObserver.getContainerInterface().onAssistantVisibilityChanged(
                    mDeviceState.getAssistantVisibility());
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy: user=" + getUserId()
                + " instance=" + System.identityHashCode(this));
        sIsInitialized = false;
        if (LockedUserState.get(this).isUserUnlocked()) {
            mInputConsumer.unregisterInputConsumer();
            mOverviewComponentObserver.onDestroy();
        }
        disposeEventHandlers("TouchInteractionService onDestroy()");
        mDeviceState.destroy();
        SystemUiProxy.INSTANCE.get(this).clearProxy();

        mAllAppsActionManager.onDestroy();

        mInputManager.unregisterInputDeviceListener(mInputDeviceListener);
        mTrackpadsConnected.clear();

        mTaskbarManager.destroy();

        if (mRecentsWindowManager != null) {
            mRecentsWindowManager.destroy();
        }
        mDesktopVisibilityController.onDestroy();
        sConnected = false;

        ScreenOnTracker.INSTANCE.get(this).removeListener(mScreenOnListener);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind: user=" + getUserId()
                + " instance=" + System.identityHashCode(this));
        return mTISBinder;
    }

    protected void onScreenOnChanged(boolean isOn) {
        if (isOn) {
            return;
        }
        long currentTime = SystemClock.uptimeMillis();
        MotionEvent cancelEvent = MotionEvent.obtain(
                currentTime, currentTime, ACTION_CANCEL, 0f, 0f, 0);
        onInputEvent(cancelEvent);
        cancelEvent.recycle();
    }

    private void onInputEvent(InputEvent ev) {
        if (!(ev instanceof MotionEvent)) {
            ActiveGestureProtoLogProxy.logUnknownInputEvent(ev.toString());
            return;
        }
        MotionEvent event = (MotionEvent) ev;

        TestLogging.recordMotionEvent(
                TestProtocol.SEQUENCE_TIS, "TouchInteractionService.onInputEvent", event);

        if (!LockedUserState.get(this).isUserUnlocked()) {
            ActiveGestureProtoLogProxy.logOnInputEventUserLocked();
            return;
        }

        NavigationMode currentNavMode = mDeviceState.getMode();
        if (mGestureStartNavMode != null && mGestureStartNavMode != currentNavMode) {
            ActiveGestureProtoLogProxy.logOnInputEventNavModeSwitched(
                    mGestureStartNavMode.name(), currentNavMode.name());
            event.setAction(ACTION_CANCEL);
        } else if (mDeviceState.isButtonNavMode()
                && !mDeviceState.supportsAssistantGestureInButtonNav()
                && !isTrackpadMotionEvent(event)) {
            ActiveGestureProtoLogProxy.logOnInputEventThreeButtonNav();
            return;
        }

        final int action = event.getActionMasked();
        // Note this will create a new consumer every mouse click, as after ACTION_UP from the click
        // an ACTION_HOVER_ENTER will fire as well.
        boolean isHoverActionWithoutConsumer = enableCursorHoverStates()
                && isHoverActionWithoutConsumer(event);

        if (enableHandleDelayedGestureCallbacks()) {
            if (action == ACTION_DOWN || isHoverActionWithoutConsumer) {
                mTaskAnimationManager.notifyNewGestureStart();
            }
            if (mTaskAnimationManager.shouldIgnoreMotionEvents()) {
                if (action == ACTION_DOWN || isHoverActionWithoutConsumer) {
                    ActiveGestureProtoLogProxy.logOnInputIgnoringFollowingEvents();
                }
                return;
            }
        }

        if (action == ACTION_DOWN || isHoverActionWithoutConsumer) {
            mGestureStartNavMode = currentNavMode;
        } else if (action == ACTION_UP || action == ACTION_CANCEL) {
            mGestureStartNavMode = null;
        }

        SafeCloseable traceToken = TraceHelper.INSTANCE.allowIpcs("TIS.onInputEvent");

        CompoundString reasonString = action == ACTION_DOWN
                ? CompoundString.newEmptyString() : CompoundString.NO_OP;
        if (action == ACTION_DOWN || isHoverActionWithoutConsumer) {
            mRotationTouchHelper.setOrientationTransformIfNeeded(event);

            boolean isOneHandedModeActive = mDeviceState.isOneHandedModeActive();
            boolean isInSwipeUpTouchRegion = mRotationTouchHelper.isInSwipeUpTouchRegion(event);
            TaskbarActivityContext tac = mTaskbarManager.getCurrentActivityContext();
            BubbleControllers bubbleControllers = tac != null ? tac.getBubbleControllers() : null;
            boolean isOnBubbles = bubbleControllers != null
                    && BubbleBarInputConsumer.isEventOnBubbles(tac, event);
            if (isInSwipeUpTouchRegion && tac != null) {
                tac.closeKeyboardQuickSwitchView();
            }
            if (mDeviceState.isButtonNavMode()
                    && mDeviceState.supportsAssistantGestureInButtonNav()) {
                reasonString.append("in three button mode which supports Assistant gesture");
                // Consume gesture event for Assistant (all other gestures should do nothing).
                if (mDeviceState.canTriggerAssistantAction(event)) {
                    reasonString.append(" and event can trigger assistant action, "
                            + "consuming gesture for assistant action");
                    mGestureState =
                            createGestureState(mGestureState, getTrackpadGestureType(event));
                    mUncheckedConsumer = tryCreateAssistantInputConsumer(mGestureState, event);
                } else {
                    reasonString.append(" but event cannot trigger Assistant, "
                            + "consuming gesture as no-op");
                    mUncheckedConsumer = InputConsumer.NO_OP;
                }
            } else if ((!isOneHandedModeActive && isInSwipeUpTouchRegion)
                    || isHoverActionWithoutConsumer || isOnBubbles) {
                reasonString.append(!isOneHandedModeActive && isInSwipeUpTouchRegion
                        ? "one handed mode is not active and event is in swipe up region, "
                                + "creating new input consumer"
                        : "isHoverActionWithoutConsumer == true, creating new input consumer");
                // Clone the previous gesture state since onConsumerAboutToBeSwitched might trigger
                // onConsumerInactive and wipe the previous gesture state
                GestureState prevGestureState = new GestureState(mGestureState);
                GestureState newGestureState = createGestureState(mGestureState,
                        getTrackpadGestureType(event));
                mConsumer.onConsumerAboutToBeSwitched();
                mGestureState = newGestureState;
                mConsumer = newConsumer(prevGestureState, mGestureState, event);
                mUncheckedConsumer = mConsumer;
            } else if ((mDeviceState.isFullyGesturalNavMode() || isTrackpadMultiFingerSwipe(event))
                    && mDeviceState.canTriggerAssistantAction(event)) {
                reasonString.append(mDeviceState.isFullyGesturalNavMode()
                        ? "using fully gestural nav and event can trigger assistant action, "
                                + "consuming gesture for assistant action"
                        : "event is a trackpad multi-finger swipe and event can trigger assistant "
                                + "action, consuming gesture for assistant action");
                mGestureState = createGestureState(mGestureState, getTrackpadGestureType(event));
                // Do not change mConsumer as if there is an ongoing QuickSwitch gesture, we
                // should not interrupt it. QuickSwitch assumes that interruption can only
                // happen if the next gesture is also quick switch.
                mUncheckedConsumer = tryCreateAssistantInputConsumer(mGestureState, event);
            } else if (mDeviceState.canTriggerOneHandedAction(event)) {
                reasonString.append("event can trigger one-handed action, "
                        + "consuming gesture for one-handed action");
                // Consume gesture event for triggering one handed feature.
                mUncheckedConsumer = new OneHandedModeInputConsumer(this, mDeviceState,
                        InputConsumer.NO_OP, mInputMonitorCompat);
            } else {
                mUncheckedConsumer = InputConsumer.NO_OP;
            }
        } else {
            // Other events
            if (mUncheckedConsumer != InputConsumer.NO_OP) {
                // Only transform the event if we are handling it in a proper consumer
                mRotationTouchHelper.setOrientationTransformIfNeeded(event);
            }
        }

        if (mUncheckedConsumer != InputConsumer.NO_OP) {
            switch (action) {
                case ACTION_DOWN:
                    ActiveGestureProtoLogProxy.logOnInputEventActionDown(reasonString);
                    // fall through
                case ACTION_UP:
                    ActiveGestureProtoLogProxy.logOnInputEventActionUp(
                            (int) event.getRawX(),
                            (int) event.getRawY(),
                            action,
                            MotionEvent.classificationToString(event.getClassification()));
                    break;
                case ACTION_MOVE:
                    ActiveGestureProtoLogProxy.logOnInputEventActionMove(
                            MotionEvent.actionToString(action),
                            MotionEvent.classificationToString(event.getClassification()),
                            event.getPointerCount());
                    break;
                default: {
                    ActiveGestureProtoLogProxy.logOnInputEventGenericAction(
                            MotionEvent.actionToString(action),
                            MotionEvent.classificationToString(event.getClassification()));
                }
            }
        }

        boolean cancelGesture = mGestureState.getContainerInterface() != null
                && mGestureState.getContainerInterface().shouldCancelCurrentGesture();
        boolean cleanUpConsumer = (action == ACTION_UP || action == ACTION_CANCEL || cancelGesture)
                && mConsumer != null
                && !mConsumer.getActiveConsumerInHierarchy().isConsumerDetachedFromGesture();
        if (cancelGesture) {
            event.setAction(ACTION_CANCEL);
        }

        if (mGestureState.isTrackpadGesture() && (action == ACTION_POINTER_DOWN
                || action == ACTION_POINTER_UP)) {
            // Skip ACTION_POINTER_DOWN and ACTION_POINTER_UP events from trackpad.
        } else if (isCursorHoverEvent(event)) {
            mUncheckedConsumer.onHoverEvent(event);
        } else {
            mUncheckedConsumer.onMotionEvent(event);
        }

        if (cleanUpConsumer) {
            reset();
        }
        traceToken.close();
    }

    private boolean isHoverActionWithoutConsumer(MotionEvent event) {
        // Only process these events when taskbar is present.
        TaskbarActivityContext tac = mTaskbarManager.getCurrentActivityContext();
        boolean isTaskbarPresent = tac != null && tac.getDeviceProfile().isTaskbarPresent
                && !tac.isPhoneMode();
        return event.isHoverEvent() && (mUncheckedConsumer.getType() & TYPE_CURSOR_HOVER) == 0
                && isTaskbarPresent;
    }

    // Talkback generates hover events on touch, which we do not want to consume.
    private boolean isCursorHoverEvent(MotionEvent event) {
        return event.isHoverEvent() && event.getSource() == InputDevice.SOURCE_MOUSE;
    }

    private InputConsumer tryCreateAssistantInputConsumer(
            GestureState gestureState, MotionEvent motionEvent) {
        return tryCreateAssistantInputConsumer(
                InputConsumer.NO_OP, gestureState, motionEvent, CompoundString.NO_OP);
    }

    private InputConsumer tryCreateAssistantInputConsumer(
            InputConsumer base,
            GestureState gestureState,
            MotionEvent motionEvent,
            CompoundString reasonString) {
        if (mDeviceState.isGestureBlockedTask(gestureState.getRunningTask())) {
            reasonString.append(
                    "%sis gesture-blocked task, using base input consumer", SUBSTRING_PREFIX);
            return base;
        } else {
            reasonString.append("%susing AssistantInputConsumer", SUBSTRING_PREFIX);
            return new AssistantInputConsumer(
                    this, gestureState, base, mInputMonitorCompat, mDeviceState, motionEvent);
        }
    }

    public GestureState createGestureState(GestureState previousGestureState,
            GestureState.TrackpadGestureType trackpadGestureType) {
        final GestureState gestureState;
        TopTaskTracker.CachedTaskInfo taskInfo;
        if (mTaskAnimationManager.isRecentsAnimationRunning()) {
            gestureState = new GestureState(mOverviewComponentObserver,
                    ActiveGestureLog.INSTANCE.getLogId());
            TopTaskTracker.CachedTaskInfo previousTaskInfo = previousGestureState.getRunningTask();
            // previousTaskInfo can be null iff previousGestureState == GestureState.DEFAULT_STATE
            taskInfo = previousTaskInfo != null
                    ? previousTaskInfo
                    : TopTaskTracker.INSTANCE.get(this).getCachedTopTask(false);
            gestureState.updateRunningTask(taskInfo);
            gestureState.updateLastStartedTaskIds(previousGestureState.getLastStartedTaskIds());
            gestureState.updatePreviouslyAppearedTaskIds(
                    previousGestureState.getPreviouslyAppearedTaskIds());
        } else {
            gestureState = new GestureState(mOverviewComponentObserver,
                    ActiveGestureLog.INSTANCE.incrementLogId());
            taskInfo = TopTaskTracker.INSTANCE.get(this).getCachedTopTask(false);
            gestureState.updateRunningTask(taskInfo);
        }
        gestureState.setTrackpadGestureType(trackpadGestureType);

        // Log initial state for the gesture.
        ActiveGestureProtoLogProxy.logRunningTaskPackage(taskInfo.getPackageName());
        ActiveGestureProtoLogProxy.logSysuiStateFlags(mDeviceState.getSystemUiStateString());
        return gestureState;
    }

    private InputConsumer newConsumer(
            GestureState previousGestureState, GestureState newGestureState, MotionEvent event) {
        TaskbarActivityContext tac = mTaskbarManager.getCurrentActivityContext();
        BubbleControllers bubbleControllers = tac != null ? tac.getBubbleControllers() : null;
        if (bubbleControllers != null && BubbleBarInputConsumer.isEventOnBubbles(tac, event)) {
            InputConsumer consumer = new BubbleBarInputConsumer(this, bubbleControllers,
                    mInputMonitorCompat);
            logInputConsumerSelectionReason(consumer, newCompoundString(
                    "event is on bubbles, creating new input consumer"));
            return consumer;
        }
        AnimatedFloat progressProxy = mSwipeUpProxyProvider.apply(mGestureState);
        if (progressProxy != null) {
            InputConsumer consumer = new ProgressDelegateInputConsumer(
                    this, mTaskAnimationManager, mGestureState, mInputMonitorCompat, progressProxy);

            logInputConsumerSelectionReason(consumer, newCompoundString(
                    "mSwipeUpProxyProvider has been set, using ProgressDelegateInputConsumer"));

            return consumer;
        }

        boolean canStartSystemGesture =
                mGestureState.isTrackpadGesture() ? mDeviceState.canStartTrackpadGesture()
                        : mDeviceState.canStartSystemGesture();

        if (!LockedUserState.get(this).isUserUnlocked()) {
            CompoundString reasonString = newCompoundString("device locked");
            InputConsumer consumer;
            if (canStartSystemGesture) {
                // This handles apps launched in direct boot mode (e.g. dialer) as well as apps
                // launched while device is locked even after exiting direct boot mode (e.g. camera).
                consumer = createDeviceLockedInputConsumer(
                        newGestureState,
                        reasonString.append("%scan start system gesture", SUBSTRING_PREFIX));
            } else {
                consumer = getDefaultInputConsumer(
                        reasonString.append("%scannot start system gesture", SUBSTRING_PREFIX));
            }
            logInputConsumerSelectionReason(consumer, reasonString);
            return consumer;
        }

        CompoundString reasonString;
        InputConsumer base;
        // When there is an existing recents animation running, bypass systemState check as this is
        // a followup gesture and the first gesture started in a valid system state.
        if (canStartSystemGesture || previousGestureState.isRecentsAnimationRunning()) {
            reasonString = newCompoundString(canStartSystemGesture
                    ? "can start system gesture, trying to use base consumer"
                    : "recents animation was running, trying to use base consumer");
            base = newBaseConsumer(previousGestureState, newGestureState, event, reasonString);
        } else {
            reasonString = newCompoundString("cannot start system gesture and recents "
                    + "animation was not running, trying to use default input consumer");
            base = getDefaultInputConsumer(reasonString);
        }
        if (mDeviceState.isGesturalNavMode() || newGestureState.isTrackpadGesture()) {
            handleOrientationSetup(base);
        }
        if (mDeviceState.isFullyGesturalNavMode() || newGestureState.isTrackpadGesture()) {
            String reasonPrefix =
                    "device is in gesture navigation mode or 3-button mode with a trackpad gesture";
            if (mDeviceState.canTriggerAssistantAction(event)) {
                reasonString.append("%s%s%sgesture can trigger the assistant, "
                                + "trying to use assistant input consumer",
                        NEWLINE_PREFIX,
                        reasonPrefix,
                        SUBSTRING_PREFIX);
                base = tryCreateAssistantInputConsumer(base, newGestureState, event, reasonString);
            }

            // If Taskbar is present, we listen for swipe or cursor hover events to unstash it.
            if (tac != null && !(base instanceof AssistantInputConsumer)) {
                // Present always on large screen or on small screen w/ flag
                boolean useTaskbarConsumer = tac.getDeviceProfile().isTaskbarPresent
                        && !tac.isPhoneMode()
                        && !tac.isInStashedLauncherState();
                if (canStartSystemGesture && useTaskbarConsumer) {
                    reasonString.append("%s%s%sTaskbarActivityContext != null, "
                                    + "using TaskbarUnstashInputConsumer",
                            NEWLINE_PREFIX,
                            reasonPrefix,
                            SUBSTRING_PREFIX);
                    base = new TaskbarUnstashInputConsumer(this, base, mInputMonitorCompat, tac,
                            mOverviewCommandHelper, mGestureState);
                }
            }
            if (enableBubblesLongPressNavHandle()) {
                // Create bubbles input consumer before NavHandleLongPressInputConsumer.
                // This allows for nav handle to fall back to bubbles.
                if (mDeviceState.isBubblesExpanded()) {
                    reasonString = newCompoundString(reasonPrefix).append(
                            "%sbubbles expanded, trying to use default input consumer",
                            SUBSTRING_PREFIX);
                    // Bubbles can handle home gesture itself.
                    base = getDefaultInputConsumer(reasonString);
                }
            }

            NavHandle navHandle = tac != null ? tac.getNavHandle()
                    : SystemUiProxy.INSTANCE.get(this);
            if (canStartSystemGesture && !previousGestureState.isRecentsAnimationRunning()
                    && navHandle.canNavHandleBeLongPressed()
                    && !ignoreThreeFingerTrackpadForNavHandleLongPress(mGestureState)) {
                reasonString.append("%s%s%sNot running recents animation, ",
                                NEWLINE_PREFIX,
                                reasonPrefix,
                                SUBSTRING_PREFIX);
                if (tac != null && tac.getNavHandle().canNavHandleBeLongPressed()) {
                    reasonString.append("stashed handle is long-pressable, ");
                }
                reasonString.append("using NavHandleLongPressInputConsumer");
                base = new NavHandleLongPressInputConsumer(this, base, mInputMonitorCompat,
                        mDeviceState, navHandle, mGestureState);
            }

            if (!enableBubblesLongPressNavHandle()) {
                // Continue overriding nav handle input consumer with bubbles
                if (mDeviceState.isBubblesExpanded()) {
                    reasonString = newCompoundString(reasonPrefix).append(
                            "%sbubbles expanded, trying to use default input consumer",
                            SUBSTRING_PREFIX);
                    // Bubbles can handle home gesture itself.
                    base = getDefaultInputConsumer(reasonString);
                }
            }

            if (mDeviceState.isSystemUiDialogShowing()) {
                reasonString = newCompoundString(reasonPrefix).append(
                        "%ssystem dialog is showing, using SysUiOverlayInputConsumer",
                        SUBSTRING_PREFIX);
                base = new SysUiOverlayInputConsumer(
                        getBaseContext(), mDeviceState, mInputMonitorCompat);
            }

            if (mGestureState.isTrackpadGesture()
                    && canStartSystemGesture && !previousGestureState.isRecentsAnimationRunning()) {
                reasonString = newCompoundString(reasonPrefix).append(
                        "%sTrackpad 3-finger gesture, using TrackpadStatusBarInputConsumer",
                        SUBSTRING_PREFIX);
                base = new TrackpadStatusBarInputConsumer(getBaseContext(), base,
                        mInputMonitorCompat);
            }

            if (mDeviceState.isScreenPinningActive()) {
                reasonString = newCompoundString(reasonPrefix).append(
                        "%sscreen pinning is active, using ScreenPinnedInputConsumer",
                        SUBSTRING_PREFIX);
                // Note: we only allow accessibility to wrap this, and it replaces the previous
                // base input consumer (which should be NO_OP anyway since topTaskLocked == true).
                base = new ScreenPinnedInputConsumer(this, newGestureState);
            }

            if (mDeviceState.canTriggerOneHandedAction(event)) {
                reasonString.append("%s%s%sgesture can trigger one handed mode, "
                                + "using OneHandedModeInputConsumer",
                        NEWLINE_PREFIX,
                        reasonPrefix,
                        SUBSTRING_PREFIX);
                base = new OneHandedModeInputConsumer(
                        this, mDeviceState, base, mInputMonitorCompat);
            }

            if (mDeviceState.isAccessibilityMenuAvailable()) {
                reasonString.append(
                        "%s%s%saccessibility menu is available, using AccessibilityInputConsumer",
                        NEWLINE_PREFIX,
                        reasonPrefix,
                        SUBSTRING_PREFIX);
                base = new AccessibilityInputConsumer(
                        this, mDeviceState, mGestureState, base, mInputMonitorCompat);
            }
        } else {
            String reasonPrefix = "device is not in gesture navigation mode";
            if (mDeviceState.isScreenPinningActive()) {
                reasonString = newCompoundString(reasonPrefix).append(
                        "%sscreen pinning is active, trying to use default input consumer",
                        SUBSTRING_PREFIX);
                base = getDefaultInputConsumer(reasonString);
            }

            if (mDeviceState.canTriggerOneHandedAction(event)) {
                reasonString.append("%s%s%sgesture can trigger one handed mode, "
                                + "using OneHandedModeInputConsumer",
                        NEWLINE_PREFIX,
                        reasonPrefix,
                        SUBSTRING_PREFIX);
                base = new OneHandedModeInputConsumer(
                        this, mDeviceState, base, mInputMonitorCompat);
            }
        }
        logInputConsumerSelectionReason(base, reasonString);
        return base;
    }

    private CompoundString newCompoundString(String substring) {
        return new CompoundString("%s%s", NEWLINE_PREFIX, substring);
    }

    private boolean ignoreThreeFingerTrackpadForNavHandleLongPress(GestureState gestureState) {
        return Flags.ignoreThreeFingerTrackpadForNavHandleLongPress()
                && gestureState.isThreeFingerTrackpadGesture();
    }

    private void logInputConsumerSelectionReason(
            InputConsumer consumer, CompoundString reasonString) {
        ActiveGestureProtoLogProxy.logSetInputConsumer(consumer.getName(), reasonString.toString());
        if ((consumer.getType() & InputConsumer.TYPE_OTHER_ACTIVITY) != 0) {
            ActiveGestureLog.INSTANCE.trackEvent(FLAG_USING_OTHER_ACTIVITY_INPUT_CONSUMER);
        }
    }

    private void handleOrientationSetup(InputConsumer baseInputConsumer) {
        baseInputConsumer.notifyOrientationSetup();
    }

    private InputConsumer newBaseConsumer(
            GestureState previousGestureState,
            GestureState gestureState,
            MotionEvent event,
            CompoundString reasonString) {
        if (mDeviceState.isKeyguardShowingOccluded()) {
            // This handles apps showing over the lockscreen (e.g. camera)
            return createDeviceLockedInputConsumer(gestureState, reasonString.append(
                    "%skeyguard is showing occluded, trying to use device locked input consumer",
                    SUBSTRING_PREFIX));
        }

        reasonString.append("%skeyguard is not showing occluded", SUBSTRING_PREFIX);

        TopTaskTracker.CachedTaskInfo runningTask = gestureState.getRunningTask();
        // Use overview input consumer for sharesheets on top of home.
        boolean forceOverviewInputConsumer = gestureState.getContainerInterface().isStarted()
                && runningTask != null
                && runningTask.isRootChooseActivity();

        // In the case where we are in an excluded, translucent overlay, ignore it and treat the
        // running activity as the task behind the overlay.
        TopTaskTracker.CachedTaskInfo otherVisibleTask = runningTask == null
                ? null
                : runningTask.getVisibleNonExcludedTask();
        if (otherVisibleTask != null) {
            ActiveGestureProtoLogProxy.logUpdateGestureStateRunningTask(
                    otherVisibleTask.getPackageName(), runningTask.getPackageName());
            gestureState.updateRunningTask(otherVisibleTask);
        }

        boolean previousGestureAnimatedToLauncher =
                previousGestureState.isRunningAnimationToLauncher()
                        || mDeviceState.isPredictiveBackToHomeInProgress();
        // with shell-transitions, home is resumed during recents animation, so
        // explicitly check against recents animation too.
        boolean launcherResumedThroughShellTransition =
                gestureState.getContainerInterface().isResumed()
                        && !previousGestureState.isRecentsAnimationRunning();
        // If a task fragment within Launcher is resumed
        boolean launcherChildActivityResumed = useActivityOverlay()
                && runningTask != null
                && runningTask.isHomeTask()
                && mOverviewComponentObserver.isHomeAndOverviewSame()
                && !launcherResumedThroughShellTransition
                && !previousGestureState.isRecentsAnimationRunning();

        if (gestureState.getContainerInterface().isInLiveTileMode()) {
            return createOverviewInputConsumer(
                    previousGestureState,
                    gestureState,
                    event,
                    forceOverviewInputConsumer,
                    reasonString.append(
                            "%sis in live tile mode, trying to use overview input consumer",
                            SUBSTRING_PREFIX));
        } else if (runningTask == null) {
            return getDefaultInputConsumer(reasonString.append(
                    "%srunning task == null", SUBSTRING_PREFIX));
        } else if (previousGestureAnimatedToLauncher
                || launcherResumedThroughShellTransition
                || forceOverviewInputConsumer) {
            return createOverviewInputConsumer(
                    previousGestureState,
                    gestureState,
                    event,
                    forceOverviewInputConsumer,
                    reasonString.append(previousGestureAnimatedToLauncher
                                    ? "%sprevious gesture animated to launcher, "
                                            + "trying to use overview input consumer"
                                    : (launcherResumedThroughShellTransition
                                            ? "%slauncher resumed through a shell transition, "
                                                    + "trying to use overview input consumer"
                                            : "%sforceOverviewInputConsumer == true, "
                                                    + "trying to use overview input consumer"),
                            SUBSTRING_PREFIX));
        } else if (mDeviceState.isGestureBlockedTask(runningTask) || launcherChildActivityResumed) {
            return getDefaultInputConsumer(reasonString.append(launcherChildActivityResumed
                    ? "%sis launcher child-task, trying to use default input consumer"
                    : "%sis gesture-blocked task, trying to use default input consumer",
                    SUBSTRING_PREFIX));
        } else {
            reasonString.append("%susing OtherActivityInputConsumer", SUBSTRING_PREFIX);
            return createOtherActivityInputConsumer(gestureState, event);
        }
    }

    public AbsSwipeUpHandler.Factory getSwipeUpHandlerFactory() {
        return mOverviewComponentObserver.isHomeAndOverviewSame()
                ? mLauncherSwipeHandlerFactory : (Flags.enableFallbackOverviewInWindow()
                ? mRecentsWindowSwipeHandlerFactory : mFallbackSwipeHandlerFactory);
    }

    private InputConsumer createOtherActivityInputConsumer(GestureState gestureState,
            MotionEvent event) {

        final AbsSwipeUpHandler.Factory factory = getSwipeUpHandlerFactory();
        final boolean shouldDefer = !mOverviewComponentObserver.isHomeAndOverviewSame()
                || gestureState.getContainerInterface().deferStartingActivity(mDeviceState, event);
        final boolean disableHorizontalSwipe = mDeviceState.isInExclusionRegion(event);
        return new OtherActivityInputConsumer(this, mDeviceState, mTaskAnimationManager,
                gestureState, shouldDefer, this::onConsumerInactive,
                mInputMonitorCompat, mInputEventReceiver, disableHorizontalSwipe, factory);
    }

    private InputConsumer createDeviceLockedInputConsumer(
            GestureState gestureState, CompoundString reasonString) {
        if ((mDeviceState.isFullyGesturalNavMode() || gestureState.isTrackpadGesture())
                && gestureState.getRunningTask() != null) {
            reasonString.append("%sdevice is in gesture nav mode or 3-button mode with a trackpad "
                    + "gesture and running task != null, using DeviceLockedInputConsumer",
                    SUBSTRING_PREFIX);
            return new DeviceLockedInputConsumer(
                    this, mDeviceState, mTaskAnimationManager, gestureState, mInputMonitorCompat);
        } else {
            return getDefaultInputConsumer(reasonString.append(
                    mDeviceState.isFullyGesturalNavMode() || gestureState.isTrackpadGesture()
                            ? "%srunning task == null, trying to use default input consumer"
                            : "%sdevice is not in gesture nav mode and it's not a trackpad gesture,"
                                    + " trying to use default input consumer",
                    SUBSTRING_PREFIX));
        }
    }

    public InputConsumer createOverviewInputConsumer(
            GestureState previousGestureState,
            GestureState gestureState,
            MotionEvent event,
            boolean forceOverviewInputConsumer,
            CompoundString reasonString) {
        RecentsViewContainer container = gestureState.getContainerInterface().getCreatedContainer();
        if (container == null) {
            return getDefaultInputConsumer(reasonString.append(
                    "%sactivity == null, trying to use default input consumer", SUBSTRING_PREFIX));
        }

        View rootview = container.getRootView();
        boolean hasWindowFocus = rootview != null && rootview.hasWindowFocus();
        boolean isPreviousGestureAnimatingToLauncher =
                previousGestureState.isRunningAnimationToLauncher()
                        || mDeviceState.isPredictiveBackToHomeInProgress();
        boolean isInLiveTileMode = gestureState.getContainerInterface().isInLiveTileMode();

        reasonString.append(hasWindowFocus
                ? "%sactivity has window focus"
                : (isPreviousGestureAnimatingToLauncher
                        ? "%sprevious gesture is still animating to launcher"
                        : isInLiveTileMode
                                ? "%sdevice is in live mode"
                                : "%sall overview focus conditions failed"), SUBSTRING_PREFIX);
        if (hasWindowFocus
                || isPreviousGestureAnimatingToLauncher
                || isInLiveTileMode) {
            reasonString.append(
                    "%soverview should have focus, using OverviewInputConsumer", SUBSTRING_PREFIX);
            return new OverviewInputConsumer(gestureState, container, mInputMonitorCompat,
                    false /* startingInActivityBounds */);
        } else {
            reasonString.append(
                    "%soverview shouldn't have focus, using OverviewWithoutFocusInputConsumer",
                    SUBSTRING_PREFIX);
            final boolean disableHorizontalSwipe = mDeviceState.isInExclusionRegion(event);
            return new OverviewWithoutFocusInputConsumer(container.asContext(), mDeviceState,
                    gestureState, mInputMonitorCompat, disableHorizontalSwipe);
        }
    }

    /**
     * To be called by the consumer when it's no longer active. This can be called by any consumer
     * in the hierarchy at any point during the gesture (ie. if a delegate consumer starts
     * intercepting touches, the base consumer can try to call this).
     */
    private void onConsumerInactive(InputConsumer caller) {
        if (mConsumer != null && mConsumer.getActiveConsumerInHierarchy() == caller) {
            reset();
        }
    }

    private void reset() {
        mConsumer = mUncheckedConsumer = getDefaultInputConsumer();
        mGestureState = DEFAULT_STATE;
        // By default, use batching of the input events, but check receiver before using in the rare
        // case that the monitor was disposed before the swipe settled
        if (mInputEventReceiver != null) {
            mInputEventReceiver.setBatchingEnabled(true);
        }
    }

    private @NonNull InputConsumer getDefaultInputConsumer() {
        return getDefaultInputConsumer(CompoundString.NO_OP);
    }

    /**
     * Returns the {@link ResetGestureInputConsumer} if user is unlocked, else NO_OP.
     */
    private @NonNull InputConsumer getDefaultInputConsumer(@NonNull CompoundString reasonString) {
        if (mResetGestureInputConsumer != null) {
            reasonString.append(
                    "%smResetGestureInputConsumer initialized, using ResetGestureInputConsumer",
                    SUBSTRING_PREFIX);
            return mResetGestureInputConsumer;
        } else {
            reasonString.append(
                    "%smResetGestureInputConsumer not initialized, using no-op input consumer",
                    SUBSTRING_PREFIX);
            // mResetGestureInputConsumer isn't initialized until onUserUnlocked(), so reset to
            // NO_OP until then (we never want these to be null).
            return InputConsumer.NO_OP;
        }
    }

    private void preloadOverview(boolean fromInit) {
        Trace.beginSection("preloadOverview(fromInit=" + fromInit + ")");
        preloadOverview(fromInit, false);
        Trace.endSection();
    }

    private void preloadOverview(boolean fromInit, boolean forSUWAllSet) {
        if (!LockedUserState.get(this).isUserUnlocked()) {
            return;
        }

        if (mDeviceState.isButtonNavMode() && !mOverviewComponentObserver.isHomeAndOverviewSame()) {
            // Prevent the overview from being started before the real home on first boot.
            return;
        }

        if ((RestoreDbTask.isPending(this) && !forSUWAllSet)
                || !mDeviceState.isUserSetupComplete()) {
            // Preloading while a restore is pending may cause launcher to start the restore
            // too early.
            return;
        }

        final BaseContainerInterface containerInterface =
                mOverviewComponentObserver.getContainerInterface();
        final Intent overviewIntent = new Intent(
                mOverviewComponentObserver.getOverviewIntentIgnoreSysUiState());
        if (containerInterface.getCreatedContainer() != null && fromInit) {
            // The activity has been created before the initialization of overview service. It is
            // usually happens when booting or launcher is the top activity, so we should already
            // have the latest state.
            return;
        }

        // TODO(b/258022658): Remove temporary logging.
        Log.i(TAG, "preloadOverview: forSUWAllSet=" + forSUWAllSet
                + ", isHomeAndOverviewSame=" + mOverviewComponentObserver.isHomeAndOverviewSame());
        ActiveGestureProtoLogProxy.logPreloadRecentsAnimation();
        mTaskAnimationManager.preloadRecentsAnimation(overviewIntent);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (!LockedUserState.get(this).isUserUnlocked()) {
            return;
        }
        final BaseContainerInterface containerInterface =
                mOverviewComponentObserver.getContainerInterface();
        final RecentsViewContainer container = containerInterface.getCreatedContainer();
        if (container == null || container.isStarted()) {
            // We only care about the existing background activity.
            return;
        }
        Configuration oldConfig = container.asContext().getResources().getConfiguration();
        boolean isFoldUnfold = isTablet(oldConfig) != isTablet(newConfig);
        if (!isFoldUnfold && mOverviewComponentObserver.canHandleConfigChanges(
                container.getComponentName(),
                container.asContext().getResources().getConfiguration().diff(newConfig))) {
            // Since navBar gestural height are different between portrait and landscape,
            // can handle orientation changes and refresh navigation gestural region through
            // onOneHandedModeChanged()
            int newGesturalHeight = ResourceUtils.getNavbarSize(
                    ResourceUtils.NAVBAR_BOTTOM_GESTURE_SIZE,
                    getApplicationContext().getResources());
            mDeviceState.onOneHandedModeChanged(newGesturalHeight);
            return;
        }

        preloadOverview(false /* fromInit */);
    }

    private static boolean isTablet(Configuration config) {
        return config.smallestScreenWidthDp >= MIN_TABLET_WIDTH;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] rawArgs) {
        // Dump everything
        if (LockedUserState.get(this).isUserUnlocked()) {
            PluginManagerWrapper.INSTANCE.get(getBaseContext()).dump(pw);
        }
        mDeviceState.dump(pw);
        if (mOverviewComponentObserver != null) {
            mOverviewComponentObserver.dump(pw);
        }
        if (mOverviewCommandHelper != null) {
            mOverviewCommandHelper.dump(pw);
        }
        if (mGestureState != null) {
            mGestureState.dump("", pw);
        }
        pw.println("Input state:");
        pw.println("\tmInputMonitorCompat=" + mInputMonitorCompat);
        pw.println("\tmInputEventReceiver=" + mInputEventReceiver);
        DisplayController.INSTANCE.get(this).dump(pw);
        pw.println("TouchState:");
        RecentsViewContainer createdOverviewContainer = mOverviewComponentObserver == null ? null
                : mOverviewComponentObserver.getContainerInterface().getCreatedContainer();
        boolean resumed = mOverviewComponentObserver != null
                && mOverviewComponentObserver.getContainerInterface().isResumed();
        pw.println("\tcreatedOverviewActivity=" + createdOverviewContainer);
        pw.println("\tresumed=" + resumed);
        pw.println("\tmConsumer=" + mConsumer.getName());
        ActiveGestureLog.INSTANCE.dump("", pw);
        RecentsModel.INSTANCE.get(this).dump("", pw);
        if (mTaskAnimationManager != null) {
            mTaskAnimationManager.dump("", pw);
        }
        if (createdOverviewContainer != null) {
            createdOverviewContainer.getDeviceProfile().dump(this, "", pw);
        }
        mTaskbarManager.dumpLogs("", pw);
        mDesktopVisibilityController.dumpLogs("", pw);
        pw.println("AssistStateManager:");
        AssistStateManager.INSTANCE.get(this).dump("\t", pw);
        SystemUiProxy.INSTANCE.get(this).dump(pw);
        DeviceConfigWrapper.get().dump("   ", pw);
    }

    private AbsSwipeUpHandler createLauncherSwipeHandler(
            GestureState gestureState, long touchTimeMs) {
        return new LauncherSwipeHandlerV2(this, mDeviceState, mTaskAnimationManager,
                gestureState, touchTimeMs, mTaskAnimationManager.isRecentsAnimationRunning(),
                mInputConsumer);
    }

    private AbsSwipeUpHandler createFallbackSwipeHandler(
            GestureState gestureState, long touchTimeMs) {
        return new FallbackSwipeHandler(this, mDeviceState, mTaskAnimationManager,
                gestureState, touchTimeMs, mTaskAnimationManager.isRecentsAnimationRunning(),
                mInputConsumer);
    }

    private AbsSwipeUpHandler createRecentsWindowSwipeHandler(
            GestureState gestureState, long touchTimeMs) {
        return new RecentsWindowSwipeHandler(this, mDeviceState, mTaskAnimationManager,
                gestureState, touchTimeMs, mTaskAnimationManager.isRecentsAnimationRunning(),
                mInputConsumer, mRecentsWindowManager);
    }
}
