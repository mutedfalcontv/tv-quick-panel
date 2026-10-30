package com.ajink.tvquick.adb;

import android.util.Log;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Minimal ADB *host* client: enough to talk to the device's own adbd over
 * localhost, authenticate with an app-owned RSA key, and run {@code shell:}
 * streams.
 *
 * <h3>Why this exists</h3>
 * The gear button on the RC600 remote only reports scancode 501 (kernel code
 * {@code 0x01f5}). The only two ways to see it from an app are
 *
 * <ol>
 *   <li>an accessibility service with {@code FLAG_REQUEST_FILTER_KEY_EVENTS}, or
 *   <li>reading {@code /dev/input/event*} through {@code getevent} as the shell
 *       user.
 * </ol>
 *
 * Option 1 is unusable on this TV: enabling the key filter flips
 * {@code InputDispatcher.mInputFilterEnabled}, every key is then routed through
 * {@code KeyboardInterceptor}, and for volume keys the interceptor consumes them
 * before any {@code KeyEntry} is created - so the platform never generates key
 * repeats and volume long-press stops ramping. This was measured on the device:
 * {@code dumpsys input} reports {@code InputFilterEnabled: true} with either
 * Projectivy's or this app's accessibility service enabled, and {@code false}
 * with both off.
 *
 * Option 2 leaves the input pipeline completely untouched, so the volume ramp
 * stays native. That is what this class implements.
 *
 * <h3>Protocol notes</h3>
 * <ul>
 *   <li>Every packet is a 24-byte little-endian header
 *       (command, arg0, arg1, data_length, data_crc32, magic=command^0xffffffff)
 *       followed by the payload.</li>
 *   <li>Host sends {@code CNXN}, adbd answers {@code AUTH(TOKEN)} with a 20-byte
 *       token. The host replies {@code AUTH(SIGNATURE)} with
 *       {@code RSA_sign(NID_sha1, token)} - which is exactly what
 *       {@code Signature("SHA1withRSA")} produces. adbd then sends {@code CNXN}
 *       when the key is in {@code /data/misc/adb/adb_keys}. A second
 *       {@code AUTH(TOKEN)} means the key was rejected (or the prompt was
 *       dismissed).</li>
 *   <li>{@code OPEN} with a {@code "shell:<command>\0"} destination starts a
 *       shell process; its stdout arrives as {@code WRTE} packets that must be
 *       acked with {@code OKAY} for flow control.</li>
 * </ul>
 */
public final class AdbConnection implements Closeable {

    private static final int A_VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 256 * 1024;

    private static final int CMD_CNXN = 0x4e584e43; // "CNXN"
    private static final int CMD_AUTH = 0x48545541; // "AUTH"
    private static final int CMD_OPEN = 0x4e45504f; // "OPEN"
    private static final int CMD_OKAY = 0x59414b4f; // "OKAY"
    private static final int CMD_CLSE = 0x45534c43; // "CLSE"
    private static final int CMD_WRTE = 0x45545257; // "WRTE"

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    private static final byte[] EMPTY = new byte[0];

    /** The device did not accept our RSA key (prompt pending or dismissed). */
    public static final class AuthRequiredException extends IOException {
        public AuthRequiredException(String message) {
            super(message);
        }
    }

    /** One open ADB stream (a shell command's stdout). */
    public static final class Stream implements Closeable {
        final int localId;
        final LinkedBlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        final CountDownLatch ready = new CountDownLatch(1);
        private volatile int remoteId;
        private volatile boolean closed;

        Stream(int localId) {
            this.localId = localId;
        }

        void markReady(int remoteId) {
            this.remoteId = remoteId;
            ready.countDown();
        }

        void offer(byte[] data) {
            if (data != null && data.length > 0) {
                chunks.offer(data);
            }
        }

        void markClosed() {
            closed = true;
            ready.countDown();
        }

