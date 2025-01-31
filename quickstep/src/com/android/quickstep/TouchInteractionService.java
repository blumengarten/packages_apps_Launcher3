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
import static com.android.quickstep.InputConsumerUtils.newConsumer;
import static com.android.quickstep.InputConsumerUtils.tryCreateAssistantInputConsumer;

import android.app.PendingIntent;
import android.app.Service;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;

import com.android.launcher3.ConstantItem;
import com.android.launcher3.EncryptionType;
import com.android.launcher3.Flags;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.desktop.DesktopAppLaunchTransitionManager;
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
import com.android.launcher3.util.MSDLPlayerWrapper;
import com.android.launcher3.util.NavigationMode;
import com.android.launcher3.util.PluginManagerWrapper;
import com.android.launcher3.util.SafeCloseable;
import com.android.launcher3.util.ScreenOnTracker;
import com.android.launcher3.util.TraceHelper;
import com.android.quickstep.OverviewCommandHelper.CommandType;
import com.android.quickstep.OverviewComponentObserver.OverviewChangeListener;
import com.android.quickstep.fallback.window.RecentsDisplayModel;
import com.android.quickstep.fallback.window.RecentsWindowSwipeHandler;
import com.android.quickstep.inputconsumers.BubbleBarInputConsumer;
import com.android.quickstep.inputconsumers.OneHandedModeInputConsumer;
import com.android.quickstep.inputconsumers.ResetGestureInputConsumer;
import com.android.quickstep.util.ActiveGestureLog;
import com.android.quickstep.util.ActiveGestureLog.CompoundString;
import com.android.quickstep.util.ActiveGestureProtoLogProxy;
import com.android.quickstep.util.ActiveTrackpadList;
import com.android.quickstep.util.ActivityPreloadUtil;
import com.android.quickstep.util.ContextualSearchStateManager;
import com.android.quickstep.views.RecentsViewContainer;
import com.android.systemui.shared.system.InputChannelCompat.InputEventReceiver;
import com.android.systemui.shared.system.InputConsumerController;
import com.android.systemui.shared.system.InputMonitorCompat;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Service connected by system-UI for handling touch interaction.
 */
public class TouchInteractionService extends Service {

    private static final String SUBSTRING_PREFIX = "; ";

    private static final String TAG = "TouchInteractionService";

    private static final ConstantItem<Boolean> HAS_ENABLED_QUICKSTEP_ONCE = backedUpItem(
            "launcher.has_enabled_quickstep_once", false, EncryptionType.ENCRYPTED);

    private final TISBinder mTISBinder = new TISBinder(this);

    private RotationTouchHelper mRotationTouchHelper;

    private final AbsSwipeUpHandler.Factory mLauncherSwipeHandlerFactory =
            this::createLauncherSwipeHandler;
    private final AbsSwipeUpHandler.Factory mFallbackSwipeHandlerFactory =
            this::createFallbackSwipeHandler;
    private final AbsSwipeUpHandler.Factory mRecentsWindowSwipeHandlerFactory =
            this::createRecentsWindowSwipeHandler;
    // This needs to be a member to be queued and potentially removed later if the service is
    // destroyed before the user is unlocked
    private final Runnable mUserUnlockedRunnable = this::onUserUnlocked;

    private final ScreenOnTracker.ScreenOnListener mScreenOnListener = this::onScreenOnChanged;
    private final OverviewChangeListener mOverviewChangeListener = this::onOverviewTargetChanged;

    private final Runnable mSysUiProxyStateChangeCallback =
            () -> {
                if (SystemUiProxy.INSTANCE.get(this).isActive()) {
                    initInputMonitor("TISBinder#onInitialize()");
                }
            };

    private final TaskbarNavButtonCallbacks mNavCallbacks = new TaskbarNavButtonCallbacks() {
        @Override
        public void onNavigateHome() {
            mOverviewCommandHelper.addCommand(CommandType.HOME);
        }

        @Override
        public void onToggleOverview() {
            mOverviewCommandHelper.addCommand(CommandType.TOGGLE);
        }

        @Override
        public void onHideOverview() {
            mOverviewCommandHelper.addCommand(CommandType.HIDE);
        }
    };

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
    private AllAppsActionManager mAllAppsActionManager;
    private ActiveTrackpadList mTrackpadsConnected;

    private NavigationMode mGestureStartNavMode = null;

    private DesktopAppLaunchTransitionManager mDesktopAppLaunchTransitionManager;

