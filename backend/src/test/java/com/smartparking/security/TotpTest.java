package com.smartparking.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpTest {
    // RFC 6238 Appendix B, SHA-1 seed "12345678901234567890" (8-digit vectors truncated to 6 digits).
    private static final String SEED = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void matchesRfc6238Vectors() {
        assertThat(Totp.code(SEED, Totp.step(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(Totp.code(SEED, Totp.step(Instant.ofEpochSecond(1111111109)))).isEqualTo("081804");
        assertThat(Totp.code(SEED, Totp.step(Instant.ofEpochSecond(1234567890)))).isEqualTo("005924");
        assertThat(Totp.code(SEED, Totp.step(Instant.ofEpochSecond(2000000000)))).isEqualTo("279037");
    }

    @Test
    void verifyToleratesOneStepDriftOnly() {
        var now = Instant.ofEpochSecond(1_700_000_000);
        long step = Totp.step(now);
        assertThat(Totp.verify(SEED, Totp.code(SEED, step - 1), now)).hasValue(step - 1);
        assertThat(Totp.verify(SEED, Totp.code(SEED, step + 1), now)).hasValue(step + 1);
        assertThat(Totp.verify(SEED, Totp.code(SEED, step + 3), now)).isEmpty();
        assertThat(Totp.verify(SEED, "12345", now)).isEmpty();
        assertThat(Totp.verify(SEED, null, now)).isEmpty();
    }

    @Test
    void base32RoundTrip() {
        String s = Totp.newSecret();
        assertThat(s).matches("[A-Z2-7]{32}");
        assertThat(Totp.base32(Totp.decode(s))).isEqualTo(s);
    }

    @Test
    void cipherRoundTripAndRandomIv() {
        var c = new SecretCipher("dGVzdC1vbmx5LWtleS0zMi1ieXRlcy1sb25nLSEhISE=");
        String a = c.encrypt("secreto"), b = c.encrypt("secreto");
        assertThat(a).isNotEqualTo(b);
        assertThat(c.decrypt(a)).isEqualTo("secreto");
        assertThat(new SecretCipher("").configured()).isFalse();
    }
}