        /**
         * Blocks until some output is available.
         *
         * @return number of bytes copied, or -1 when the stream has ended
         */
        public int read(byte[] dst) throws IOException {
            while (true) {
                byte[] head;
                try {
                    head = chunks.poll(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
                if (head != null) {
                    if (head.length <= dst.length) {
                        System.arraycopy(head, 0, dst, 0, head.length);
                        return head.length;
                    }
                    // Split: keep the tail for the next call.
                    System.arraycopy(head, 0, dst, 0, dst.length);
                    byte[] rest = new byte[head.length - dst.length];
                    System.arraycopy(head, dst.length, rest, 0, rest.length);
                    chunks.offer(rest);
                    return dst.length;
                }
                if (closed) {
                    return -1;
                }
            }
        }

        @Override
        public void close() {
            closed = true;
        }

        int remoteId() {
            return remoteId;
        }
    }

    private static final class Packet {
        int command;
        int arg0;
        int arg1;
        byte[] payload = EMPTY;
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Map<Integer, Stream> streams = new HashMap<>();
    private int nextLocalId = 1;
    private volatile boolean closed;
    private Thread reader;

    private AdbConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    /**
     * Connects, authenticates and starts the packet reader.
     *
     * @param authTimeoutMs how long to wait for the on-screen authorization
     *                      prompt to be accepted (the first time this app
     *                      connects the TV asks the user)
     */
    public static AdbConnection open(String host, int port, PrivateKey key, int authTimeoutMs)
            throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), Math.min(authTimeoutMs, 8000));
            s.setTcpNoDelay(true);
            s.setSoTimeout(authTimeoutMs);
            AdbConnection c = new AdbConnection(s);
            c.handshake(key);
            s.setSoTimeout(0);
            c.startReader();
            return c;
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
                // best effort
            }
            throw e;
        }
    }

    private void handshake(PrivateKey key) throws IOException {
        send(CMD_CNXN, A_VERSION, MAX_PAYLOAD, ("host::features=shell_v2,adb_native_data:1").getBytes(StandardCharsets.UTF_8));
        int step = 0;
        while (true) {
            Packet p = readPacket();
            if (p.command == CMD_CNXN) {
                return;
            }
            if (p.command == CMD_AUTH && p.arg0 == AUTH_TOKEN) {
                if (step == 0) {
                    // Prove we own the key adbd may already know.
                    send(CMD_AUTH, AUTH_SIGNATURE, 0, sign(key, p.payload));
                    step = 1;
                    continue;
                }
                if (step == 1) {
                    // Unknown key: hand over the public half so adbd can show
                    // the "Allow USB debugging?" dialog. It blocks until the
                    // user answers, then sends CNXN.
                    send(CMD_AUTH, AUTH_RSAPUBLICKEY, 0, AdbKey.publicKeyPayload(key));
                    step = 2;
                    continue;
                }
                throw new AuthRequiredException("device rejected the adb key");
            }
            throw new IOException("unexpected handshake packet 0x" + Integer.toHexString(p.command));
        }
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws IOException {
        try {
            Signature signature = Signature.getInstance("SHA1withRSA");
            signature.initSign(key);
            signature.update(token);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IOException("cannot sign the adb token", e);
        }
    }

    private static final boolean DEBUG_PACKETS = false;
    private int mTraceCount;

    private void startReader() {
        reader = new Thread(new Runnable() {
            @Override
            public void run() {
                Log.i("TvGearBridge", "reader thread start");
                try {
                    while (!closed) {
                        Packet p = readPacket();
                        Stream stream = streamFor(p.arg1);
                        if (DEBUG_PACKETS && mTraceCount < 24) {
                            mTraceCount++;
                            Log.d("TvGearBridge", "pkt " + hex(p.command) + " a0=" + p.arg0
                                    + " a1=" + p.arg1 + " len=" + p.payload.length
                                    + (stream == null ? " (no stream)" : ""));
                        }
                        if (p.command == CMD_WRTE) {
                            // Flow control: adbd blocks the stream until acked.
                            send(CMD_OKAY, p.arg0, p.arg1, EMPTY);
                            if (stream != null) {
                                stream.offer(p.payload);
                            }
                        } else if (p.command == CMD_OKAY) {
                            if (stream != null) {
                                stream.markReady(p.arg0);
                            }
                        } else if (p.command == CMD_CLSE) {
                            if (stream != null) {
                                stream.markClosed();
                            }
                        }
                    }
                } catch (IOException e) {
                    if (!closed) {
                        closeQuietly();
                    }
                }
            }
        }, "adb-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private Stream streamFor(int localId) {
        synchronized (streams) {
            return streams.get(localId);
        }
    }

    /** Opens a {@code shell:<command>} stream. */
    public Stream openShell(String command) throws IOException {
        final Stream stream;
        final int localId;
        synchronized (streams) {
            localId = nextLocalId++;
            stream = new Stream(localId);
            streams.put(localId, stream);
        }
        send(CMD_OPEN, localId, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        try {
            if (!stream.ready.await(10, TimeUnit.SECONDS)) {
                throw new IOException("no OKAY for " + command);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while opening " + command, e);
        }
        return stream;
    }

    /**
     * Runs a shell command and does not wait for its output. Used for
     * {@code am start}, where the shell uid is allowed to start activities from
     * the background while a plain foreground service is not.
     */
    public void exec(String command) throws IOException {
        final Stream stream = openShell(command);
        // Output is drained and acked by the reader thread; drop the stream.
        synchronized (streams) {
            streams.remove(stream.localId);
        }
    }

    private void send(int command, int arg0, int arg1, byte[] payload) throws IOException {
        byte[] header = new byte[24];
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(command);
        b.putInt(arg0);
        b.putInt(arg1);
        b.putInt(payload.length);
        b.putInt(checksum(payload));
        b.putInt(command ^ 0xFFFFFFFF);
        synchronized (out) {
            out.write(header);
            if (payload.length > 0) {
                out.write(payload);
            }
            out.flush();
        }
    }

    private Packet readPacket() throws IOException {
        byte[] header = new byte[24];
        readFully(header);
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        Packet p = new Packet();
        p.command = b.getInt();
        p.arg0 = b.getInt();
        p.arg1 = b.getInt();
        int length = b.getInt();
        b.getInt(); // checksum: not validated, we do not need it
        int magic = b.getInt();
        if ((p.command ^ 0xFFFFFFFF) != magic) {
            throw new IOException("bad packet magic");
        }
        if (length < 0 || length > MAX_PAYLOAD * 4) {
            throw new IOException("bad packet length " + length);
        }
        if (length > 0) {
            p.payload = new byte[length];
            readFully(p.payload);
        }
        return p;
    }

    private void readFully(byte[] dst) throws IOException {
        int off = 0;
        while (off < dst.length) {
            int n = in.read(dst, off, dst.length - off);
            if (n < 0) {
                throw new EOFException("adb connection closed");
            }
            off += n;
        }
    }

    private static int checksum(byte[] payload) {
        int sum = 0;
        for (byte b : payload) {
            sum += b & 0xFF;
        }
        return sum;
    }

    private static String hex(int value) {
        switch (value) {
            case CMD_CNXN: return "CNXN";
            case CMD_AUTH: return "AUTH";
            case CMD_OPEN: return "OPEN";
            case CMD_OKAY: return "OKAY";
            case CMD_CLSE: return "CLSE";
            case CMD_WRTE: return "WRTE";
            default: return "0x" + Integer.toHexString(value);
        }
    }

    public boolean isClosed() {
        return closed;
    }

    private void closeQuietly() {
        try {
            close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        synchronized (streams) {
            for (Stream s : streams.values()) {
                s.markClosed();
            }
            streams.clear();
        }
        socket.close();
    }
}
