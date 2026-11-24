package com.smartparking.modules.pricing;

import static org.assertj.core.api.Assertions.*;

import com.smartparking.modules.pricing.domain.CoverageWindows;
import com.smartparking.modules.pricing.domain.CoverageWindows.Contract;
import com.smartparking.modules.pricing.domain.ParkingRateRequest.Period;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Periods paid by monthly contracts, evaluated in the branch time zone (Bogotá, UTC-5, no DST). */
class CoverageWindowsTest {
    private static final ZoneId BOG = ZoneId.of("America/Bogota");
    private static Instant at(String local) { return LocalDateTime.parse(local).atZone(BOG).toInstant(); }

    @Test
    void unrestrictedContractCoversItsValidityInsideTheStay() {
        var c = new Contract(at("2026-03-01T00:00"), at("2026-03-31T00:00"), "1234567", null, null);
        assertThat(CoverageWindows.covered(List.of(c), at("2026-03-30T20:00"), at("2026-03-31T02:00"), BOG))
            .containsExactly(new Period(at("2026-03-30T20:00"), at("2026-03-31T00:00")));
    }

    @Test
    void nightWindowCrossesMidnightAndBelongsToTheDayItStarts() {
        // Monday to Friday, 20:00–06:00. 2026-03-13 is a Friday, 2026-03-14 a Saturday.
        var night = new Contract(at("2026-01-01T00:00"), at("2026-12-31T00:00"), "12345", LocalTime.of(20, 0), LocalTime.of(6, 0));
        var periods = CoverageWindows.covered(List.of(night), at("2026-03-13T18:00"), at("2026-03-14T23:00"), BOG);
        // Friday 20:00 → Saturday 06:00 is covered; Saturday night is not (Saturday not allowed).
        assertThat(periods).containsExactly(new Period(at("2026-03-13T20:00"), at("2026-03-14T06:00")));
    }

    @Test
    void daytimeWindowOnAllowedDaysOnly() {
        var office = new Contract(at("2026-01-01T00:00"), at("2026-12-31T00:00"), "135", LocalTime.of(7, 0), LocalTime.of(19, 0));
        // Monday 2026-03-16 06:00 to Wednesday 2026-03-18 08:00.
        var periods = CoverageWindows.covered(List.of(office), at("2026-03-16T06:00"), at("2026-03-18T08:00"), BOG);
        assertThat(periods).containsExactly(new Period(at("2026-03-16T07:00"), at("2026-03-16T19:00")), new Period(at("2026-03-18T07:00"), at("2026-03-18T08:00")));
    }

    @Test
    void overlappingContractsAreMergedAndExpiredOnesStopCovering() {
        var a = new Contract(at("2026-03-01T00:00"), at("2026-03-10T12:00"), "1234567", null, null);
        var b = new Contract(at("2026-03-10T00:00"), at("2026-03-10T18:00"), "1234567", null, null);
        var periods = CoverageWindows.covered(List.of(a, b), at("2026-03-10T08:00"), at("2026-03-10T22:00"), BOG);
        assertThat(periods).containsExactly(new Period(at("2026-03-10T08:00"), at("2026-03-10T18:00")));
        assertThat(CoverageWindows.covered(List.of(a), at("2026-03-11T08:00"), at("2026-03-11T10:00"), BOG)).isEmpty();
    }

    @Test
    void invalidContractsAreRejected() {
        assertThatThrownBy(() -> new Contract(Instant.EPOCH, Instant.EPOCH, "8", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Contract(Instant.EPOCH, Instant.EPOCH, "1", LocalTime.NOON, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Contract(Instant.EPOCH, Instant.EPOCH, "1", LocalTime.NOON, LocalTime.NOON)).isInstanceOf(IllegalArgumentException.class);
    }
}
