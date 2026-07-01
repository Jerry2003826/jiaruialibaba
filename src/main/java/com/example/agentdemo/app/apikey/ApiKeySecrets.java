package com.example.agentdemo.app.apikey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates strong-random runtime API keys and hashes them for storage. The plaintext is
 * {@code app_<random>}; only the SHA-256 hash is ever persisted, and lookups are by hash.
 */
public final class ApiKeySecrets {

    /** Plaintext prefix that identifies an app API key (vs. a JWT) on the wire. */
    public static final String PREFIX = "app_";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private ApiKeySecrets() {
    }

    /** Generates a new public key id (safe to store and show). */
    public static String newKeyId() {
        return "ak_" + randomToken(9);
    }

    /** Generates a new plaintext key: {@code app_<32 random bytes, base64url>}. */
    public static String newPlaintextKey() {
        return PREFIX + randomToken(32);
    }

    /** Returns the lowercase hex SHA-256 of the plaintext key. */
    public static String hash(String plaintextKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(plaintextKey.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /** True when a presented credential looks like an app API key. */
    public static boolean looksLikeApiKey(String candidate) {
        return candidate != null && candidate.startsWith(PREFIX);
    }

    private static String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return URL_ENCODER.encodeToString(buffer);
    }

}
