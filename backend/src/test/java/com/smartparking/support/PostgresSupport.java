package com.smartparking.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One real PostgreSQL instance per test JVM.
 * Testcontainers is preferred; when Docker is unavailable the zonky embedded PostgreSQL
 * binaries (also a real PostgreSQL server) are used. H2 or mocks are never used.
 */
public final class PostgresSupport {
    public record Connection(String url, String user, String password, String engine) {}
    private static Connection connection;

    private PostgresSupport() {}

    public static synchronized Connection connection() {
        if (connection != null) return connection;
        boolean forceEmbedded = "embedded".equalsIgnoreCase(System.getenv("SP_TEST_DB"));
        if (!forceEmbedded && dockerAvailable()) {
            var container = new PostgreSQLContainer("postgres:18-alpine")
                .withDatabaseName("smartparking").withUsername("smartparking").withPassword("test-only");
            container.start();
            connection = new Connection(container.getJdbcUrl(), container.getUsername(), container.getPassword(), "testcontainers");
        } else {
            try {
                var postgres = EmbeddedPostgres.builder().start();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { postgres.close(); } catch (IOException ignored) { } }));
                connection = new Connection(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "", "embedded-postgres");
            } catch (IOException e) {
                throw new IllegalStateException("No se pudo iniciar PostgreSQL para pruebas", e);
            }
        }
        System.out.println("[tests] PostgreSQL engine: " + connection.engine());
        return connection;
    }

    private static boolean dockerAvailable() {
        try { return DockerClientFactory.instance().isDockerAvailable(); } catch (Throwable e) { return false; }
    }
}
