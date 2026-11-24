package com.smartparking.security;

import com.smartparking.modules.auth.application.AuthService;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean HttpSessionSecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, HttpSessionSecurityContextRepository repository, AuthService auth,
                                    @Value("${app.cors.allowed-origins:}") String origins) throws Exception {
        http.authorizeHttpRequests(a -> a
            .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/mfa/verify", "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password", "/api/v1/auth/logout").permitAll()
            .requestMatchers("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
            .requestMatchers("/api/v1/openapi/**", "/api/v1/docs/**", "/swagger-ui/**").permitAll()
            .requestMatchers("/api/**").authenticated()
            .anyRequest().denyAll());
        http.securityContext(c -> c.securityContextRepository(repository));
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED).sessionFixation(f -> f.newSession()));
        // SPA reads the token from a non-HttpOnly cookie and echoes it in X-XSRF-TOKEN; the session cookie stays HttpOnly.
        http.csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
            .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()));
        http.cors(c -> c.configurationSource(cors(origins)));
        http.formLogin(f -> f.disable()).httpBasic(b -> b.disable()).logout(l -> l.disable());
        http.addFilterAfter(new SessionValidityFilter(auth), SecurityContextHolderAwareRequestFilter.class);
        http.exceptionHandling(e -> e.authenticationEntryPoint((r, s, x) -> {
            s.setStatus(401); s.setCharacterEncoding("UTF-8"); s.setContentType("application/problem+json"); s.getWriter().write("{\"status\":401,\"detail\":\"Inicie sesión para continuar\"}");
        }).accessDeniedHandler((r, s, x) -> {
            s.setStatus(403); s.setCharacterEncoding("UTF-8"); s.setContentType("application/problem+json"); s.getWriter().write("{\"status\":403,\"detail\":\"Permiso denegado o token CSRF inválido\"}");
        }));
        http.headers(h -> h
            .contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'; form-action 'self'"))
            .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
            .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
            .frameOptions(f -> f.deny()));
        return http.build();
    }

    private static UrlBasedCorsConfigurationSource cors(String origins) {
        var config = new CorsConfiguration();
        List<String> allowed = Arrays.stream(origins.split(",")).map(String::trim).filter(o -> !o.isEmpty()).toList();
        if (allowed.stream().anyMatch(o -> o.equals("*"))) throw new IllegalStateException("CORS_ALLOWED_ORIGINS no admite comodines");
        config.setAllowedOrigins(allowed);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        config.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "X-Organization-Id", "X-Branch-Id", "Idempotency-Key"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
