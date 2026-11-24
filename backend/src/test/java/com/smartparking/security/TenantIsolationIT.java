package com.smartparking.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.support.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** M01/M02/M03/E: authentication, scope headers are never trusted, cross-tenant access is impossible. */
class TenantIsolationIT extends IntegrationTest {

    @Test
    void anonymousRequestsAreRejected() throws Exception {
        var res = mvc.perform(MockMvcRequestBuilders.get("/api/v1/auth/me").accept(MediaType.APPLICATION_JSON)).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentType()).contains("application/problem+json");
        // Filter-written bodies must be valid UTF-8 (regression: Latin-1 "sesión").
        assertThat(res.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
        assertThat(new String(res.getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8)).contains("Inicie sesión");
    }

    @Test
    void invalidPasswordFailsAndRepeatedFailuresLockTheAccount() throws Exception {
        var t = fx.tenant();
        var user = fx.user(t, "OPERATOR");
        for (int i = 0; i < 5; i++) {
            var r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email", user.email(), "password", "incorrecta-123456")))).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
        }
        var locked = mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email", user.email(), "password", PASSWORD)))).andReturn();
        assertThat(locked.getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void browserSameOriginRequestsPassAndForeignOriginsAreBlocked() throws Exception {
        // Regression: browsers always send Origin; a same-origin request (proxy preserving Host) must work.
        var same = mvc.perform(MockMvcRequestBuilders.get("/api/v1/auth/csrf").header("Origin", "http://localhost")).andReturn().getResponse();
        assertThat(same.getStatus()).isEqualTo(200);
        var t = fx.tenant();
        var user = fx.user(t, "OPERATOR");
        var login = mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login").header("Origin", "http://localhost")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email", user.email(), "password", PASSWORD)))).andReturn().getResponse();
        assertThat(login.getStatus()).isEqualTo(200);
        var foreign = mvc.perform(MockMvcRequestBuilders.get("/api/v1/auth/csrf").header("Origin", "http://evil.example")).andReturn().getResponse();
        assertThat(foreign.getStatus()).isEqualTo(403);
    }

    @Test
    void loginWithoutCsrfIsRejected() throws Exception {
        var r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", "x@y.co", "password", "whatever-123456")))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void organizationBCannotSeeOrModifyOrganizationA() throws Exception {
        var a = fx.tenant();
        var b = fx.tenant();
        var adminA = login(fx.user(a, "ADMIN"));
        var adminB = login(fx.user(b, "ADMIN"));
        UUID customerA = UUID.fromString(adminA.post("/api/v1/catalogs/customers", Map.of("name", "Cliente A", "document_number", "900", "consent", "true")).expect(200).body().get("id").asString());

        // B forging A's scope headers is refused.
        adminB.scope(a.organizationId(), a.branchId()).get("/api/v1/catalogs/customers").expect(403);
        // B with its own scope cannot see or edit A's record.
        assertThat(adminB.get("/api/v1/catalogs/customers").expect(200).text()).doesNotContain(customerA.toString());
        adminB.put("/api/v1/catalogs/customers/" + customerA, Map.of("name", "Robado", "document_number", "1", "consent", "true")).expect(404);
        // B mixing its org with A's branch is refused.
        adminB.scope(b.organizationId(), a.branchId()).get("/api/v1/catalogs/customers").expect(403);
        assertThat(db.sql("SELECT name FROM customers WHERE id=:id").param("id", customerA).query(String.class).single()).isEqualTo("Cliente A");
    }

    @Test
    void crossTenantForeignKeysAreRejectedByTheDatabase() throws Exception {
        var a = fx.tenant();
        var b = fx.tenant();
        UUID customerB = fx.customer(b);
        var adminA = login(fx.user(a, "ADMIN"));
        // Vehicle of A pointing to B's customer violates the composite FK → 409.
        adminA.post("/api/v1/catalogs/vehicles", Map.of("plate", "XYZ987", "country_code", "CO", "type", "CAR", "customer_id", customerB.toString())).expect(409);
    }

    @Test
    void operatorOnlySeesAssignedBranch() throws Exception {
        var t = fx.tenant();
        UUID other = fx.branch(t, "B2");
        var operator = login(fx.user(t, "OPERATOR"));
        operator.get("/api/v1/cash-sessions").expect(200);
        operator.scope(t.organizationId(), other).get("/api/v1/cash-sessions").expect(403);
        var scopes = operator.get("/api/v1/auth/me").expect(200).body().get("scopes");
        assertThat(scopes.size()).isEqualTo(1);
        // Org-wide admin sees both branches.
        var admin = login(fx.user(t, "ADMIN"));
        assertThat(admin.get("/api/v1/auth/me").expect(200).body().get("scopes").size()).isEqualTo(2);
        admin.scope(t.organizationId(), other).get("/api/v1/cash-sessions").expect(200);
    }

    @Test
    void customerCannotCallAdministrativeEndpointsAndOnlySeesOwnData() throws Exception {
        var t = fx.tenant();
        var c1 = fx.user(t, "CUSTOMER");
        var c2 = fx.user(t, "CUSTOMER");
        fx.vehicle(t, fx.customerOf(c2), "CAR");
        var customer = login(c1);
        customer.get("/api/v1/catalogs/customers").expect(403);
        customer.get("/api/v1/parking-sessions").expect(403);
        customer.get("/api/v1/users").expect(403);
        customer.get("/api/v1/audit-logs").expect(403);
        customer.post("/api/v1/portal/vehicles", Map.of("plate", "KLM45A", "type", "MOTORCYCLE")).expect(200);
        var vehicles = customer.get("/api/v1/portal/vehicles").expect(200).body();
        assertThat(vehicles.size()).isEqualTo(1);
        assertThat(vehicles.get(0).get("plate").asString()).isEqualTo("KLM45A");
    }

    @Test
    void suspendedMembershipLosesAccessImmediately() throws Exception {
        var t = fx.tenant();
        var opActor = fx.user(t, "OPERATOR");
        var operator = login(opActor);
        operator.get("/api/v1/cash-sessions").expect(200);
        var admin = login(fx.user(t, "ADMIN"));
        admin.post("/api/v1/users/" + opActor.userId() + "/suspend", null).expect(200);
        operator.get("/api/v1/cash-sessions").expect(403);
    }

    @Test
    void auditLogsCannotBeModified() {
        var t = fx.tenant();
        var actor = fx.user(t, "ADMIN");
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO audit_logs(id,organization_id,branch_id,actor_id,action,entity_type) VALUES(:id,:o,:b,:a,'TEST','test')")
            .param("id", id).param("o", t.organizationId()).param("b", t.branchId()).param("a", actor.userId()).update();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> db.sql("UPDATE audit_logs SET action='X' WHERE id=:id").param("id", id).update())
            .hasMessageContaining("immutable");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> db.sql("DELETE FROM audit_logs WHERE id=:id").param("id", id).update())
            .hasMessageContaining("immutable");
    }
}
