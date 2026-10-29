package com.ajink.tvquick;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Intercepts the gear button of the RC600 remote.
 *
 * The button reports scancode 501 (KEY_BRL_DOT5 on the kernel side). No
 * keylayout entry maps it, so it arrives as KEYCODE_MENU with the scancode
 * preserved, and nothing else on the system handles it. Accessibility key
 * filtering lets us take it before the window manager sees it.
 *
 * <p>Volume keys are NOT consumed here: we return {@code false} for every
 * scancode except the gear button's 501. Returning {@code false} is what
 * tells the framework "this event is not mine, dispatch it normally". The
 * method is intentionally a single integer comparison on the fast path so
 * the accessibility IPC overhead is as small as possible — every key event
 * on the device, including volume repeats, passes through this method while
 * the service is enabled, and a slow response can break repeat timing.
 *
 * <p>Volume long-press regression: holding VOL+/VOL- steps once instead of
 * ramping. Single press works. Suspected cause is the IPC overhead of routing
 * every key event through this service. See docs/bugs/volume-long-press.md.
 */
public class KeyService extends AccessibilityService {

    private static final String TAG = "TvQuickKey";
    static final int GEAR_SCANCODE = 501;

    /** Volume key scan codes on the R4G kernel keylayout. */
    private static final int SCAN_VOL_UP = 115;
    private static final int SCAN_VOL_DOWN = 114;

    /**
     * Set to true to log every volume key event (including repeats) for
     * diagnosing the long-press regression. Off by default: the logging is on
     * the hot path and adds overhead of its own.
     */
    private static final boolean DEBUG_VOLUME = false;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            info = new AccessibilityServiceInfo();
        }
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        info.notificationTimeout = 100;
        setServiceInfo(info);
        Log.i(TAG, "connected, filtering key events");
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        int scanCode = event.getScanCode();

        // Fast path: every key that is not the gear button is left for the
        // system. Returning false means "not consumed".
        if (scanCode != GEAR_SCANCODE) {
            if (DEBUG_VOLUME
                    && (scanCode == SCAN_VOL_UP || scanCode == SCAN_VOL_DOWN)) {
                Log.d(TAG, "volume " + (scanCode == SCAN_VOL_UP ? "up" : "down")
                        + " action=" + event.getAction()
                        + " repeat=" + event.getRepeatCount()
                        + " -> pass through");
            }
            return false;
        }

        // Gear button: only act on the initial press. Repeats and the release
        // are consumed (return true) but do nothing, so holding the gear button
        // does not toggle the panel repeatedly.
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            Intent i = new Intent(this, QuickPanelActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            i.setAction(QuickPanelActivity.sVisible
                    ? QuickPanelActivity.ACTION_TOGGLE
                    : QuickPanelActivity.ACTION_OPEN);
            try {
                startActivity(i);
            } catch (Exception e) {
                Log.w(TAG, "cannot start panel", e);
                return false;
            }
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // only key filtering is used
    }

    @Override
    public void onInterrupt() {
        // not used
    }
}
