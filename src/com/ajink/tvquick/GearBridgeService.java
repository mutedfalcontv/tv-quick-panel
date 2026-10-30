package com.ajink.tvquick;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import com.ajink.tvquick.adb.AdbConnection;
import com.ajink.tvquick.adb.AdbKey;

import java.security.PrivateKey;

/**
 * Watches the RC600 gear button over the device's own adb daemon and opens the
 * quick panel - without touching the input pipeline.
 *
 * <h3>Why not an accessibility service</h3>
 * The gear button reports kernel scancode 501, which has no keylayout entry, so
 * it surfaces as {@code KEYCODE_MENU} and no app can see it through the normal
 * key path. The usual trick is an accessibility service with
 * {@code FLAG_REQUEST_FILTER_KEY_EVENTS}. Measured on this TV, that has a fatal
 * side effect: the global input filter turns on ({@code dumpsys input} ->
 * {@code InputFilterEnabled: true}), every key is routed through
 * {@code KeyboardInterceptor} in system_server, and for volume keys the
 * interceptor consumes them before {@code InputDispatcher} creates a
 * {@code KeyEntry}. Repeats are generated per {@code KeyEntry}, so a held
 * volume key never ramps - one step per press. Projectivy's accessibility
 * service causes exactly the same thing.
 *
 * <p>So this service keeps the filter off and instead reads raw input events
 * with {@code getevent} as the shell user, over a localhost connection to adbd.
 * Volume keys keep their native long-press ramp because nothing in the input
 * path changed.
 *
 * <h3>Launching the panel</h3>
 * A foreground service is not allowed to start activities from the background
 * on Android 10+, so the panel is started with {@code am start} on the adb
 * stream, where the shell uid has that privilege. The direct
 * {@code startActivity} fallback only fires when the app already has a
 * background-activity-launch exemption.
 *
 * <h3>Setup (one time)</h3>
 * The first connection makes the TV show "Allow USB debugging?" for this app's
 * key. Accept it once; the key then stays in {@code /data/misc/adb/adb_keys}.
 * ADB over the network must stay enabled (it is on this device,
 * {@code service.adb.tcp.port=5555}).
 */
public class GearBridgeService extends Service {

    static final String TAG = "TvGearBridge";

    private static final String ACTION_START = "com.ajink.tvquick.action.START_BRIDGE";
    private static final String ACTION_STOP = "com.ajink.tvquick.action.STOP_BRIDGE";

    private static final String CHANNEL_ID = "gear_bridge";
    private static final int NOTIFICATION_ID = 42;

    /** adbd listens here because service.adb.tcp.port=5555 on this TV. */
    private static final int ADB_PORT = 5555;
    private static final String ADB_HOST = "127.0.0.1";

    /** Kernel scancode of the gear button (KEY_BRL_DOT5 on the RC600). */
    private static final int GEAR_SCANCODE = 501;
    /** EV_KEY */
    private static final String EV_KEY = "0001";

    /** Debounce for the gear, in case getevent repeats the down event. */
    private static final long GEAR_DEBOUNCE_MS = 250;
    private static final long AUTH_WAIT_MS = 120_000;

    private static final String PANEL_ACTION_TOGGLE = QuickPanelActivity.ACTION_TOGGLE;
    private static final String PANEL_COMPONENT =
            "com.ajink.tvquick/com.ajink.tvquick.QuickPanelActivity";

    private volatile boolean mRunning;
    private Thread mWorker;
    private volatile AdbConnection mConnection;
    private volatile boolean mRetryNow;
    private long mLastGearAt;
    private String mState = "starting";

