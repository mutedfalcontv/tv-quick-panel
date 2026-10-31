package com.ajink.tvquick.adb;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * The RSA key this app presents to adbd.
 *
 * <p>If {@code adbkey} (a PEM PKCS#8 key, the file an ADB host keeps in
 * {@code ~/.android}) is present in the app's external files directory, it is
 * used. Pushing the same key the PC already authorized means the bridge
 * authenticates with no "Allow USB debugging?" dialog at all, and nothing
 * depends on the TV persisting a key it has just been shown.
 *
 * <p>Without that file the key is generated once and kept in SharedPreferences.
 * The TV then shows the usual "Allow USB debugging?" dialog on first connect;
 * the dialog and the persistence of the approval are unreliable on this
 * firmware, which is why the pushed key is the documented setup.
 */
public final class AdbKey {

    private static final String TAG = "TvGearBridge";
    private static final String PREFS = "adb_bridge";
    private static final String KEY_PKCS8 = "private_key_pkcs8";

    /** Name shown in the TV's authorization dialog and in adb_keys. */
    private static final String KEY_NAME = "tvquick@tv";

    /**
     * Name sent with the public key. adbd matches an adb_keys entry by the whole
     * key line, name included: the same key material under a different name is a
     * different entry, and shows the authorization dialog again. When a pushed
     * key is used, its own name (from {@code adbkey.pub}) is sent.
     */
    private static volatile String sName = KEY_NAME;

    private AdbKey() {
    }

    public static synchronized PrivateKey get(Context context) {
        PrivateKey pushed = readPushedKey(context);
        if (pushed != null) {
            sName = readPushedKeyName(context);
            Log.i(TAG, "using pushed adb key, name=" + sName);
            return pushed;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String stored = prefs.getString(KEY_PKCS8, null);
        if (stored != null) {
            try {
                byte[] der = Base64.decode(stored, Base64.NO_WRAP);
                return KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(der));
            } catch (Exception e) {
                Log.w(TAG, "stored adb key unusable, generating a new one", e);
            }
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            prefs.edit()
                    .putString(KEY_PKCS8,
                            Base64.encodeToString(pair.getPrivate().getEncoded(), Base64.NO_WRAP))
                    .apply();
            Log.i(TAG, "generated adb key; the TV will ask to authorize it once");
            return pair.getPrivate();
        } catch (Exception e) {
            throw new IllegalStateException("cannot create the adb key", e);
        }
    }

    /** Reads {@code adbkey} from the app's external files dir, if present. */
    private static PrivateKey readPushedKey(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            return null;
        }
        File file = new File(dir, "adbkey");
        if (!file.isFile()) {
            return null;
        }
        StringBuilder body = new StringBuilder(768);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.US_ASCII))) {
            boolean inBody = false;
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("-----BEGIN")) {
                    inBody = true;
                } else if (line.startsWith("-----END")) {
                    break;
                } else if (inBody) {
                    body.append(line);
                }
            }
            byte[] der = Base64.decode(body.toString(), Base64.NO_WRAP);
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            Log.w(TAG, "pushed adb key unusable: " + e);
            return null;
        }
    }

    /** Reads the key name from {@code adbkey.pub} ("<base64> <name>"). */
    private static String readPushedKeyName(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            return KEY_NAME;
        }
        File file = new File(dir, "adbkey.pub");
        if (!file.isFile()) {
            return KEY_NAME;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.US_ASCII))) {
            String line = reader.readLine();
            if (line != null) {
                int space = line.indexOf(' ');
                if (space > 0) {
                    String name = line.substring(space + 1).trim();
                    if (!name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot read adbkey.pub: " + e);
        }
        return KEY_NAME;
    }

    /**
     * Encodes the public half in the format adbd expects for
     * {@code AUTH(RSAPUBLICKEY)}: base64 of
     *
     * <pre>
     * uint32 modulus_size_words;
     * uint32 n0inv;              // -1 / n mod 2^32
     * uint32 modulus[64];        // little endian
     * uint32 rr[64];             // R^2 mod n, R = 2^2048
     * uint32 exponent;           // 65537
     * </pre>
     *
     * 524 bytes total; adbd rejects anything else with "Invalid base64 key",
     * which also means the key is never persisted in adb_keys and the
     * authorization dialog comes back after every reconnect.
     *
     * Followed by {@code " name\0"}. Without this packet adbd cannot show the
     * authorization dialog at all - it has no key to display, so it silently
     * rejects the signature (the device log shows only
     * {@code Calling send_auth_request...} twice).
     */
    public static byte[] publicKeyPayload(PrivateKey privateKey) {
        if (!(privateKey instanceof RSAPrivateCrtKey)) {
            throw new IllegalArgumentException("not an RSA CRT key");
        }
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) privateKey;
        BigInteger modulus = crt.getModulus();
        int words = (modulus.bitLength() + 31) / 32;

        BigInteger two32 = BigInteger.ONE.shiftLeft(32);
        // n0inv = -(1 / n) mod 2^32
        BigInteger n0inv = two32.subtract(modulus.modInverse(two32)).mod(two32);
        // rr = (2^(32*words))^2 mod n
        BigInteger r = BigInteger.ONE.shiftLeft(32 * words);
        BigInteger rr = r.multiply(r).mod(modulus);

        ByteBuffer buffer = ByteBuffer.allocate(8 + words * 8 + 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(words);
        buffer.putInt(n0inv.intValue());
        putWords(buffer, modulus, words);
        putWords(buffer, rr, words);
        buffer.putInt(crt.getPublicExponent().intValue());

        String encoded = Base64.encodeToString(buffer.array(), Base64.NO_WRAP);
        return (encoded + " " + sName + "\0").getBytes(StandardCharsets.UTF_8);
    }

    private static void putWords(ByteBuffer buffer, BigInteger value, int words) {
        for (int i = 0; i < words; i++) {
            buffer.putInt(value.shiftRight(32 * i).intValue());
        }
    }
}