    private DisplayController.DisplayInfoChangeListener mDisplayInfoChangeListener;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate: user=" + getUserId()
                + " instance=" + System.identityHashCode(this));
        // Initialize anything here that is needed in direct boot mode.
        // Everything else should be initialized in onUserUnlocked() below.
        mMainChoreographer = Choreographer.getInstance();
        mDeviceState = RecentsAnimationDeviceState.INSTANCE.get(this);
        mRotationTouchHelper = RotationTouchHelper.INSTANCE.get(this);
        mAllAppsActionManager = new AllAppsActionManager(
                this, UI_HELPER_EXECUTOR, this::createAllAppsPendingIntent);
        mTrackpadsConnected = new ActiveTrackpadList(this, () -> {
            if (mInputMonitorCompat != null && !mTrackpadsConnected.isEmpty()) {
                // Don't destroy and reinitialize input monitor due to trackpad
                // connecting when it's already set up.
                return;
            }
            initInputMonitor("onTrackpadConnected()");
        });

        mTaskbarManager = new TaskbarManager(this, mAllAppsActionManager, mNavCallbacks,
                RecentsDisplayModel.getINSTANCE().get(this));
        mDesktopAppLaunchTransitionManager =
                new DesktopAppLaunchTransitionManager(this, SystemUiProxy.INSTANCE.get(this));
        mDesktopAppLaunchTransitionManager.registerTransitions();
        mInputConsumer = InputConsumerController.getRecentsAnimationInputConsumer();

        // Call runOnUserUnlocked() before any other callbacks to ensure everything is initialized.
        LockedUserState.get(this).runOnUserUnlocked(mUserUnlockedRunnable);
        mDisplayInfoChangeListener =
                mDeviceState.addNavigationModeChangedCallback(this::onNavigationModeChanged);
        ScreenOnTracker.INSTANCE.get(this).addListener(mScreenOnListener);
        SystemUiProxy.INSTANCE.get(this).addOnStateChangeListener(mSysUiProxyStateChangeCallback);
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
        mTaskAnimationManager = new TaskAnimationManager(this, mDeviceState);
        mOverviewComponentObserver = OverviewComponentObserver.INSTANCE.get(this);
        mOverviewCommandHelper = new OverviewCommandHelper(this,
                mOverviewComponentObserver, mTaskAnimationManager,
                RecentsDisplayModel.getINSTANCE().get(this),
                SystemUiProxy.INSTANCE.get(this).getFocusState(), mTaskbarManager);
        mResetGestureInputConsumer = new ResetGestureInputConsumer(
                mTaskAnimationManager, mTaskbarManager::getCurrentActivityContext);
        mInputConsumer.registerInputConsumer();

        // Initialize the task tracker
        TopTaskTracker.INSTANCE.get(this);

        // Temporarily disable model preload
        // new ModelPreload().start(this);
        resetHomeBounceSeenOnQuickstepEnabledFirstTime();

        mOverviewComponentObserver.addOverviewChangeListener(mOverviewChangeListener);
        onOverviewTargetChanged(mOverviewComponentObserver.isHomeAndOverviewSame());

        mTaskbarManager.onUserUnlocked();
    }

    public OverviewCommandHelper getOverviewCommandHelper() {
        return mOverviewCommandHelper;
    }

    public TaskbarManager getTaskbarManager() {
        return mTaskbarManager;
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

    private void onOverviewTargetChanged(boolean isHomeAndOverviewSame) {
        mAllAppsActionManager.setHomeAndOverviewSame(isHomeAndOverviewSame);
        RecentsViewContainer newOverviewContainer =
                mOverviewComponentObserver.getContainerInterface().getCreatedContainer();
        if (newOverviewContainer != null) {
            if (newOverviewContainer instanceof StatefulActivity activity) {
                // This will also call setRecentsViewContainer() internally.
                mTaskbarManager.setActivity(activity);
            } else {
                mTaskbarManager.setRecentsViewContainer(newOverviewContainer);
            }
        }
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

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy: user=" + getUserId()
                + " instance=" + System.identityHashCode(this));
        if (LockedUserState.get(this).isUserUnlocked()) {
            mInputConsumer.unregisterInputConsumer();
            mOverviewComponentObserver.removeOverviewChangeListener(mOverviewChangeListener);
        }
        if (mTaskAnimationManager != null) {
            mTaskAnimationManager.onDestroy();
        }

        disposeEventHandlers("TouchInteractionService onDestroy()");
        SystemUiProxy.INSTANCE.get(this).clearProxy();

        mAllAppsActionManager.onDestroy();

        mTrackpadsConnected.destroy();
        mTaskbarManager.destroy();
        if (mDesktopAppLaunchTransitionManager != null) {
            mDesktopAppLaunchTransitionManager.unregisterTransitions();
        }
        mDesktopAppLaunchTransitionManager = null;
        mDeviceState.removeDisplayInfoChangeListener(mDisplayInfoChangeListener);
        LockedUserState.get(this).removeOnUserUnlockedRunnable(mUserUnlockedRunnable);
        ScreenOnTracker.INSTANCE.get(this).removeListener(mScreenOnListener);
        SystemUiProxy.INSTANCE.get(this)
                .removeOnStateChangeListener(mSysUiProxyStateChangeCallback);
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
            if (mDeviceState.isButtonNavMode()
                    && mDeviceState.supportsAssistantGestureInButtonNav()) {
                reasonString.append("in three button mode which supports Assistant gesture");
                // Consume gesture event for Assistant (all other gestures should do nothing).
                if (mDeviceState.canTriggerAssistantAction(event)) {
                    reasonString.append(" and event can trigger assistant action, "
                            + "consuming gesture for assistant action");
                    mGestureState =
                            createGestureState(mGestureState, getTrackpadGestureType(event));
                    mUncheckedConsumer = tryCreateAssistantInputConsumer(
                            this, mDeviceState, mInputMonitorCompat, mGestureState, event);
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
                mConsumer = newConsumer(
                        this,
                        mResetGestureInputConsumer,
                        mOverviewComponentObserver,
                        mDeviceState,
                        prevGestureState,
                        mGestureState,
                        mTaskAnimationManager,
                        mInputMonitorCompat,
                        getSwipeUpHandlerFactory(),
                        this::onConsumerInactive,
                        mInputEventReceiver,
                        mTaskbarManager,
                        mOverviewCommandHelper,
                        event);
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
                mUncheckedConsumer = tryCreateAssistantInputConsumer(
                        this, mDeviceState, mInputMonitorCompat, mGestureState, event);
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

    public AbsSwipeUpHandler.Factory getSwipeUpHandlerFactory() {
        boolean recentsInWindow =
                Flags.enableFallbackOverviewInWindow() || Flags.enableLauncherOverviewInWindow();
        return mOverviewComponentObserver.isHomeAndOverviewSame()
                ? mLauncherSwipeHandlerFactory : (recentsInWindow
                ? mRecentsWindowSwipeHandlerFactory : mFallbackSwipeHandlerFactory);
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

        ActivityPreloadUtil.preloadOverviewForTIS(this, false /* fromInit */);
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
        DesktopVisibilityController.INSTANCE.get(this).dumpLogs("", pw);
        pw.println("ContextualSearchStateManager:");
        ContextualSearchStateManager.INSTANCE.get(this).dump("\t", pw);
        SystemUiProxy.INSTANCE.get(this).dump(pw);
        DeviceConfigWrapper.get().dump("   ", pw);
        TopTaskTracker.INSTANCE.get(this).dump(pw);
    }

    private AbsSwipeUpHandler createLauncherSwipeHandler(
            GestureState gestureState, long touchTimeMs) {
        return new LauncherSwipeHandlerV2(this, mTaskAnimationManager,
                gestureState, touchTimeMs, mTaskAnimationManager.isRecentsAnimationRunning(),
                mInputConsumer, MSDLPlayerWrapper.INSTANCE.get(this));
    }

    private AbsSwipeUpHandler createFallbackSwipeHandler(
            GestureState gestureState, long touchTimeMs) {
        return new FallbackSwipeHandler(this, mTaskAnimationManager,
                gestureState, touchTimeMs, mTaskAnimationManager.isRecentsAnimationRunning(),
                mInputConsumer, MSDLPlayerWrapper.INSTANCE.get(this));
    }

    private AbsSwipeUpHandler createRecentsWindowSwipeHandler(
            GestureState gestureState, long touchTimeMs) {
        return new RecentsWindowSwipeHandler(this, mTaskAnimationManager,
                gestureState, touchTimeMs, mTaskAnimationManager.isRecentsAnimationRunning(),
                mInputConsumer, MSDLPlayerWrapper.INSTANCE.get(this));
    }

    @VisibleForTesting
    public void injectFakeTrackpadForTesting() {
        mTrackpadsConnected.add(1000);
        initInputMonitor("tapl testing");
    }

    @VisibleForTesting
    public void ejectFakeTrackpadForTesting() {
        mTrackpadsConnected.clear();
        // This method destroys the current input monitor if set up, and only init a new one
        // in 3-button mode if {@code mTrackpadsConnected} is not empty. So in other words,
        // it will destroy the input monitor.
        initInputMonitor("tapl testing");
    }

    /** Refreshes the current overview target. */
    @VisibleForTesting
    public void refreshOverviewTarget() {
        mAllAppsActionManager.onDestroy();
        onOverviewTargetChanged(mOverviewComponentObserver.isHomeAndOverviewSame());
    }
}
