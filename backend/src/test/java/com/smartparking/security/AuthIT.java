package com.smartparking.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.smartparking.modules.notifications.application.MailGateway;
import com.smartparking.support.IntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** M01: MFA for privileged roles, one-time password reset, session revocation and access log. */
@TestPropertySource(properties = {"app.security.mfa-enabled=true", "app.security.require-mfa-privileged=true", "app.security.login-attempts-per-ip=100000"})
class AuthIT extends IntegrationTest {

    record Mail(String to, String subject, String body) {}

    @TestConfiguration
    static class CapturingMail {
        static final List<Mail> SENT = new CopyOnWriteArrayList<>();
        @Bean @Primary MailGateway capturingMailGateway() {
            return new MailGateway() {
                public Result send(String to, String subject, String body) { SENT.add(new Mail(to, subject, body)); return Result.SENT; }
                public String mode() { return "TEST"; }
            };
        }
    }

    @Autowired MailGateway mailGateway;

    private int post(MockHttpSession session, String url, Object body) throws Exception {
        var b = MockMvcRequestBuilders.post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (session != null) b.session(session);
        return mvc.perform(b).andReturn().getResponse().getStatus();
    }

    @Test
    void privilegedRoleWithoutMfaIsAskedToEnrollAndCanThenOperate() throws Exception {
        var t = fx.tenant();
        var actor = fx.user(t, "ADMIN");
        var admin = login(actor);
        var denied = admin.get("/api/v1/catalogs/customers").expect(403).body();
        assertThat(denied.get("code").asString()).isEqualTo("MFA_ENROLLMENT_REQUIRED");

        var setup = admin.post("/api/v1/auth/mfa/setup", null).expect(200).body();
        String secret = setup.get("secret").asString();
        assertThat(setup.get("otpauthUri").asString()).startsWith("otpauth://totp/");
        String stored = db.sql("SELECT mfa_secret_encrypted FROM users WHERE id=:id").param("id", actor.userId()).query(String.class).single();
        assertThat(stored).startsWith("v1:").doesNotContain(secret);

        admin.post("/api/v1/auth/mfa/enable", Map.of("code", "000000")).expect(422);
        long step = Totp.step(Instant.now());
        var enabled = admin.post("/api/v1/auth/mfa/enable", Map.of("code", Totp.code(secret, step))).expect(200).body();
        assertThat(enabled.get("recoveryCodes").size()).isEqualTo(8);
        admin.get("/api/v1/catalogs/customers").expect(200);

        // New login now needs the second factor; the password alone yields no authenticated session.
        var session = new MockHttpSession();
        var first = mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", actor.email(), "password", PASSWORD)))).andReturn();
        assertThat(json.readTree(first.getResponse().getContentAsString()).get("mfaRequired").asBoolean()).isTrue();
        var pending = (MockHttpSession) first.getRequest().getSession(false);
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/api/v1/auth/me").session(pending)).andReturn().getResponse().getStatus()).isEqualTo(401);

        // Replaying the code used at enrollment is rejected.
        assertThat(post(pending, "/api/v1/auth/mfa/verify", Map.of("code", Totp.code(secret, step)))).isEqualTo(401);
        var ok = mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/mfa/verify").session(pending).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("code", Totp.code(secret, step + 1))))).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        var authed = (MockHttpSession) ok.getRequest().getSession(false);
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/api/v1/auth/me").session(authed)).andReturn().getResponse().getStatus()).isEqualTo(200);

        // Recovery code works exactly once.
        String recovery = enabled.get("recoveryCodes").get(0).asString();
        for (int expected : new int[]{200, 401}) {
            var s2 = new MockHttpSession();
            var l = mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login").session(s2).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", actor.email(), "password", PASSWORD)))).andReturn();
            assertThat(post((MockHttpSession) l.getRequest().getSession(false), "/api/v1/auth/mfa/verify", Map.of("recoveryCode", recovery))).isEqualTo(expected);
        }
        assertThat(db.sql("SELECT count(*) FROM access_events WHERE user_id=:u AND outcome='MFA_FAILURE'").param("u", actor.userId()).query(Long.class).single()).isGreaterThanOrEqualTo(2L);
    }

    @Test
    void operatorsAreNotForcedToEnrollMfa() throws Exception {
        var t = fx.tenant();
        login(fx.user(t, "OPERATOR")).get("/api/v1/cash-sessions").expect(200);
    }

    @Test
    void passwordResetIsOneTimeAndRevokesExistingSessions() throws Exception {
        var t = fx.tenant();
        var actor = fx.user(t, "OPERATOR");
        var existing = login(actor);
        existing.get("/api/v1/cash-sessions").expect(200);

        CapturingMail.SENT.clear();
        assertThat(post(null, "/api/v1/auth/forgot-password", Map.of("email", "nadie@prueba.local"))).isEqualTo(202);
        assertThat(CapturingMail.SENT).isEmpty();
        assertThat(post(null, "/api/v1/auth/forgot-password", Map.of("email", actor.email()))).isEqualTo(202);
        assertThat(CapturingMail.SENT).hasSize(1);
        var m = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(CapturingMail.SENT.getFirst().body());
        assertThat(m.find()).isTrue();
        String token = m.group(1);
        // Only the hash is stored.
        assertThat(db.sql("SELECT count(*) FROM password_reset_tokens WHERE token_hash=:t").param("t", token).query(Long.class).single()).isZero();

        assertThat(post(null, "/api/v1/auth/reset-password", Map.of("token", token, "newPassword", "corta"))).isEqualTo(422);
        String newPassword = "Nueva-clave-segura-2026";
        assertThat(post(null, "/api/v1/auth/reset-password", Map.of("token", token, "newPassword", newPassword))).isEqualTo(204);
        assertThat(post(null, "/api/v1/auth/reset-password", Map.of("token", token, "newPassword", newPassword + "x"))).isEqualTo(422);

        // The session opened before the change is revoked.
        assertThat(existing.get("/api/v1/cash-sessions").status()).isEqualTo(401);
        login(actor.email(), newPassword, t.organizationId(), t.branchId()).get("/api/v1/cash-sessions").expect(200);
        assertThat(db.sql("SELECT count(*) FROM access_events WHERE user_id=:u AND outcome='PASSWORD_RESET'").param("u", actor.userId()).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void logoutInvalidatesSession() throws Exception {
        var t = fx.tenant();
        var op = login(fx.user(t, "OPERATOR"));
        op.post("/api/v1/auth/logout", null).expect(204);
        assertThat(op.get("/api/v1/auth/me").status()).isEqualTo(401);
    }

    @Test
    void unknownRoutesAndInvalidPayloadsReturnProblemDetails() throws Exception {
        var t = fx.tenant();
        var op = login(fx.user(t, "OPERATOR"));
        var notFound = op.get("/api/v1/no-existe");
        assertThat(notFound.status()).isEqualTo(404);
        assertThat(notFound.result.getResponse().getContentType()).contains("application/problem+json");
        op.post("/api/v1/cash-sessions/open", Map.of("openingBalance", "-5")).expect(422);
    }
}
