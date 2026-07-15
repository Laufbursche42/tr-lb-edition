// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.srt;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Hardware-backed encryption for the SRT streaming URL.
 *
 * The AES-256-GCM key lives in the Android Keystore under alias "lb_srt_key" and is
 * generated on first use. Because the key material never leaves the device's secure
 * hardware (TEE/StrongBox where available) and is non-exportable, a stored value cannot
 * be decrypted off-device even if someone extracts it from the APK or app storage. This
 * replaces the old RC4-with-embedded-key scheme, whose key shipped inside the binary.
 *
 * Stored format: "k1:" + Base64(iv[12] || ciphertext+tag), NO_WRAP. The "k1:" prefix lets
 * the JS layer distinguish new values from legacy RC4 values and fall back accordingly.
 *
 * Requires API 23+. All operations are wrapped in try/catch and return null on any failure.
 */
public final class SrtCrypto {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "lb_srt_key";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final String PREFIX = "k1:";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private SrtCrypto() {
    }

    /**
     * Encrypts the given plaintext with the hardware-backed key.
     *
     * @return "k1:" + Base64(iv[12] + AES-GCM ciphertext+tag), or null on failure.
     */
    public static String encrypt(String plain) {
        try {
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Decrypts a value produced by {@link #encrypt(String)}.
     *
     * @return plaintext, or null if the input is not "k1:" prefixed or on failure.
     */
    public static String decrypt(String stored) {
        try {
            if (stored == null || !stored.startsWith(PREFIX)) {
                return null;
            }
            byte[] all = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
            if (all.length <= IV_LEN) {
                return null;
            }
            byte[] iv = new byte[IV_LEN];
            byte[] ct = new byte[all.length - IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            System.arraycopy(all, IV_LEN, ct, 0, ct.length);
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Loads the existing hardware-backed key or generates a new non-exportable one.
     */
    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build();
        kg.init(spec);
        return kg.generateKey();
    }
}
