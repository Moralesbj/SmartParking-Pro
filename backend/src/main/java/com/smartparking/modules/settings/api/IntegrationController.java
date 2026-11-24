package com.smartparking.modules.settings.api;

import com.smartparking.common.tenancy.ScopeService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/integrations")
public class IntegrationController {
    private final ScopeService scopes;
    public IntegrationController(ScopeService scopes) { this.scopes=scopes; }
    @GetMapping public List<Map<String,String>> integrations() {
        scopes.admin();return List.of(
            Map.of("provider","DIAN","status","NOT_CONFIGURED","detail","Requiere proveedor habilitado, contrato, implementación del adaptador y credenciales"),
            Map.of("provider","PAYMENT_GATEWAY","status","NOT_CONFIGURED","detail","Solo registro de pagos externos comprobados; sin procesamiento de tarjetas"),
            Map.of("provider","EMAIL_SMS_WHATSAPP","status","NOT_CONFIGURED","detail","Solo bandeja interna; ningún mensaje se envía fuera del sistema"),
            Map.of("provider","ALPR","status","SIMULATOR","detail","Placa introducida manualmente, sin OCR real"),
            Map.of("provider","DEVICES","status","SIMULATOR","detail","No se conecta ni acciona hardware físico")
        );
    }
}
