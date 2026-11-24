package com.smartparking.security;

import com.smartparking.common.errors.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for secrets stored at rest (TOTP seeds, device credentials).
 * The key comes only from APP_ENCRYPTION_KEY (base64, 32 bytes). Without it, features needing encryption refuse to run.
 */
@Component
public class SecretCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public SecretCipher(@Value("${app.security.encryption-key:}") String base64Key) {
        SecretKeySpec parsed = null;
        if (base64Key != null && !base64Key.isBlank()) {
            byte[] raw = Base64.getDecoder().decode(base64Key.trim());
            if (raw.length != 32) throw new IllegalStateException("APP_ENCRYPTION_KEY debe tener 32 bytes en base64");
            parsed = new SecretKeySpec(raw, "AES");
        }
        this.key = parsed;
    }

    public boolean configured() { return key != null; }

    private SecretKeySpec requireKey() {
        if (key == null) throw new ApiException(503, "Cifrado no configurado: defina APP_ENCRYPTION_KEY");
        return key;
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[12]; RANDOM.nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return "v1:" + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Error de cifrado", e); }
    }

    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith("v1:")) throw new IllegalStateException("Formato cifrado desconocido");
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(3));
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, requireKey(), new GCMParameterSpec(128, in, 0, 12));
            return new String(cipher.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Error de descifrado", e); }
    }
}
