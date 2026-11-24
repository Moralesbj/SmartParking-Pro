package com.smartparking.modules.pricing;

import static org.assertj.core.api.Assertions.*;

import com.smartparking.modules.pricing.application.ParkingRateService;
import com.smartparking.modules.pricing.domain.ParkingRateCalculationDTO;
import com.smartparking.modules.pricing.domain.ParkingRateRequest;
import com.smartparking.modules.pricing.domain.ParkingRateRequest.Period;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** ParkingRateService: per-minute charge per 12 h block, capped by the full rate, unlimited blocks. */
class ParkingRateServiceTest {
    private static final ParkingRateService ENGINE = new ParkingRateService();
    private static final Instant ENTRY = Instant.parse("2026-03-14T13:07:00Z");
    // Example configuration of the specification for cars. In production these values come from vehicle_rates.
    private static final BigDecimal CAR_MINUTE = new BigDecimal("150"), CAR_FULL = new BigDecimal("32000");

    private static ParkingRateCalculationDTO car(Duration stay) {
        return ENGINE.calculate(ParkingRateRequest.of(ENTRY, ENTRY.plus(stay), "CAR", CAR_MINUTE, CAR_FULL, 720, 0));
    }

    /** Table "Liquidaciones esperadas" of the specification, row by row. */
    @ParameterizedTest(name = "{0} min → plenas {1} + adicional {2} = {3}")
    @CsvSource({
        "10,0,1500,1500", "60,0,9000,9000", "120,0,18000,18000", "180,0,27000,27000",
        "213,0,31950,31950", "214,32000,0,32000", "360,32000,0,32000", "720,32000,0,32000",
        "721,32000,150,32150", "780,32000,9000,41000", "840,32000,18000,50000", "900,32000,27000,59000",
        "934,64000,0,64000", "1200,64000,0,64000", "1440,64000,0,64000",
        "1500,64000,9000,73000", "1560,64000,18000,82000", "1620,64000,27000,91000", "1654,96000,0,96000",
        "2160,96000,0,96000", "2220,96000,9000,105000", "2880,128000,0,128000", "3600,160000,0,160000",
        "4320,192000,0,192000", "10080,448000,0,448000"
    })
    void specificationTableForCars(long minutes, String fullRates, String additional, String total) {
        var r = car(Duration.ofMinutes(minutes));
        assertThat(r.fullRatesAmount()).isEqualByComparingTo(fullRates);
        assertThat(r.additionalAmount()).isEqualByComparingTo(additional);
        assertThat(r.totalAmount()).isEqualByComparingTo(total);
        assertThat(r.grossAmount()).isEqualByComparingTo(total);
        assertThat(r.totalAmount().scale()).isEqualTo(2);
    }

    @Test
    void fullRateIsReachedAtMinute214AndFrozenUntilTheBlockEnds() {
        var at213 = car(Duration.ofMinutes(213));
        assertThat(at213.currentBlockCapped()).isFalse();
        assertThat(at213.minutesUntilFullRate()).isEqualTo(1L);
        var at214 = car(Duration.ofMinutes(214));
        assertThat(at214.currentBlockCapped()).isTrue();
        assertThat(at214.fullRatesReached()).isEqualTo(1);
        assertThat(at214.minutesUntilFullRate()).isNull();
        assertThat(car(Duration.ofMinutes(719)).totalAmount()).isEqualByComparingTo("32000");
    }

    @Test
    void secondBlockDoesNotChargeAnotherFullRateImmediately() { // RN-016
        var r = car(Duration.ofMinutes(721));
        assertThat(r.completedBlocks()).isEqualTo(1);
        assertThat(r.currentBlockNumber()).isEqualTo(2);
        assertThat(r.currentBlockMinutes()).isEqualTo(1);
        assertThat(r.currentBlockAmount()).isEqualByComparingTo("150");
        assertThat(r.previousBlocksAmount()).isEqualByComparingTo("32000");
        assertThat(r.blocks()).hasSize(2);
        assertThat(r.blocks().get(1).startsAt()).isEqualTo(ENTRY.plus(Duration.ofHours(12)));
        assertThat(r.blocks().get(1).endsAt()).isEqualTo(ENTRY.plus(Duration.ofHours(24)));
    }

    @Test
    void displayFieldsAtExactBlockBoundary() {
        var r = car(Duration.ofHours(24));
        assertThat(r.billedMinutes()).isEqualTo(1440);
        assertThat(r.completedBlocks()).isEqualTo(2);
        assertThat(r.fullRatesReached()).isEqualTo(2);
        assertThat(r.currentBlockNumber()).isEqualTo(2);
        assertThat(r.currentBlockMinutes()).isEqualTo(720);
        assertThat(r.previousBlocksAmount()).isEqualByComparingTo("32000");
        assertThat(r.currentBlockAmount()).isEqualByComparingTo("32000");
    }

