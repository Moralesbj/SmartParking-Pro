package com.smartparking.modules.notifications.api;

import com.smartparking.common.tenancy.ScopeService;
import com.smartparking.modules.notifications.application.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Each user manages only their own preferences; the user id always comes from the session. */
@RestController
@RequestMapping("/api/v1/notification-preferences")
public class NotificationPreferenceController {
    private final NotificationService notifications; private final ScopeService scopes;
    public NotificationPreferenceController(NotificationService notifications, ScopeService scopes) { this.notifications = notifications; this.scopes = scopes; }

    public record Preference(@NotBlank String eventType, @NotBlank @Pattern(regexp = "IN_APP|EMAIL|SMS|WHATSAPP") String channel, @NotNull Boolean enabled) {}

    @GetMapping public List<Map<String, Object>> list() { return notifications.preferences(scopes.actor()); }
    @PutMapping public List<Map<String, Object>> update(@Valid @RequestBody Preference p) {
        notifications.setPreference(scopes.actor(), p.eventType(), p.channel(), p.enabled());
        return notifications.preferences(scopes.actor());
    }
}
