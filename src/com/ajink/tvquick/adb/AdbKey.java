package com.ajink.tvquick.adb;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

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
 * <p>Generated once on first use and kept in SharedPreferences. The TV shows the
 * usual "Allow USB debugging?" dialog the first time the bridge connects; the
 * fingerprint is derived from this key, so accepting it once is enough until the
 * app is uninstalled (which deletes the private key and therefore requires a new
 * approval).
 */
public final class AdbKey {

    private static final String TAG = "TvGearBridge";
    private static final String PREFS = "adb_bridge";
    private static final String KEY_PKCS8 = "private_key_pkcs8";

    /** Name shown in the TV's authorization dialog and in adb_keys. */
    private static final String KEY_NAME = "tvquick@tv";

    private AdbKey() {
    }

    public static synchronized PrivateKey get(Context context) {
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
        return (encoded + " " + KEY_NAME + "\0").getBytes(StandardCharsets.UTF_8);
    }

    private static void putWords(ByteBuffer buffer, BigInteger value, int words) {
        for (int i = 0; i < words; i++) {
            buffer.putInt(value.shiftRight(32 * i).intValue());
        }
    }
}
