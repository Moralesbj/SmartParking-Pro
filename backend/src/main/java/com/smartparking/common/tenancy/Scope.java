package com.smartparking.common.tenancy;

import java.util.UUID;

public record Scope(UUID organizationId, UUID branchId, UUID actorId, String role) {
    public boolean administrator() { return role.equals("ADMIN") || role.equals("SUPERADMIN"); }
    public boolean supervisor() { return administrator() || role.equals("SUPERVISOR"); }
}
