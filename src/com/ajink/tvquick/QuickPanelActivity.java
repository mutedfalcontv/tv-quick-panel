package com.ajink.tvquick;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class QuickPanelActivity extends Activity {

    public static final String ACTION_OPEN = "com.ajink.tvquick.action.OPEN";
    public static final String ACTION_TOGGLE = "com.ajink.tvquick.action.TOGGLE";

    /**
     * The intent SystemUI's TvNotificationPanel fires for the gear button
     * (keycode 83). Declaring it keeps the panel toggleable by that action (and
     * by {@code am start} for testing); the physical key itself is read by
     * GearBridgeService, which sends {@link #ACTION_TOGGLE}.
     */
    private static final String ACTION_TOGGLE_NOTIFICATION_PANEL =
            "android.app.action.TOGGLE_NOTIFICATION_HANDLER_PANEL";

    private static final ComponentName INPUTS =
            new ComponentName("com.realtek.tv", "com.realtek.tv.SelectInputActivity");
    private static final ComponentName DISPLAY =
            new ComponentName("com.android.tv.settings", "com.realtek.tv.settings.activity.PictureSetupActivity");
    /**
     * Same activity also hosts the Sound / Screen / Audio output panels; the
     * page is chosen with this extra and opens as a translucent left panel so
     * whatever is playing keeps running behind it.
     */
    private static final String EXTRA_DETAIL_TAG = "detail_tag";
    private static final String DETAIL_PICTURE = "detail_picture";
    private static final String DETAIL_SOUND = "detail_sound";
    private static final ComponentName WIFI =
            new ComponentName("com.android.tv.settings", "com.android.tv.settings.connectivity.NetworkActivity");
    /**
     * Stock launcher's tile host for vendor slices. Exported but protected by
     * WRITE_SECURE_SETTINGS, which this app has been granted.
     */
    private static final ComponentName VENDOR_SLICE =
            new ComponentName("com.android.tv.settings", "com.realtek.tv.settings.activity.VendorSliceActivity");
    private static final String ACTION_SLICE_SETTINGS = "android.settings.SLICE_SETTINGS";
    /** Remotes & Accessories page. */
    private static final String BLUETOOTH_SLICE =
            "content://com.android.tv.settings.accessories.sliceprovider/general";
    private static final ComponentName ACCESSIBILITY =
            new ComponentName("com.android.tv.settings", "com.android.tv.settings.oemlink.AccessibilitySettingsActivity");
    private static final String TAG = "TvQuickPanel";

    /** Scancode the RC600 gear button reports (KEY_BRL_DOT5). */
    private static final int GEAR_SCANCODE = 501;

    private static final ComponentName SETTINGS =
            new ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings");
    /**
     * The glass launcher. The panel can open it from its Home button, which is the
     * reverse of the launcher opening the panel from All apps - both directions are
     * needed so the gear never becomes a one-way door.
     */
    private static final ComponentName LAUNCHER =
            new ComponentName("com.ajink.tvglass", "com.ajink.tvglass.HomeActivity");

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private TextView mClock;
    private TextView mDate;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            mHandler.postDelayed(this, 10_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.panel);
        applyImmersive();

        mClock = findViewById(R.id.clock);
        mDate = findViewById(R.id.date);

        bind(R.id.tile_inputs, INPUTS, "com.android.tv.action.VIEW_INPUTS");
        bind(R.id.tile_display, DISPLAY, "android.settings.DISPLAY_SETTINGS");
        bindDetail(R.id.tile_sound, DETAIL_SOUND);
        bind(R.id.tile_wifi, WIFI, "android.settings.NETWORK_OPERATOR_SETTINGS");
        bindSlice(R.id.tile_bluetooth, BLUETOOTH_SLICE);
        bind(R.id.tile_accessibility, ACCESSIBILITY, "android.settings.ACCESSIBILITY_SETTINGS");
        bind(R.id.gear, SETTINGS, "android.settings.SETTINGS");
        findViewById(R.id.home).setOnClickListener(v -> launchLauncher());

        findViewById(R.id.tile_inputs).requestFocus();

        // The gear is read over adbd by GearBridgeService, and this is the only
        // path that also leaves the volume long-press alone. Opening the panel
        // starts it, so the button works from the first launch after install.
        GearBridgeService.start(this);
    }

    private void bind(int id, final ComponentName target, final String fallbackAction) {
        View v = findViewById(id);
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                launch(target, fallbackAction);
            }
        });
    }

    /** Opens a Display & Sound page as the translucent side panel stock uses. */
    private void bindDetail(int id, final String detailTag) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                launchDetail(detailTag);
            }
        });
    }

    private void launchDetail(String detailTag) {
        Log.i(TAG, "launch detail " + detailTag);
        Intent i = new Intent();
        i.setComponent(DISPLAY);
        i.putExtra(EXTRA_DETAIL_TAG, detailTag);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        try {
            startActivity(i);
            finish();
        } catch (Exception e) {
            Log.w(TAG, "detail " + detailTag + " -> " + e);
            Toast.makeText(this, "Settings unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    /** Opens one of the vendor settings pages that live behind a slice URI. */
    private void bindSlice(int id, final String sliceUri) {        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                launchSlice(sliceUri);
            }
        });
    }

    private void launchSlice(String sliceUri) {
        Log.i(TAG, "launch slice " + sliceUri);
        Intent i = new Intent(ACTION_SLICE_SETTINGS);
        i.setComponent(VENDOR_SLICE);
        i.putExtra("slice_uri", sliceUri);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            finish();
        } catch (Exception e) {
            Log.w(TAG, "slice " + sliceUri + " -> " + e);
            Toast.makeText(this, "Settings unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    private void launch(ComponentName target, String fallbackAction) {
        Log.i(TAG, "launch " + target.flattenToShortString());
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setComponent(target);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "component " + target.flattenToShortString() + " -> " + e);

            if (fallbackAction != null) {
                Intent a = new Intent(fallbackAction);
                a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(a);
                } catch (Exception e2) {
                    Log.w(TAG, "action " + fallbackAction + " -> " + e2);
                    Toast.makeText(this, target.getPackageName() + " unavailable", Toast.LENGTH_SHORT).show();
                    return;
                }
            } else {
                Toast.makeText(this, target.getPackageName() + " unavailable", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        finish();
    }

    /**
     * Home button: hand the screen back to the glass launcher, or to whatever the
     * system considers home if the launcher was uninstalled.
     */
    private void launchLauncher() {
        Log.i(TAG, "launch launcher");
        Intent i = new Intent(Intent.ACTION_MAIN)
                .setComponent(LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            finish();
        } catch (Exception e) {
            Log.w(TAG, "launcher -> " + e);
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(home);
                finish();
            } catch (Exception e2) {
                Toast.makeText(this, R.string.launcher_unavailable, Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * Full-screen, like the launcher. Without this the panel inherits the system
     * bars from whatever was on screen, which puts a clock and Wi-Fi icons above a
     * sheet that already has its own - the one thing the mockup did not show.
     */
    private void applyImmersive() {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
            return;
        }
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void updateClock() {
        Date now = new Date();
        boolean h24 = DateFormat.is24HourFormat(this);
        SimpleDateFormat time = new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.getDefault());
        SimpleDateFormat day = new SimpleDateFormat("EEE, MMM d", Locale.getDefault());
        if (mClock != null) mClock.setText(time.format(now));
        if (mDate != null) mDate.setText(day.format(now));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Fallback only: on this TV SystemUI intercepts the gear keycode before
        // any activity sees it, so the panel is toggled through onNewIntent
        // below. If a build stops intercepting, this keeps the key working.
        if (event.getScanCode() == GEAR_SCANCODE
                || keyCode == KeyEvent.KEYCODE_NOTIFICATION) {
            if (event.getRepeatCount() == 0) {
                finish();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String action = intent == null ? null : intent.getAction();
        // Re-entry only happens when this activity is already at the top of its
        // task, so both actions mean "close it". Do not consult a visibility
        // flag: the panel is paused for a moment around the hand-off and would
        // latch the wrong state.
        if (ACTION_TOGGLE.equals(action)
                || ACTION_TOGGLE_NOTIFICATION_PANEL.equals(action)) {
            finish();
            return;
        }
        setIntent(intent);
        updateClock();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        updateClock();
        mHandler.removeCallbacks(mTick);
        mHandler.postDelayed(mTick, 10_000L);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mTick);
    }
}
