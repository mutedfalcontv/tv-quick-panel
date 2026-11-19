package com.ajink.tvquick;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import com.ajink.tvquick.adb.AdbConnection;
import com.ajink.tvquick.adb.AdbKey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * <p>So this service keeps the filter off and instead reads the RC600's evdev
 * node with {@code cat} as the shell user, over a localhost connection to adbd.
 * (The text form of {@code getevent} is not used: its stdout is block-buffered
 * into a pipe, so a few key presses never arrive.) Volume keys keep their native
 * long-press ramp because nothing in the input path changed.
 *
 * <h3>Launching the panel</h3>
 * A foreground service is not allowed to start activities from the background
 * on Android 10+, so the panel is started with {@code am start} on the adb
 * stream, where the shell uid has that privilege. The direct
 * {@code startActivity} fallback only fires when the app already has a
 * background-activity-launch exemption.
 *
 * <h3>Setup (one time)</h3>
 * The app authenticates as an ADB host. Push the PC's already-authorized key
 * once, so no "Allow USB debugging?" dialog is ever needed (this firmware does
 * not reliably persist a newly accepted key):
 *
 * <pre>
 * adb push ~/.android/adbkey \
 *     /sdcard/Android/data/com.ajink.tvquick/files/adbkey
 * </pre>
 *
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
    /** struct input_event type for EV_KEY. */
    private static final int EV_KEY = 0x01;

    /**
     * Record / voice keys that should open the Assistant. The RC600's record
     * button is one of these; this input device advertises both codes, and the
     * scancode still has to be confirmed with a physical press.
     */
    private static final int KEY_RECORD = 167;
    private static final int KEY_VOICECOMMAND = 217;

    /** Katniss's trampoline: the activity that starts the Assistant listening. */
    private static final String ASSIST_COMPONENT =
            "com.google.android.katniss/com.google.android.apps.tvsearch.app.launch.trampoline.SearchActivityTrampoline";

    private static final long ASSIST_DEBOUNCE_MS = 1500;


    /**
     * Size of {@code struct input_event}: timeval (two longs) + u16 type +
     * u16 code + s32 value. 16 bytes in a 32-bit process, 24 in a 64-bit one.
     */
    private final int mEventSize =
            (Build.VERSION.SDK_INT >= 23 && Process.is64Bit()) ? 24 : 16;

    /** Debounce for the gear, in case getevent repeats the down event. */
    private static final long GEAR_DEBOUNCE_MS = 250;
    /**
     * How long the first connect waits for the TV's "Allow USB debugging?"
     * prompt. Five minutes: on a cold boot the prompt can sit on screen while
     * the user finds the remote, and closing the transport early loses the
     * always-allow grant.
     */
    private static final long AUTH_WAIT_MS = 300_000;

    /**
     * How long the event stream may stay silent before the watchdog verifies
     * that the {@code cat} shell on the adbd side is still running. A standby
     * kills that child without adbd ever reporting it: no CLSE, no EOF, the TCP
     * connection just stays ESTABLISHED - which is what stranded the bridge
     * after every standby until the app was killed and reopened.
     */
    private static final long STREAM_SILENCE_MS = 60_000;

    /** How often the watchdog thread wakes up to look at the stream. */
    private static final long WATCHDOG_POLL_MS = 10_000;

    /** Budget for the liveness probe and the getevent -p listing. */
    private static final long SHELL_PROBE_TIMEOUT_MS = 10_000;

    private static final String PANEL_ACTION_TOGGLE = QuickPanelActivity.ACTION_TOGGLE;
    private static final String PANEL_COMPONENT =
            "com.ajink.tvquick/com.ajink.tvquick.QuickPanelActivity";

    private volatile boolean mRunning;
    private Thread mWorker;
    private volatile AdbConnection mConnection;
    private volatile boolean mRetryNow;
    private long mLastGearAt;
    private long mLastAssistAt;

    /** The evdev node whose {@code cat} shell is expected to be streaming, or null. */
    private volatile String mStreamDevice;
    /** When the event stream last delivered bytes; the watchdog compares against it. */
    private volatile long mLastStreamDataAt;
    /** Set by the screen-on receiver: verify the stream on the next watchdog pass. */
    private volatile boolean mVerifyNow;
    private Thread mWatchdog;

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Standby is exactly when the shell dies silently, so verify the
            // stream the moment the screen is back instead of waiting out the
            // whole silence window.
            Log.i(TAG, "screen on - verifying the event stream");
            mVerifyNow = true;
        }
    };

    /** Explicit connection phase; the notification text is its only user-visible trace. */
    enum BridgeState {
        STARTING("starting"),
        CONNECTING("connecting"),
        CONNECTED("connected"),
        AUTHORIZING("authorize"),
        RECONNECTING("reconnecting"),
        STOPPED("stopped");

        final String text;

        BridgeState(String text) {
            this.text = text;
        }
    }

    /**
     * Explicit gear latch. DOWN means the physical button is held and only a release
     * re-arms it, so the kernel's repeat events can never toggle the panel twice.
     */
    enum GearLatch {
        UP,
        DOWN
    }

    private volatile BridgeState mState = BridgeState.STARTING;
    private GearLatch mLatch = GearLatch.UP;

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

        registerReceiver(mScreenReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
        mWatchdog = new Thread(new Runnable() {
            @Override
            public void run() {
                watchdogLoop();
            }
        }, "gear-watchdog");
        mWatchdog.setDaemon(true);
        mWatchdog.start();
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
        setState(BridgeState.STOPPED);
        try {
            unregisterReceiver(mScreenReceiver);
        } catch (Exception ignored) {
            // best effort; the receiver may never have been registered
        }
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
                setState(BridgeState.CONNECTING);
                Log.i(TAG, "connecting to adb " + ADB_HOST + ":" + ADB_PORT);
                connection = AdbConnection.open(ADB_HOST, ADB_PORT, key, (int) AUTH_WAIT_MS);
                mConnection = connection;
                backoff = 1000;
                authFailures = 0;
                // A fresh transport carries no history: whatever the latch remembered
                // before the reconnect is stale, so start from UP and let the next
                // physical press arm it normally.
                mLatch = GearLatch.UP;
                setState(BridgeState.CONNECTED);
                Log.i(TAG, "adb connected, looking for the RC600 node");

                // The node number is not stable across reboots, and the BLE
                // remote only exposes it after it wakes. Resolve by name and
                // wait on the SAME transport until it appears - every new
                // transport makes this firmware raise a fresh "Allow USB
                // debugging?" dialog, so reconnecting per probe would flood
                // the screen with prompts while the remote is asleep.
                String device = findGearDevice(connection);
                int absentProbes = 0;
                while (device == null && mRunning) {
                    setState(BridgeState.RECONNECTING);
                    if (absentProbes == 0) {
                        Log.i(TAG, "RC600 input node not present yet; waiting on this transport");
                    }
                    absentProbes++;
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    // Transport trouble throws and falls into the reconnect path.
                    device = findGearDevice(connection);
                }
                if (!mRunning) {
                    return;
                }
                Log.i(TAG, "reading " + device);

                AdbConnection.Stream stream = connection.openShell("cat " + device);
                mStreamDevice = device;
                mLastStreamDataAt = SystemClock.elapsedRealtime();

                byte[] chunk = new byte[4096];
                byte[] event = new byte[mEventSize];
                int have = 0;
                int n;
                long received = 0;
                while (mRunning && (n = stream.read(chunk)) >= 0) {
                    if (n > 0) {
                        mLastStreamDataAt = SystemClock.elapsedRealtime();
                    }
                    received += n;
                    for (int i = 0; i < n; i++) {
                        event[have++] = chunk[i];
                        if (have == mEventSize) {
                            handleEvent(event);
                            have = 0;
                        }
                    }
                }
                if (mRunning) {
                    setState(BridgeState.RECONNECTING);
                    Log.w(TAG, "input stream ended after " + received + " bytes");
                    backoff = 2000;
                }
            } catch (AdbConnection.AuthRequiredException e) {
                setState(BridgeState.AUTHORIZING);
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
                    setState(BridgeState.RECONNECTING);
                }
            } finally {
                mStreamDevice = null;
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
     * Finds the RC600 evdev node by name. The node number is not stable across
     * reboots, and this BLE remote only exposes the node after it wakes, so the
     * lookup runs on every connect and the caller retries while it is absent.
     * Returns null only when the listing answered and named no RC600; transport
     * trouble propagates so the caller reconnects instead of waiting forever on
     * a dead connection.
     */
    private String findGearDevice(AdbConnection connection) throws IOException {
        AdbConnection.Stream probe = connection.openShell("getevent -p");
        StringBuilder out = new StringBuilder(4096);
        byte[] buffer = new byte[2048];
        long deadline = SystemClock.elapsedRealtime() + SHELL_PROBE_TIMEOUT_MS;
        int n;
        while ((n = probe.read(buffer)) >= 0) {
            out.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
            if (out.length() > 64 * 1024
                    || SystemClock.elapsedRealtime() > deadline) {
                break;
            }
        }
        String current = null;
        for (String line : out.toString().split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("add device")) {
                int colon = trimmed.indexOf(": ");
                if (colon > 0) {
                    current = trimmed.substring(colon + 2).trim();
                }
            } else if (current != null && trimmed.startsWith("name:")
                    && trimmed.contains("RC600")) {
                return current;
            }
        }
        return null;
    }

    /**
     * Watches the event stream for a silent death. A standby kills the
     * {@code cat} child on the adbd side without any protocol trace: this
     * firmware's adbd never sends CLSE and never closes the socket, so the
     * worker blocks in {@code Stream.read} forever on a connection that looks
     * perfectly healthy. When no byte has arrived for a while - or the screen
     * just came back on, which is when the kill happens - a side stream checks
     * the process table. If the {@code cat} is gone the connection is torn down
     * and the worker's read fails, which runs the normal reconnect path.
     */
    private void watchdogLoop() {
        while (mRunning) {
            long deadline = SystemClock.elapsedRealtime() + WATCHDOG_POLL_MS;
            while (mRunning && SystemClock.elapsedRealtime() < deadline && !mVerifyNow) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    // Conditions are re-evaluated; shutdown is noticed via mRunning.
                }
            }
            if (!mRunning) {
                return;
            }
            boolean verifyRequested = mVerifyNow;
            mVerifyNow = false;
            AdbConnection connection = mConnection;
            String device = mStreamDevice;
            if (connection == null || device == null || connection.isClosed()) {
                continue;
            }
            long since = SystemClock.elapsedRealtime() - mLastStreamDataAt;
            if (!verifyRequested && since < STREAM_SILENCE_MS) {
                continue;
            }
            if (isEventStreamAlive(connection, device)) {
                mLastStreamDataAt = SystemClock.elapsedRealtime();
                continue;
            }
            Log.w(TAG, "event stream died silently; forcing a reconnect");
            try {
                connection.close();
            } catch (Exception ignored) {
                // The worker notices the closed stream and reconnects.
            }
        }
    }

    /**
     * True when a {@code cat} process for this evdev node is still running on
     * the adbd side. The probe runs as the shell user, the same uid as adbd's
     * children, so their process table is readable: {@code pgrep -x cat} lists
     * every cat, and the cmdline filter picks the one reading this node.
     */
    private boolean isEventStreamAlive(AdbConnection connection, String device) {
        try {
            AdbConnection.Stream probe = connection.openShell(
                    "for p in $(pgrep -x cat 2>/dev/null); do "
                            + "tr '\\0' ' ' < /proc/$p/cmdline 2>/dev/null; done"
                            + " | grep -F 'cat " + device + "'");
            StringBuilder out = new StringBuilder();
            byte[] buffer = new byte[2048];
            long deadline = SystemClock.elapsedRealtime() + SHELL_PROBE_TIMEOUT_MS;
            int n;
            while ((n = probe.read(buffer)) >= 0) {
                out.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                if (out.length() > 16 * 1024
                        || SystemClock.elapsedRealtime() > deadline) {
                    break;
                }
            }
            boolean alive = out.toString().contains("cat " + device);
            Log.i(TAG, "event stream liveness: " + (alive ? "alive" : "gone"));
            return alive;
        } catch (Exception e) {
            Log.w(TAG, "event stream liveness check failed: " + e);
            return false;
        }
    }

    /**
     * Parses one {@code struct input_event}: timeval, then u16 type, u16 code
     * and s32 value, all little-endian.
     *
     * <p>The text output of {@code getevent} is not usable here: when stdout is
     * a pipe it is block-buffered, so a handful of key presses never arrive.
     * {@code cat} on the device node streams each event as it happens.
     */
    private void handleEvent(byte[] ev) {
        // Skip the timeval, which is two 4-byte or two 8-byte fields.
        final int off = mEventSize == 24 ? 16 : 8;
        int type = (ev[off] & 0xFF) | ((ev[off + 1] & 0xFF) << 8);
        if (type != EV_KEY) {
            return;
        }
        int code = (ev[off + 2] & 0xFF) | ((ev[off + 3] & 0xFF) << 8);
        int value = (ev[off + 4] & 0xFF)
                | ((ev[off + 5] & 0xFF) << 8)
                | ((ev[off + 6] & 0xFF) << 16)
                | ((ev[off + 7] & 0xFF) << 24);
        if (code == GEAR_SCANCODE) {
            handleGear(value);
        } else if (code == KEY_RECORD || code == KEY_VOICECOMMAND) {
            handleAssist(code, value);
        }
    }

    private void handleGear(int value) {
        if (value == 0) {
            // Release: re-arm the latch.
            mLatch = GearLatch.UP;
            return;
        }
        if (value != 1 || mLatch == GearLatch.DOWN) {
            // Repeat of a held key, or a value we do not care about.
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - mLastGearAt < GEAR_DEBOUNCE_MS) {
            return;
        }
        mLatch = GearLatch.DOWN;
        mLastGearAt = now;
        Log.i(TAG, "gear pressed");
        togglePanel();
    }

    /**
     * The physical remote's record/voice key: start the Assistant with the TV's
     * own microphone. The remote itself is a HID device only, so its microphone
     * is not an audio source Android can use.
     */
    private void handleAssist(int code, int value) {
        if (value != 1) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - mLastAssistAt < ASSIST_DEBOUNCE_MS) {
            return;
        }
        mLastAssistAt = now;
        Log.i(TAG, "assist key " + code);
        AdbConnection connection = mConnection;
        if (connection != null && !connection.isClosed()) {
            try {
                connection.exec("am start -n " + ASSIST_COMPONENT);
                return;
            } catch (Exception e) {
                Log.w(TAG, "am start for the Assistant failed: " + e);
            }
        }
        try {
            startActivity(new Intent(Intent.ACTION_ASSIST)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Log.w(TAG, "cannot open the Assistant", e);
        }
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

    /**
     * Records the connection phase and refreshes the notification that exposes it.
     * Only transitions are published, so repeated calls are free.
     */
    private void setState(BridgeState state) {
        synchronized (this) {
            if (state == mState) {
                return;
            }
            mState = state;
        }
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
                .setContentText(getString(R.string.bridge_state, mState.text))
                .setContentIntent(content)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }
}
