package com.smartparking.modules.pricing.application;

import com.smartparking.modules.pricing.domain.ParkingRateCalculationDTO;
import com.smartparking.modules.pricing.domain.ParkingRateCalculationDTO.Block;
import com.smartparking.modules.pricing.domain.ParkingRateRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The only component that computes what a stay costs. Pure: no database, no clock, no stored state.
 *
 * <ul>
 *   <li>Time counts from the exact entry instant (RN-001, RN-007). Every started minute is billable ("minuto o fracción");
 *       fractions of a second are ignored.</li>
 *   <li>Consecutive blocks of {@code blockMinutes} (720 by default), unlimited in number (RN-002, RN-008).</li>
 *   <li>Inside each block the minute rate accrues until it reaches the full rate; then the block stays frozen at the full
 *       rate until it ends (RN-003). A new block starts again at the minute rate and adds to the total (RN-004, RN-005, RN-016).</li>
 *   <li>Nothing resets at midnight: local time is never consulted (RN-006).</li>
 *   <li>A stay no longer than the courtesy time costs nothing; past it, the stay is billed from minute zero.</li>
 *   <li>Minutes starting inside a period covered by a monthly plan are not billed (RN-017, RN-018).</li>
 *   <li>Money is BigDecimal with scale 2 (RN-013).</li>
 * </ul>
 */
@Service
public class ParkingRateService {
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    public ParkingRateCalculationDTO calculate(ParkingRateRequest r) {
        long staySeconds = Duration.between(r.entryAt(), r.settledAt()).getSeconds();
        boolean withinGrace = r.graceMinutes() > 0 && staySeconds <= r.graceMinutes() * 60L;
        long minutes = withinGrace ? 0 : ceilDiv(staySeconds, 60);
        long size = r.blockMinutes();
        List<long[]> covered = coveredMinuteRanges(r, minutes);

        long blockCount = ceilDiv(minutes, size);
        List<Block> blocks = new ArrayList<>((int) Math.min(blockCount, 10_000));
        BigDecimal gross = ZERO, fullRates = ZERO;
        long reached = 0, coveredTotal = 0;
        for (long k = 0; k < blockCount; k++) {
            long start = k * size, end = Math.min(start + size, minutes);
            long coveredHere = overlap(covered, start, end), billable = end - start - coveredHere;
            BigDecimal raw = r.minuteRate().multiply(BigDecimal.valueOf(billable));
            boolean capped = raw.compareTo(r.fullRate()) >= 0;
            BigDecimal amount = money(capped ? r.fullRate() : raw);
            if (capped) { reached++; fullRates = fullRates.add(amount); }
            gross = gross.add(amount);
            coveredTotal += coveredHere;
            blocks.add(new Block(k + 1, r.entryAt().plusSeconds(start * 60), r.entryAt().plusSeconds((start + size) * 60),
                end - start, coveredHere, billable, amount, capped));
        }

        Block current = blocks.isEmpty() ? null : blocks.getLast();
        BigDecimal currentAmount = current == null ? ZERO : current.amount();
        Long untilFull = null;
        if (current != null && !current.fullRateReached()) {
            long needed = r.fullRate().subtract(currentAmount).divide(r.minuteRate(), 0, RoundingMode.CEILING).longValueExact();
            if (needed <= size - current.elapsedMinutes()) untilFull = needed;
        } else if (current == null && !withinGrace) {
            untilFull = r.fullRate().divide(r.minuteRate(), 0, RoundingMode.CEILING).longValueExact();
        }

        BigDecimal discounts = money(r.authorizedDiscounts()), payments = money(r.previousPayments());
        BigDecimal total = gross.subtract(discounts).max(ZERO);
        return new ParkingRateCalculationDTO(r.vehicleType(), r.entryAt(), r.settledAt(), staySeconds, minutes, withinGrace,
            money(r.minuteRate()), money(r.fullRate()), r.blockMinutes(), r.graceMinutes(),
            minutes / size, reached, fullRates, gross.subtract(fullRates), gross.subtract(currentAmount),
            current == null ? 1 : current.number(), current == null ? 0 : current.elapsedMinutes(), currentAmount,
            current != null && current.fullRateReached(), untilFull,
            coveredTotal, gross, discounts, total, payments, total.subtract(payments).max(ZERO), payments.subtract(total).max(ZERO),
            List.copyOf(blocks));
    }

    /** Minute i (0-based) is covered when it starts inside a covered period. Returns merged [from, to) index ranges. */
    private static List<long[]> coveredMinuteRanges(ParkingRateRequest r, long minutes) {
        List<long[]> ranges = new ArrayList<>();
        for (var p : r.coveredPeriods()) {
            long from = Math.max(0, ceilDiv(Duration.between(r.entryAt(), p.from()).getSeconds(), 60));
            long to = Math.min(minutes, ceilDiv(Duration.between(r.entryAt(), p.to()).getSeconds(), 60));
            if (to > from) ranges.add(new long[]{from, to});
        }
        ranges.sort(Comparator.comparingLong(a -> a[0]));
        List<long[]> merged = new ArrayList<>();
        for (var range : ranges) {
            if (!merged.isEmpty() && range[0] <= merged.getLast()[1]) merged.getLast()[1] = Math.max(merged.getLast()[1], range[1]);
            else merged.add(range.clone());
        }
        return merged;
    }

    private static long overlap(List<long[]> ranges, long start, long end) {
        long total = 0;
        for (var range : ranges) total += Math.max(0, Math.min(range[1], end) - Math.max(range[0], start));
        return total;
    }

    /** Ceiling division that is also correct for negative numerators (offsets before entry). */
    private static long ceilDiv(long value, long divisor) { return Math.ceilDiv(value, divisor); }

    private static BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP); }
}
