package com.smartparking.security;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 TOTP (HMAC-SHA1, 30 s, 6 digits) compatible with common authenticator apps. */
public final class Totp {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();
    private Totp() {}

    public static String newSecret() {
        byte[] bytes = new byte[20]; RANDOM.nextBytes(bytes);
        return base32(bytes);
    }

    public static long step(Instant at) { return Math.floorDiv(at.getEpochSecond(), 30); }

    public static String code(String secret, long step) {
        try {
            var mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decode(secret), "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0xf;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    /** Accepts ±1 step of clock drift; returns the matched step so callers can reject replays. */
    public static OptionalLong verify(String secret, String code, Instant now) {
        if (code == null || !code.matches("\\d{6}")) return OptionalLong.empty();
        long current = step(now);
        for (long s = current - 1; s <= current + 1; s++) {
            if (java.security.MessageDigest.isEqual(code(secret, s).getBytes(), code.getBytes())) return OptionalLong.of(s);
        }
        return OptionalLong.empty();
    }

    public static String uri(String issuer, String account, String secret) {
        var enc = (java.util.function.Function<String, String>) v -> java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + enc.apply(issuer) + ":" + enc.apply(account) + "?secret=" + secret + "&issuer=" + enc.apply(issuer) + "&algorithm=SHA1&digits=6&period=30";
    }

    static String base32(byte[] data) {
        var sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff); bits += 8;
            while (bits >= 5) { sb.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31)); bits -= 5; }
        }
        if (bits > 0) sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    static byte[] decode(String s) {
        String clean = s.replace("=", "").replace(" ", "").toUpperCase();
        var out = new java.io.ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char c : clean.toCharArray()) {
            int v = ALPHABET.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("Base32 inválido");
            buffer = (buffer << 5) | v; bits += 5;
            if (bits >= 8) { out.write((buffer >> (bits - 8)) & 0xff); bits -= 8; }
        }
        return out.toByteArray();
    }
}
