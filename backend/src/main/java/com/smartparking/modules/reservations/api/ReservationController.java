package com.smartparking.modules.reservations.api;

import com.smartparking.common.api.ScopedDatabase;
import com.smartparking.common.tenancy.ScopeService;
import com.smartparking.modules.reservations.application.ReservationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/reservations")
public class ReservationController {
    private final ReservationService service;private final ScopeService scopes;private final ScopedDatabase data;
    public ReservationController(ReservationService service,ScopeService scopes,ScopedDatabase data) { this.service=service;this.scopes=scopes;this.data=data; }
    public record Booking(UUID customerId,@NotNull UUID vehicleId,@NotNull UUID spaceId,@NotNull Instant startsAt,@NotNull Instant endsAt,@NotNull UUID idempotencyKey) {}
    @GetMapping public List<Map<String,Object>> list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return data.list(scopes.staff(),"reservations",page,size); }
    @GetMapping("/availability") public List<Map<String,Object>> available(@RequestParam String type,@RequestParam Instant startsAt,@RequestParam Instant endsAt) { return service.availability(scopes.current(),type,startsAt,endsAt); }
    @PostMapping public Map<String,Object> create(@Valid @RequestBody Booking b) { return service.create(scopes.current(),b.customerId(),b.vehicleId(),b.spaceId(),b.startsAt(),b.endsAt(),b.idempotencyKey()); }
    @PostMapping("/{id}/cancel") public Map<String,Object> cancel(@PathVariable UUID id) { return service.cancel(scopes.current(),id); }
    @PostMapping("/{id}/check-in") public Map<String,Object> checkIn(@PathVariable UUID id) { return service.checkIn(scopes.staff(),id); }
}