    @Test
    void startedMinuteIsBilledAndFractionsOfSecondAreIgnored() {
        assertThat(car(Duration.ZERO).totalAmount()).isEqualByComparingTo("0");
        assertThat(car(Duration.ofSeconds(1)).totalAmount()).isEqualByComparingTo("150");
        assertThat(car(Duration.ofSeconds(60)).totalAmount()).isEqualByComparingTo("150");
        assertThat(car(Duration.ofSeconds(61)).totalAmount()).isEqualByComparingTo("300");
        assertThat(car(Duration.ofHours(12).plusMillis(900)).totalAmount()).isEqualByComparingTo("32000");
        assertThat(car(Duration.ofHours(12).plusSeconds(1)).totalAmount()).isEqualByComparingTo("32150");
    }

    @Test
    void unlimitedBlocksForVeryLongStays() { // RN-008
        var year = car(Duration.ofDays(365));
        assertThat(year.blocks()).hasSize(730);
        assertThat(year.totalAmount()).isEqualByComparingTo("23360000");
        assertThat(car(Duration.ofDays(30).plusMinutes(60)).totalAmount()).isEqualByComparingTo("1929000"); // 60 full rates + 60 min
    }

    @Test
    void noResetAtMidnightOnlyTheEntryInstantMatters() { // RN-006, RN-007
        ZoneId bogota = ZoneId.of("America/Bogota");
        Instant beforeMidnight = LocalDateTime.parse("2026-03-14T23:00").atZone(bogota).toInstant();
        Instant morning = LocalDateTime.parse("2026-03-14T08:00").atZone(bogota).toInstant();
        var crossing = ENGINE.calculate(ParkingRateRequest.of(beforeMidnight, beforeMidnight.plus(Duration.ofHours(2)), "CAR", CAR_MINUTE, CAR_FULL, 720, 0));
        var daytime = ENGINE.calculate(ParkingRateRequest.of(morning, morning.plus(Duration.ofHours(2)), "CAR", CAR_MINUTE, CAR_FULL, 720, 0));
        assertThat(crossing.totalAmount()).isEqualByComparingTo("18000").isEqualByComparingTo(daytime.totalAmount());
        assertThat(crossing.blocks()).hasSize(1);
    }

    @ParameterizedTest(name = "{0}: {1}/min, plena {2} → alcanza a los {3} min")
    @CsvSource({ "MOTORCYCLE,80,15000,188", "BICYCLE,30,5000,167", "CARGO,250,50000,200" })
    void otherCategoriesReachTheirOwnFullRate(String type, String minute, String full, long reachAt) {
        var before = ENGINE.calculate(ParkingRateRequest.of(ENTRY, ENTRY.plus(Duration.ofMinutes(reachAt - 1)), type, new BigDecimal(minute), new BigDecimal(full), 720, 0));
        var at = ENGINE.calculate(ParkingRateRequest.of(ENTRY, ENTRY.plus(Duration.ofMinutes(reachAt)), type, new BigDecimal(minute), new BigDecimal(full), 720, 0));
        var twoBlocks = ENGINE.calculate(ParkingRateRequest.of(ENTRY, ENTRY.plus(Duration.ofHours(24)), type, new BigDecimal(minute), new BigDecimal(full), 720, 0));
        assertThat(before.currentBlockCapped()).isFalse();
        assertThat(before.totalAmount()).isLessThan(new BigDecimal(full));
        assertThat(at.currentBlockCapped()).isTrue();
        assertThat(at.totalAmount()).isEqualByComparingTo(full);
        assertThat(twoBlocks.totalAmount()).isEqualByComparingTo(new BigDecimal(full).multiply(BigDecimal.TWO));
        assertThat(at.vehicleType()).isEqualTo(type);
    }

    @Test
    void courtesyTimeIsFreeAndOnceExceededTheStayIsBilledFromMinuteZero() {
        var inside = ENGINE.calculate(ParkingRateRequest.of(ENTRY, ENTRY.plus(Duration.ofMinutes(10)), "CAR", CAR_MINUTE, CAR_FULL, 720, 10));
        assertThat(inside.withinGrace()).isTrue();
        assertThat(inside.billedMinutes()).isZero();
        assertThat(inside.totalAmount()).isEqualByComparingTo("0");
        var outside = ENGINE.calculate(ParkingRateRequest.of(ENTRY, ENTRY.plus(Duration.ofMinutes(10).plusSeconds(1)), "CAR", CAR_MINUTE, CAR_FULL, 720, 10));
        assertThat(outside.withinGrace()).isFalse();
        assertThat(outside.totalAmount()).isEqualByComparingTo("1650"); // 11 started minutes
    }

