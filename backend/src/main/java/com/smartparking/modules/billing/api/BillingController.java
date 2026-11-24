package com.smartparking.modules.billing.api;

import com.smartparking.common.api.ScopedDatabase;
import com.smartparking.common.tenancy.ScopeService;
import com.smartparking.modules.billing.application.BillingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class BillingController {
    private final BillingService billing;private final ScopeService scopes;private final ScopedDatabase data;
    public BillingController(BillingService billing,ScopeService scopes,ScopedDatabase data) { this.billing=billing;this.scopes=scopes;this.data=data; }
    public record Payment(@NotNull UUID orderId,@NotNull UUID cashSessionId,@NotBlank String method,@NotNull @DecimalMin("0.01") @Digits(integer=12,fraction=2) BigDecimal amount,@Size(max=160) String reference,@NotNull UUID idempotencyKey) {}
    public record Refund(@NotNull UUID paymentId,@NotBlank @Size(max=400) String reason,@NotNull UUID idempotencyKey) {}
    @GetMapping("/payment-orders") public List<Map<String,Object>> orders(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return data.list(scopes.staff(),"payment_orders",page,size); }
    @GetMapping("/payments") public List<Map<String,Object>> payments(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return data.list(scopes.staff(),"payments",page,size); }
    @PostMapping("/payments") public Map<String,Object> capture(@Valid @RequestBody Payment b) { return billing.capture(scopes.staff(),b.orderId(),b.cashSessionId(),b.method(),b.amount(),b.reference(),b.idempotencyKey()); }
    @PostMapping("/refunds") public Map<String,Object> refund(@Valid @RequestBody Refund b) { return billing.refund(scopes.supervisor(),b.paymentId(),b.reason(),b.idempotencyKey()); }
    @GetMapping("/payments/{id}/receipt") public Map<String,Object> receipt(@PathVariable UUID id) { return Map.of("kind","RECIBO_INTERNO","payment",data.row(scopes.staff(),"payments",id,false),"notice","No constituye factura electrónica DIAN"); }
    @GetMapping("/invoices") public Map<String,Object> invoices() { scopes.staff(); return Map.of("status","NOT_CONFIGURED","items",List.of(),"detail","No hay proveedor de facturación electrónica configurado"); }
}
