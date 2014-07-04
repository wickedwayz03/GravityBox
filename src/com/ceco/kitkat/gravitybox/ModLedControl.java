/*
 * Copyright (C) 2014 Peter Gregus for GravityBox Project (C3C076@xda)
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

package com.ceco.kitkat.gravitybox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ceco.kitkat.gravitybox.ledcontrol.LedSettings;
import com.ceco.kitkat.gravitybox.ledcontrol.LedSettings.ActiveScreenMode;
import com.ceco.kitkat.gravitybox.ledcontrol.LedSettings.HeadsUpMode;
import com.ceco.kitkat.gravitybox.ledcontrol.QuietHours;
import com.ceco.kitkat.gravitybox.ledcontrol.QuietHoursActivity;
import com.ceco.kitkat.gravitybox.ledcontrol.LedSettings.LedMode;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ModLedControl {
    private static final String TAG = "GB:ModLedControl";
    public static final boolean DEBUG = false;
    private static final String CLASS_USER_HANDLE = "android.os.UserHandle";
    private static final String CLASS_NOTIFICATION_MANAGER_SERVICE = "com.android.server.NotificationManagerService";
    private static final String CLASS_STATUSBAR_MGR_SERVICE = "com.android.server.StatusBarManagerService";
    private static final String CLASS_BASE_STATUSBAR = "com.android.systemui.statusbar.BaseStatusBar";
    private static final String CLASS_PHONE_STATUSBAR = "com.android.systemui.statusbar.phone.PhoneStatusBar";
    private static final String CLASS_STATUSBAR_NOTIFICATION = "android.service.notification.StatusBarNotification";
    private static final String CLASS_KG_TOUCH_DELEGATE = "com.android.systemui.statusbar.phone.KeyguardTouchDelegate";
    private static final String CLASS_NOTIF_DATA_ENTRY = "com.android.systemui.statusbar.NotificationData.Entry";
    private static final String CLASS_EXPAND_HELPER = "com.android.systemui.ExpandHelper";
    private static final String CLASS_HEADSUP_NOTIF_VIEW = "com.android.systemui.statusbar.policy.HeadsUpNotificationView";
    private static final String PACKAGE_NAME_PHONE = "com.android.phone";
    public static final String PACKAGE_NAME_SYSTEMUI = "com.android.systemui";
    private static final int MISSED_CALL_NOTIF_ID = 1;
    private static final String NOTIF_EXTRA_HEADS_UP_MODE = "gbHeadsUpMode";
    private static final String NOTIF_EXTRA_HEADS_UP_EXPANDED = "gbHeadsUpExpanded";
    private static final String NOTIF_EXTRA_ACTIVE_SCREEN_MODE = "gbActiveScreenMode";

    private static XSharedPreferences mPrefs;
    private static Notification mNotifOnNextScreenOff;
    private static Context mContext;
    private static Handler mHandler;
    private static PowerManager mPm;
    private static SensorManager mSm;
    private static KeyguardManager mKm;
    private static Sensor mProxSensor;
    private static boolean mProxSensorListenerRegistered;
    private static boolean mScreenCovered;
    private static boolean mOnPanelRevealedBlocked;
    private static QuietHours mQuietHours;
    private static Map<String, Long> mNotifTimestamps = new HashMap<String, Long>();
    private static boolean mUserPresent;

    private static BroadcastReceiver mScreenOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mNotifOnNextScreenOff != null) {
                try {
                    NotificationManager nm = 
                        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                    nm.notify(MISSED_CALL_NOTIF_ID, mNotifOnNextScreenOff);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
                mNotifOnNextScreenOff = null;
            }
            context.unregisterReceiver(this);
        }
    };

    private static SensorEventListener mProxSensorEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) { 
            mScreenCovered = event.values[0] == 0;
            if (DEBUG) log("Screen covered: " + mScreenCovered);
        }
        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    private static BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(LedSettings.ACTION_UNC_SETTINGS_CHANGED) ||
                    action.equals(QuietHoursActivity.ACTION_QUIET_HOURS_CHANGED)) {
                mPrefs.reload();
                mQuietHours = new QuietHours(mPrefs);
                if (intent.hasExtra(LedSettings.EXTRA_UNC_AS_ENABLED)) {
                    toggleActiveScreenFeature(intent.getBooleanExtra(
                            LedSettings.EXTRA_UNC_AS_ENABLED, false));
                }
            }
            if (action.equals(Intent.ACTION_USER_PRESENT)) {
                if (DEBUG) log("User present");
                mUserPresent = true;
                mOnPanelRevealedBlocked = false;
                if (mProxSensorListenerRegistered && mSm != null && mProxSensor != null) {
                    mSm.unregisterListener(mProxSensorEventListener, mProxSensor);
                    mProxSensorListenerRegistered = false;
                    if (DEBUG) log("Prox sensor listener unregistered");
                }
            }
            if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                mUserPresent = false;
                if (!mProxSensorListenerRegistered && mSm != null && mProxSensor != null) {
                    mSm.registerListener(mProxSensorEventListener, mProxSensor, SensorManager.SENSOR_DELAY_NORMAL);
                    mProxSensorListenerRegistered = true;
                    if (DEBUG) log("Prox sensor listener registered");
                }
            }
        }
    };

    public static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    public static void initZygote() {
        mPrefs = new XSharedPreferences(GravityBox.PACKAGE_NAME, "ledcontrol");
        mPrefs.makeWorldReadable();
        mQuietHours = new QuietHours(mPrefs);

        try {
            XposedHelpers.findAndHookMethod(NotificationManager.class, "notify",
                    String.class, int.class, Notification.class, notifyHookPkg);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        try {
            XposedHelpers.findAndHookMethod(NotificationManager.class, "notifyAsUser",
                    String.class, int.class, Notification.class, CLASS_USER_HANDLE, notifyHookPkg);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        try {
            XposedHelpers.findAndHookMethod(NotificationManager.class, "cancel",
                    String.class, int.class, cancelHook);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        try {
            XposedHelpers.findAndHookMethod(NotificationManager.class, "cancelAsUser",
                    String.class, int.class, CLASS_USER_HANDLE, cancelHook);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        try {
            XposedHelpers.findAndHookMethod(NotificationManager.class, "cancelAll", cancelHook);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        try {
            final Class<?> nmsClass = XposedHelpers.findClass(CLASS_NOTIFICATION_MANAGER_SERVICE, null);
            XposedBridge.hookAllConstructors(nmsClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    if (mContext == null) {
                        mContext = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                        mHandler = (Handler) XposedHelpers.getObjectField(param.thisObject, "mHandler");

                        IntentFilter intentFilter = new IntentFilter();
                        intentFilter.addAction(LedSettings.ACTION_UNC_SETTINGS_CHANGED);
                        intentFilter.addAction(Intent.ACTION_USER_PRESENT);
                        intentFilter.addAction(QuietHoursActivity.ACTION_QUIET_HOURS_CHANGED);
                        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
                        mContext.registerReceiver(mBroadcastReceiver, intentFilter);

                        toggleActiveScreenFeature(!mPrefs.getBoolean(LedSettings.PREF_KEY_LOCKED, false) && 
                                mPrefs.getBoolean(LedSettings.PREF_KEY_ACTIVE_SCREEN_ENABLED, false));
                        if (DEBUG) log("Notification manager service initialized");
                    }
                }
            });

            XposedHelpers.findAndHookMethod(CLASS_NOTIFICATION_MANAGER_SERVICE, null, "enqueueNotificationWithTag",
                    String.class, String.class, String.class, int.class, Notification.class, 
                    int[].class, int.class, notifyHook);

            XposedHelpers.findAndHookMethod(CLASS_STATUSBAR_MGR_SERVICE, null, "onPanelRevealed", 
                    new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                    if (mOnPanelRevealedBlocked) {
                        param.setResult(null);
                        if (DEBUG) log("onPanelRevealed blocked");
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static XC_MethodHook notifyHookPkg = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
            // Phone missed calls: fix AOSP bug preventing LED from working for missed calls
            final Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
            final String pkgName = context.getPackageName();
            if (mNotifOnNextScreenOff == null && pkgName.equals(PACKAGE_NAME_PHONE) && 
                    (Integer)param.args[1] == MISSED_CALL_NOTIF_ID) {
                mNotifOnNextScreenOff = (Notification) param.args[2];
                context.registerReceiver(mScreenOffReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
                if (DEBUG) log("Scheduled missed call notification for next screen off");
                return;
            }
        }
    };

    private static XC_MethodHook notifyHook = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
            try {
                if (mPrefs.getBoolean(LedSettings.PREF_KEY_LOCKED, false)) {
                    if (DEBUG) log("Ultimate notification control feature locked.");
                    return;
                }

                Notification n = (Notification) param.args[4];
                if (n.extras.containsKey("gbIgnoreNotification")) return;

                final String pkgName = (String) param.args[0];

                LedSettings ls = LedSettings.deserialize(mPrefs.getStringSet(pkgName, null));
                if (!ls.getEnabled()) {
                    // use default settings in case they are active
                    ls = LedSettings.deserialize(mPrefs.getStringSet("default", null));
                    if (!ls.getEnabled() && !mQuietHours.quietHoursActive(ls, n, mUserPresent)) {
                        return;
                    }
                }
                if (DEBUG) log(pkgName + ": " + ls.toString());

                final boolean qhActive = mQuietHours.quietHoursActive(ls, n, mUserPresent);
                final boolean qhActiveIncludingLed = qhActive && mQuietHours.muteLED;
                final boolean qhActiveIncludingVibe = qhActive && mQuietHours.muteVibe;
                final boolean isOngoing = ((n.flags & Notification.FLAG_ONGOING_EVENT) == 
                        Notification.FLAG_ONGOING_EVENT);

                if (isOngoing && !ls.getOngoing() && !qhActive) {
                    if (DEBUG) log("Ongoing led control disabled. Ignoring.");
                    return;
                }

                // lights
                if (qhActiveIncludingLed || 
                        (ls.getEnabled() && ls.getLedMode() == LedMode.OFF)) {
                    n.defaults &= ~Notification.DEFAULT_LIGHTS;
                    n.flags &= ~Notification.FLAG_SHOW_LIGHTS;
                } else if (ls.getEnabled() && ls.getLedMode() == LedMode.OVERRIDE) {
                    n.defaults &= ~Notification.DEFAULT_LIGHTS;
                    n.flags |= Notification.FLAG_SHOW_LIGHTS;
                    n.ledOnMS = ls.getLedOnMs();
                    n.ledOffMS = ls.getLedOffMs();
                    n.ledARGB = ls.getColor();
                }

                // vibration
                if (qhActiveIncludingVibe) {
                    n.defaults &= ~Notification.DEFAULT_VIBRATE;
                    n.vibrate = new long[] {0};
                } else {
                    if (ls.getVibrateOverride() && ls.getVibratePattern() != null) {
                        n.defaults &= ~Notification.DEFAULT_VIBRATE;
                        n.vibrate = ls.getVibratePattern();
                    }
                }

                // sound
                if (qhActive) {
                    n.defaults &= ~Notification.DEFAULT_SOUND;
                    n.sound = null;
                    n.flags &= ~Notification.FLAG_INSISTENT;
                } else {
                    if (ls.getSoundOverride()) {
                        n.defaults &= ~Notification.DEFAULT_SOUND;
                        n.sound = ls.getSoundUri();
                    }
                    if (ls.getSoundOnlyOnce()) {
                        if (ls.getSoundOnlyOnceTimeout() > 0) {
                            if (mNotifTimestamps.containsKey(pkgName) &&
                                    (System.currentTimeMillis() - mNotifTimestamps.get(pkgName) < 
                                            ls.getSoundOnlyOnceTimeout())) {
                                n.defaults &= ~Notification.DEFAULT_SOUND;
                                n.defaults &= ~Notification.DEFAULT_VIBRATE;
                                n.sound = null;
                                n.vibrate = new long[] {0};
                                n.flags &= ~Notification.FLAG_ONLY_ALERT_ONCE;
                            } else {
                                mNotifTimestamps.put(pkgName, System.currentTimeMillis());
                            }
                        } else {
                            n.flags |= Notification.FLAG_ONLY_ALERT_ONCE;
                        }
                    } else {
                        n.flags &= ~Notification.FLAG_ONLY_ALERT_ONCE;
                    }
                    if (ls.getInsistent()) {
                        n.flags |= Notification.FLAG_INSISTENT;
                    } else {
                        n.flags &= ~Notification.FLAG_INSISTENT;
                    }
                }

                if (ls.getEnabled()) {
                    // heads up mode
                    n.extras.putString(NOTIF_EXTRA_HEADS_UP_MODE, ls.getHeadsUpMode().toString());
                    if (ls.getHeadsUpMode() != HeadsUpMode.OFF) {
                        n.extras.putBoolean(NOTIF_EXTRA_HEADS_UP_EXPANDED,
                                ls.getHeadsUpExpanded());
                    }
                    // active screen mode
                    if (ls.getActiveScreenEnabled() && !qhActive && !isOngoing && mPm != null &&
                            !mPm.isScreenOn() && !mScreenCovered && mKm.isKeyguardLocked()) {
                        n.extras.putString(NOTIF_EXTRA_ACTIVE_SCREEN_MODE,
                                ls.getActiveScreenMode().toString());
                    }
                }

                if (DEBUG) log("Notification info: defaults=" + n.defaults + "; flags=" + n.flags);
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }

        @Override
        protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
            try {
                final String pkgName = (String) param.args[0];
                Notification n = (Notification) param.args[4];
                if (!n.extras.containsKey(NOTIF_EXTRA_ACTIVE_SCREEN_MODE))
                    return;

                final ActiveScreenMode asMode = ActiveScreenMode.valueOf(
                        n.extras.getString(NOTIF_EXTRA_ACTIVE_SCREEN_MODE));
                if (DEBUG) log("Performing Active Screen for " + pkgName + " with mode " +
                        asMode.toString());
                mOnPanelRevealedBlocked = asMode == ActiveScreenMode.EXPAND_PANEL;
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (asMode == ActiveScreenMode.EXPAND_PANEL) {
                            mContext.sendBroadcast(new Intent(ModHwKeys.ACTION_EXPAND_NOTIFICATIONS));
                        }
                        final WakeLock wl = mPm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK |
                                PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, TAG);
                        wl.acquire();
                        wl.release();
                    }
                }, 1000);
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
    };

    private static XC_MethodHook cancelHook = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
            try {
                final Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                final String pkgName = context.getPackageName();
                final boolean isMissedCallNotifOrAll = pkgName.equals(PACKAGE_NAME_PHONE) &&
                        (param.args.length == 0 || (Integer) param.args[1] == MISSED_CALL_NOTIF_ID);
                if (isMissedCallNotifOrAll && mNotifOnNextScreenOff != null) {
                    mNotifOnNextScreenOff = null;
                    context.unregisterReceiver(mScreenOffReceiver);
                    if (DEBUG) log("Pending missed call notification canceled");
                }
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
    };

    private static void toggleActiveScreenFeature(boolean enable) {
        try {
            if (enable && mContext != null) {
                mScreenCovered = false;
                mPm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
                mKm = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
                mSm = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
                mProxSensor = mSm.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            } else {
                mProxSensor = null;
                mSm = null;
                mPm = null;
                mKm = null;
            }
            if (DEBUG) log("Active screen feature: " + enable);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    // SystemUI package
    private static WindowManager.LayoutParams mHeadsUpLp;

    public static void init(final XSharedPreferences prefs, final ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(CLASS_PHONE_STATUSBAR, classLoader, "start", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!(Boolean)XposedHelpers.getBooleanField(param.thisObject, "mUseHeadsUp")) {
                        XposedHelpers.setBooleanField(param.thisObject, "mUseHeadsUp", true);
                        XposedHelpers.callMethod(param.thisObject, "addHeadsUpView");
                    }
                }
            });

            XposedHelpers.findAndHookMethod(CLASS_BASE_STATUSBAR, classLoader, "shouldInterrupt",
                    CLASS_STATUSBAR_NOTIFICATION, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    prefs.reload();

                    Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                    Notification n = (Notification) XposedHelpers.getObjectField(param.args[0], "notification");
                    View headsUpView = (View) XposedHelpers.getObjectField(param.thisObject, "mHeadsUpNotificationView");
                    int sysUiVis = XposedHelpers.getIntField(param.thisObject, "mSystemUiVisibility");

                    maybeUpdateHeadsUpLayout(context, headsUpView, isStatusBarHidden(sysUiVis) ? 0 :
                        (Integer) XposedHelpers.callMethod(param.thisObject, "getStatusBarHeight"));

                    // show expanded heads up for non-intrusive incoming call
                    if (isNonIntrusiveIncomingCallNotification(n) && isHeadsUpAllowed(context)) {
                        n.extras.putBoolean(NOTIF_EXTRA_HEADS_UP_EXPANDED, true);
                        param.setResult(true);
                        return;
                    }

                    // disable when panels are disabled
                    if (!(Boolean) XposedHelpers.callMethod(param.thisObject, "panelsEnabled")) {
                        if (DEBUG) log("shouldInterrupt: NO due to panels being disabled");
                        param.setResult(false);
                        return;
                    }

                    // explicitly disable for all ongoing notifications
                    if ((Boolean) XposedHelpers.callMethod(param.args[0], "isOngoing")) {
                        if (DEBUG) log("Disabling heads up for ongoing notification");
                        param.setResult(false);
                        return;
                    }

                    // show if active screen heads up mode set
                    if (n.extras.containsKey(NOTIF_EXTRA_ACTIVE_SCREEN_MODE) &&
                            ActiveScreenMode.valueOf(n.extras.getString(NOTIF_EXTRA_ACTIVE_SCREEN_MODE)) ==
                                ActiveScreenMode.HEADS_UP) {
                        if (DEBUG) log("Showing active screen heads up");
                        param.setResult(true);
                        return;
                    }

                    // get desired mode set by UNC or use default
                    HeadsUpMode mode = n.extras.containsKey(NOTIF_EXTRA_HEADS_UP_MODE) ?
                            HeadsUpMode.valueOf(n.extras.getString(NOTIF_EXTRA_HEADS_UP_MODE)) :
                                HeadsUpMode.ALWAYS;
                    if (DEBUG) log("Heads up mode: " + mode.toString());

                    switch (mode) {
                        default:
                        case DEFAULT:
                            if (shouldNotDisturb(context)) {
                                param.setResult(false);
                            }
                            return;
                        case ALWAYS: param.setResult(isHeadsUpAllowed(context)); return;
                        case OFF: param.setResult(false); return;
                        case IMMERSIVE:
                            param.setResult(isStatusBarHidden(sysUiVis) && isHeadsUpAllowed(context));
                            return;
                    }
                }
            });

            XposedHelpers.findAndHookMethod(CLASS_NOTIF_DATA_ENTRY, classLoader, "setInterruption", 
                    XC_MethodReplacement.DO_NOTHING);

            XposedHelpers.findAndHookMethod(CLASS_PHONE_STATUSBAR, classLoader, "resetHeadsUpDecayTimer",
                    new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object headsUpView = XposedHelpers.getObjectField(param.thisObject, "mHeadsUpNotificationView");
                    Object headsUp = XposedHelpers.getObjectField(headsUpView, "mHeadsUp");
                    Object sbNotif = XposedHelpers.getObjectField(headsUp, "notification");
                    Notification n = (Notification) XposedHelpers.getObjectField(sbNotif, "notification");
                    int timeout = n.extras.containsKey(NOTIF_EXTRA_ACTIVE_SCREEN_MODE) ? 10000 :
                            prefs.getInt(GravityBoxSettings.PREF_KEY_HEADS_UP_TIMEOUT, 5) * 1000;
                    XposedHelpers.setIntField(param.thisObject, "mHeadsUpNotificationDecay", timeout);
                }
            });

            XposedHelpers.findAndHookMethod(CLASS_EXPAND_HELPER, classLoader, "isInside",
                    View.class, float.class, float.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (prefs.getBoolean(GravityBoxSettings.PREF_KEY_HEADS_UP_ONE_FINGER, false)) {
                        param.setResult(true);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(CLASS_HEADSUP_NOTIF_VIEW, classLoader,
                    "setNotification", CLASS_NOTIF_DATA_ENTRY, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object sbNotif = XposedHelpers.getObjectField(param.args[0], "notification");
                    Notification n = (Notification) XposedHelpers.getObjectField(sbNotif, "notification");
                    final boolean expanded = n.extras.containsKey(NOTIF_EXTRA_HEADS_UP_EXPANDED) ?
                            n.extras.getBoolean(NOTIF_EXTRA_HEADS_UP_EXPANDED, false) :
                                prefs.getBoolean(GravityBoxSettings.PREF_KEY_HEADS_UP_EXPANDED, false);
                    Object headsUp = XposedHelpers.getObjectField(param.thisObject, "mHeadsUp");
                    Object row = XposedHelpers.getObjectField(headsUp, "row");
                    XposedHelpers.callMethod(row, "setExpanded", expanded);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void maybeUpdateHeadsUpLayout(Context context, View headsUpView, int yOffset) {
        if (mHeadsUpLp == null) {
            mHeadsUpLp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL, // above the status bar!
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                    PixelFormat.TRANSLUCENT);
            mHeadsUpLp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            mHeadsUpLp.gravity = Gravity.TOP;
            mHeadsUpLp.y = -1;
            mHeadsUpLp.setTitle("Heads Up");
            mHeadsUpLp.packageName = context.getPackageName();
            mHeadsUpLp.windowAnimations = context.getResources().getIdentifier(
                    "Animation.StatusBar.HeadsUp", "style", PACKAGE_NAME_SYSTEMUI);
        }

        final boolean layoutChanged = mHeadsUpLp.y != yOffset;
        if (layoutChanged) {
            mHeadsUpLp.y = yOffset;
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            wm.updateViewLayout(headsUpView, mHeadsUpLp);
        }
    }

    private static boolean isHeadsUpAllowed(Context context) {
        if (context == null) return false;

        Object kgTouchDelegate = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass(CLASS_KG_TOUCH_DELEGATE, context.getClassLoader()),
                "getInstance", context);
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return (pm.isScreenOn() &&
                !(Boolean) XposedHelpers.callMethod(kgTouchDelegate, "isShowingAndNotHidden") &&
                !(Boolean) XposedHelpers.callMethod(kgTouchDelegate, "isInputRestricted") &&
                !shouldNotDisturb(context));
    }

    private static boolean isStatusBarHidden(int systemUiVisibility) {
        return ((systemUiVisibility & View.SYSTEM_UI_FLAG_FULLSCREEN) ==
                View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private static boolean isNonIntrusiveIncomingCallNotification(Notification n) {
        return (n != null &&
                n.extras.getBoolean(ModDialer.NOTIF_EXTRA_NON_INTRUSIVE_CALL, false));
    }

    private static String getTopLevelPackageName(Context context) {
        try {
            final ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningTaskInfo> taskInfo = am.getRunningTasks(1);
            ComponentName cn = taskInfo.get(0).topActivity;
            return cn.getPackageName();
        } catch (Throwable t) {
            log("Error getting top level package: " + t.getMessage());
            return null;
        }
    }

    private static boolean shouldNotDisturb(Context context) {
        String pkgName = getTopLevelPackageName(context);
        final XSharedPreferences uncPrefs = new XSharedPreferences(GravityBox.PACKAGE_NAME, "ledcontrol");
        if(!uncPrefs.getBoolean(LedSettings.PREF_KEY_LOCKED, false) && pkgName != null) {
            LedSettings ls = LedSettings.deserialize(uncPrefs.getStringSet(pkgName, null));
            if (!ls.getEnabled()) {
                // fall back to default settings
                ls = LedSettings.deserialize(uncPrefs.getStringSet("default", null));
            }
            return (ls.getEnabled() && ls.getHeadsUpDnd());
        } else {
            return false;
        }
    }
}