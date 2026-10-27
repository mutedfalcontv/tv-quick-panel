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
 */
public class KeyService extends AccessibilityService {

    private static final String TAG = "TvQuickKey";
    static final int GEAR_SCANCODE = 501;

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
        if (event.getScanCode() != GEAR_SCANCODE) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
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