    @Test
    void customBlockDurationFromConfiguration() {
        var sixHours = ENGINE.calculate(ParkingRateRequest.of(ENTRY, ENTRY.plus(Duration.ofHours(7)), "CAR", CAR_MINUTE, CAR_FULL, 360, 0));
        assertThat(sixHours.totalAmount()).isEqualByComparingTo("41000"); // 32000 + 60 min
        assertThat(sixHours.blockMinutes()).isEqualTo(360);
    }

    @Test
    void discountsAndPreviousPaymentsGiveTheBalance() {
        var r = ENGINE.calculate(new ParkingRateRequest(ENTRY, ENTRY.plus(Duration.ofMinutes(780)), "CAR", CAR_MINUTE, CAR_FULL, 720, 0,
            new BigDecimal("5000"), new BigDecimal("20000"), List.of()));
        assertThat(r.grossAmount()).isEqualByComparingTo("41000");
        assertThat(r.authorizedDiscounts()).isEqualByComparingTo("5000");
        assertThat(r.totalAmount()).isEqualByComparingTo("36000");
        assertThat(r.previousPayments()).isEqualByComparingTo("20000");
        assertThat(r.balanceDue()).isEqualByComparingTo("16000");
        assertThat(r.creditBalance()).isEqualByComparingTo("0");
        var overDiscounted = ENGINE.calculate(new ParkingRateRequest(ENTRY, ENTRY.plus(Duration.ofMinutes(10)), "CAR", CAR_MINUTE, CAR_FULL, 720, 0,
            new BigDecimal("5000"), new BigDecimal("1000"), List.of()));
        assertThat(overDiscounted.totalAmount()).isEqualByComparingTo("0");
        assertThat(overDiscounted.creditBalance()).isEqualByComparingTo("1000");
    }

    @Test
    void minutesCoveredByAMonthlyPlanAreNotBilledNorBilledTwice() { // RN-017, RN-018
        // Plan covers the first 5 hours; 2 h more remain billable.
        var periods = List.of(new Period(ENTRY.minus(Duration.ofDays(3)), ENTRY.plus(Duration.ofHours(5))));
        var r = ENGINE.calculate(new ParkingRateRequest(ENTRY, ENTRY.plus(Duration.ofHours(7)), "CAR", CAR_MINUTE, CAR_FULL, 720, 0, null, null, periods));
        assertThat(r.coveredMinutes()).isEqualTo(300);
        assertThat(r.totalAmount()).isEqualByComparingTo("18000");
        // Overlapping periods are merged: the same minute is never excluded twice.
        var overlapping = List.of(new Period(ENTRY, ENTRY.plus(Duration.ofHours(3))), new Period(ENTRY.plus(Duration.ofHours(2)), ENTRY.plus(Duration.ofHours(5))));
        assertThat(ENGINE.calculate(new ParkingRateRequest(ENTRY, ENTRY.plus(Duration.ofHours(7)), "CAR", CAR_MINUTE, CAR_FULL, 720, 0, null, null, overlapping)).coveredMinutes())
            .isEqualTo(300);
        // Whole stay covered → nothing to pay.
        var all = List.of(new Period(ENTRY.minus(Duration.ofDays(1)), ENTRY.plus(Duration.ofDays(2))));
        assertThat(ENGINE.calculate(new ParkingRateRequest(ENTRY, ENTRY.plus(Duration.ofHours(30)), "CAR", CAR_MINUTE, CAR_FULL, 720, 0, null, null, all)).totalAmount())
            .isEqualByComparingTo("0");
    }

    @Test
    void sameInputsAlwaysGiveTheSameResult() {
        assertThat(car(Duration.ofMinutes(1654))).isEqualTo(car(Duration.ofMinutes(1654)));
    }

    @Test
    void invalidInputsAreRejected() {
        assertThatThrownBy(() -> ParkingRateRequest.of(ENTRY, ENTRY.minusSeconds(1), "CAR", CAR_MINUTE, CAR_FULL, 720, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParkingRateRequest.of(ENTRY, ENTRY, "CAR", BigDecimal.ZERO, CAR_FULL, 720, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParkingRateRequest.of(ENTRY, ENTRY, "CAR", CAR_MINUTE, new BigDecimal("100"), 720, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParkingRateRequest.of(ENTRY, ENTRY, "CAR", CAR_MINUTE, CAR_FULL, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParkingRateRequest.of(ENTRY, ENTRY, "CAR", CAR_MINUTE, CAR_FULL, 720, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParkingRateRequest(ENTRY, ENTRY, "CAR", CAR_MINUTE, CAR_FULL, 720, 0, new BigDecimal("-1"), null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParkingRateRequest.of(ENTRY, ENTRY, " ", CAR_MINUTE, CAR_FULL, 720, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
