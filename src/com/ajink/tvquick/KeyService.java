package com.ajink.tvquick;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Turns the RC600 gear button into the quick panel's on/off switch, and gets out
 * of the way of every other key.
 *
 * <h3>Why an accessibility filter is needed at all</h3>
 * The gear reports scancode 501 (KEY_BRL_DOT5), which the TV's RC600.kl maps to
 * KEYCODE_NOTIFICATION. SystemUI claims that keycode before any activity sees it:
 * TvNotificationPanel tries to launch
 * {@code android.app.action.TOGGLE_NOTIFICATION_HANDLER_PANEL}, but it only starts
 * the handler when the resolved activity belongs to a <em>system</em> package and
 * requires STATUS_BAR_SERVICE. A normally installed app can never match that, so
 * without a filter the button does nothing at all on this TV.
 *
 * <h3>Why the filter is not always on</h3>
 * Requesting FLAG_REQUEST_FILTER_KEY_EVENTS switches on the platform's global
 * input filter, and that has a hard cost on this build: the window policy consumes
 * volume keys inside the interceptor, so the service never receives them, and the
 * key's DOWN never reaches InputDispatcher either - which means no key repeats are
 * generated. A held volume key then moves one step and stops. Measured on the TV:
 * filter off ramps, filter on gives a single step.
 *
 * <p>Nothing app-side can repair that (the key cannot be seen, and re-injecting is
 * a system-only permission), so the filter is enabled only while this app is the
 * foreground app - the launcher or the panel - where the gear is the useful key
 * and volume is not. As soon as another app comes forward the filter is dropped
 * and the native volume ramp works exactly as stock.
 *
 * <h3>Why window events are not enough</h3>
 * TYPE_WINDOW_STATE_CHANGED fires when a window is added or its title changes, not
 * when an existing window is brought back to the front. Returning to the launcher
 * (or to a panel that was covered by a settings page) therefore produces no event,
 * and the filter would stay off. Both of our activities broadcast
 * {@link #ACTION_OURS_FOREGROUND} from {@code onResume} to cover that case; the
 * window events still handle every other app.
 *
 * <h3>Toggle without tracking state</h3>
 * The panel is started with the same action SystemUI would use. The first start
 * creates the activity (opens); any later start is delivered to the single-top
 * instance, whose onNewIntent finishes it (closes). No visibility flag is kept
 * here, because the panel is briefly paused around that hand-off and a flag
 * latched the wrong value.
 */
public class KeyService extends AccessibilityService {

    private static final String TAG = "TvQuickKey";
    private static final int GEAR_SCANCODE = 501;

    /** Same action SystemUI's notification-panel handler would launch. */
    private static final String ACTION_TOGGLE_NOTIFICATION_PANEL =
            "android.app.action.TOGGLE_NOTIFICATION_HANDLER_PANEL";

    private static final ComponentName PANEL =
            new ComponentName("com.ajink.tvquick", "com.ajink.tvquick.QuickPanelActivity");

    private static final String LAUNCHER_PACKAGE = "com.ajink.tvglass";

    /** Both apps are ours, so the filter may be on while either is in front. */
    private static final String PANEL_PACKAGE = "com.ajink.tvquick";

    /**
     * Sent by the launcher and the panel when they resume, so the filter comes
     * back even though resuming an existing window raises no accessibility event.
     */
    static final String ACTION_OURS_FOREGROUND =
            "com.ajink.tvquick.action.OURS_FOREGROUND";

    /**
     * When an activity of ours resumes, the window it covered is being torn down
     * and its own window-state event can land a moment later (measured: 140 ms).
     * Events from other packages inside this window are that tail, not a real
     * switch, and must not turn the filter back off.
     */
    private static final long FOREGROUND_GRACE_MS = 500;

    private boolean mFilterOn;

    private BroadcastReceiver mOursForeground;

    /** Uptime of the last ACTION_OURS_FOREGROUND, for {@link #FOREGROUND_GRACE_MS}. */
    private long mOursForegroundAt;

    /**
     * The key is delivered down and up; only the initial down should toggle, or
     * one press would open the panel and immediately close it again.
     */
    private boolean mGearDown;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (mOursForeground == null) {
            mOursForeground = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    mOursForegroundAt = SystemClock.uptimeMillis();
                    setFilter(true);
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_OURS_FOREGROUND);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(mOursForeground, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(mOursForeground, filter);
            }
        }
        // The launcher is what starts the service in practice, so assume ours
        // until a window-state event says otherwise.
        setFilter(true);
    }

    @Override
    public void onDestroy() {
        if (mOursForeground != null) {
            unregisterReceiver(mOursForeground);
            mOursForeground = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event.getScanCode() != GEAR_SCANCODE) {
            // Never consume anything else: volume, D-pad and media keys keep
            // their native handling.
            return false;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (!mGearDown && event.getRepeatCount() == 0) {
                mGearDown = true;
                togglePanel();
            }
        } else {
            mGearDown = false;
        }
        // Consume both edges so the key never reaches anything else.
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        boolean ours = pkg != null
                && (LAUNCHER_PACKAGE.contentEquals(pkg) || PANEL_PACKAGE.contentEquals(pkg));
        if (!ours
                && SystemClock.uptimeMillis() - mOursForegroundAt < FOREGROUND_GRACE_MS) {
            return;
        }
        // Ours: claim the gear. Anything else: hand volume back to the platform.
        setFilter(ours);
    }

    /**
     * Toggles FLAG_REQUEST_FILTER_KEY_EVENTS without restarting the service, so
     * the switch is instant and never needs a settings write.
     */
    private void setFilter(boolean on) {
        if (on == mFilterOn) {
            return;
        }
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            return;
        }
        if (on) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        } else {
            info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        }
        setServiceInfo(info);
        mFilterOn = on;
        Log.i(TAG, "key filter " + (on ? "on" : "off"));
    }

    private void togglePanel() {
        Intent i = new Intent(ACTION_TOGGLE_NOTIFICATION_PANEL)
                .setComponent(PANEL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "cannot toggle the panel", e);
        }
    }

    @Override
    public void onInterrupt() {
    }
}
