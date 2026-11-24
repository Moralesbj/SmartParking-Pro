package com.smartparking.modules.subscriptions.api;

import com.smartparking.common.api.ScopedDatabase;
import com.smartparking.common.tenancy.ScopeService;
import com.smartparking.modules.subscriptions.application.SubscriptionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class SubscriptionController {
    private final SubscriptionService service; private final ScopedDatabase data; private final ScopeService scopes;
    public SubscriptionController(SubscriptionService service, ScopedDatabase data, ScopeService scopes) { this.service = service; this.data = data; this.scopes = scopes; }

    /** endsAt is optional: by default the contract lasts the plan's duration. */
    public record Contract(@NotNull UUID customerId, @NotNull UUID vehicleId, @NotNull UUID planId, @NotNull Instant startsAt, Instant endsAt) {}
    public record PlanBranches(@NotNull @Size(max = 200) Set<UUID> branchIds) {}

    @GetMapping("/subscriptions") public List<Map<String, Object>> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return data.list(scopes.staff(), "subscriptions", page, size); }
    @PostMapping("/subscriptions") public Map<String, Object> create(@Valid @RequestBody Contract b) { return service.create(scopes.supervisor(), b.customerId(), b.vehicleId(), b.planId(), b.startsAt(), b.endsAt()); }
    @PostMapping("/subscriptions/{id}/renew") public Map<String, Object> renew(@PathVariable UUID id) { return service.renew(scopes.supervisor(), id); }
    @PostMapping("/subscriptions/{id}/suspend") public Map<String, Object> suspend(@PathVariable UUID id) { return service.suspend(scopes.supervisor(), id); }
    @PostMapping("/subscriptions/{id}/reactivate") public Map<String, Object> reactivate(@PathVariable UUID id) { return service.reactivate(scopes.supervisor(), id); }
    @GetMapping("/subscription-plans/{id}/branches") public List<Map<String, Object>> branches(@PathVariable UUID id) { return service.planBranches(scopes.staff(), id); }
    @PutMapping("/subscription-plans/{id}/branches") public List<Map<String, Object>> authorize(@PathVariable UUID id, @Valid @RequestBody PlanBranches b) { return service.authorizeBranches(scopes.admin(), id, b.branchIds()); }
}