    public static void start(android.content.Context context) {
        Intent i = new Intent(context, GearBridgeService.class);
        i.setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot start the gear bridge", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForegroundInternal();
        mRunning = true;
        mWorker = new Thread(new Runnable() {
            @Override
            public void run() {
                runLoop();
            }
        }, "gear-bridge");
        mWorker.setDaemon(true);
        mWorker.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundInternal();
        }
        // Opening the panel is a user action: retry immediately instead of
        // waiting out the authorization backoff.
        //
        // NOTE: do not interrupt the worker here - a thread that is mid-handshake
        // or mid-`await` on a stream sees the interrupt and tears the stream
        // down. The worker notices mRetryNow during its backoff sleep instead.
        mRetryNow = true;
        // START_STICKY: the gear button is the only way into the panel, so the
        // watcher has to come back on its own after a kill.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mRunning = false;
        AdbConnection c = mConnection;
        mConnection = null;
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
        if (mWorker != null) {
            mWorker.interrupt();
            mWorker = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- worker

    private void runLoop() {
        long backoff = 1000;
        int authFailures = 0;
        PrivateKey key;
        try {
            key = AdbKey.get(this);
        } catch (Exception e) {
            Log.e(TAG, "no adb key", e);
            return;
        }
        while (mRunning) {
            AdbConnection connection = null;
            try {
                Log.i(TAG, "connecting to adb " + ADB_HOST + ":" + ADB_PORT);
                connection = AdbConnection.open(ADB_HOST, ADB_PORT, key, (int) AUTH_WAIT_MS);
                mConnection = connection;
                backoff = 1000;
                authFailures = 0;
                setState("connected");
                Log.i(TAG, "adb connected, opening getevent");

                AdbConnection.Stream stream = connection.openShell("getevent");
                Log.i(TAG, "getevent running");

                byte[] buffer = new byte[4096];
                StringBuilder line = new StringBuilder(128);
                int n;
                long received = 0;
                while (mRunning && (n = stream.read(buffer)) >= 0) {
                    received += n;
                    for (int i = 0; i < n; i++) {
                        char ch = (char) (buffer[i] & 0xFF);
                        if (ch == '\n') {
                            handleLine(line);
                            line.setLength(0);
                        } else if (ch != '\r' && line.length() < 512) {
                            line.append(ch);
                        }
                    }
                }
                if (mRunning) {
                    Log.w(TAG, "getevent ended after " + received + " bytes, tail=" + line);
                    backoff = 2000;
                }
            } catch (AdbConnection.AuthRequiredException e) {
                setState("authorize");
                authFailures++;
                // Every reconnect attempt makes adbd raise a fresh "Allow USB
                // debugging?" dialog. Back off hard so the screen is not
                // flooded; opening the panel retries immediately.
                long authBackoff = 60_000L << Math.min(authFailures - 1, 3);
                backoff = Math.min(authBackoff, 300_000L);
                Log.w(TAG, "adb key not authorized (attempt " + authFailures
                        + ") - accept the prompt on the TV, retrying in "
                        + (backoff / 1000) + "s");
            } catch (Exception e) {
                backoff = Math.min(backoff * 2, 30_000);
                if (mRunning) {
                    Log.w(TAG, "adb bridge: " + e);
                    setState("reconnecting");
                }
            } finally {
                mConnection = null;
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (Exception ignored) {
                        // best effort
                    }
                }
            }
            if (!mRunning) {
                return;
            }
            // Sleep in slices so a user-triggered retry does not wait out the
            // whole backoff.
            long deadline = SystemClock.elapsedRealtime() + backoff;
            while (mRunning && SystemClock.elapsedRealtime() < deadline && !mRetryNow) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (!mRunning) {
                        return;
                    }
                    break;
                }
            }
            if (mRetryNow) {
                mRetryNow = false;
                backoff = 1000;
                authFailures = 0;
            }
        }
    }

    /**
     * Parses one {@code getevent} line:
     * {@code /dev/input/event4: 0001 01f5 00000001} (type, code, value in hex).
     */
    private void handleLine(CharSequence raw) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "ev: " + raw);
        }
        int colon = -1;
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) == ':') {
                colon = i;
                break;
            }
        }
        if (colon < 0 || raw.length() < colon + 3) {
            return;
        }
        String[] parts = raw.subSequence(colon + 1, raw.length()).toString().trim().split("\\s+");
        if (parts.length < 3 || !EV_KEY.equals(parts[0])) {
            return;
        }
        int code;
        int value;
        try {
            code = Integer.parseInt(parts[1], 16);
            value = Integer.parseInt(parts[2], 16);
        } catch (NumberFormatException e) {
            return;
        }
        if (code != GEAR_SCANCODE || value != 1) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - mLastGearAt < GEAR_DEBOUNCE_MS) {
            return;
        }
        mLastGearAt = now;
        Log.i(TAG, "gear pressed");
        togglePanel();
    }

    private void togglePanel() {
        AdbConnection connection = mConnection;
        if (connection != null && !connection.isClosed()) {
            try {
                connection.exec("am start -a " + PANEL_ACTION_TOGGLE + " -n " + PANEL_COMPONENT);
                return;
            } catch (Exception e) {
                Log.w(TAG, "am start over adb failed: " + e);
            }
        }
        // Fallback for devices/configs where the app may start activities from
        // the background (overlay permission, system app, ...).
        Intent i = new Intent(this, QuickPanelActivity.class);
        i.setAction(PANEL_ACTION_TOGGLE);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "cannot open the panel", e);
        }
    }

    // ---------------------------------------------------------- notification

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.bridge_channel), NotificationManager.IMPORTANCE_MIN);
        channel.setShowBadge(false);
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);
    }

    private void startForegroundInternal() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void setState(String state) {
        if (state.equals(mState)) {
            return;
        }
        mState = state;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        Intent open = new Intent(this, QuickPanelActivity.class)
                .setAction(QuickPanelActivity.ACTION_TOGGLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return builder
                .setSmallIcon(R.drawable.ic_gear)
                .setContentTitle(getString(R.string.bridge_title))
                .setContentText(getString(R.string.bridge_state, mState))
                .setContentIntent(content)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }
}
