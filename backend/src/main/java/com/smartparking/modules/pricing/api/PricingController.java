package com.smartparking.modules.pricing.api;

import com.smartparking.common.tenancy.ScopeService;
import com.smartparking.modules.pricing.application.VehicleRateService;
import com.smartparking.modules.pricing.domain.ParkingRateCalculationDTO;
import com.smartparking.modules.vehicles.domain.VehicleCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PricingController {
    private final VehicleRateService rates; private final ScopeService scopes;
    public PricingController(VehicleRateService rates, ScopeService scopes) { this.rates = rates; this.scopes = scopes; }

    public record QuoteRequest(@NotNull @Pattern(regexp = VehicleCategory.PATTERN) String vehicleType, @NotNull Instant entryAt, @NotNull Instant exitAt) {}
    public record RateUpdate(@NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal minuteRate,
                             @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal fullRate,
                             @Min(1) @Max(10080) int blockMinutes,
                             @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal monthlyFee,
                             @Min(0) @Max(1440) int graceMinutes, @Min(0) @Max(100000) int capacity, @NotNull Boolean active) {}

    /** Tariff simulator: prices a hypothetical stay with the branch's current configuration. Read-only. */
    @PostMapping("/pricing/quote")
    public ParkingRateCalculationDTO quote(@Valid @RequestBody QuoteRequest b) { return rates.simulate(scopes.staff(), b.vehicleType(), b.entryAt(), b.exitAt()); }

    /** The four categories of the selected branch. Staff can read them; only administrators change them. */
    @GetMapping("/vehicle-rates")
    public List<Map<String, Object>> list() { return rates.list(scopes.staff()); }

    @PutMapping("/vehicle-rates/{type}")
    public Map<String, Object> update(@PathVariable String type, @Valid @RequestBody RateUpdate b) {
        return rates.update(scopes.admin(), type, new VehicleRateService.Update(b.minuteRate(), b.fullRate(), b.blockMinutes(), b.monthlyFee(), b.graceMinutes(), b.capacity(), b.active()));
    }

    @GetMapping("/vehicle-rates/{type}/history")
    public List<Map<String, Object>> history(@PathVariable String type) { return rates.history(scopes.supervisor(), type); }
}
