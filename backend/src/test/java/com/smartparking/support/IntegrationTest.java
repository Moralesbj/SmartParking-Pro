package com.smartparking.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Full application context against real PostgreSQL with Flyway migrations. */
@SpringBootTest
@AutoConfigureMockMvc
// MFA enforcement has a dedicated suite (AuthIT overrides this); other suites focus on business rules.
// All MockMvc logins share 127.0.0.1; the per-IP limiter itself is covered by RateLimiterTest.
@org.springframework.test.context.TestPropertySource(properties = {"app.security.require-mfa-privileged=false", "app.security.login-attempts-per-ip=100000"})
public abstract class IntegrationTest {
    public static final String PASSWORD = "Prueba-solo-local-2026!";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        var c = PostgresSupport.connection();
        registry.add("spring.datasource.url", c::url);
        registry.add("spring.datasource.username", c::user);
        registry.add("spring.datasource.password", c::password);
        registry.add("app.jobs.enabled", () -> "false");
        registry.add("server.servlet.session.cookie.secure", () -> "false");
        registry.add("app.security.encryption-key", () -> "dGVzdC1vbmx5LWtleS0zMi1ieXRlcy1sb25nLSEhISE=");
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcClient db;
    @Autowired protected PasswordEncoder encoder;
    @Autowired protected ObjectMapper json;
    protected Fixtures fx;

    @BeforeEach
    void fixtures() { fx = new Fixtures(db, encoder); }

    /** An authenticated browser-like client bound to one organization/branch scope. */
    public final class Client {
        public final MockHttpSession session;
        public UUID organizationId, branchId;
        Client(MockHttpSession session, UUID organizationId, UUID branchId) { this.session = session; this.organizationId = organizationId; this.branchId = branchId; }
        public Client scope(UUID org, UUID branch) { return new Client(session, org, branch); }
        private MockHttpServletRequestBuilder prepare(MockHttpServletRequestBuilder b, Object body) throws Exception {
            b.session(session).with(csrf()).accept(MediaType.APPLICATION_JSON);
            if (organizationId != null) b.header("X-Organization-Id", organizationId.toString());
            if (branchId != null) b.header("X-Branch-Id", branchId.toString());
            if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
            return b;
        }
        public Response get(String url) throws Exception { return call(MockMvcRequestBuilders.get(url), null); }
        public Response post(String url, Object body) throws Exception { return call(MockMvcRequestBuilders.post(url), body); }
        public Response put(String url, Object body) throws Exception { return call(MockMvcRequestBuilders.put(url), body); }
        public Response delete(String url) throws Exception { return call(MockMvcRequestBuilders.delete(url), null); }
        public Response call(MockHttpServletRequestBuilder b, Object body) throws Exception {
            return new Response(mvc.perform(prepare(b, body)).andReturn());
        }
    }

    public final class Response {
        public final MvcResult result;
        Response(MvcResult result) { this.result = result; }
        public int status() { return result.getResponse().getStatus(); }
        public String text() throws Exception { return result.getResponse().getContentAsString(); }
        public JsonNode body() throws Exception { var t = text(); return t.isBlank() ? NullNode.getInstance() : json.readTree(t); }
        public Response expect(int expected) throws Exception {
            if (status() != expected) throw new AssertionError("Esperado HTTP " + expected + " pero fue " + status() + ": " + text());
            return this;
        }
    }

    protected Client login(String email, String password, UUID org, UUID branch) throws Exception {
        var session = new MockHttpSession();
        var result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login").session(session).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email", email, "password", password)))).andReturn();
        if (result.getResponse().getStatus() != 200) throw new AssertionError("Login falló: " + result.getResponse().getContentAsString());
        var authenticated = (MockHttpSession) result.getRequest().getSession(false);
        return new Client(authenticated, org, branch);
    }

    protected Client login(Fixtures.Actor actor) throws Exception { return login(actor.email(), PASSWORD, actor.organizationId(), actor.branchId()); }
}
