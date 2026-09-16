package com.ai.jmeter.agent.domain.governance;

import java.util.List;
import java.util.Set;

/**
 * Who is asking, and on whose behalf.
 *
 * @param id     the identity the run is attributed to; recorded in provenance, so it must be the
 *               durable subject an identity provider issues, not a display name
 * @param tenant the organization this principal acts for
 * @param roles  what they may do within it; empty means they may do nothing
 */
public record Principal(String id, TenantId tenant, Set<Role> roles) {

    public Principal {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A principal must have an identity");
        }
        if (tenant == null) {
            throw new IllegalArgumentException("A principal must belong to a tenant");
        }
        id = id.strip();
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    /**
     * The principal a run is attributed to when tenancy is switched off entirely: one operator,
     * one tenant, on their own machine. Named so that it is obvious in an audit trail that no
     * identity provider vouched for this.
     *
     * @return the single local operator
     */
    public static Principal localOperator() {
        return new Principal("local-operator", TenantId.local(), Set.of(Role.ADMIN));
    }

    public boolean can(Permission permission) {
        return roles.stream().anyMatch(role -> role.grants(permission));
    }

    /** @return {@code true} when this principal acts for the given tenant. */
    public boolean belongsTo(TenantId other) {
        return tenant.equals(other);
    }

    public String describe() {
        return "%s@%s as %s".formatted(
                id, tenant, roles.stream().map(Enum::name).sorted().toList());
    }

    /** @return the roles in a stable order, for provenance and logs. */
    public List<String> roleNames() {
        return roles.stream().map(Enum::name).sorted().toList();
    }
}
