package me.skerik.justlogs.util;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/** Keyed hashing (HMAC-SHA256): IP addresses cannot be brute-forced from a database dump without {@code secrets.key}. */
public final class IpHmac {

    private IpHmac() {
    }

    /** @return base64url (no padding, 43 characters) */
    public static String hashIp(SecretKey key, String ip) {
        byte[] digest = hmacSha256(key, normalizeIp(ip).getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    public static String normalizeIp(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        String s = raw.strip().toLowerCase(Locale.ROOT);
        if (s.startsWith("/")) s = s.substring(1);
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        int zone = s.indexOf('%');
        if (zone > 0) s = s.substring(0, zone);
        return s;
    }

    public static byte[] hmacSha256(SecretKey key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
