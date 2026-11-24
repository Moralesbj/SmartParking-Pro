package com.smartparking;

import com.smartparking.support.PostgresSupport;
import org.springframework.boot.SpringApplication;

/**
 * Developer launcher for machines without Docker or a local PostgreSQL: starts a throwaway real PostgreSQL
 * (Testcontainers or embedded binaries) and runs the application against it. Data is lost on exit.
 * Lives in test scope so it never ships in the production jar.
 *
 *   ./mvnw spring-boot:test-run -Dspring-boot.run.main-class=com.smartparking.LocalDevApplication
 */
public final class LocalDevApplication {
    private LocalDevApplication() {}

    public static void main(String[] args) {
        var c = PostgresSupport.connection();
        System.setProperty("spring.datasource.url", c.url());
        System.setProperty("spring.datasource.username", c.user());
        System.setProperty("spring.datasource.password", c.password());
        System.setProperty("server.servlet.session.cookie.secure", "false");
        SpringApplication.from(SmartParkingApplication::main).run(args);
    }
}
