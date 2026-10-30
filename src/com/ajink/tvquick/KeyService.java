package com.ajink.tvquick;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Intercepts the gear button of the RC600 remote and keeps volume long-press
 * working.
 *
 * <h3>Gear button</h3>
 * The button reports scancode 501 (KEY_BRL_DOT5 on the kernel side). No
 * keylayout entry maps it, so it arrives as KEYCODE_MENU with the scancode
 * preserved, and nothing else on the system handles it. Accessibility key
 * filtering lets us take it before the window manager sees it.
 *
 * <h3>Why the volume long-press broke</h3>
 * When any accessibility service sets FLAG_REQUEST_FILTER_KEY_EVENTS the
 * platform enables the global input filter (InputDispatcher::
 * shouldSendKeyToInputFilterLocked -> mInputFilterEnabled). From then on every
 * key event goes through system_server's KeyboardInterceptor instead of
 * straight to the focused app.
 *
 * KeyboardInterceptor has a volume special case (AOSP 14,
 * services/accessibility/.../KeyboardInterceptor.java:getEventDelay): for
 * VOLUME_UP/VOLUME_DOWN it first asks the WindowManagerPolicy
 * (interceptKeyBeforeDispatching(null, event, policyFlags)) and only forwards
 * the key to accessibility services if the policy did not consume it. When the
 * policy does not consume it (devices without TV routing) and no service
 * handles it, the platform re-injects the event into InputDispatcher.
 *
 * InputDispatcher generates key repeats per KeyEntry (dispatchKeyLocked) and
 * hardcodes repeatCount = 0 for everything that enters through notifyKey
 * (InputDispatcher.cpp: "InputDispatcher tracks and generates key repeats on
 * behalf of whatever notifies it, so repeatCount should always be set to 0").
 * A key that is swallowed by the input filter never creates that KeyEntry, so
 * the re-injected copy is a fresh press: the system stops generating repeats
 * and the volume ramps exactly one step per physical press.
 *
 * <h3>Fix</h3>
 * Do not let the volume keys fall through the filter unhandled. We consume
 * them (return true) and drive the ramp ourselves: one step on the initial
 * press, then a self-scheduled repeat every {@link #RAMP_INTERVAL_MS} ms until
 * the key is released, capped at {@link #RAMP_MAX_MS}. The step is applied with
 * AudioManager + FLAG_SHOW_UI, so the on-screen volume bar still appears.
 *
 * If the device uses TV routing the policy consumes volume keys inside the
 * interceptor and they never reach this service, so this code stays inert and
 * the platform keeps handling them - no double stepping.
 *
 * Reference: docs/bugs/volume-long-press.md and
 * docs/analysis/tvquickactions-key-interception.md.
 */
public class KeyService extends AccessibilityService {

    private static final String TAG = "TvQuickKey";
    static final int GEAR_SCANCODE = 501;

    /**
     * Diagnostics. Logs volume and gear keys (never D-pad, to keep navigation
     * off the hot path). Turn off once the ramp is confirmed on the TV.
     */
    private static final boolean DEBUG_KEYS = false;

    /** Delay before the first repeat: matches the usual long-press threshold. */
    private static final long RAMP_START_DELAY_MS = 400;
    /** Repeat period while the key is held. */
    private static final long RAMP_INTERVAL_MS = 120;
    /** Hard stop so a missed ACTION_UP cannot run away with the volume. */
    private static final long RAMP_MAX_MS = 8000;

    private AudioManager mAudio;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /** Key code currently ramping, or 0 when idle. */
    private int mRampKeyCode;
    private long mRampStartUptime;

    private final Runnable mRampStep = new Runnable() {
        @Override
        public void run() {
            if (mRampKeyCode == 0) {
                return;
            }
            if (SystemClock.uptimeMillis() - mRampStartUptime > RAMP_MAX_MS) {
                stopRamp();
                return;
            }
            applyVolumeStep(mRampKeyCode);
            mHandler.postDelayed(this, RAMP_INTERVAL_MS);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        mAudio = (AudioManager) getSystemService(AUDIO_SERVICE);
        // Service info comes from res/xml/key_service.xml, which already sets
        // flagRequestFilterKeyEvents + canRequestFilterKeyEvents. Do not call
        // setServiceInfo() here: it replaces the whole config, and passing a
        // fresh AccessibilityServiceInfo silently drops the key-filter flag
        // (that is exactly what disabled gear detection in build 5848d22).
        Log.i(TAG, "connected: key filtering from XML, volume ramp owned by service");
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        final int keyCode = event.getKeyCode();
        final int action = event.getAction();
        final int scanCode = event.getScanCode();

        if (DEBUG_KEYS && (scanCode == GEAR_SCANCODE
                || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE)) {
            Log.d(TAG, "key action=" + action + " code=" + keyCode
                    + " scan=" + scanCode + " repeat=" + event.getRepeatCount()
                    + " long=" + event.isLongPress());
        }

        // ---- gear button -------------------------------------------------
        if (scanCode == GEAR_SCANCODE) {
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                openOrTogglePanel();
            }
            // Consume down and up so the MENU key never leaks to the app.
            return true;
        }

        // ---- volume: own repeat engine -----------------------------------
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (action == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) {
                        applyVolumeStep(keyCode);
                        startRamp(keyCode);
                    } else {
                        // Defensive: if the platform ever delivers real repeats
                        // (e.g. TV routing), let it drive and stop our timer so
                        // the volume does not step twice.
                        stopRamp();
                        applyVolumeStep(keyCode);
                    }
                } else if (action == KeyEvent.ACTION_UP) {
                    stopRamp();
                }
                return true;

            case KeyEvent.KEYCODE_VOLUME_MUTE:
                if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    if (mAudio != null) {
                        mAudio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                                AudioManager.ADJUST_TOGGLE_MUTE,
                                AudioManager.FLAG_SHOW_UI);
                    }
                }
                return true;

            default:
                // Everything else (D-pad, back, media keys, ...) passes through
                // untouched: single comparison on the hot path, no allocation.
                return false;
        }
    }

    /** Applies one volume step and shows the system volume bar. */
    private void applyVolumeStep(int keyCode) {
        if (mAudio == null) {
            mAudio = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (mAudio == null) {
                return;
            }
        }
        final int direction = keyCode == KeyEvent.KEYCODE_VOLUME_UP
                ? AudioManager.ADJUST_RAISE
                : AudioManager.ADJUST_LOWER;
        try {
            mAudio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction,
                    AudioManager.FLAG_SHOW_UI);
        } catch (Exception e) {
            Log.w(TAG, "cannot adjust volume", e);
        }
    }

    private void startRamp(int keyCode) {
        stopRamp();
        mRampKeyCode = keyCode;
        mRampStartUptime = SystemClock.uptimeMillis();
        mHandler.postDelayed(mRampStep, RAMP_START_DELAY_MS);
    }

    private void stopRamp() {
        mRampKeyCode = 0;
        mHandler.removeCallbacks(mRampStep);
    }

    private void openOrTogglePanel() {
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
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Only key filtering is used.
    }

    @Override
    public void onInterrupt() {
        stopRamp();
    }

    @Override
    public void onDestroy() {
        stopRamp();
        super.onDestroy();
    }
}
