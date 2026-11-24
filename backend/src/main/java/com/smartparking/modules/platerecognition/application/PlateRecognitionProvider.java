package com.smartparking.modules.platerecognition.application;

import java.math.BigDecimal;
import java.util.List;

/**
 * Port to an ALPR engine. A reading is only a suggestion: {@link AlprService} applies the binding rules.
 * Java package is {@code platerecognition} because Java identifiers cannot contain hyphens.
 */
public interface PlateRecognitionProvider {
    record Reading(String plate, BigDecimal confidence, boolean providerRequestsReview, String mode, List<String> warnings) {}
    record SimulatedFrame(String plate, BigDecimal declaredConfidence, double noise, int blur, double angle, boolean occlude) {}

    /** Simulated camera: produce a reading for a synthetic frame. */
    Reading simulate(SimulatedFrame frame);
    String mode();
}
