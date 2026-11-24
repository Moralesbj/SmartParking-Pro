package com.smartparking.modules.pricing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Everything ParkingRateService needs to price one stay. All values come from the stay's frozen snapshot
 * (taken from the database at entry), never from constants in code.
 *
 * @param coveredPeriods periods paid by a monthly plan; minutes starting inside them are not billed (RN-017, RN-018)
 */
public record ParkingRateRequest(Instant entryAt, Instant settledAt, String vehicleType, BigDecimal minuteRate, BigDecimal fullRate,
                                 int blockMinutes, int graceMinutes, BigDecimal authorizedDiscounts, BigDecimal previousPayments,
                                 List<Period> coveredPeriods) {
    public record Period(Instant from, Instant to) {
        public Period {
            if (from == null || to == null || to.isBefore(from)) throw new IllegalArgumentException("Periodo cubierto inválido");
        }
    }

    public ParkingRateRequest {
        if (entryAt == null || settledAt == null) throw new IllegalArgumentException("Se requieren ingreso y liquidación");
        if (settledAt.isBefore(entryAt)) throw new IllegalArgumentException("La liquidación es anterior al ingreso");
        if (vehicleType == null || vehicleType.isBlank()) throw new IllegalArgumentException("Se requiere el tipo de vehículo");
        if (minuteRate == null || minuteRate.signum() <= 0) throw new IllegalArgumentException("La tarifa por minuto debe ser positiva");
        if (fullRate == null || fullRate.compareTo(minuteRate) < 0) throw new IllegalArgumentException("La tarifa plena no puede ser menor que la tarifa por minuto");
        if (blockMinutes < 1) throw new IllegalArgumentException("El bloque debe durar al menos un minuto");
        if (graceMinutes < 0) throw new IllegalArgumentException("El tiempo de cortesía no puede ser negativo");
        authorizedDiscounts = authorizedDiscounts == null ? BigDecimal.ZERO : authorizedDiscounts;
        previousPayments = previousPayments == null ? BigDecimal.ZERO : previousPayments;
        if (authorizedDiscounts.signum() < 0 || previousPayments.signum() < 0) throw new IllegalArgumentException("Descuentos y pagos no pueden ser negativos");
        coveredPeriods = coveredPeriods == null ? List.of() : List.copyOf(coveredPeriods);
    }

    /** Stay without discounts, payments or monthly coverage. */
    public static ParkingRateRequest of(Instant entryAt, Instant settledAt, String vehicleType, BigDecimal minuteRate, BigDecimal fullRate, int blockMinutes, int graceMinutes) {
        return new ParkingRateRequest(entryAt, settledAt, vehicleType, minuteRate, fullRate, blockMinutes, graceMinutes, null, null, null);
    }
}
