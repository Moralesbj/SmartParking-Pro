package com.smartparking.modules.auth.application;

import com.smartparking.common.errors.ApiException;
import com.smartparking.modules.notifications.application.MailGateway;
import com.smartparking.security.SecretCipher;
import com.smartparking.security.Totp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    public record Client(String ip, String userAgent) {}
    public record LoginResult(UUID userId, boolean mfaRequired, Instant authenticatedAt) {}
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DUMMY_HASH = "$2a$12$wJ/YhDq4rL1FIQmFzf2pIOcNihEM5dULNPTzqBqJv6K1EmvT4CPk2";
    private final JdbcClient db; private final PasswordEncoder encoder; private final SecretCipher cipher; private final MailGateway mail;
    private final String publicUrl;
    private final boolean mfaFeature;
    /** Minimum password length (NIST SP 800-63B minimum). */
    public static final int MIN_PASSWORD_LENGTH = 8, MAX_PASSWORD_LENGTH = 72;

    public AuthService(JdbcClient db, PasswordEncoder encoder, SecretCipher cipher, MailGateway mail, @Value("${app.public-url:http://localhost:5173}") String publicUrl,
                       @Value("${app.security.mfa-enabled:false}") boolean mfaFeature) {
        this.db = db; this.encoder = encoder; this.cipher = cipher; this.mail = mail; this.publicUrl = publicUrl; this.mfaFeature = mfaFeature;
    }

    /** Whether two-step verification exists in this deployment (app.security.mfa-enabled). */
    public boolean mfaAvailable() { return mfaFeature; }

    private void requireMfaFeature() {
        if (!mfaFeature) throw new ApiException(404, "La verificación en dos pasos está desactivada", "MFA_DISABLED");
    }

    public static String sha256(String v) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String token(int bytes) { byte[] b = new byte[bytes]; RANDOM.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }

    /** Runs in the caller's transaction; callers use noRollbackFor so failures and lockouts persist. */
    void access(UUID user, String email, String outcome, Client c) {
        db.sql("INSERT INTO access_events(id,user_id,email_fingerprint,outcome,ip,user_agent) VALUES(:id,:user,:fp,:outcome,:ip,:ua)")
            .param("id", UUID.randomUUID()).param("user", user).param("fp", email == null ? null : sha256("access:" + email))
            .param("outcome", outcome).param("ip", c.ip()).param("ua", c.userAgent() == null ? null : c.userAgent().substring(0, Math.min(200, c.userAgent().length()))).update();
    }

    @Transactional(noRollbackFor = ApiException.class)
    public LoginResult login(String rawEmail, String password, Client c) {
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        var row = db.sql("SELECT id,password_hash,failed_attempts,locked_until,mfa_enabled FROM users WHERE email=:email AND active FOR UPDATE")
            .param("email", email).query().listOfRows().stream().findFirst();
        if (row.isEmpty()) { encoder.matches(password, DUMMY_HASH); access(null, email, "LOGIN_FAILURE", c); throw new ApiException(401, "Credenciales inválidas"); }
        var u = row.get(); UUID id = (UUID) u.get("id");
        var locked = (Timestamp) u.get("locked_until");
        if (locked != null && locked.toInstant().isAfter(Instant.now())) { access(id, null, "LOCKED", c); throw new ApiException(429, "Demasiados intentos. Intente más tarde"); }
        if (!encoder.matches(password, (String) u.get("password_hash"))) {
            db.sql("UPDATE users SET failed_attempts=failed_attempts+1,locked_until=CASE WHEN failed_attempts>=4 THEN now()+interval '15 minutes' ELSE locked_until END WHERE id=:id").param("id", id).update();
            access(id, null, "LOGIN_FAILURE", c);
            throw new ApiException(401, "Credenciales inválidas");
        }
        db.sql("UPDATE users SET failed_attempts=0,locked_until=NULL WHERE id=:id").param("id", id).update();
        boolean mfa = mfaFeature && Boolean.TRUE.equals(u.get("mfa_enabled"));
        access(id, null, mfa ? "MFA_REQUIRED" : "LOGIN_SUCCESS", c);
        return new LoginResult(id, mfa, Instant.now());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void verifyMfa(UUID user, String code, String recoveryCode, Client c) {
        requireMfaFeature();
        var u = db.sql("SELECT mfa_secret_encrypted,mfa_last_step,mfa_enabled FROM users WHERE id=:id AND active FOR UPDATE").param("id", user).query().singleRow();
        if (!Boolean.TRUE.equals(u.get("mfa_enabled"))) throw new ApiException(409, "MFA no está activo");
        if (recoveryCode != null && !recoveryCode.isBlank()) {
            int used = db.sql("UPDATE mfa_recovery_codes SET used_at=now() WHERE user_id=:u AND code_hash=:h AND used_at IS NULL")
                .param("u", user).param("h", sha256(recoveryCode.trim().toUpperCase(Locale.ROOT))).update();
            if (used != 1) { access(user, null, "MFA_FAILURE", c); throw new ApiException(401, "Código inválido"); }
        } else {
            var step = Totp.verify(cipher.decrypt((String) u.get("mfa_secret_encrypted")), code, Instant.now());
            Long last = (Long) u.get("mfa_last_step");
            if (step.isEmpty() || (last != null && step.getAsLong() <= last)) { access(user, null, "MFA_FAILURE", c); throw new ApiException(401, "Código inválido o ya utilizado"); }
            db.sql("UPDATE users SET mfa_last_step=:s WHERE id=:id").param("s", step.getAsLong()).param("id", user).update();
        }
        access(user, null, "MFA_SUCCESS", c);
    }

    @Transactional
    public Map<String, String> setupMfa(UUID user) {
        requireMfaFeature();
        var u = db.sql("SELECT email,mfa_enabled FROM users WHERE id=:id AND active").param("id", user).query().singleRow();
        if (Boolean.TRUE.equals(u.get("mfa_enabled"))) throw new ApiException(409, "MFA ya está activo");
        String secret = Totp.newSecret();
        db.sql("UPDATE users SET mfa_secret_encrypted=:s WHERE id=:id").param("s", cipher.encrypt(secret)).param("id", user).update();
        return Map.of("secret", secret, "otpauthUri", Totp.uri("SmartParking Pro", (String) u.get("email"), secret));
    }

    @Transactional
    public List<String> enableMfa(UUID user, String code, Client c) {
        requireMfaFeature();
        var u = db.sql("SELECT mfa_secret_encrypted,mfa_enabled FROM users WHERE id=:id AND active FOR UPDATE").param("id", user).query().singleRow();
        if (Boolean.TRUE.equals(u.get("mfa_enabled"))) throw new ApiException(409, "MFA ya está activo");
        if (u.get("mfa_secret_encrypted") == null) throw new ApiException(409, "Inicie la configuración de MFA");
        var step = Totp.verify(cipher.decrypt((String) u.get("mfa_secret_encrypted")), code, Instant.now());
        if (step.isEmpty()) throw new ApiException(422, "Código inválido");
        db.sql("UPDATE users SET mfa_enabled=true,mfa_last_step=:s,updated_at=now() WHERE id=:id").param("s", step.getAsLong()).param("id", user).update();
        db.sql("DELETE FROM mfa_recovery_codes WHERE user_id=:u").param("u", user).update();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String rc = token(6).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "X");
            codes.add(rc);
            db.sql("INSERT INTO mfa_recovery_codes(id,user_id,code_hash) VALUES(:id,:u,:h)").param("id", UUID.randomUUID()).param("u", user).param("h", sha256(rc)).update();
        }
        access(user, null, "MFA_ENABLED", c);
        return codes;
    }

    /** Always succeeds from the caller's perspective to avoid account enumeration. */
    @Transactional
    public void logout(UUID user, Client c) { access(user, null, "LOGOUT", c); }

    @Transactional
    public void forgotPassword(String rawEmail, Client c) {
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        var user = db.sql("SELECT id FROM users WHERE email=:e AND active").param("e", email).query(UUID.class).optional();
        if (user.isEmpty()) { access(null, email, "PASSWORD_RESET_REQUESTED", c); return; }
        db.sql("UPDATE password_reset_tokens SET used_at=now() WHERE user_id=:u AND used_at IS NULL").param("u", user.get()).update();
        String token = token(32);
        db.sql("INSERT INTO password_reset_tokens(id,user_id,token_hash,expires_at) VALUES(:id,:u,:h,now()+interval '30 minutes')")
            .param("id", UUID.randomUUID()).param("u", user.get()).param("h", sha256(token)).update();
        mail.send(email, "Restablecer contraseña — SmartParking Pro",
            "Use este enlace durante los próximos 30 minutos: " + publicUrl + "/restablecer?token=" + token + "\nSi no lo solicitó, ignore este mensaje.");
        access(user.get(), null, "PASSWORD_RESET_REQUESTED", c);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void resetPassword(String token, String newPassword, Client c) {
        validatePassword(newPassword);
        var row = db.sql("SELECT id,user_id FROM password_reset_tokens WHERE token_hash=:h AND used_at IS NULL AND expires_at>now() FOR UPDATE")
            .param("h", sha256(token)).query().listOfRows().stream().findFirst().orElseThrow(() -> new ApiException(422, "Enlace inválido o vencido"));
        UUID user = (UUID) row.get("user_id");
        db.sql("UPDATE password_reset_tokens SET used_at=now() WHERE id=:id").param("id", row.get("id")).update();
        // credentials_changed_at revokes every existing session for this user.
        db.sql("UPDATE users SET password_hash=:h,failed_attempts=0,locked_until=NULL,credentials_changed_at=now(),updated_at=now() WHERE id=:id")
            .param("h", encoder.encode(newPassword)).param("id", user).update();
        access(user, null, "PASSWORD_RESET", c);
    }

    public static void validatePassword(String p) {
        if (p == null || p.length() < MIN_PASSWORD_LENGTH || p.length() > MAX_PASSWORD_LENGTH)
            throw new ApiException(422, "La contraseña debe tener entre " + MIN_PASSWORD_LENGTH + " y " + MAX_PASSWORD_LENGTH + " caracteres");
        if (p.chars().distinct().count() < 6) throw new ApiException(422, "La contraseña es demasiado simple");
    }

    /** Session is valid only if the user is active and credentials did not change after authentication. */
    public boolean sessionValid(UUID user, Instant authenticatedAt) {
        return db.sql("SELECT count(*) FROM users WHERE id=:id AND active AND credentials_changed_at<=:at")
            .param("id", user).param("at", Timestamp.from(authenticatedAt)).query(Long.class).single() == 1;
    }

    /** Authentication instants come from the database clock, the same clock that stamps credential changes. */
    public Instant databaseNow() { return db.sql("SELECT clock_timestamp()").query(Timestamp.class).single().toInstant(); }

    public boolean mfaEnabled(UUID user) {
        return mfaFeature && db.sql("SELECT mfa_enabled FROM users WHERE id=:id").param("id", user).query(Boolean.class).single();
    }
}
