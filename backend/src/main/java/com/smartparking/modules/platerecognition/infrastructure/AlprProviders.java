package com.smartparking.modules.platerecognition.infrastructure;

import com.smartparking.common.errors.ApiException;
import com.smartparking.modules.platerecognition.application.PlateRecognitionProvider;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Configuration
public class AlprProviders {

    /** ALPR_MODE=SIMULATOR (default): in-process, echoes the declared plate and confidence. Always labelled SIMULATOR. */
    static final class InternalSimulator implements PlateRecognitionProvider {
        public Reading simulate(SimulatedFrame f) {
            BigDecimal c = f.declaredConfidence() == null ? new BigDecimal("0.99") : f.declaredConfidence();
            return new Reading(f.plate(), c, false, "SIMULATOR", List.of("SIMULATED_RESULT"));
        }
        public String mode() { return "SIMULATOR"; }
    }

    /** ALPR_MODE=HTTP: ai-service renders a synthetic frame and runs its OpenCV pipeline on it. */
    static final class AiServiceClient implements PlateRecognitionProvider {
        private final RestClient http; private final String token;
        AiServiceClient(String baseUrl, String token) {
            var jdk = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            var factory = new JdkClientHttpRequestFactory(jdk); factory.setReadTimeout(Duration.ofSeconds(5));
            this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
            this.token = token;
        }
        @SuppressWarnings("unchecked")
        public Reading simulate(SimulatedFrame f) {
            try {
                Map<String, Object> body = http.post().uri("/v1/simulator/frames").header("X-Service-Token", token)
                    .body(Map.of("plate", f.plate(), "noise", f.noise(), "blur", f.blur(), "angle", f.angle(), "occlude", f.occlude()))
                    .retrieve().body(Map.class);
                if (body == null) throw new ApiException(502, "Servicio ALPR sin respuesta");
                List<String> warnings = new ArrayList<>((List<String>) body.getOrDefault("warnings", List.of()));
                warnings.add("SIMULATED_CAMERA");
                return new Reading((String) body.get("plate"), new BigDecimal(String.valueOf(body.get("confidence"))),
                    Boolean.TRUE.equals(body.get("requires_review")), "SIMULATED_CAMERA", warnings);
            } catch (RestClientException e) {
                // Provider outage never blocks manual operation; the caller records it as unavailable.
                throw new ApiException(503, "Servicio ALPR no disponible; registre la placa manualmente", "ALPR_UNAVAILABLE");
            }
        }
        public String mode() { return "HTTP"; }
    }

    @Bean
    PlateRecognitionProvider plateRecognitionProvider(@Value("${app.alpr.mode:SIMULATOR}") String mode,
                                                      @Value("${app.alpr.base-url:http://localhost:8000}") String baseUrl,
                                                      @Value("${app.alpr.service-token:}") String token) {
        return switch (mode.toUpperCase()) {
            case "SIMULATOR" -> new InternalSimulator();
            case "HTTP" -> new AiServiceClient(baseUrl, token);
            default -> throw new IllegalStateException("ALPR_MODE inválido: " + mode);
        };
    }
}
