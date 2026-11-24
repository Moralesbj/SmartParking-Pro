package com.smartparking.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.smartparking.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Default configuration: two-step verification is switched off (app.security.mfa-enabled=false). Login asks only for
 * e-mail and password, even for administrators and even if a second factor was stored earlier, and the admin
 * requirement does not apply although require-mfa-privileged is true.
 */
@TestPropertySource(properties = {"app.security.require-mfa-privileged=true"})
class MfaDisabledIT extends IntegrationTest {

    @Test
    void administratorsLogInWithPasswordOnlyAndOperateWithoutSecondFactor() throws Exception {
        var t = fx.tenant();
        var actor = fx.user(t, "ADMIN");
        // A second factor stored before the feature was switched off is ignored.
        db.sql("UPDATE users SET mfa_enabled=true WHERE id=:id").param("id", actor.userId()).update();
        var raw = mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login").session(new MockHttpSession()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", actor.email(), "password", PASSWORD)))).andReturn();
        assertThat(raw.getResponse().getStatus()).isEqualTo(200);
        assertThat(raw.getResponse().getContentAsString()).doesNotContain("mfaRequired");

        var admin = login(actor);
        admin.get("/api/v1/users").expect(200);
        admin.get("/api/v1/vehicle-rates").expect(200);
        var me = admin.get("/api/v1/auth/me").expect(200).body();
        assertThat(me.get("mfaAvailable").asBoolean()).isFalse();
        assertThat(me.get("user").get("mfa_enabled").asBoolean()).isFalse();
        assertThat(db.sql("SELECT count(*) FROM access_events WHERE user_id=:u AND outcome='MFA_REQUIRED'").param("u", actor.userId()).query(Long.class).single()).isZero();
    }

    @Test
    void enrollmentEndpointsAreDisabled() throws Exception {
        var t = fx.tenant();
        var superadmin = login(fx.user(t, "SUPERADMIN", true));
        var setup = superadmin.post("/api/v1/auth/mfa/setup", null).expect(404).body();
        assertThat(setup.get("code").asString()).isEqualTo("MFA_DISABLED");
        superadmin.post("/api/v1/auth/mfa/enable", Map.of("code", "123456")).expect(404);
        superadmin.get("/api/v1/organizations").expect(200);
    }

    @Test
    void passwordsNeedAtLeastEightCharacters() throws Exception {
        var t = fx.tenant();
        var admin = login(fx.user(t, "ADMIN"));
        admin.post("/api/v1/users", Map.of("email", "corta@prueba.local", "fullName", "Corta", "password", "Abc1234", "role", "OPERATOR")).expect(422);
        admin.post("/api/v1/users", Map.of("email", "simple@prueba.local", "fullName", "Simple", "password", "aaaa1111", "role", "OPERATOR")).expect(422);
        String email = "ocho" + System.nanoTime() + "@prueba.local";
        admin.post("/api/v1/users", Map.of("email", email, "fullName", "Operador ocho", "password", "Prueba12", "role", "OPERATOR")).expect(200);
        login(email, "Prueba12", t.organizationId(), t.branchId()).get("/api/v1/parking-sessions").expect(200);
    }
}
