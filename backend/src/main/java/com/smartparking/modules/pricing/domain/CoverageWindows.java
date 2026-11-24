package com.smartparking.modules.pricing.domain;

import com.smartparking.modules.pricing.domain.ParkingRateRequest.Period;
import java.time.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns monthly contracts into the concrete periods they pay for, in the branch time zone.
 * A plan may restrict the days of the week (ISO 1 = Monday … 7 = Sunday) and a daily time window; a window whose end is
 * not after its start crosses midnight and belongs to the day it starts on (e.g. Monday 20:00 → Tuesday 06:00).
 */
public final class CoverageWindows {
    private CoverageWindows() {}

    /** @param endsAt contract end already extended by the plan's grace days */
    public record Contract(Instant startsAt, Instant endsAt, String allowedDays, LocalTime allowedStart, LocalTime allowedEnd) {
        public Contract {
            if (startsAt == null || endsAt == null || allowedDays == null || !allowedDays.matches("[1-7]{1,7}")) throw new IllegalArgumentException("Contrato inválido");
            if ((allowedStart == null) != (allowedEnd == null) || (allowedStart != null && allowedStart.equals(allowedEnd))) throw new IllegalArgumentException("Horario inválido");
        }
        boolean unrestricted() { return allowedStart == null && allowedDays.chars().distinct().count() == 7; }
    }

    /** Merged covered periods inside [from, to). */
    public static List<Period> covered(List<Contract> contracts, Instant from, Instant to, ZoneId zone) {
        List<Period> raw = new ArrayList<>();
        for (var c : contracts) {
            Instant ws = max(c.startsAt(), from), we = min(c.endsAt(), to);
            if (!we.isAfter(ws)) continue;
            if (c.unrestricted()) { raw.add(new Period(ws, we)); continue; }
            LocalDate last = LocalDate.ofInstant(we, zone);
            for (LocalDate d = LocalDate.ofInstant(ws, zone).minusDays(1); !d.isAfter(last); d = d.plusDays(1)) {
                if (c.allowedDays().indexOf('0' + d.getDayOfWeek().getValue()) < 0) continue;
                Instant s, e;
                if (c.allowedStart() == null) { s = d.atStartOfDay(zone).toInstant(); e = d.plusDays(1).atStartOfDay(zone).toInstant(); }
                else {
                    s = ZonedDateTime.of(d, c.allowedStart(), zone).toInstant();
                    e = ZonedDateTime.of(c.allowedEnd().isAfter(c.allowedStart()) ? d : d.plusDays(1), c.allowedEnd(), zone).toInstant();
                }
                Instant is = max(s, ws), ie = min(e, we);
                if (ie.isAfter(is)) raw.add(new Period(is, ie));
            }
        }
        raw.sort(Comparator.comparing(Period::from));
        List<Period> merged = new ArrayList<>();
        for (var p : raw) {
            if (!merged.isEmpty() && !p.from().isAfter(merged.getLast().to())) {
                var lastPeriod = merged.removeLast();
                merged.add(new Period(lastPeriod.from(), max(lastPeriod.to(), p.to())));
            } else merged.add(p);
        }
        return List.copyOf(merged);
    }

    private static Instant max(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
    private static Instant min(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
}
